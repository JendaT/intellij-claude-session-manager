package com.github.jendalen.claudesessionmanager.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Centralized notification helper for the plugin.
 * Eliminates duplication across services and actions.
 */
object NotificationHelper {

    const val GROUP_ID = "ClaudeSessionManager"

    /**
     * Shows a notification with title and content.
     */
    fun show(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * Shows a notification with content only (no title).
     */
    fun show(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * Shows an info notification.
     */
    fun info(project: Project, title: String, content: String) {
        show(project, title, content, NotificationType.INFORMATION)
    }

    /**
     * Shows a warning notification.
     */
    fun warning(project: Project, title: String, content: String) {
        show(project, title, content, NotificationType.WARNING)
    }

    /**
     * Shows an error notification.
     */
    fun error(project: Project, title: String, content: String) {
        show(project, title, content, NotificationType.ERROR)
    }
}
