package com.github.jendalen.claudesessionmanager.util

import com.intellij.notification.Notification
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
     * Builds an escaped notification without showing it, for callers that need to
     * `addAction` first. The two auto-resume notifications built their own because of
     * that and bypassed the escaping - an `<img src>` in an OSC-set tab name was fetched
     * by the notification's editor pane.
     */
    fun create(title: String, content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(title), StringUtil.escapeXmlEntities(content), type)

    /**
     * Shows a notification with title and content.
     */
    fun show(project: Project, title: String, content: String, type: NotificationType) {
        create(title, content, type).notify(project)
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


}
