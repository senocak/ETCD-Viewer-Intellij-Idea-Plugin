package com.github.senocak.etcd.ui.cluster

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.model.EtcdAlarm
import com.github.senocak.etcd.model.EtcdMember
import com.github.senocak.etcd.model.EtcdStatus
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdTab
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The Cluster tab: membership, endpoint status, alarms and maintenance.
 *
 * Compaction, defragmentation and member removal all affect the live cluster, so each is behind
 * a confirmation that spells out what it will do.
 */
class EtcdClusterPanel(private val project: Project) : JPanel(BorderLayout()), EtcdTab {

    override val title: String = "Cluster"

    override val component: JComponent get() = this

    private var client: EtcdClient? = null
    private var status: EtcdStatus? = null

    private val statusLabel = JBLabel().apply { border = JBUI.Borders.empty(6) }

    private val memberModel = MemberTableModel()
    private val memberTable = JBTable(memberModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = 180
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).preferredWidth = 240
        columnModel.getColumn(3).preferredWidth = 240
        columnModel.getColumn(4).preferredWidth = 80
    }

    private val alarmModel = AlarmTableModel()
    private val alarmTable = JBTable(alarmModel).apply { setShowGrid(false) }

    private val refreshButton = EtcdUiUtil.button("Refresh", AllIcons.Actions.Refresh) { refresh() }
    private val addMemberButton = EtcdUiUtil.button("Add Member…", AllIcons.General.Add) { addMember() }
    private val removeMemberButton = EtcdUiUtil.button("Remove Member", AllIcons.General.Remove) { removeMember() }
    private val promoteButton = EtcdUiUtil.button("Promote Learner", AllIcons.Actions.Upload) { promoteMember() }
    private val compactButton = EtcdUiUtil.button("Compact…", AllIcons.Actions.GC) { compact() }
    private val defragButton = EtcdUiUtil.button("Defragment", AllIcons.Actions.RealIntentionBulb) { defragment() }

    init {
        add(
            EtcdUiUtil.toolbar(
                refreshButton,
                addMemberButton,
                removeMemberButton,
                promoteButton,
                compactButton,
                defragButton,
            ),
            BorderLayout.NORTH,
        )
        val tables = OnePixelSplitter(true, 0.7f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(JBLabel("Members").apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
                add(JBScrollPane(memberTable), BorderLayout.CENTER)
            }
            secondComponent = JPanel(BorderLayout()).apply {
                add(JBLabel("Alarms").apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
                add(JBScrollPane(alarmTable), BorderLayout.CENTER)
            }
        }
        add(tables, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        setConnected(false)
    }

    override fun onConnectionChanged(client: EtcdClient?) {
        this.client = client
        setConnected(client != null)
        if (client != null) {
            refresh()
        } else {
            memberModel.setMembers(emptyList())
            alarmModel.setAlarms(emptyList())
            statusLabel.text = ""
        }
    }

    override fun onActivated() {
        if (client != null) refresh()
    }

    private fun setConnected(connected: Boolean) = EtcdUiUtil.setEnabled(
        connected,
        refreshButton,
        addMemberButton,
        removeMemberButton,
        promoteButton,
        compactButton,
        defragButton,
    )

    private fun refresh() {
        val active = client ?: return
        EtcdTasks.run(
            project = project,
            title = "Loading etcd cluster state",
            work = {
                Triple(active.cluster.status(), active.cluster.memberList(), active.cluster.alarms())
            },
            onSuccess = { (newStatus, members, alarms) ->
                status = newStatus
                memberModel.setMembers(members, newStatus.leader)
                alarmModel.setAlarms(alarms)
                statusLabel.text = buildString {
                    append("<html>etcd <b>${newStatus.version}</b>")
                    append(" &nbsp;•&nbsp; cluster ${java.lang.Long.toUnsignedString(newStatus.clusterId)}")
                    append(" &nbsp;•&nbsp; revision ${newStatus.revision}")
                    append(" &nbsp;•&nbsp; raft term ${newStatus.raftTerm}, index ${newStatus.raftIndex}")
                    append(" &nbsp;•&nbsp; DB ${EtcdUiUtil.formatBytes(newStatus.dbSize)}")
                    append(" (${EtcdUiUtil.formatBytes(newStatus.dbSizeInUse)} in use)")
                    if (newStatus.errors.isNotEmpty()) {
                        append(" &nbsp;•&nbsp; <b>errors:</b> ${newStatus.errors.joinToString("; ")}")
                    }
                    append("</html>")
                }
            },
        )
    }

    private fun addMember() {
        val active = client ?: return
        val urls = Messages.showInputDialog(
            this,
            "Peer URLs of the new member, comma separated:",
            "Add etcd Member",
            null,
            "http://new-node:2380",
            null,
        )?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        if (urls.isEmpty()) return

        val asLearner = Messages.showYesNoDialog(
            project,
            "Add as a learner?\n\nLearners replicate the log but do not vote, so adding one cannot break quorum.",
            "Add etcd Member",
            "Add as Learner",
            "Add as Voting Member",
            Messages.getQuestionIcon(),
        ) == Messages.YES

        EtcdTasks.run(
            project = project,
            title = "Adding member",
            work = { active.cluster.memberAdd(urls, asLearner) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Added member ${urls.joinToString()}")
                refresh()
            },
        )
    }

    private fun removeMember() {
        val member = selectedMember() ?: return
        val active = client ?: return
        val confirmed = EtcdUiUtil.confirmDestructive(
            project,
            "Remove Member",
            "Remove member '${member.name}' (${member.idHex}) from the cluster?\n\n" +
                "Removing a voting member from a ${memberModel.rowCount}-member cluster can break quorum.",
        )
        if (!confirmed) return

        EtcdTasks.run(
            project = project,
            title = "Removing member",
            work = { active.cluster.memberRemove(member.id) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Removed member ${member.name}")
                refresh()
            },
        )
    }

    private fun promoteMember() {
        val member = selectedMember() ?: return
        val active = client ?: return
        if (!member.isLearner) {
            Messages.showInfoMessage(this, "'${member.name}' is already a voting member.", "Not a Learner")
            return
        }
        EtcdTasks.run(
            project = project,
            title = "Promoting learner",
            work = { active.cluster.memberPromote(member.id) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Promoted ${member.name} to a voting member")
                refresh()
            },
        )
    }

    private fun compact() {
        val active = client ?: return
        val currentRevision = status?.revision ?: 0
        val revision = Messages.showInputDialog(
            this,
            "Discard all history at or before this revision.\nCurrent revision is $currentRevision.",
            "Compact etcd History",
            null,
            currentRevision.toString(),
            object : com.intellij.openapi.ui.InputValidator {
                override fun checkInput(text: String?) = text?.trim()?.toLongOrNull()?.let { it > 0 } == true
                override fun canClose(text: String?) = checkInput(text)
            },
        )?.trim()?.toLongOrNull() ?: return

        val confirmed = EtcdUiUtil.confirmDestructive(
            project,
            "Compact History",
            "Compact every revision at or before $revision?\n\n" +
                "History before that point is discarded permanently, and watches starting from a " +
                "compacted revision will fail.",
        )
        if (!confirmed) return

        EtcdTasks.run(
            project = project,
            title = "Compacting to revision $revision",
            work = { active.kv.compact(revision) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Compacted history up to revision $revision")
                refresh()
            },
        )
    }

    private fun defragment() {
        val active = client ?: return
        val confirmed = EtcdUiUtil.confirmDestructive(
            project,
            "Defragment",
            "Defragment the backend database of ${active.connection.baseUrl}?\n\n" +
                "The member stops serving requests while this runs, which can take a while on a large store.",
        )
        if (!confirmed) return

        EtcdTasks.run(
            project = project,
            title = "Defragmenting",
            work = { active.cluster.defragment() },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Defragmentation finished")
                refresh()
            },
        )
    }

    private fun selectedMember(): EtcdMember? {
        val row = memberTable.selectedRow.takeIf { it >= 0 }
        if (row == null) {
            Messages.showInfoMessage(this, "Select a member first.", "No Member Selected")
            return null
        }
        return memberModel.memberAt(memberTable.convertRowIndexToModel(row))
    }

    private class MemberTableModel : AbstractTableModel() {
        private var members: List<EtcdMember> = emptyList()
        private var leaderId: Long = 0

        fun setMembers(newMembers: List<EtcdMember>, leader: Long = 0) {
            members = newMembers
            leaderId = leader
            fireTableDataChanged()
        }

        fun memberAt(index: Int): EtcdMember? = members.getOrNull(index)

        override fun getRowCount(): Int = members.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = members[rowIndex].let {
            when (columnIndex) {
                0 -> it.idHex
                1 -> it.name + if (it.id == leaderId) "  (leader)" else ""
                2 -> it.clientUrls.joinToString(", ")
                3 -> it.peerUrls.joinToString(", ")
                else -> if (it.isLearner) "learner" else "voting"
            }
        }

        private companion object {
            val COLUMNS = arrayOf("ID", "Name", "Client URLs", "Peer URLs", "Role")
        }
    }

    private class AlarmTableModel : AbstractTableModel() {
        private var alarms: List<EtcdAlarm> = emptyList()

        fun setAlarms(newAlarms: List<EtcdAlarm>) {
            alarms = newAlarms
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = alarms.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "Member" else "Alarm"

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = alarms[rowIndex].let {
            if (columnIndex == 0) java.lang.Long.toUnsignedString(it.memberId, 16) else it.alarm
        }
    }
}
