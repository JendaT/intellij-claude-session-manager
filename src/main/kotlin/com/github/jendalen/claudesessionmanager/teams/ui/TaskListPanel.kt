package com.github.jendalen.claudesessionmanager.teams.ui

import com.github.jendalen.claudesessionmanager.teams.model.TaskStatus
import com.github.jendalen.claudesessionmanager.teams.model.TeamTask
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Panel displaying the team's task list with status, description, and assignee.
 */
class TaskListPanel : JPanel(BorderLayout()) {

    private val tableModel = TaskTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 30
        columnModel.getColumn(0).maxWidth = 40
        columnModel.getColumn(1).preferredWidth = 250
        columnModel.getColumn(2).preferredWidth = 80
        columnModel.getColumn(3).preferredWidth = 70
    }

    init {
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun updateTasks(tasks: List<TeamTask>) {
        tableModel.setTasks(tasks)
    }

    private class TaskTableModel : AbstractTableModel() {
        private var tasks: List<TeamTask> = emptyList()

        private val columns = arrayOf("", "Description", "Assignee", "Status")

        fun setTasks(newTasks: List<TeamTask>) {
            tasks = newTasks
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = tasks.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val task = tasks[rowIndex]
            return when (columnIndex) {
                0 -> statusIcon(task.status)
                1 -> task.description.lineSequence().firstOrNull() ?: ""
                2 -> task.assignee
                3 -> task.status.name.lowercase().replace('_', ' ')
                else -> ""
            }
        }

        private fun statusIcon(status: TaskStatus): String {
            return when (status) {
                TaskStatus.COMPLETED -> "[v]"
                TaskStatus.IN_PROGRESS -> "[>]"
                TaskStatus.PENDING -> "[ ]"
                TaskStatus.BLOCKED -> "[x]"
            }
        }
    }
}
