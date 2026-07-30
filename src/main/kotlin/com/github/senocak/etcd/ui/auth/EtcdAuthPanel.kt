package com.github.senocak.etcd.ui.auth

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.KeyUtil
import com.github.senocak.etcd.model.EtcdPermission
import com.github.senocak.etcd.model.EtcdRole
import com.github.senocak.etcd.model.EtcdUser
import com.github.senocak.etcd.model.PermissionType
import com.github.senocak.etcd.service.EtcdTasks
import com.github.senocak.etcd.ui.EtcdTab
import com.github.senocak.etcd.ui.EtcdUiUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The Auth tab: etcd's RBAC — users, roles and the permissions attached to them.
 *
 * Enabling auth locks out anonymous clients immediately, so the toggle warns first and the
 * connection has to carry credentials afterwards.
 */
class EtcdAuthPanel(private val project: Project) : JPanel(BorderLayout()), EtcdTab {

    override val title: String = "Auth"

    override val component: JComponent get() = this

    private var client: EtcdClient? = null

    private val userModel = UserTableModel()
    private val userTable = JBTable(userModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val roleModel = RoleTableModel()
    private val roleTable = JBTable(roleModel).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val refreshButton = EtcdUiUtil.button("Refresh", AllIcons.Actions.Refresh) { refresh() }
    private val addUserButton = EtcdUiUtil.button("Add User…", AllIcons.General.Add) { addUser() }
    private val deleteUserButton = EtcdUiUtil.button("Delete User", AllIcons.General.Remove) { deleteUser() }
    private val passwordButton = EtcdUiUtil.button("Change Password…", AllIcons.Actions.Edit) { changePassword() }
    private val grantRoleButton = EtcdUiUtil.button("Grant Role…", AllIcons.Actions.Upload) { grantRole() }
    private val revokeRoleButton = EtcdUiUtil.button("Revoke Role…", AllIcons.Actions.Download) { revokeRole() }

    private val addRoleButton = EtcdUiUtil.button("Add Role…", AllIcons.General.Add) { addRole() }
    private val deleteRoleButton = EtcdUiUtil.button("Delete Role", AllIcons.General.Remove) { deleteRole() }
    private val grantPermButton = EtcdUiUtil.button("Grant Permission…", AllIcons.Actions.Upload) { grantPermission() }
    private val revokePermButton =
        EtcdUiUtil.button("Revoke Permission", AllIcons.Actions.Download) { revokePermission() }

    private val enableAuthButton = EtcdUiUtil.button("Enable Auth", AllIcons.Actions.Execute) { enableAuth() }
    private val disableAuthButton = EtcdUiUtil.button("Disable Auth", AllIcons.Actions.Suspend) { disableAuth() }

    init {
        add(EtcdUiUtil.toolbar(refreshButton, enableAuthButton, disableAuthButton), BorderLayout.NORTH)

        val usersPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Users").apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
            add(JBScrollPane(userTable), BorderLayout.CENTER)
            add(
                EtcdUiUtil.toolbar(
                    addUserButton,
                    deleteUserButton,
                    passwordButton,
                    grantRoleButton,
                    revokeRoleButton,
                ),
                BorderLayout.SOUTH,
            )
        }
        val rolesPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Roles").apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
            add(JBScrollPane(roleTable), BorderLayout.CENTER)
            add(
                EtcdUiUtil.toolbar(addRoleButton, deleteRoleButton, grantPermButton, revokePermButton),
                BorderLayout.SOUTH,
            )
        }
        add(
            OnePixelSplitter(false, 0.45f).apply {
                firstComponent = usersPanel
                secondComponent = rolesPanel
            },
            BorderLayout.CENTER,
        )
        setConnected(false)
    }

    override fun onConnectionChanged(client: EtcdClient?) {
        this.client = client
        setConnected(client != null)
        if (client != null) {
            refresh()
        } else {
            userModel.setUsers(emptyList())
            roleModel.setRoles(emptyList())
        }
    }

    override fun onActivated() {
        if (client != null) refresh()
    }

    private fun setConnected(connected: Boolean) = EtcdUiUtil.setEnabled(
        connected,
        refreshButton,
        addUserButton,
        deleteUserButton,
        passwordButton,
        grantRoleButton,
        revokeRoleButton,
        addRoleButton,
        deleteRoleButton,
        grantPermButton,
        revokePermButton,
        enableAuthButton,
        disableAuthButton,
    )

    private fun refresh() {
        val active = client ?: return
        EtcdTasks.run(
            project = project,
            title = "Loading etcd users and roles",
            work = { active.auth.users() to active.auth.roles() },
            onSuccess = { (users, roles) ->
                userModel.setUsers(users)
                roleModel.setRoles(roles)
            },
        )
    }

    private fun addUser() {
        val active = client ?: return
        val name = prompt("User name:", "Add etcd User") ?: return
        val password = Messages.showPasswordDialog(project, "Password for '$name':", "Add etcd User", null) ?: return
        EtcdTasks.run(
            project = project,
            title = "Adding user $name",
            work = { active.auth.userAdd(name, password) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Added user $name")
                refresh()
            },
        )
    }

    private fun deleteUser() {
        val user = selectedUser() ?: return
        val active = client ?: return
        if (!EtcdUiUtil.confirmDestructive(project, "Delete User", "Delete user '${user.name}'?")) return
        EtcdTasks.run(
            project = project,
            title = "Deleting user ${user.name}",
            work = { active.auth.userDelete(user.name) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Deleted user ${user.name}")
                refresh()
            },
        )
    }

    private fun changePassword() {
        val user = selectedUser() ?: return
        val active = client ?: return
        val password = Messages.showPasswordDialog(project,
            "New password for '${user.name}':",
            "Change etcd Password",
            null,
        ) ?: return
        EtcdTasks.run(
            project = project,
            title = "Changing password for ${user.name}",
            work = { active.auth.userChangePassword(user.name, password) },
            onSuccess = { EtcdTasks.notifyInfo(project, "Password changed for ${user.name}") },
        )
    }

    private fun grantRole() {
        val user = selectedUser() ?: return
        val active = client ?: return
        val role = chooseRole("Grant which role to '${user.name}'?", roleModel.roleNames()) ?: return
        EtcdTasks.run(
            project = project,
            title = "Granting $role to ${user.name}",
            work = { active.auth.userGrantRole(user.name, role) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Granted $role to ${user.name}")
                refresh()
            },
        )
    }

    private fun revokeRole() {
        val user = selectedUser() ?: return
        val active = client ?: return
        if (user.roles.isEmpty()) {
            Messages.showInfoMessage(this, "'${user.name}' has no roles.", "Nothing to Revoke")
            return
        }
        val role = chooseRole("Revoke which role from '${user.name}'?", user.roles) ?: return
        EtcdTasks.run(
            project = project,
            title = "Revoking $role from ${user.name}",
            work = { active.auth.userRevokeRole(user.name, role) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Revoked $role from ${user.name}")
                refresh()
            },
        )
    }

    private fun addRole() {
        val active = client ?: return
        val name = prompt("Role name:", "Add etcd Role") ?: return
        EtcdTasks.run(
            project = project,
            title = "Adding role $name",
            work = { active.auth.roleAdd(name) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Added role $name")
                refresh()
            },
        )
    }

    private fun deleteRole() {
        val role = selectedRole() ?: return
        val active = client ?: return
        if (!EtcdUiUtil.confirmDestructive(project, "Delete Role", "Delete role '${role.name}'?")) return
        EtcdTasks.run(
            project = project,
            title = "Deleting role ${role.name}",
            work = { active.auth.roleDelete(role.name) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Deleted role ${role.name}")
                refresh()
            },
        )
    }

    private fun grantPermission() {
        val role = selectedRole() ?: return
        val active = client ?: return
        val dialog = PermissionDialog(role.name)
        if (!dialog.showAndGet()) return
        EtcdTasks.run(
            project = project,
            title = "Granting permission to ${role.name}",
            work = { active.auth.roleGrantPermission(role.name, dialog.key, dialog.type, dialog.asPrefix) },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Granted ${dialog.type} on '${dialog.key}' to ${role.name}")
                refresh()
            },
        )
    }

    private fun revokePermission() {
        val role = selectedRole() ?: return
        val active = client ?: return
        if (role.permissions.isEmpty()) {
            Messages.showInfoMessage(this, "'${role.name}' has no permissions.", "Nothing to Revoke")
            return
        }
        val labels = role.permissions.map { describe(it) }
        val chosen = choose("Revoke which permission from '${role.name}'?", "Revoke Permission", labels)
            ?: return
        val permission = role.permissions[labels.indexOf(chosen)]

        EtcdTasks.run(
            project = project,
            title = "Revoking permission from ${role.name}",
            work = {
                active.auth.roleRevokePermission(
                    role.name,
                    KeyUtil.encode(permission.key),
                    KeyUtil.encode(permission.rangeEnd),
                )
            },
            onSuccess = {
                EtcdTasks.notifyInfo(project, "Revoked permission from ${role.name}")
                refresh()
            },
        )
    }

    private fun enableAuth() {
        val active = client ?: return
        val confirmed = EtcdUiUtil.confirmDestructive(
            project,
            "Enable Authentication",
            "Enable etcd authentication?\n\n" +
                "Anonymous clients are locked out immediately. Make sure a 'root' user exists and that " +
                "this connection is configured with its credentials, or you will lose access.",
        )
        if (!confirmed) return
        EtcdTasks.run(
            project = project,
            title = "Enabling auth",
            work = { active.auth.enable() },
            onSuccess = {
                EtcdTasks.notifyWarning(
                    project,
                    "Authentication enabled — reconnect with credentials to keep working",
                )
            },
        )
    }

    private fun disableAuth() {
        val active = client ?: return
        val confirmed = EtcdUiUtil.confirmDestructive(
            project,
            "Disable Authentication",
            "Disable etcd authentication?\n\nEvery client will then have unrestricted access to the key space.",
        )
        if (!confirmed) return
        EtcdTasks.run(
            project = project,
            title = "Disabling auth",
            work = { active.auth.disable() },
            onSuccess = { EtcdTasks.notifyWarning(project, "Authentication disabled") },
        )
    }

    private fun prompt(message: String, title: String): String? =
        Messages.showInputDialog(this, message, title, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun chooseRole(message: String, roles: List<String>): String? {
        if (roles.isEmpty()) {
            Messages.showInfoMessage(this, "No roles defined yet.", "No Roles")
            return null
        }
        return choose(message, "Select Role", roles)
    }

    /** Single-choice picker. Replaces the deprecated `Messages.showChooseDialog`. */
    private fun choose(message: String, title: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        val combo = ComboBox(options.toTypedArray())
        val dialog = object : DialogWrapper(project) {
            init {
                setTitle(title)
                init()
            }

            override fun createCenterPanel(): JComponent = com.intellij.ui.dsl.builder.panel {
                row(message) { cell(combo) }
            }.also { it.border = JBUI.Borders.empty(8) }
        }
        return if (dialog.showAndGet()) combo.selectedItem as? String else null
    }

    private fun selectedUser(): EtcdUser? {
        val row = userTable.selectedRow.takeIf { it >= 0 }
        if (row == null) {
            Messages.showInfoMessage(this, "Select a user first.", "No User Selected")
            return null
        }
        return userModel.userAt(userTable.convertRowIndexToModel(row))
    }

    private fun selectedRole(): EtcdRole? {
        val row = roleTable.selectedRow.takeIf { it >= 0 }
        if (row == null) {
            Messages.showInfoMessage(this, "Select a role first.", "No Role Selected")
            return null
        }
        return roleModel.roleAt(roleTable.convertRowIndexToModel(row))
    }

    /** Collects the key, scope and permission type for a grant. */
    private inner class PermissionDialog(roleName: String) : DialogWrapper(project) {
        private val keyField = JBTextField(24)
        private val prefixCheckBox = JBCheckBox("Apply to the whole prefix", true)
        private val typeCombo = ComboBox(PermissionType.entries.toTypedArray())

        val key: String get() = keyField.text.trim()
        val asPrefix: Boolean get() = prefixCheckBox.isSelected
        val type: PermissionType get() = typeCombo.selectedItem as PermissionType

        init {
            title = "Grant Permission to '$roleName'"
            init()
        }

        override fun createCenterPanel(): JComponent = com.intellij.ui.dsl.builder.panel {
            row("Key:") { cell(keyField) }
            row("") { cell(prefixCheckBox) }
            row("Permission:") { cell(typeCombo) }
        }.also { it.border = JBUI.Borders.empty(8) }
    }

    private class UserTableModel : AbstractTableModel() {
        private var users: List<EtcdUser> = emptyList()

        fun setUsers(newUsers: List<EtcdUser>) {
            users = newUsers
            fireTableDataChanged()
        }

        fun userAt(index: Int): EtcdUser? = users.getOrNull(index)

        override fun getRowCount(): Int = users.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "User" else "Roles"

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = users[rowIndex].let {
            if (columnIndex == 0) it.name else it.roles.joinToString(", ")
        }
    }

    private class RoleTableModel : AbstractTableModel() {
        private var roles: List<EtcdRole> = emptyList()

        fun setRoles(newRoles: List<EtcdRole>) {
            roles = newRoles
            fireTableDataChanged()
        }

        fun roleAt(index: Int): EtcdRole? = roles.getOrNull(index)

        fun roleNames(): List<String> = roles.map { it.name }

        override fun getRowCount(): Int = roles.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "Role" else "Permissions"

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = roles[rowIndex].let {
            if (columnIndex == 0) it.name else it.permissions.joinToString(", ") { p -> describe(p) }
        }
    }

    private companion object {

        /** Renders a permission the way `etcdctl role get` does. */
        fun describe(permission: EtcdPermission): String {
            val scope = when {
                permission.rangeEnd.isEmpty() -> "'${permission.keyText}'"
                permission.rangeEnd.size == 1 && permission.rangeEnd[0].toInt() == 0 -> "everything"
                permission.key.contentEquals(
                    KeyUtil.decode(KeyUtil.prefixRange(permission.keyText).first),
                ) -> "'${permission.keyText}' (prefix)"

                else -> "['${permission.keyText}', '${permission.rangeEndText}')"
            }
            return "${permission.type} on $scope"
        }
    }
}
