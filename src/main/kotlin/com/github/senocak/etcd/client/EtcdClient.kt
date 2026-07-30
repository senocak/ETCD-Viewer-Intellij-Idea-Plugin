package com.github.senocak.etcd.client

import com.github.senocak.etcd.model.EtcdStatus
import com.github.senocak.etcd.settings.EtcdConnection
import java.util.Collections

/**
 * A connected etcd cluster.
 *
 * Groups the four API families over one shared [EtcdHttpTransport] and keeps track of the watch
 * streams it opened so they can all be shut down with the connection. Every method here blocks,
 * so callers must stay off the EDT.
 */
class EtcdClient(
    val connection: EtcdConnection,
    passwordSupplier: () -> String?,
) : AutoCloseable {

    private val transport = EtcdHttpTransport(connection, passwordSupplier)

    private val watches: MutableSet<EtcdWatchStream> = Collections.synchronizedSet(LinkedHashSet())

    val kv = EtcdKvApi(transport)
    val lease = EtcdLeaseApi(transport)
    val cluster = EtcdClusterApi(transport)
    val auth = EtcdAuthApi(transport)

    /** Round-trips against the endpoint, authenticating if needed. Used by "Test Connection". */
    fun testConnection(): EtcdStatus = cluster.status()

    /**
     * Opens a watch and remembers it, so [close] can tear it down.
     *
     * The stream removes itself from the registry once it ends, whether that was a [stop] or a
     * server-side cancellation.
     */
    fun watch(
        prefix: String,
        watchPrefix: Boolean,
        startRevision: Long = 0,
        listener: EtcdWatchStream.Listener,
    ): EtcdWatchStream {
        lateinit var stream: EtcdWatchStream
        stream = EtcdWatchStream(
            transport = transport,
            prefix = prefix,
            watchPrefix = watchPrefix,
            startRevision = startRevision,
            listener = object : EtcdWatchStream.Listener {
                override fun onCreated(watchId: Long) = listener.onCreated(watchId)

                override fun onEvent(event: com.github.senocak.etcd.model.WatchEvent) = listener.onEvent(event)

                override fun onClosed(error: EtcdException?) {
                    watches.remove(stream)
                    listener.onClosed(error)
                }
            },
        )
        watches.add(stream)
        stream.start()
        return stream
    }

    override fun close() {
        // Copy first: each stop() removes its stream from the set via the onClosed callback.
        synchronized(watches) { watches.toList() }.forEach { runCatching { it.stop() } }
        watches.clear()
        transport.close()
    }
}
