package com.github.senocak.etcd.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import java.time.Duration
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Small Swing helpers shared by the tool window tabs. */
object EtcdUiUtil {

    /** A left-aligned strip of toolbar controls with consistent spacing. */
    fun toolbar(vararg components: Component): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            components.forEach { add(it) }
        }

    /** A compact toolbar button. Buttons start disabled and are enabled once connected. */
    fun button(text: String, icon: javax.swing.Icon? = null, action: () -> Unit): JButton =
        JButton(text, icon).apply {
            isFocusable = false
            margin = JBUI.insets(2, 6)
            addActionListener { action() }
        }

    /** A greyed-out hint shown in the middle of an empty panel. */
    fun placeholder(text: String): JBLabel =
        JBLabel(text, AllIcons.General.Information, SwingConstants.CENTER).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

    /** Enables or disables a whole group of controls in one call. */
    fun setEnabled(enabled: Boolean, vararg components: JComponent) {
        components.forEach { it.isEnabled = enabled }
    }

    /** Renders a byte count the way etcd's own tooling does. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }

    /** Renders a lease TTL in seconds as a readable duration. */
    fun formatTtl(seconds: Long): String = when {
        seconds < 0 -> "expired"
        seconds < 60 -> "${seconds}s"
        else -> Duration.ofSeconds(seconds).let {
            buildString {
                if (it.toHours() > 0) append("${it.toHours()}h ")
                if (it.toMinutesPart() > 0) append("${it.toMinutesPart()}m ")
                append("${it.toSecondsPart()}s")
            }.trim()
        }
    }

    /** Shortens a value for display in a table cell. */
    fun preview(text: String, limit: Int = 120): String {
        val single = text.replace(Regex("\\s+"), " ").trim()
        return if (single.length <= limit) single else single.take(limit) + "…"
    }

    /**
     * Asks for confirmation before something irreversible.
     *
     * Used for prefix deletes, compaction, defragmentation, member removal and disabling auth.
     */
    fun confirmDestructive(project: Project, title: String, message: String): Boolean =
        Messages.showYesNoDialog(project, message, title, "Proceed", "Cancel", Messages.getWarningIcon()) ==
            Messages.YES
}
