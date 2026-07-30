package com.github.senocak.etcd.service

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.settings.EtcdConnection
import com.github.senocak.etcd.settings.EtcdCredentials
import com.github.senocak.etcd.settings.EtcdSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Holds the project's single active etcd connection.
 *
 * The tool window sections never build clients themselves; they read [client] and listen on
 * [TOPIC], so connecting and disconnecting both flow through one place. The client is closed
 * with the project, which also stops any watch streams it opened.
 *
 * There is no reconnect: disconnecting and connecting again does the same thing, and picks up
 * edited settings on the way because the picker is repopulated when the settings dialog closes.
 */
@Service(Service.Level.PROJECT)
class EtcdConnectionService(private val project: Project) : Disposable {

    interface Listener {
        /** [client] is null when the connection was dropped. */
        fun connectionChanged(client: EtcdClient?)
    }

    @Volatile
    var client: EtcdClient? = null
        private set

    val connection: EtcdConnection? get() = client?.connection

    val isConnected: Boolean get() = client != null

    /**
     * Opens a connection and verifies it by asking etcd for its status.
     *
     * Blocking — call it from a background thread. Any previous connection is closed first, so
     * switching endpoints never leaves an orphaned client or watch behind.
     */
    fun connect(target: EtcdConnection): EtcdClient {
        closeCurrent()
        val newClient = EtcdClient(target.copy()) { EtcdCredentials.getPassword(target.id) }
        try {
            newClient.testConnection()
        } catch (e: Exception) {
            newClient.close()
            throw e
        }
        client = newClient
        EtcdSettingsState.getInstance().lastUsedConnectionId = target.id
        publish(newClient)
        return newClient
    }

    fun disconnect() {
        if (client == null) return
        closeCurrent()
        publish(null)
    }

    private fun closeCurrent() {
        client?.let { runCatching { it.close() } }
        client = null
    }

    /**
     * Announces a connection change on the EDT.
     *
     * The message bus delivers synchronously on the calling thread, and [connect] deliberately
     * runs on a background thread — so publishing directly from there would hand every listener,
     * all of which drive Swing, to a non-EDT thread. Dispatching here keeps that contract in the
     * service rather than relying on each listener to remember it.
     */
    private fun publish(newClient: EtcdClient?) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).connectionChanged(newClient)
        }, ModalityState.any())
    }

    override fun dispose() = closeCurrent()

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("etcd connection", Listener::class.java)

        fun getInstance(project: Project): EtcdConnectionService = project.service()
    }
}
