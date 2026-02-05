package com.github.jendalen.claudesessionmanager.ui

import com.github.jendalen.claudesessionmanager.model.ClaudeSession
import com.github.jendalen.claudesessionmanager.services.ClaudeSessionService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Dialog for selecting a Claude session to bind to a terminal tab.
 */
class SessionPickerDialog(
    private val project: Project
) : DialogWrapper(project, true) {

    private val sessionService = ClaudeSessionService.getInstance(project)
    private val allSessions: List<ClaudeSession> = sessionService.getSessions()

    private val listModel = DefaultListModel<ClaudeSession>()
    private val sessionList = JBList(listModel)
    private val searchField = JBTextField()

    var selectedSession: ClaudeSession? = null
        private set

    init {
        title = "Select Claude Session"
        setOKButtonText("Bind")
        init()
        updateList("")
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(500, 400)

        // Search field
        searchField.emptyText.text = "Search sessions..."
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateList(searchField.text)
            override fun removeUpdate(e: DocumentEvent) = updateList(searchField.text)
            override fun changedUpdate(e: DocumentEvent) = updateList(searchField.text)
        })
        panel.add(searchField, BorderLayout.NORTH)

        // Session list
        sessionList.cellRenderer = SessionListCellRenderer()
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.addListSelectionListener {
            selectedSession = sessionList.selectedValue
            isOKActionEnabled = selectedSession != null
        }
        sessionList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && selectedSession != null) {
                    doOKAction()
                }
            }
        })

        val scrollPane = JBScrollPane(sessionList)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Info label
        val infoLabel = JLabel("${allSessions.size} sessions found for this project")
        infoLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        panel.add(infoLabel, BorderLayout.SOUTH)

        isOKActionEnabled = false
        return panel
    }

    private fun updateList(filter: String) {
        listModel.clear()
        val filtered = if (filter.isBlank()) {
            allSessions
        } else {
            allSessions.filter { session ->
                session.displaySummary().contains(filter, ignoreCase = true) ||
                session.sessionId.contains(filter, ignoreCase = true) ||
                session.gitBranch.contains(filter, ignoreCase = true)
            }
        }
        filtered.forEach { listModel.addElement(it) }
    }

    override fun getPreferredFocusedComponent(): JComponent = searchField

    /**
     * Custom cell renderer for session list.
     */
    private class SessionListCellRenderer : ColoredListCellRenderer<ClaudeSession>() {
        private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(ZoneId.systemDefault())

        override fun customizeCellRenderer(
            list: JList<out ClaudeSession>,
            value: ClaudeSession?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return

            // Main text: summary
            append(value.displaySummary(), SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Branch info
            if (value.gitBranch.isNotBlank()) {
                append("  ")
                append("[${value.gitBranch}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            // Message count
            append("  ")
            append("${value.messageCount} msgs", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

            // Date
            append("  ")
            val date = dateFormatter.format(value.modifiedInstant())
            append(date, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    companion object {
        /**
         * Shows the dialog and returns the selected session, or null if cancelled.
         */
        fun show(project: Project): ClaudeSession? {
            val dialog = SessionPickerDialog(project)
            return if (dialog.showAndGet()) {
                dialog.selectedSession
            } else {
                null
            }
        }
    }
}
