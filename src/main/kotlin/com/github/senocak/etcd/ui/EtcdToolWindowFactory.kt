package com.github.senocak.etcd.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Creates the etcd tool window. Available in every project — etcd is not tied to project content. */
class EtcdToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EtcdToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // Tying the panel to the content disposes its message-bus subscription with the window.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
