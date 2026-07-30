package com.github.senocak.etcd.ui

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.service.EtcdConnectionService
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.settings.EtcdConfigurable
import com.github.senocak.etcd.settings.EtcdConnection
import com.github.senocak.etcd.settings.EtcdSettingsState
import com.github.senocak.etcd.ui.kv.EtcdKvPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.DefaultComboBoxModel

/**
 * The etcd tool window: a connection picker on top, the Keys browser below.
 *
 * Only the Keys section ships today. Further sections (Watch, Leases, Cluster, Auth) are
 * described in the README; each would implement [EtcdTab], join [sections], and the single
 * component below would go back to being a `JBTabbedPane`.
 *
 * Owns the wiring between [EtcdConnectionService] and the sections — a section never connects on
 * its own, it just reacts to [EtcdTab.onConnectionChanged].
 */
class EtcdToolWindowPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = EtcdConnectionService.getInstance(project)

    private val connectionModel = DefaultComboBoxModel<EtcdConnection>()
    private val connectionCombo = ComboBox(connectionModel).apply {
        // SimpleListCellRenderer is deprecated in 262, but its replacement
        // (dsl.listCellRenderer.textListCellRenderer) is still internal API in 253/261, and
        // internal-API usage fails the Plugin Verifier outright. Deprecated-but-public wins.
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value?.toString() ?: "<no connections configured>"
        }
        toolTipText = "Saved etcd connections"
    }

    private val connectButton = EtcdUiUtil.button("Connect", AllIcons.Actions.Execute) { connect() }
    private val disconnectButton = EtcdUiUtil.button("Disconnect", AllIcons.Actions.Suspend) { disconnect() }
    private val refreshButton = EtcdUiUtil.button("Reconnect", AllIcons.Actions.Refresh) { reconnect() }
    private val settingsButton = EtcdUiUtil.button("Connections…", AllIcons.General.Settings) { openSettings() }
    private val statusLabel = JBLabel(NOT_CONNECTED).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val kvPanel = EtcdKvPanel(project)

    private val sections: List<EtcdTab> = listOf(kvPanel)

    init {
        toolbar = EtcdUiUtil.toolbar(
            JBLabel("Connection:"),
            connectionCombo,
            connectButton,
            disconnectButton,
            refreshButton,
            settingsButton,
            statusLabel,
        )
        // A single section needs no tab strip; add one back when a second section arrives.
        setContent(kvPanel.component)

        // Another window (or the settings page) may change the connection; stay in sync.
        project.messageBus.connect(this).subscribe(
            EtcdConnectionService.TOPIC,
            object : EtcdConnectionService.Listener {
                override fun connectionChanged(client: EtcdClient?) = applyConnection(client)
            },
        )

        reloadConnections()
        applyConnection(service.client)
    }

    /**
     * Reloads the connection list from settings, keeping the current selection where possible.
     *
     * Called on construction and after the settings dialog closes.
     */
    fun reloadConnections() {
        val previouslySelected = (connectionCombo.selectedItem as? EtcdConnection)?.id
        val settings = EtcdSettingsState.getInstance()
        connectionModel.removeAllElements()
        settings.connections.forEach { connectionModel.addElement(it) }

        val preferredId = previouslySelected
            ?: service.connection?.id
            ?: settings.lastUsedConnectionId
        connectionCombo.selectedItem = settings.connections.firstOrNull { it.id == preferredId }
            ?: settings.connections.firstOrNull()
        updateButtons()
    }

    private fun connect() {
        val target = connectionCombo.selectedItem as? EtcdConnection ?: return
        statusLabel.text = "Connecting to ${target.name}…"
        EtcdTasks.run(
            project = project,
            title = "Connecting to ${target.name}",
            work = { service.connect(target) },
            onSuccess = { /* the message bus drives the UI update */ },
            onError = { error ->
                statusLabel.text = NOT_CONNECTED
                updateButtons()
                EtcdTasks.notifyError(project, "Connecting to ${target.name}", error)
            },
        )
    }

    /**
     * Disconnects on a background thread.
     *
     * Closing the client stops its watches and then closes the HTTP client, which blocks until
     * in-flight requests finish — not something to do on the EDT.
     */
    private fun disconnect() {
        disconnectButton.isEnabled = false
        EtcdTasks.run(project, "Disconnecting from etcd", { service.disconnect() })
    }

    private fun reconnect() {
        EtcdTasks.run(project, "Reconnecting to etcd", { service.reconnect() })
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EtcdConfigurable::class.java)
        reloadConnections()
    }

    private fun applyConnection(client: EtcdClient?) {
        statusLabel.text = client?.let { "Connected to ${it.connection.name} (${it.connection.baseUrl})" }
            ?: NOT_CONNECTED
        if (client != null) {
            // Keep the combo pointing at whatever is actually connected.
            (0 until connectionModel.size)
                .map { connectionModel.getElementAt(it) }
                .firstOrNull { it.id == client.connection.id }
                ?.let { connectionCombo.selectedItem = it }
        }
        updateButtons()
        sections.forEach { it.onConnectionChanged(client) }
        if (client != null) sections.forEach { it.onActivated() }
    }

    private fun updateButtons() {
        val connected = service.isConnected
        val hasSelection = connectionCombo.selectedItem != null
        connectButton.isEnabled = hasSelection && !connected
        disconnectButton.isEnabled = connected
        refreshButton.isEnabled = connected
        connectionCombo.isEnabled = !connected
    }

    /**
     * Closing the tool window must stop the watch the Keys section opened.
     *
     * Handing the sections a null connection is exactly the teardown they already do on
     * disconnect, so there is no second code path to keep in step.
     */
    override fun dispose() {
        sections.forEach { runCatching { it.onConnectionChanged(null) } }
    }

    private companion object {
        const val NOT_CONNECTED = "Not connected"
    }
}
