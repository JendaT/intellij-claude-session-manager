package com.github.jendalen.claudesessionmanager.teams.ui

import com.github.jendalen.claudesessionmanager.teams.model.InboxMessage
import com.github.jendalen.claudesessionmanager.teams.services.TeamsSettingsService
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Panel showing the last N inter-agent messages in the team.
 * Read-only, plain text only (no HTML rendering for security).
 */
class MessageLogPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<InboxMessage>()
    private val messageList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MessageCellRenderer()
    }

    init {
        add(JBScrollPane(messageList), BorderLayout.CENTER)
    }

    fun addMessage(message: InboxMessage) {
        val settings = TeamsSettingsService.getInstance().getSettings()
        listModel.addElement(message)

        // Trim to max messages
        while (listModel.size() > settings.maxMessagesInLog) {
            listModel.remove(0)
        }

        // Auto-scroll to bottom
        messageList.ensureIndexIsVisible(listModel.size() - 1)
    }

    fun setMessages(messages: List<InboxMessage>) {
        listModel.clear()
        val settings = TeamsSettingsService.getInstance().getSettings()
        val trimmed = messages.takeLast(settings.maxMessagesInLog)
        trimmed.forEach { listModel.addElement(it) }

        if (listModel.size() > 0) {
            messageList.ensureIndexIsVisible(listModel.size() - 1)
        }
    }

    private class MessageCellRenderer : ColoredListCellRenderer<InboxMessage>() {
        override fun customizeCellRenderer(
            list: JList<out InboxMessage>,
            value: InboxMessage,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            val settings = TeamsSettingsService.getInstance().getSettings()

            append("${value.from}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(" -> ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("${value.to}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.contentPreview(settings.truncateMessageLength), SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Full content in tooltip. Escape HTML to prevent injection via
            // message content that could contain <script> or other tags.
            toolTipText = value.content
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }
}
