package com.github.jendalen.claudesessionmanager.terminal

import com.github.jendalen.claudesessionmanager.model.TabIdentifier
import com.github.jendalen.claudesessionmanager.services.SettingsService
import com.github.jendalen.claudesessionmanager.util.SessionIdValidator
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import javax.swing.Icon

/**
 * Represents a terminal tab that we can work with.
 * Abstracts over the differences between classic and reworked terminal.
 */
data class TerminalTabContext(
    val tabName: String,
    val terminalView: TerminalView?,
    val shellWidget: ShellTerminalWidget?,
    val content: Content?
) {
    fun canExecuteCommands(): Boolean = terminalView != null || shellWidget != null
}

/**
 * Helper for terminal operations.
 *
 * Works with ShellTerminalWidget (classic terminal) and TerminalView (reworked terminal).
 */
object TerminalHelper {

    private val logger = Logger.getInstance(TerminalHelper::class.java)

    /**
     * Key to mark terminal tabs as team-managed.
     * V1.0 services check this via [isTeamTab] to avoid interfering with team tabs.
     * V2.0 services set this via Content.putUserData().
     */
    val TEAM_TAB_KEY = Key.create<String>("ClaudeTeams.TeamTab")

    /**
     * Checks if a terminal tab Content is managed by the team system.
     */
    fun isTeamTab(content: Content): Boolean {
        return content.getUserData(TEAM_TAB_KEY) != null
    }

    /**
     * Gets the terminal context from the action event.
     * This is the main entry point for actions - works with both terminal engines.
     */
    fun getTerminalContext(e: AnActionEvent): TerminalTabContext? {
        val project = e.project ?: return null

        // Get the terminal tool window
        val toolWindow = getTerminalToolWindow(project) ?: return null
        val contentManager = toolWindow.contentManager
        val selectedContent = contentManager.selectedContent ?: return null

        val tabName = selectedContent.displayName ?: "Terminal"

        // Try to get TerminalView from DataContext (works when focus is in terminal)
        var terminalView: TerminalView? = try {
            e.getData(TerminalView.DATA_KEY)
        } catch (ex: Exception) {
            logger.debug("TerminalView.DATA_KEY not available: ${ex.message}")
            null
        }

        // If DataContext doesn't have TerminalView, try TerminalToolWindowTabsManager
        if (terminalView == null) {
            terminalView = findTerminalViewByTabName(project, tabName)
        }

        // Try to get ShellTerminalWidget (classic terminal)
        val shellWidget = findShellWidgetByName(project, tabName)

        // Return context if we have at least a tab name
        return TerminalTabContext(
            tabName = tabName,
            terminalView = terminalView,
            shellWidget = shellWidget,
            content = selectedContent
        )
    }

