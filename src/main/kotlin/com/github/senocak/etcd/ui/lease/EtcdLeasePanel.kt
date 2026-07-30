package com.github.senocak.etcd.ui.lease

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.model.EtcdLease
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdTab
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The Leases tab: grant, inspect, keep alive and revoke leases.
 *
 * Keys attached to a lease vanish when it expires, so the attached-key list is the main thing
 * worth seeing here — it is what makes a lease's blast radius visible before revoking it.
 */
class EtcdLeasePanel(private val project: Project) : JPanel(BorderLayout()), EtcdTab {

    override val title: String = "Leases"

    override val component: JComponent get() = this

    private var client: EtcdClient? = null

    private val tableModel = LeaseTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = 190
        columnModel.getColumn(1).preferredWidth = 90
        columnModel.getColumn(2).preferredWidth = 90
        columnModel.getColumn(3).preferredWidth = 70
        columnModel.getColumn(4).preferredWidth = 420
    }

    private val refreshButton = EtcdUiUtil.button("Refresh", AllIcons.Actions.Refresh) { refresh() }
    private val grantButton = EtcdUiUtil.button("Grant…", AllIcons.General.Add) { grant() }
    private val keepAliveButton = EtcdUiUtil.button("Keep Alive", AllIcons.Actions.Restart) { keepAlive() }
    private val revokeButton = EtcdUiUtil.button("Revoke", AllIcons.General.Remove) { revoke() }

    init {
        add(
            EtcdUiUtil.toolbar(refreshButton, grantButton, keepAliveButton, revokeButton),
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
        setConnected(false)
    }

    override fun onConnectionChanged(client: EtcdClient?) {
        this.client = client
        setConnected(client != null)
        if (client != null) refresh() else tableModel.setLeases(emptyList())
    }

    override fun onActivated() {
        if (client != null) refresh()
    }

    private fun setConnected(connected: Boolean) =
        EtcdUiUtil.setEnabled(connected, refreshButton, grantButton, keepAliveButton, revokeButton)

    private fun refresh() {
        val active = client ?: return
        EtcdTasks.run(
            project = project,
            title = "Loading etcd leases",
            work = { active.lease.list() },
            onSuccess = { tableModel.setLeases(it) },
        )
    }

    private fun grant() {
        val active = client ?: return
        val input = Messages.showInputDialog(
            this,
            "Time to live, in seconds:",
            "Grant etcd Lease",
            null,
            "60",
            object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(text: String?) = text?.trim()?.toLongOrNull()?.let { it > 0 } == true
                override fun canClose(text: String?) = checkInput(text)
            },
        )?.trim()?.toLongOrNull() ?: return

        EtcdTasks.run(
            project = project,
            title = "Granting lease",
            work = { active.lease.grant(input) },
            onSuccess = { lease ->
                EtcdTasks.notifyInfo(
                    project,
                    "Granted lease ${java.lang.Long.toUnsignedString(lease.id)} with TTL ${lease.ttl}s",
                )
                refresh()
            },
        )
    }

    private fun keepAlive() {
        val lease = selectedLease() ?: return
        val active = client ?: return
        EtcdTasks.run(
            project = project,
            title = "Renewing lease",
            work = { active.lease.keepAlive(lease.id) },
            onSuccess = { ttl ->
                EtcdTasks.notifyInfo(project, "Lease ${lease.idText} renewed, TTL now ${ttl}s")
                refresh()
            },
        )
    }

    private fun revoke() {
        val lease = selectedLease() ?: return
        val active = client ?: return
        val attached = lease.keys.size
        val warning = if (attached > 0) {
            "Revoke lease ${lease.idText}?\n\nThis immediately deletes $attached key(s) attached to it:\n" +
                lease.keys.take(10).joinToString("\n") { "  • $it" } +
                if (attached > 10) "\n  … and ${attached - 10} more" else ""
        } else {
            "Revoke lease ${lease.idText}?"
        }
        if (!EtcdUiUtil.confirmDestructive(project, "Revoke Lease", warning)) return

        EtcdTasks.run(
            project = project,
            title = "Revoking lease",
            work = { active.lease.revoke(lease.id) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Revoked lease ${lease.idText}")
                refresh()
            },
        )
    }

    private fun selectedLease(): EtcdLease? {
        val row = table.selectedRow.takeIf { it >= 0 }
        if (row == null) {
            Messages.showInfoMessage(this, "Select a lease first.", "No Lease Selected")
            return null
        }
        return tableModel.leaseAt(table.convertRowIndexToModel(row))
    }

    private class LeaseTableModel : AbstractTableModel() {
        private var leases: List<EtcdLease> = emptyList()

        fun setLeases(newLeases: List<EtcdLease>) {
            leases = newLeases
            fireTableDataChanged()
        }

        fun leaseAt(index: Int): EtcdLease? = leases.getOrNull(index)

        override fun getRowCount(): Int = leases.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = leases[rowIndex].let {
            when (columnIndex) {
                0 -> it.idText
                1 -> if (it.expired) "expired" else EtcdUiUtil.formatTtl(it.ttl)
                2 -> EtcdUiUtil.formatTtl(it.grantedTtl)
                3 -> it.keys.size
                else -> it.keys.joinToString(", ")
            }
        }

        private companion object {
            val COLUMNS = arrayOf("Lease ID", "TTL remaining", "Granted TTL", "Keys", "Attached keys")
        }
    }
}

/** Lease ids are unsigned 64-bit, so they must not be printed as signed longs. */
private val EtcdLease.idText: String get() = java.lang.Long.toUnsignedString(id)
