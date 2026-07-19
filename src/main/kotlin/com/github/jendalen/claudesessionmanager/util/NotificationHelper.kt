package com.github.jendalen.claudesessionmanager.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/**
 * Centralized notification helper for the plugin.
 * Eliminates duplication across services and actions.
 *
 * Notification content renders an HTML subset, and much of what the plugin shows comes from
 * untrusted sources (tab names settable via OSC escape sequences, session summaries from
 * ~/.claude data). All text is therefore HTML-escaped here, at the single choke point.
 */
object NotificationHelper {

    const val GROUP_ID = "ClaudeSessionManager"

    /**
     * Shows a notification with title and content.
     */
    fun show(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(title), StringUtil.escapeXmlEntities(content), type)
            .notify(project)
    }

    /**
     * Shows a notification with content only (no title).
     */
    fun show(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(content), type)
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
}
