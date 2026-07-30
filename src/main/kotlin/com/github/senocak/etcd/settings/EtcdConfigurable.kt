package com.github.senocak.etcd.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * *Settings → Tools → etcd* — the list of saved connections.
 *
 * Works on copies until Apply, so Cancel discards edits. Passwords are written straight to
 * PasswordSafe when a dialog is confirmed, since PasswordSafe has no notion of a pending change.
 */
class EtcdConfigurable : Configurable {

    private val listModel = DefaultListModel<EtcdConnection>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SimpleListCellRenderer.create { label, value, _ -> label.text = value?.toString().orEmpty() }
    }

    private var modified = false

    override fun getDisplayName(): String = "etcd"

    override fun createComponent(): JComponent {
        reset()
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addConnection() }
            .setEditAction { editConnection() }
            .setRemoveAction { removeConnection() }
            .disableUpDownActions()
            .createPanel()
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBLabel("etcd connections available to every project:"), BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
        }
    }

    private fun addConnection() {
        val dialog = EtcdConnectionDialog(activeProject(), EtcdConnection(), isNew = true)
        if (!dialog.showAndGet()) return
        val created = dialog.connection
        EtcdCredentials.setPassword(created.id, created.username, dialog.password)
        listModel.addElement(created)
        list.selectedIndex = listModel.size() - 1
        modified = true
    }

    private fun editConnection() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val dialog = EtcdConnectionDialog(activeProject(), listModel.get(index), isNew = false)
        if (!dialog.showAndGet()) return
        val edited = dialog.connection
        EtcdCredentials.setPassword(edited.id, edited.username, dialog.password)
        listModel.set(index, edited)
        modified = true
    }

    private fun removeConnection() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        listModel.remove(index)
        modified = true
    }

    override fun isModified(): Boolean = modified

    override fun apply() {
        EtcdSettingsState.getInstance().replaceAll(listModel.elements().toList())
        modified = false
    }

    override fun reset() {
        listModel.clear()
        EtcdSettingsState.getInstance().connections.forEach { listModel.addElement(it.copy()) }
        modified = false
    }

    /** The settings dialog is application-level, so any open project will do for file choosers. */
    private fun activeProject() = ProjectManager.getInstance().openProjects.firstOrNull()
}
