package com.github.senocak.etcd.ui.kv

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.EtcdException
import com.github.senocak.etcd.client.EtcdWatchStream
import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.WatchEvent
import com.github.senocak.etcd.model.WatchEventType
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdTab
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * The Keys tab: a lazy prefix tree on the left, the value editor on the right.
 *
 * This is where the CRUD lives — create, read, update and delete, plus prefix-wide delete and
 * export.
 */
class EtcdKvPanel(private val project: Project) : JPanel(BorderLayout()), EtcdTab {

    override val title: String = "Keys"

    override val component: JComponent get() = this

    private var client: EtcdClient? = null

    private val editorPanel = EtcdValueEditorPanel(project)

    /** Internal rather than private so tests can assert what the live watch put on screen. */
    internal val tree = EtcdKeyTree(project) { key -> editorPanel.showKey(key) }

    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter by prefix, e.g. /app/"
        addKeyboardListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) applyFilter()
            }
        })
    }

    private val refreshButton = EtcdUiUtil.button("Refresh", AllIcons.Actions.Refresh) { refresh() }
    private val newKeyButton = EtcdUiUtil.button("New Key", AllIcons.General.Add) { createKey() }
    private val deleteKeyButton = EtcdUiUtil.button("Delete Key", AllIcons.General.Remove) { deleteKey() }
    private val deletePrefixButton =
        EtcdUiUtil.button("Delete Prefix…", AllIcons.Actions.GC) { deletePrefix() }
    private val exportButton = EtcdUiUtil.button("Export…", AllIcons.ToolbarDecorator.Export) { exportPrefix() }

    /**
     * Keeps the tree in step with the cluster by watching it.
     *
     * On by default: without it, a key added or removed by etcdctl or another client stays
     * invisible until a manual refresh. Can be switched off for very busy key spaces.
     */
    private val liveUpdatesCheckBox = JBCheckBox("Live updates", true).apply {
        toolTipText = "Watch the browsed prefix and apply changes as they happen"
        addActionListener { if (isSelected) startWatching() else stopWatching() }
    }

    /** The watch feeding [liveUpdatesCheckBox]; null when live updates are off or disconnected. */
    private var watch: EtcdWatchStream? = null

    init {
        val leftPanel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = leftPanel
            secondComponent = editorPanel
        }
        add(
            EtcdUiUtil.toolbar(
                refreshButton,
                newKeyButton,
                deleteKeyButton,
                deletePrefixButton,
                exportButton,
                liveUpdatesCheckBox,
            ),
            BorderLayout.NORTH,
        )
        add(splitter, BorderLayout.CENTER)
        border = JBUI.Borders.empty()

        // Saving from the editor can create a key the tree has not seen yet.
        editorPanel.onSaved = { key ->
            tree.reload()
            tree.selectKey(key)
        }

        registerDeleteShortcut()
        setConnected(false)
    }

    override fun onConnectionChanged(client: EtcdClient?) {
        stopWatching()
        this.client = client
        editorPanel.setClient(client)
        tree.setClient(client, prefixFilter())
        setConnected(client != null)
        if (client != null && liveUpdatesCheckBox.isSelected) startWatching()
    }

    /**
     * Watches the browsed prefix and folds each event straight into the tree.
     *
     * Events are applied incrementally rather than by refetching, so a change somewhere else in
     * the key space does not collapse the folder the user is working in.
     */
    private fun startWatching() {
        val active = client ?: return
        stopWatching()
        val watchedPrefix = tree.rootPrefix
        watch = active.watch(
            prefix = watchedPrefix,
            watchPrefix = true,
            listener = object : EtcdWatchStream.Listener {
                override fun onEvent(event: WatchEvent) = onEdt {
                    val key = event.kv.keyText
                    when (event.type) {
                        WatchEventType.PUT -> tree.onKeyPut(key)
                        WatchEventType.DELETE -> tree.onKeyDeleted(key)
                    }
                    editorPanel.onExternalChange(key, event.type == WatchEventType.DELETE)
                }

                override fun onClosed(error: EtcdException?) = onEdt {
                    watch = null
                    if (error != null && liveUpdatesCheckBox.isSelected) {
                        // Say so rather than silently going stale, which is the whole problem
                        // live updates exist to solve.
                        liveUpdatesCheckBox.isSelected = false
                        EtcdTasks.notifyWarning(
                            project,
                            "Live updates stopped: ${error.message}. Use Refresh to see changes.",
                        )
                    }
                }
            },
        )
    }

    private fun stopWatching() {
        watch?.stop()
        watch = null
    }

    /** Whether a watch is currently feeding the tree. Used by tests to await the wiring. */
    internal val isLiveUpdating: Boolean get() = watch?.isRunning == true

    /** Narrows the browsed prefix, exactly as typing in the filter box does. */
    internal fun setBrowsedPrefix(prefix: String) {
        searchField.text = prefix
    }

    /** Watch callbacks arrive on the stream's reader thread; the tree must be touched on the EDT. */
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) block()
        }, ModalityState.any())
    }

    override fun onActivated() {
        if (client != null && tree.selectedKey() == null) tree.requestFocusInWindow()
    }

    /** Reloads the tree and the value on screen. Also called by the Watch tab on live changes. */
    fun refresh() {
        tree.reload()
        editorPanel.refresh()
    }

    private fun setConnected(connected: Boolean) {
        EtcdUiUtil.setEnabled(
            connected,
            refreshButton,
            newKeyButton,
            deleteKeyButton,
            deletePrefixButton,
            exportButton,
            searchField,
        )
    }

    private fun prefixFilter(): String = searchField.text.trim()

    private fun applyFilter() {
        tree.setClient(client, prefixFilter())
        // The watch is scoped to the browsed prefix, so it has to follow the filter.
        if (liveUpdatesCheckBox.isSelected) startWatching()
    }

    private fun createKey() {
        val suggested = tree.selectedPrefix().orEmpty()
        val key = Messages.showInputDialog(
            this,
            "Full key path:",
            "New etcd Key",
            null,
            suggested,
            null,
        )?.trim()
        if (key.isNullOrEmpty()) return
        val active = client ?: return

        EtcdTasks.run(
            project = project,
            title = "Checking $key",
            work = { active.kv.get(key) },
            onSuccess = { existing ->
                if (existing != null) {
                    Messages.showWarningDialog(this, "'$key' already exists.", "Key Exists")
                    tree.selectKey(key)
                } else {
                    // Nothing is written until the user saves a value.
                    editorPanel.showNewKey(key)
                }
            },
        )
    }

    private fun deleteKey() {
        val key = tree.selectedKey() ?: editorPanel.displayedKey
        if (key == null) {
            Messages.showInfoMessage(this, "Select a key first.", "No Key Selected")
            return
        }
        val active = client ?: return
        if (!EtcdUiUtil.confirmDestructive(project, "Delete Key", "Delete '$key'?\n\nThis cannot be undone.")) return

        EtcdTasks.run(
            project = project,
            title = "Deleting $key",
            work = { active.kv.delete(key) },
            onSuccess = { deleted ->
                EtcdTasks.notifyInfo(project, if (deleted > 0) "Deleted $key" else "$key did not exist")
                refresh()
            },
        )
    }

    /**
     * Deletes an entire prefix, after showing how many keys that is.
     *
     * The count is fetched first and named in the confirmation, because "delete /" and
     * "delete /app/config/" look identical in a dialog until you see the number.
     */
    private fun deletePrefix() {
        val active = client ?: return
        val prefix = Messages.showInputDialog(
            this,
            "Delete every key under this prefix:",
            "Delete etcd Prefix",
            null,
            tree.selectedPrefix().orEmpty(),
            null,
        )?.trim() ?: return
        if (prefix.isEmpty()) {
            Messages.showWarningDialog(
                this,
                "An empty prefix would delete the entire key space. Enter a prefix.",
                "Prefix Required",
            )
            return
        }

        EtcdTasks.run(
            project = project,
            title = "Counting keys under $prefix",
            work = { active.kv.countPrefix(prefix) },
            onSuccess = { count ->
                if (count == 0L) {
                    Messages.showInfoMessage(this, "No keys match '$prefix'.", "Nothing to Delete")
                    return@run
                }
                val confirmed = EtcdUiUtil.confirmDestructive(
                    project,
                    "Delete Prefix",
                    "Delete $count key(s) under '$prefix'?\n\nThis cannot be undone.",
                )
                if (!confirmed) return@run
                EtcdTasks.run(
                    project = project,
                    title = "Deleting $prefix",
                    work = { active.kv.deletePrefix(prefix) },
                    onSuccess = { deleted ->
                        EtcdTasks.notifyInfo(project, "Deleted $deleted key(s) under $prefix")
                        refresh()
                    },
                )
            },
        )
    }

    /** Writes every key under a prefix to a JSON file as a flat key/value object. */
    private fun exportPrefix() {
        val active = client ?: return
        val prefix = tree.selectedPrefix().orEmpty()
        val descriptor = FileSaverDescriptor("Export etcd Keys", "Save keys under '$prefix' as JSON", "json")
        val chosen = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "etcd-export.json") ?: return
        val target: File = chosen.file

        EtcdTasks.run(
            project = project,
            title = "Exporting $prefix",
            work = {
                val result = active.kv.rangePrefix(prefix)
                // A flat key -> value object; values that are not UTF-8 text come out lossy, which
                // is why the export is offered as a convenience rather than as a backup format.
                val fields: List<Pair<String, Any?>> = result.kvs.map { it.keyText to it.valueText }
                target.writeText(Json.obj(*fields.toTypedArray()).write())
                result.kvs.size
            },
            onSuccess = { count ->
                EtcdTasks.notifyInfo(project, "Exported $count key(s) to ${target.absolutePath}")
            },
        )
    }

    /** Delete key removes the selected key, matching how the rest of the IDE's trees behave. */
    private fun registerDeleteShortcut() {
        val actionKey = "etcd.deleteKey"
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), actionKey)
        tree.actionMap.put(
            actionKey,
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = deleteKey()
            },
        )
    }
}
