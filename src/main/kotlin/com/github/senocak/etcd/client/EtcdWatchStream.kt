package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdKeyValue
import com.github.senocak.etcd.model.WatchEvent
import com.github.senocak.etcd.model.WatchEventType
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live watch on a key or prefix, backed by `POST /v3/watch`.
 *
 * The gateway turns etcd's bidirectional watch stream into a single request carrying one
 * `create_request`, followed by a response of newline-delimited JSON frames. Closing the
 * request body after the create is what etcd's own documented `curl -N` example does, and the
 * server keeps streaming events regardless.
 *
 * Events are read on a dedicated daemon thread and handed to [listener] from that thread — the
 * UI is responsible for hopping to the EDT.
 */
class EtcdWatchStream(
    private val transport: EtcdHttpTransport,
    private val prefix: String,
    private val watchPrefix: Boolean,
    private val startRevision: Long,
    private val listener: Listener,
) {

    interface Listener {
        /** Called once when etcd confirms the watch was created. */
        fun onCreated(watchId: Long) = Unit

        fun onEvent(event: WatchEvent)

        /** Called when the stream ends, with [error] set if it ended abnormally. */
        fun onClosed(error: EtcdException?)
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var body: InputStream? = null

    @Volatile
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ run() }, "etcd-watch-$prefix").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the watch. Safe to call from any thread, and safe to call twice. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Closing the response body unblocks the reader thread, which then exits its loop.
        runCatching { body?.close() }
        thread?.interrupt()
    }

    private fun run() {
        var failure: EtcdException? = null
        try {
            transport.ensureAuthenticated()
            val createRequest = buildCreateRequest()
            // No per-request timeout: a watch is expected to stay open indefinitely.
            val request = transport.newRequest("/v3/watch", Json.obj("create_request" to createRequest)).build()
            val response: HttpResponse<InputStream> =
                transport.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw EtcdException(
                    "etcd refused the watch on '$prefix' (HTTP ${response.statusCode()})",
                    httpStatus = response.statusCode(),
                )
            }
            body = response.body()
            readFrames(response.body())
        } catch (e: EtcdException) {
            if (running.get()) failure = e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // A close() from stop() surfaces here as an IOException; that is a normal shutdown.
            if (running.get()) failure = EtcdException("Watch on '$prefix' failed: ${e.message}", e)
        } finally {
            running.set(false)
            runCatching { body?.close() }
            body = null
            listener.onClosed(failure)
        }
    }

    private fun buildCreateRequest() = if (watchPrefix) {
        val (key, rangeEnd) = KeyUtil.prefixRange(prefix)
        Json.obj(
            "key" to key,
            "range_end" to rangeEnd,
            "prev_kv" to true,
            "start_revision" to startRevision.takeIf { it > 0 },
        )
    } else {
        Json.obj(
            "key" to KeyUtil.encode(prefix),
            "prev_kv" to true,
            "start_revision" to startRevision.takeIf { it > 0 },
        )
    }

    private fun readFrames(input: InputStream) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val frame = try {
                    Json.parseObject(line)
                } catch (e: Exception) {
                    LOG.warn("Skipping unparseable watch frame: ${line.take(200)}", e)
                    continue
                }
                // Every gateway stream frame is wrapped in "result"; errors arrive unwrapped.
                val result = frame.obj("result")
                if (result == null) {
                    val error = frame.string("error").ifEmpty { frame.string("message") }
                    if (error.isNotEmpty()) throw EtcdException(error, grpcCode = frame.int("code"))
                    continue
                }
                if (result.bool("created")) {
                    listener.onCreated(result.long("watch_id"))
                    continue
                }
                if (result.bool("canceled")) {
                    val reason = result.string("cancel_reason").ifEmpty { "etcd canceled the watch" }
                    throw EtcdException("Watch on '$prefix' canceled: $reason")
                }
                parseEvents(result).forEach(listener::onEvent)
            }
        }
    }

    companion object {
        private val LOG = logger<EtcdWatchStream>()

        /**
         * Turns the `result` object of a watch frame into events.
         *
         * Pure, so the frame format can be tested without a live cluster.
         */
        @JvmStatic
        fun parseEvents(result: com.github.senocak.etcd.json.JsonObject): List<WatchEvent> =
            result.objects("events").mapNotNull { event ->
                val kvJson = event.obj("kv") ?: return@mapNotNull null
                WatchEvent(
                    // PUT is enum value 0, which proto3 JSON omits from the payload entirely.
                    type = if (event.string("type", "PUT") == "DELETE") {
                        WatchEventType.DELETE
                    } else {
                        WatchEventType.PUT
                    },
                    kv = EtcdKeyValue.from(kvJson),
                    prevKv = event.obj("prev_kv")?.let { EtcdKeyValue.from(it) },
                )
            }
    }
}