    /**
     * Finds a TerminalView by tab name using TerminalToolWindowTabsManager.
     * This is the correct API for reworked terminal (WebStorm 2025+).
     */
    fun findTerminalViewByTabName(project: Project, tabName: String): TerminalView? {
        return try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            for (tab in tabsManager.tabs) {
                val content = tab.content
                if (content.displayName == tabName) {
                    return tab.view
                }
            }
            null
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowTabsManager not available: ${ex.message}")
            null
        }
    }

    /**
     * Gets the TerminalView from the action event's data context.
     * This is the official way to get the terminal from context menu actions (reworked terminal).
     */
    fun getTerminalViewFromEvent(e: AnActionEvent): TerminalView? {
        return try {
            e.getData(TerminalView.DATA_KEY)
        } catch (ex: Exception) {
            logger.debug("TerminalView.DATA_KEY not available: ${ex.message}")
            null
        }
    }

    /**
     * Checks if the action is being performed in a terminal context.
     * Works with both terminal engines.
     */
    fun isInTerminalContext(e: AnActionEvent): Boolean {
        val project = e.project ?: return false

        // Check if we're in the terminal tool window
        val toolWindow = getTerminalToolWindow(project) ?: return false
        if (!toolWindow.isVisible) return false

        // Check if there's a selected content (a tab)
        val contentManager = toolWindow.contentManager
        return contentManager.selectedContent != null
    }

    /**
     * Gets a ShellTerminalWidget from the action event context.
     * Returns the currently selected terminal tab's widget.
     *
     * NOTE: This may return null in the reworked terminal (2025+).
     * Use getTerminalContext() for a more robust approach.
     */
    fun getShellTerminalWidgetFromEvent(e: AnActionEvent): ShellTerminalWidget? {
        val project = e.project ?: return null
        val context = getTerminalContext(e)
        return context?.shellWidget ?: getSelectedShellTerminalWidget(project)
    }

    /**
     * Finds a ShellTerminalWidget by tab name.
     */
    private fun findShellWidgetByName(project: Project, tabName: String): ShellTerminalWidget? {
        val terminalManager = TerminalToolWindowManager.getInstance(project)

        for (widget in terminalManager.terminalWidgets) {
            if (widget is ShellTerminalWidget) {
                val title = widget.terminalTitle.buildTitle()
                if (title == tabName) {
                    return widget
                }
            }
        }
        return null
    }

    /**
     * Gets the currently selected ShellTerminalWidget.
     */
    fun getSelectedShellTerminalWidget(project: Project): ShellTerminalWidget? {
        val terminalManager = TerminalToolWindowManager.getInstance(project)

        // Get the tool window to find the selected tab
        val toolWindow = getTerminalToolWindow(project) ?: return null
        val contentManager = toolWindow.contentManager
        val selectedContent = contentManager.selectedContent

        if (selectedContent != null) {
            val widget = findShellWidgetByName(project, selectedContent.displayName ?: "")
            if (widget != null) return widget
        }

        // Fallback: return first ShellTerminalWidget
        return terminalManager.terminalWidgets
            .filterIsInstance<ShellTerminalWidget>()
            .firstOrNull()
    }

    /**
     * Gets all ShellTerminalWidgets in the project.
     */
    fun getAllShellTerminalWidgets(project: Project): List<ShellTerminalWidget> {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        return terminalManager.terminalWidgets.filterIsInstance<ShellTerminalWidget>()
    }

    /**
     * Creates a TabIdentifier for a terminal context.
     */
    fun createTabIdentifier(context: TerminalTabContext): TabIdentifier {
        return TabIdentifier(
            name = context.tabName,
            createdAt = System.currentTimeMillis(),
            shellPid = null,
            workingDirectory = null
        )
    }

    /**
     * Creates a TabIdentifier for a terminal widget.
     */
    fun createTabIdentifier(widget: ShellTerminalWidget): TabIdentifier {
        return TabIdentifier(
            name = widget.terminalTitle.buildTitle() ?: "Terminal",
            createdAt = System.currentTimeMillis(),
            shellPid = null,
            workingDirectory = null
        )
    }

    /**
     * Executes a command in the terminal using the context.
     * Works with both terminal engines.
     */
    fun executeCommand(context: TerminalTabContext, command: String): Boolean {
        // Try TerminalView API first (reworked terminal)
        context.terminalView?.let { terminalView ->
            try {
                terminalView.createSendTextBuilder()
                    .shouldExecute()
                    .send(command)
                logger.info("Executed command via TerminalView: $command")
                return true
            } catch (ex: Exception) {
                logger.debug("TerminalView.sendText failed: ${ex.message}")
            }
        }

        // Fallback to ShellTerminalWidget (classic terminal)
        context.shellWidget?.let { widget ->
            return executeCommandInWidget(widget, command)
        }

        logger.warn("Cannot execute command - no terminal interface available")
        return false
    }

    /**
     * Executes a command in the terminal.
     * Tries TerminalView API first (reworked terminal), falls back to ShellTerminalWidget.
     */
    fun executeCommand(e: AnActionEvent, command: String): Boolean {
        val context = getTerminalContext(e)
        if (context != null) {
            return executeCommand(context, command)
        }

        // Legacy fallback
        val terminalView = getTerminalViewFromEvent(e)
        if (terminalView != null) {
            try {
                terminalView.createSendTextBuilder()
                    .shouldExecute()
                    .send(command)
                logger.info("Executed command via TerminalView: $command")
                return true
            } catch (ex: Exception) {
                logger.debug("TerminalView.sendText failed: ${ex.message}")
            }
        }

        val project = e.project ?: return false
        val widget = getSelectedShellTerminalWidget(project) ?: return false
        return executeCommandInWidget(widget, command)
    }

    /**
     * Executes a command in a specific ShellTerminalWidget.
     */
    fun executeCommandInWidget(widget: ShellTerminalWidget, command: String): Boolean {
        return try {
            widget.executeCommand(command)
            logger.info("Executed command via ShellTerminalWidget: $command")
            true
        } catch (ex: Exception) {
            logger.warn("Failed to execute command: ${ex.message}", ex)
            false
        }
    }

    /**
     * Gets the configured Claude command.
     */
    fun getClaudeCommand(): String {
        return SettingsService.getInstance().claudeCommand
    }

    /**
     * Builds the resume command using the configured Claude command.
     * Validates session ID to prevent command injection.
     *
     * @throws IllegalArgumentException if sessionId is not a valid UUID
     */
    fun buildResumeCommand(sessionId: String): String {
        // SECURITY: Validate session ID to prevent command injection
        require(SessionIdValidator.isValid(sessionId)) {
            "Invalid session ID format: $sessionId"
        }
        // Session ID is now guaranteed to be a valid UUID (alphanumeric + hyphens only)
        return "${getClaudeCommand()} --resume $sessionId"
    }

    /**
     * Resumes a Claude session in the given terminal.
     */
    fun resumeClaudeSession(widget: ShellTerminalWidget, sessionId: String): Boolean {
        val command = buildResumeCommand(sessionId)
        return executeCommandInWidget(widget, command)
    }

    /**
     * Executes the resume command in a terminal, trying multiple approaches.
     * This is designed to work from context menu actions where DataContext doesn't have terminal info.
     */
    fun executeCommandInTerminal(project: Project, context: TerminalTabContext, sessionId: String): Boolean {
        val command = buildResumeCommand(sessionId)

        // APPROACH 1 (BEST): Use TerminalToolWindowTabsManager API for reworked terminal (2025+)
        // This is the correct official API for WebStorm 2025's reworked terminal
        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            val tabs = tabsManager.tabs
            logger.debug("Found ${tabs.size} terminal tabs via TerminalToolWindowTabsManager")

            for (tab in tabs) {
                val tabContent = tab.content
                val tabName = tabContent.displayName ?: continue

                if (tabName == context.tabName) {
                    val terminalView = tab.view
                    try {
                        terminalView.createSendTextBuilder()
                            .shouldExecute()
                            .send(command)
                        logger.info("Executed command via TerminalToolWindowTabsManager: $command")
                        return true
                    } catch (ex: Exception) {
                        logger.warn("TerminalToolWindowTabsManager sendText failed: ${ex.message}", ex)
                    }
                }
            }
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowTabsManager not available: ${ex.message}")
        }

        // APPROACH 2: Try TerminalView if available in context (from DataContext)
        context.terminalView?.let { terminalView ->
            try {
                terminalView.createSendTextBuilder()
                    .shouldExecute()
                    .send(command)
                logger.info("Executed command via context TerminalView: $command")
                return true
            } catch (ex: Exception) {
                logger.debug("Context TerminalView.sendText failed: ${ex.message}")
            }
        }

        // APPROACH 3: Try ShellTerminalWidget if available (classic terminal)
        context.shellWidget?.let { widget ->
            if (executeCommandInWidget(widget, command)) {
                return true
            }
        }

        // APPROACH 4: Find ShellTerminalWidget by tab name (classic terminal fallback)
        val widget = findWidgetByName(project, context.tabName)
        if (widget != null) {
            if (executeCommandInWidget(widget, command)) {
                return true
            }
        }

        // APPROACH 5: Use TerminalToolWindowManager widgets (classic terminal)
        try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            for (termWidget in terminalManager.terminalWidgets) {
                if (termWidget is ShellTerminalWidget) {
                    val title = termWidget.terminalTitle.buildTitle()
                    if (title == context.tabName) {
                        if (executeCommandInWidget(termWidget, command)) {
                            return true
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowManager approach failed: ${ex.message}")
        }

        logger.warn("All approaches failed to execute command in terminal '${context.tabName}'")
        return false
    }

    /**
     * Tries to find a TerminalView in a component hierarchy.
     */
    private fun findTerminalViewInComponent(component: java.awt.Component?): TerminalView? {
        if (component == null) return null

        // Check if component itself is or contains TerminalView
        if (component is javax.swing.JComponent) {
            // Try to get TerminalView from client properties
            val terminalView = component.getClientProperty(TerminalView.DATA_KEY.name)
            if (terminalView is TerminalView) {
                return terminalView
            }

            // Recursively check children
            for (i in 0 until component.componentCount) {
                val child = component.getComponent(i)
                val found = findTerminalViewInComponent(child)
                if (found != null) return found
            }
        }

        return null
    }

    /**
     * Resumes a Claude session using action event context.
     */
    fun resumeClaudeSession(e: AnActionEvent, sessionId: String): Boolean {
        val command = buildResumeCommand(sessionId)
        return executeCommand(e, command)
    }

    /**
     * Finds a terminal widget by its tab name.
     */
    fun findWidgetByName(project: Project, name: String): ShellTerminalWidget? {
        return getAllShellTerminalWidgets(project).find {
            it.terminalTitle.buildTitle() == name
        }
    }

    /**
     * Gets the terminal tool window.
     */
    fun getTerminalToolWindow(project: Project) =
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")

    /**
     * Creates a new terminal tab and returns the widget.
     */
    fun createNewTerminalTab(project: Project, tabName: String? = null): ShellTerminalWidget? {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        return try {
            @Suppress("DEPRECATION")
            terminalManager.createLocalShellWidget(project.basePath ?: "", tabName ?: "Claude")
        } catch (e: Exception) {
            logger.warn("Failed to create terminal tab: ${e.message}", e)
            null
        }
    }

    // Icon for bound Claude sessions
    private val claudeIcon: Icon? by lazy {
        try {
            val icon = IconLoader.getIcon("/icons/claude.svg", TerminalHelper::class.java)
            logger.info("Claude icon loaded successfully: $icon (${icon.iconWidth}x${icon.iconHeight})")
            icon
        } catch (e: Exception) {
            logger.error("Failed to load Claude icon: ${e.message}", e)
            null
        }
    }

    /**
     * Sets the Claude icon on a terminal tab by name.
     */
    fun setClaudeIconOnTab(project: Project, tabName: String) {
        val toolWindow = getTerminalToolWindow(project) ?: return
        val contentManager = toolWindow.contentManager

        for (content in contentManager.contents) {
            if (content.displayName == tabName) {
                try {
                    content.icon = claudeIcon
                    logger.debug("Set Claude icon on tab '$tabName'")
                } catch (e: Exception) {
                    logger.warn("Failed to set icon on tab '$tabName': ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Removes the Claude icon from a terminal tab by name.
     */
    fun removeClaudeIconFromTab(project: Project, tabName: String) {
        val toolWindow = getTerminalToolWindow(project) ?: return
        val contentManager = toolWindow.contentManager

        for (content in contentManager.contents) {
            if (content.displayName == tabName) {
                try {
                    content.icon = null
                    logger.debug("Removed icon from tab '$tabName'")
                } catch (e: Exception) {
                    logger.warn("Failed to remove icon from tab '$tabName': ${e.message}")
                }
                break
            }
        }
    }

    // Timing constants for icon application retry
    private val ICON_APPLY_DELAYS_MS = listOf(100, 500, 1000)

    /**
     * Sets the Claude icon on a Content directly.
     * Uses delayed application to override terminal's default icon.
     *
     * @param content The content to set the icon on
     * @param parentDisposable Optional parent disposable for alarm lifecycle management.
     *                         If null, alarm will be disposed after last request completes.
     */
    fun setClaudeIconOnContent(content: Content, parentDisposable: Disposable? = null) {
        val icon = claudeIcon
        if (icon == null) {
            logger.warn("Cannot set icon - claudeIcon is null")
            return
        }

        // Apply icon immediately
        applyIconToContent(content, icon)

        // Also apply with delays to override terminal's icon management
        // Create alarm with proper lifecycle management to prevent memory leaks
        val alarm = if (parentDisposable != null) {
            Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
        } else {
            // Create a self-disposing alarm
            val disposable = Disposer.newDisposable("ClaudeIconAlarm")
            val a = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
            // Schedule disposal after last request
            a.addRequest({ Disposer.dispose(disposable) }, ICON_APPLY_DELAYS_MS.max() + 100)
            a
        }

        for (delay in ICON_APPLY_DELAYS_MS) {
            alarm.addRequest({ applyIconToContent(content, icon) }, delay)
        }
    }

    /**
     * Removes the Claude icon from a Content.
     */
    fun removeClaudeIconFromContent(content: Content) {
        try {
            // Reset icon (terminal will restore its default)
            content.icon = null
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, false)
            logger.debug("Removed Claude icon from '${content.displayName}'")
        } catch (e: Exception) {
            logger.warn("Failed to remove Claude icon from '${content.displayName}': ${e.message}", e)
        }
    }

    private fun applyIconToContent(content: Content, icon: javax.swing.Icon) {
        try {
            // CRITICAL: Enable icon display on content tabs
            // Without this flag, setIcon() is effectively a no-op
            content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)

            if (content.icon != icon) {
                logger.debug("Setting Claude icon on content '${content.displayName}', current icon: ${content.icon}")
                content.icon = icon
                logger.debug("Icon applied to '${content.displayName}'")
            }
        } catch (e: Exception) {
            logger.warn("Failed to set icon on '${content.displayName}': ${e.message}", e)
        }
    }
}
