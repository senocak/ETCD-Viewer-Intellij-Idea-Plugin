package com.github.senocak.etcd.settings

import com.intellij.util.xmlb.annotations.Tag
import java.util.UUID

/**
 * A saved etcd endpoint.
 *
 * Serialised into `etcd-connections.xml` by [EtcdSettingsState], so every property must be a
 * `var` with a default and the class must keep a no-argument constructor. Secrets are
 * deliberately absent: passwords and keystore passphrases live in [EtcdCredentials].
 */
@Tag("connection")
class EtcdConnection() {

    /** Stable identity, also the PasswordSafe key. Survives renames. */
    var id: String = UUID.randomUUID().toString()

    var name: String = "etcd"

    /** Base URL of the client endpoint, e.g. `http://localhost:2379`. */
    var url: String = "http://localhost:2379"

    /** Empty means anonymous; otherwise the plugin authenticates before its first request. */
    var username: String = ""

    /** PEM file holding the CA that signed the server certificate. Empty means the JDK default. */
    var caCertPath: String = ""

    /** PEM certificate presented to the server for mTLS. */
    var clientCertPath: String = ""

    /** PKCS#8 PEM private key matching [clientCertPath]. */
    var clientKeyPath: String = ""

    /** Skips all certificate and hostname checks. Development only. */
    var trustAllCertificates: Boolean = false

    /** Separator the key browser splits on to build its tree. */
    var keySeparator: String = "/"

    var requestTimeoutMs: Int = 10_000

    /** Page size for range queries in the key browser. */
    var pageSize: Int = 500

    constructor(name: String, url: String) : this() {
        this.name = name
        this.url = url
    }

    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)

    val requiresAuthentication: Boolean get() = username.isNotBlank()

    /** The endpoint with any trailing slash removed, ready for path concatenation. */
    val baseUrl: String get() = url.trimEnd('/')

    fun copy(): EtcdConnection = EtcdConnection().also {
        it.id = id
        it.name = name
        it.url = url
        it.username = username
        it.caCertPath = caCertPath
        it.clientCertPath = clientCertPath
        it.clientKeyPath = clientKeyPath
        it.trustAllCertificates = trustAllCertificates
        it.keySeparator = keySeparator
        it.requestTimeoutMs = requestTimeoutMs
        it.pageSize = pageSize
    }

    /** Validation shared by the connection dialog and the settings page. Null means valid. */
    fun validate(): String? = when {
        name.isBlank() -> "Connection name must not be empty"
        url.isBlank() -> "Endpoint URL must not be empty"
        !url.startsWith("http://", true) && !url.startsWith("https://", true) ->
            "Endpoint URL must start with http:// or https://"
        clientCertPath.isNotBlank() != clientKeyPath.isNotBlank() ->
            "Client certificate and client key must be provided together"
        requestTimeoutMs <= 0 -> "Request timeout must be greater than zero"
        pageSize <= 0 -> "Page size must be greater than zero"
        else -> null
    }

    override fun toString(): String = "$name — $url"
}
