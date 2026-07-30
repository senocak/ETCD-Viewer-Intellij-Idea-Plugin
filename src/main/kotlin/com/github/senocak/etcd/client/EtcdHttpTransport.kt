package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.json.JsonObject
import com.github.senocak.etcd.json.JsonParseException
import com.github.senocak.etcd.settings.EtcdConnection
import java.io.FileInputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Carries JSON requests to etcd's v3 gRPC-gateway over plain HTTP.
 *
 * Owns one [HttpClient] per connection, along with its TLS configuration and the auth token
 * obtained from `/v3/auth/authenticate`. All etcd RPCs are `POST`s with a JSON body, so a single
 * [post] covers the whole API surface.
 */
class EtcdHttpTransport(
    val connection: EtcdConnection,
    private val passwordSupplier: () -> String?,
) : AutoCloseable {

    private val token = AtomicReference<String?>(null)

    private val timeout: Duration = Duration.ofMillis(connection.requestTimeoutMs.toLong())

    val httpClient: HttpClient by lazy { buildHttpClient() }

    /**
     * Sends [body] to [path] and returns the parsed response.
     *
     * Authenticates lazily on the first call when the connection has a username, and retries
     * exactly once if etcd rejects a token that has since expired or been revoked.
     */
    fun post(path: String, body: JsonObject = Json.obj()): JsonObject {
        ensureAuthenticated()
        return try {
            send(path, body)
        } catch (e: EtcdException) {
            if (!e.isAuthTokenRejected || !connection.requiresAuthentication) throw e
            // The cached token went stale — authenticate again and replay the request once.
            token.set(null)
            ensureAuthenticated()
            send(path, body)
        }
    }

    /** Builds a request for [path] with auth and content headers applied. */
    fun newRequest(path: String, body: JsonObject): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(connection.baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.write()))
        // etcd expects the bare token in Authorization, with no "Bearer " prefix.
        token.get()?.let { builder.header("Authorization", it) }
        return builder
    }

    /** Authenticates if credentials are configured and no token is held yet. */
    fun ensureAuthenticated() {
        if (!connection.requiresAuthentication || token.get() != null) return
        val password = passwordSupplier()
            ?: throw EtcdException("No password stored for user '${connection.username}'")
        val response = send(
            "/v3/auth/authenticate",
            Json.obj("name" to connection.username, "password" to password),
        )
        val issued = response.string("token")
        if (issued.isEmpty()) throw EtcdException("etcd did not return an authentication token")
        token.set(issued)
    }

    /** Drops the cached token so the next request re-authenticates. */
    fun invalidateToken() = token.set(null)

    private fun send(path: String, body: JsonObject): JsonObject {
        val request = newRequest(path, body).timeout(timeout).build()
        val response: HttpResponse<String> = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: ConnectException) {
            throw EtcdException("Cannot reach etcd at ${connection.baseUrl}: ${e.message}", e)
        } catch (e: HttpTimeoutException) {
            throw EtcdException("Request to ${connection.baseUrl}$path timed out after ${timeout.toMillis()} ms", e)
        } catch (e: SSLHandshakeException) {
            throw EtcdException("TLS handshake with ${connection.baseUrl} failed: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EtcdException("Request to etcd was interrupted", e)
        } catch (e: java.io.IOException) {
            throw EtcdException("Request to ${connection.baseUrl}$path failed: ${e.message}", e)
        }

        val parsed = runCatching { Json.parseObject(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) throw errorFrom(response, parsed, path)
        // The gateway can answer 200 with an error envelope, so check the body as well.
        if (parsed != null && (parsed.has("error") || parsed.has("code") && !parsed.has("header"))) {
            throw errorFrom(response, parsed, path)
        }
        return parsed
            ?: throw EtcdException("etcd returned a non-JSON response for $path: ${response.body().take(200)}")
    }

    private fun errorFrom(response: HttpResponse<String>, parsed: JsonObject?, path: String): EtcdException {
        val detail = parsed?.let { it.string("error").ifEmpty { it.string("message") } }.orEmpty()
        val code = parsed?.takeIf { it.has("code") }?.int("code")
        val text = when {
            detail.isNotEmpty() -> detail
            response.body().isNotBlank() -> response.body().take(300)
            else -> "HTTP ${response.statusCode()}"
        }
        val hint = if (response.statusCode() == 404) {
            " — is the gRPC gateway enabled on this endpoint?"
        } else {
            ""
        }
        return EtcdException("$text$hint", grpcCode = code, httpStatus = response.statusCode())
    }

    private fun buildHttpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
        if (connection.isSecure) {
            val sslContext = buildSslContext()
            builder.sslContext(sslContext)
            if (connection.trustAllCertificates) {
                // Clearing the endpoint identification algorithm is what switches off hostname
                // verification for the JDK client; the permissive trust manager alone is not enough.
                builder.sslParameters(sslContext.defaultSSLParameters.apply {
                    endpointIdentificationAlgorithm = null
                })
            }
        }
        return builder.build()
    }

    private fun buildSslContext(): SSLContext {
        val trustManagers: Array<TrustManager>? = when {
            connection.trustAllCertificates -> arrayOf(TRUST_EVERYTHING)
            connection.caCertPath.isNotBlank() -> arrayOf(trustManagerFor(connection.caCertPath))
            else -> null // JDK default trust store
        }
        val keyManagers = if (connection.clientCertPath.isNotBlank() && connection.clientKeyPath.isNotBlank()) {
            keyManagersFor(connection.clientCertPath, connection.clientKeyPath)
        } else {
            null
        }
        return try {
            SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
        } catch (e: Exception) {
            throw EtcdException("Failed to set up TLS for ${connection.name}: ${e.message}", e)
        }
    }

    private fun trustManagerFor(caCertPath: String): X509TrustManager {
        try {
            val certificates = FileInputStream(caCertPath).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input)
            }
            if (certificates.isEmpty()) throw EtcdException("No certificate found in $caCertPath")
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
            }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw EtcdException("No usable trust manager for $caCertPath")
        } catch (e: EtcdException) {
            throw e
        } catch (e: Exception) {
            throw EtcdException("Failed to load CA certificate $caCertPath: ${e.message}", e)
        }
    }

    private fun keyManagersFor(certPath: String, keyPath: String): Array<javax.net.ssl.KeyManager> {
        try {
            val chain = FileInputStream(certPath).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input)
                    .map { it as X509Certificate }
            }
            if (chain.isEmpty()) throw EtcdException("No certificate found in $certPath")
            val privateKey = readPrivateKey(keyPath)
            // An in-memory keystore avoids asking the user for a keystore password they don't have.
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("client", privateKey, EMPTY_PASSWORD, chain.toTypedArray())
            }
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(store, EMPTY_PASSWORD)
            return factory.keyManagers
        } catch (e: EtcdException) {
            throw e
        } catch (e: Exception) {
            throw EtcdException("Failed to load client certificate $certPath: ${e.message}", e)
        }
    }

    /**
     * Reads an unencrypted PKCS#8 PEM private key.
     *
     * The key algorithm is not recorded in the PEM header, so each supported algorithm is tried
     * in turn — this covers the RSA and EC keys that etcd deployments actually use.
     */
    private fun readPrivateKey(keyPath: String): java.security.PrivateKey {
        val pem = java.io.File(keyPath).readText()
        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")) {
            throw EtcdException(
                "$keyPath is in PKCS#1 format. Convert it with: " +
                    "openssl pkcs8 -topk8 -nocrypt -in $keyPath -out client-pkcs8.key"
            )
        }
        if (pem.contains("ENCRYPTED PRIVATE KEY")) {
            throw EtcdException("$keyPath is encrypted; decrypt it before use or supply a PKCS#12 bundle")
        }
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        if (base64.isEmpty()) throw EtcdException("$keyPath does not contain a PKCS#8 private key")
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
        for (algorithm in listOf("RSA", "EC", "Ed25519")) {
            runCatching { return KeyFactory.getInstance(algorithm).generatePrivate(spec) }
        }
        throw EtcdException("Unsupported private key algorithm in $keyPath")
    }

    override fun close() {
        token.set(null)
        // HttpClient became AutoCloseable in Java 21; closing releases its selector thread.
        runCatching { httpClient.close() }
    }

    private companion object {
        val EMPTY_PASSWORD = CharArray(0)

        /** Accepts any certificate. Only ever installed when the user opts in explicitly. */
        val TRUST_EVERYTHING = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}

/** Convenience for call sites that only care that the payload parsed. */
internal fun String.parseJsonObjectOrNull(): JsonObject? =
    try {
        Json.parseObject(this)
    } catch (_: JsonParseException) {
        null
    }
