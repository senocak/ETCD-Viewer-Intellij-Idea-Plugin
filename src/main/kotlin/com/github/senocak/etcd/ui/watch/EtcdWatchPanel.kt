package com.github.senocak.etcd.ui.watch

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.EtcdException
import com.github.senocak.etcd.client.EtcdWatchStream
import com.github.senocak.etcd.model.WatchEvent
import com.github.senocak.etcd.model.WatchEventType
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdTab
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** One row of the event log. */
private data class WatchRow(
    val time: String,
    val type: WatchEventType,
    val key: String,
    val revision: Long,
    val value: String,
)

/**
 * The Watch tab: streams live PUT and DELETE events for a key or prefix.
 *
 * Events arrive on the watch stream's own thread, so every table update hops to the EDT.
 */
class EtcdWatchPanel(private val project: Project) : JPanel(BorderLayout()), EtcdTab {

    override val title: String = "Watch"

    override val component: JComponent get() = this

    private var client: EtcdClient? = null
    private var stream: EtcdWatchStream? = null

    private val prefixField = JBTextField(24).apply {
        emptyText.text = "Key or prefix, e.g. /app/"
    }
    private val prefixCheckBox = JBCheckBox("Watch as prefix", true)
    private val autoScrollCheckBox = JBCheckBox("Auto-scroll", true)

    private val startButton = EtcdUiUtil.button("Start", AllIcons.Actions.Execute) { start() }
    private val stopButton = EtcdUiUtil.button("Stop", AllIcons.Actions.Suspend) { stop() }
    private val clearButton = EtcdUiUtil.button("Clear", AllIcons.Actions.GC) { clear() }

    private val statusLabel = JBLabel("Idle").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val tableModel = EventTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoCreateRowSorter = false
        columnModel.getColumn(0).preferredWidth = 90
        columnModel.getColumn(1).preferredWidth = 70
        columnModel.getColumn(2).preferredWidth = 320
        columnModel.getColumn(3).preferredWidth = 80
        columnModel.getColumn(4).preferredWidth = 400
    }

    /** Set by the tool window so a live change can refresh the Keys tab. */
    var onKeySpaceChanged: (() -> Unit)? = null

    private val notifyKeysTabCheckBox = JBCheckBox("Refresh Keys tab on change", false)

    init {
        add(
            EtcdUiUtil.toolbar(
                JBLabel("Watch:"),
                prefixField,
                prefixCheckBox,
                startButton,
                stopButton,
                clearButton,
                autoScrollCheckBox,
                notifyKeysTabCheckBox,
                statusLabel,
            ),
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
        setConnected(false)
    }

    override fun onConnectionChanged(client: EtcdClient?) {
        stop()
        this.client = client
        setConnected(client != null)
    }

    private fun setConnected(connected: Boolean) {
        EtcdUiUtil.setEnabled(connected, prefixField, prefixCheckBox, clearButton)
        startButton.isEnabled = connected && stream == null
        stopButton.isEnabled = connected && stream != null
    }

    private fun start() {
        val active = client ?: return
        if (stream != null) return
        val prefix = prefixField.text.trim()
        val asPrefix = prefixCheckBox.isSelected
        if (prefix.isEmpty() && !asPrefix) {
            statusLabel.text = "Enter a key to watch"
            return
        }
        statusLabel.text = "Starting…"

        stream = active.watch(
            prefix = prefix,
            watchPrefix = asPrefix,
            listener = object : EtcdWatchStream.Listener {
                override fun onCreated(watchId: Long) = onEdt {
                    statusLabel.text = "Watching '${prefix.ifEmpty { "(all keys)" }}'"
                }

                override fun onEvent(event: WatchEvent) = onEdt {
                    tableModel.add(
                        WatchRow(
                            time = LocalTime.now().format(TIME_FORMAT),
                            type = event.type,
                            key = event.kv.keyText,
                            revision = event.kv.modRevision,
                            value = when (event.type) {
                                WatchEventType.DELETE ->
                                    event.prevKv?.let { "(was) ${EtcdUiUtil.preview(it.valueText)}" }.orEmpty()

                                WatchEventType.PUT -> EtcdUiUtil.preview(event.kv.valueText)
                            },
                        ),
                    )
                    if (autoScrollCheckBox.isSelected) {
                        val last = tableModel.rowCount - 1
                        table.scrollRectToVisible(table.getCellRect(last, 0, true))
                    }
                    if (notifyKeysTabCheckBox.isSelected) onKeySpaceChanged?.invoke()
                }

                override fun onClosed(error: EtcdException?) = onEdt {
                    stream = null
                    statusLabel.text = error?.let { "Stopped: ${it.message}" } ?: "Stopped"
                    if (error != null) EtcdTasks.notifyError(project, "etcd watch", error)
                    setConnected(client != null)
                }
            },
        )
        setConnected(true)
    }

    private fun stop() {
        stream?.stop()
        stream = null
        setConnected(client != null)
    }

    private fun clear() = tableModel.clear()

    /** Watch callbacks arrive on the stream's reader thread; the table must be touched on the EDT. */
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) block()
        }, com.intellij.openapi.application.ModalityState.any())
    }

    private class EventTableModel : AbstractTableModel() {
        private val rows = ArrayList<WatchRow>()

        /** Bounded so a busy cluster cannot grow the log without limit. */
        private val maxRows = 5_000

        fun add(row: WatchRow) {
            rows.add(row)
            if (rows.size > maxRows) {
                val drop = rows.size - maxRows
                repeat(drop) { rows.removeAt(0) }
                fireTableDataChanged()
            } else {
                fireTableRowsInserted(rows.size - 1, rows.size - 1)
            }
        }

        fun clear() {
            rows.clear()
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let {
            when (columnIndex) {
                0 -> it.time
                1 -> it.type.name
                2 -> it.key
                3 -> it.revision
                else -> it.value
            }
        }

        private companion object {
            val COLUMNS = arrayOf("Time", "Type", "Key", "Revision", "Value")
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
