package com.github.senocak.etcd.service

import com.github.senocak.etcd.client.EtcdException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Runs blocking etcd calls off the EDT and delivers their results back on it.
 *
 * Every etcd RPC is a synchronous HTTP round trip, so nothing in [com.github.senocak.etcd.client]
 * may be called from the event thread. Funnelling UI work through here keeps that rule in one
 * place and gives every failure a consistent balloon.
 */
object EtcdTasks {

    private val LOG = logger<EtcdTasks>()

    const val NOTIFICATION_GROUP = "etcd"

    /**
     * Runs [work] on a background thread with a progress indicator, then [onSuccess] on the EDT.
     *
     * Failures are reported as an error balloon unless [onError] handles them. The result is
     * discarded if the project closes while the task is running.
     */
    fun <T> run(
        project: Project,
        title: String,
        work: () -> T,
        onSuccess: (T) -> Unit = {},
        onError: ((Exception) -> Unit)? = null,
    ) {
        object : Task.Backgroundable(project, title, true) {
            private var result: T? = null
            private var failure: Exception? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = work()
                } catch (e: EtcdException) {
                    failure = e
                } catch (e: Exception) {
                    LOG.warn("etcd task '$title' failed", e)
                    failure = e
                }
            }

            override fun onSuccess() {
                val error = failure
                if (error != null) {
                    if (onError != null) onError(error) else notifyError(project, title, error)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                onSuccess(result as T)
            }
        }.queue()
    }

    /** Posts an error balloon describing [error]. */
    fun notifyError(project: Project, title: String, error: Exception) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        notify(project, "$title failed: $detail", NotificationType.ERROR)
    }

    fun notifyInfo(project: Project, message: String) = notify(project, message, NotificationType.INFORMATION)

    fun notifyWarning(project: Project, message: String) = notify(project, message, NotificationType.WARNING)

    private fun notify(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(message, type)
                .notify(project)
        }
    }
}
