package com.github.senocak.etcd.ui.kv

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.KeyUtil
import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdKeyValue
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.SwingConstants

/** How a value is rendered for editing. */
enum class ValueView(val label: String) {
    TEXT("Text"),
    JSON("JSON"),
    BASE64("Base64"),
    HEX("Hex (read-only)"),
}

/**
 * Shows and edits the value of one key.
 *
 * Saving is guarded by a compare-and-set on the key's mod revision, so a save cannot silently
 * overwrite a change someone else made since the value was loaded.
 */
class EtcdValueEditorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var client: EtcdClient? = null
    private var current: EtcdKeyValue? = null

    private val keyLabel = JBLabel().apply { border = JBUI.Borders.empty(4, 6) }
    private val metadataLabel = JBLabel().apply {
        border = JBUI.Borders.empty(2, 6)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val viewCombo = ComboBox(ValueView.entries.toTypedArray()).apply {
        renderer = com.intellij.ui.SimpleListCellRenderer.create { label, value, _ ->
            label.text = value?.label.orEmpty()
        }
        addActionListener { renderValue() }
    }

    private val editor = EditorTextField("", project, plainTextType()).apply {
        setOneLineMode(false)
        addSettingsProvider { it.configureAsValueEditor() }
    }

    private val saveButton = EtcdUiUtil.button("Save", AllIcons.Actions.MenuSaveall) { save() }
    private val revertButton = EtcdUiUtil.button("Revert", AllIcons.Actions.Rollback) { renderValue() }
    private val formatButton = EtcdUiUtil.button("Format JSON", AllIcons.Actions.PrettyPrint) { formatJson() }
    private val copyKeyButton = EtcdUiUtil.button("Copy Key", AllIcons.Actions.Copy) { copyKey() }

    private val emptyState = EtcdUiUtil.placeholder("Select a key to view its value")

    init {
        val header = JPanel(BorderLayout()).apply {
            add(keyLabel, BorderLayout.CENTER)
            add(EtcdUiUtil.toolbar(JBLabel("View:"), viewCombo), BorderLayout.EAST)
        }
        val footer = JPanel(BorderLayout()).apply {
            add(metadataLabel, BorderLayout.CENTER)
            add(
                EtcdUiUtil.toolbar(formatButton, copyKeyButton, revertButton, saveButton),
                BorderLayout.EAST,
            )
        }
        add(header, BorderLayout.NORTH)
        add(emptyState, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        showEmpty()
    }

    fun setClient(newClient: EtcdClient?) {
        client = newClient
        if (newClient == null) showEmpty()
    }

    /** Loads [key] and displays it. Passing null clears the panel. */
    fun showKey(key: String?) {
        val active = client
        if (key == null || active == null) {
            showEmpty()
            return
        }
        EtcdTasks.run(
            project = project,
            title = "Reading etcd key",
            work = { active.kv.get(key) },
            onSuccess = { kv ->
                if (kv == null) {
                    showEmpty("Key '$key' no longer exists")
                } else {
                    current = kv
                    showEditor()
                    chooseDefaultView(kv)
                    renderValue()
                }
            },
        )
    }

    /** Prepares the panel for a key that does not exist yet. */
    fun showNewKey(key: String) {
        current = EtcdKeyValue(key.toByteArray(Charsets.UTF_8), ByteArray(0), 0, 0, 0, 0)
        showEditor()
        viewCombo.selectedItem = ValueView.TEXT
        renderValue()
    }

    /** Reloads the key currently displayed, if any. Used after an external change. */
    fun refresh() = current?.let { showKey(it.keyText) }

    val displayedKey: String? get() = current?.keyText

    private fun showEmpty(message: String = "Select a key to view its value") {
        current = null
        emptyState.isVisible = true
        emptyState.text = message
        editor.isVisible = false
        keyLabel.text = ""
        metadataLabel.text = ""
        EtcdUiUtil.setEnabled(false, saveButton, revertButton, formatButton, copyKeyButton, viewCombo)
        remove(editor)
        add(emptyState, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showEditor() {
        remove(emptyState)
        add(editor, BorderLayout.CENTER)
        editor.isVisible = true
        EtcdUiUtil.setEnabled(true, saveButton, revertButton, copyKeyButton, viewCombo)
        revalidate()
        repaint()
    }

    /** Picks the most useful view for a value: JSON when it parses, Hex when it is not text. */
    private fun chooseDefaultView(kv: EtcdKeyValue) {
        viewCombo.selectedItem = when {
            !KeyUtil.isPrintableText(kv.value) -> ValueView.HEX
            looksLikeJson(kv.valueText) -> ValueView.JSON
            else -> ValueView.TEXT
        }
    }

    private fun renderValue() {
        val kv = current ?: return
        val view = viewCombo.selectedItem as? ValueView ?: ValueView.TEXT
        editor.fileType = if (view == ValueView.JSON) jsonTypeOrPlain() else plainTextType()
        editor.text = when (view) {
            ValueView.TEXT, ValueView.JSON -> kv.valueText
            ValueView.BASE64 -> KeyUtil.encode(kv.value)
            ValueView.HEX -> KeyUtil.toHex(kv.value)
        }
        // Hex is a rendering, not a round-trippable encoding, so it cannot be saved from.
        editor.setViewer(view == ValueView.HEX)
        saveButton.isEnabled = view != ValueView.HEX
        revertButton.isEnabled = view != ValueView.HEX
        formatButton.isEnabled = view == ValueView.JSON
        keyLabel.text = "<html><b>${kv.keyText}</b></html>"
        metadataLabel.text = if (kv.version == 0L) {
            "new key — not yet written"
        } else {
            buildString {
                append("create rev ${kv.createRevision}   mod rev ${kv.modRevision}   version ${kv.version}")
                if (kv.lease != 0L) append("   lease ${java.lang.Long.toUnsignedString(kv.lease)}")
                append("   ${kv.value.size} bytes")
            }
        }
    }

    /** Turns what is in the editor back into the bytes to store. */
    private fun editedBytes(): ByteArray? = when (viewCombo.selectedItem as? ValueView) {
        ValueView.BASE64 -> runCatching { KeyUtil.decode(editor.text.trim()) }.getOrNull()
        ValueView.HEX -> null
        else -> editor.text.toByteArray(Charsets.UTF_8)
    }

    private fun save() {
        val kv = current ?: return
        val active = client ?: return
        val bytes = editedBytes()
        if (bytes == null) {
            Messages.showErrorDialog(this, "The Base64 value is not valid.", "Cannot Save")
            return
        }
        val key = kv.keyText
        val expectedRevision = kv.modRevision

        EtcdTasks.run(
            project = project,
            title = "Saving $key",
            work = {
                if (expectedRevision == 0L) {
                    // Brand new key: only create it if nobody beat us to it.
                    active.kv.putIfAbsent(key, bytes, kv.lease)
                } else {
                    active.kv.putIfUnchanged(key, bytes, expectedRevision, kv.lease)
                }
            },
            onSuccess = { succeeded ->
                if (succeeded) {
                    EtcdTasks.notifyInfo(project, "Saved $key")
                    showKey(key)
                    onSaved?.invoke(key)
                } else {
                    confirmOverwrite(active, key, bytes, kv.lease)
                }
            },
        )
    }

    /**
     * The compare-and-set failed, meaning the key changed underneath us.
     *
     * Rather than lose the user's edit, offer an unconditional overwrite or a reload.
     */
    private fun confirmOverwrite(active: EtcdClient, key: String, bytes: ByteArray, lease: Long) {
        val choice = Messages.showYesNoCancelDialog(
            this,
            "'$key' changed in etcd since it was loaded.\n\nOverwrite it with your version, or discard your edit and reload?",
            "Key Changed",
            "Overwrite",
            "Reload",
            "Cancel",
            Messages.getWarningIcon(),
        )
        when (choice) {
            Messages.YES -> EtcdTasks.run(
                project = project,
                title = "Overwriting $key",
                work = { active.kv.put(key, bytes, lease) },
                onSuccess = {
                    EtcdTasks.notifyInfo(project, "Overwrote $key")
                    showKey(key)
                    onSaved?.invoke(key)
                },
            )

            Messages.NO -> showKey(key)
            else -> Unit
        }
    }

    private fun formatJson() {
        val text = editor.text
        val formatted = runCatching { prettyPrint(Json.parse(text)) }.getOrNull()
        if (formatted == null) {
            Messages.showErrorDialog(this, "The value is not valid JSON.", "Cannot Format")
            return
        }
        editor.text = formatted
    }

    private fun copyKey() {
        current?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.keyText)) }
    }

    /** Called after a successful save, so the tree can refresh the affected branch. */
    var onSaved: ((String) -> Unit)? = null

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) &&
            !(trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            return false
        }
        return runCatching { Json.parse(trimmed) }.isSuccess
    }

    /**
     * The JSON file type if the IDE has JSON support, otherwise plain text.
     *
     * Resolved by extension rather than by class so the plugin keeps working in IDEs where the
     * JSON plugin is absent, without declaring a dependency on it.
     */
    private fun jsonTypeOrPlain(): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension("json").takeIf { it != PlainTextFileType.INSTANCE }
            ?: plainTextType()

    private fun plainTextType(): FileType = PlainTextFileType.INSTANCE

    private companion object {

        fun EditorEx.configureAsValueEditor() {
            setVerticalScrollbarVisible(true)
            setHorizontalScrollbarVisible(true)
            settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = true
                isUseSoftWraps = true
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
        }

        /** Re-emits parsed JSON with two-space indentation. */
        fun prettyPrint(value: com.github.senocak.etcd.json.JsonValue, indent: String = ""): String {
            val next = "$indent  "
            return when (value) {
                is com.github.senocak.etcd.json.JsonObject -> if (value.fields.isEmpty()) {
                    "{}"
                } else {
                    value.fields.entries.joinToString(
                        separator = ",\n",
                        prefix = "{\n",
                        postfix = "\n$indent}",
                    ) { (k, v) -> "$next${Json.of(k).write()}: ${prettyPrint(v, next)}" }
                }

                is com.github.senocak.etcd.json.JsonArray -> if (value.items.isEmpty()) {
                    "[]"
                } else {
                    value.items.joinToString(
                        separator = ",\n",
                        prefix = "[\n",
                        postfix = "\n$indent]",
                    ) { "$next${prettyPrint(it, next)}" }
                }

                else -> value.write()
            }
        }
    }
}
