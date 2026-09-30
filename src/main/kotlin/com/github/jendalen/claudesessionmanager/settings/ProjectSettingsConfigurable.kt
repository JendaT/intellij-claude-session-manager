package com.github.jendalen.claudesessionmanager.settings

import com.github.jendalen.claudesessionmanager.model.TabProfile
import com.github.jendalen.claudesessionmanager.services.ProjectSettingsService
import com.github.jendalen.claudesessionmanager.util.LaunchArgsValidator
import com.github.jendalen.claudesessionmanager.util.TabProfiles
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import javax.swing.JComponent

/**
 * Project-scoped settings page ("Launch Profile") nested under the application page:
 * the project's default launch arguments and its tab profiles.
 */
class ProjectSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: DialogPanel? = null
    private val settings get() = ProjectSettingsService.getInstance(project)

    private var extraLaunchArgs = settings.extraLaunchArgs

    /** Mutable row for the table editor; converted to [TabProfile] on read. */
    private class ProfileRow(
        var name: String = "",
        var workingDirectory: String = "",
        var launchArgs: String = "",
        var keepOpen: Boolean = true
    ) {
        // Trimmed here: a trailing space typed into the name cell would otherwise make a
        // profile that never matches its own tab.
        fun toProfile() = TabProfile(name.trim(), workingDirectory.trim(), launchArgs.trim(), keepOpen)

        companion object {
            fun of(p: TabProfile) = ProfileRow(p.name, p.workingDirectory, p.launchArgs, p.keepOpen)
        }
    }

    private class TextColumn(
        title: String,
        private val get: (ProfileRow) -> String,
        private val set: (ProfileRow, String) -> Unit
    ) : ColumnInfo<ProfileRow, String>(title) {
        override fun valueOf(item: ProfileRow): String = get(item)
        override fun isCellEditable(item: ProfileRow): Boolean = true
        override fun setValue(item: ProfileRow, value: String?) = set(item, value ?: "")
    }

    private class KeepOpenColumn : ColumnInfo<ProfileRow, Boolean>("Keep open") {
        override fun valueOf(item: ProfileRow): Boolean = item.keepOpen
        override fun isCellEditable(item: ProfileRow): Boolean = true
        override fun setValue(item: ProfileRow, value: Boolean?) { item.keepOpen = value == true }
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun getWidth(table: javax.swing.JTable): Int = JBUI.scale(80)
    }

    private val model = ListTableModel<ProfileRow>(
        TextColumn("Tab name", { it.name }, { r, v -> r.name = v }),
        TextColumn("Working directory", { it.workingDirectory }, { r, v -> r.workingDirectory = v }),
        TextColumn("Launch arguments (empty: project default)", { it.launchArgs }, { r, v -> r.launchArgs = v }),
        KeepOpenColumn()
    )
    private var table: TableView<ProfileRow>? = null
    private val problems = JBLabel()

    override fun getDisplayName(): String = "Launch Profile"

    override fun createComponent(): JComponent {
        val tableView = TableView(model).apply {
            setShowGrid(false)
            visibleRowCount = 5
        }
        table = tableView
        model.addTableModelListener { refreshProblems() }
        val decorated = ToolbarDecorator.createDecorator(tableView)
            .setAddAction {
                model.addRow(ProfileRow())
                val row = model.rowCount - 1
                tableView.selectionModel.setSelectionInterval(row, row)
                tableView.editCellAt(row, 0)
            }
            .setRemoveAction {
                tableView.cellEditor?.stopCellEditing()
                tableView.selectedRows.sortedDescending().forEach { model.removeRow(it) }
            }
            .createPanel()

        panel = panel {
            group("Extra Launch Arguments (this project)") {
                row("Arguments:") {
                    textField()
                        .bindText(::extraLaunchArgs)
                        .columns(60)
                        .comment(
                            "Inserted after the Claude command on every launch in this project: " +
                                "<code>claude &lt;arguments&gt; --resume &lt;id&gt;</code>. " +
                                "Example: <code>--channels plugin:name@marketplace</code>. " +
                                "Letters, digits and <code>_ . : = / , @ -</code> only; session selection flags are not allowed."
                        )
                }
            }
            group("Tab Profiles") {
                row {
                    comment(
                        "A terminal tab named exactly as a profile starts Claude in the profile's directory " +
                            "(for example a git worktree outside the project folder) with the profile's arguments: " +
                            "<code>cd '&lt;dir&gt;' &amp;&amp; claude &lt;arguments&gt; --resume &lt;id&gt;</code>. " +
                            "Other tabs are unaffected. Renaming a profile tab makes it an ordinary tab. " +
                            "\"Keep open\" tabs are reopened at project open. Open them with " +
                            "Tools | Open Profile Tabs or from the terminal's new-tab menu."
                    )
                }
                row { cell(decorated).align(Align.FILL) }.resizableRow()
                row { cell(problems) }
            }
        }
        reset()
        return panel!!
    }

    /**
     * The table's rows as profiles. [commitEdit] ends an in-progress cell edit first -
     * apply() needs that, but isModified() must not: the Settings dialog polls it while
     * the user is typing, and committing there would end the edit under their cursor.
     */
    private fun currentProfiles(commitEdit: Boolean): List<TabProfile> {
        if (commitEdit) table?.cellEditor?.stopCellEditing()
        return model.items.map { it.toProfile() }
    }

    /** Errors block the save; warnings are informational (a worktree may come later). */
    private fun refreshProblems() {
        val v = TabProfiles.validate(model.items.map { it.toProfile() })
        val lines = v.errors.map { "Error: $it" } + v.warnings.map { "Warning: $it" }
        problems.text = if (lines.isEmpty()) "" else
            "<html>" + lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
        problems.foreground = if (v.errors.isNotEmpty()) JBUI.CurrentTheme.Validator.errorBorderColor()
        else JBUI.CurrentTheme.Validator.warningBorderColor()
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true ||
            extraLaunchArgs != settings.extraLaunchArgs ||
            currentProfiles(commitEdit = false) != settings.storedTabProfiles
    }

    override fun apply() {
        // Transfer the UI value to the bound property first - without this the field
        // appears to save but reverts on the next open (documented trap in this codebase).
        panel?.apply()
        val reason = LaunchArgsValidator.rejectionReason(extraLaunchArgs)
        if (reason != null) {
            throw ConfigurationException(reason)
        }
        val profiles = currentProfiles(commitEdit = true)
        val validation = TabProfiles.validate(profiles)
        if (!validation.isValid) {
            throw ConfigurationException(validation.errors.joinToString("\n"), "Tab Profiles")
        }
        settings.extraLaunchArgs = extraLaunchArgs
        settings.storedTabProfiles = profiles
        // Reload what was stored (arguments normalised), so isModified() settles.
        model.items = settings.storedTabProfiles.map { ProfileRow.of(it) }
    }

    override fun reset() {
        extraLaunchArgs = settings.extraLaunchArgs
        panel?.reset()
        model.items = settings.storedTabProfiles.map { ProfileRow.of(it) }
        refreshProblems()
    }

    override fun disposeUIResources() {
        panel = null
        table = null
    }
}
