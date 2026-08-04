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
    val content: Content?,
    val tabId: String? = null,  // Stable per-tab id (Content UserData), when available
    val persistentName: String? = null  // Terminal-persisted tab name (survives restart)
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
     * Stable per-tab identity stored in the tab's Content UserData.
     *
     * This is the primary key used to match terminal tabs to session bindings. Unlike the
     * tab's displayName, it survives renames - including Claude Code's OSC-0 title takeover
     * (which renames every Claude tab to "Claude Code" on recent JetBrains builds). The
     * Content object keeps the same UserData for its whole lifetime within an IDE session.
     */
    val STABLE_TAB_KEY = Key.create<String>("ClaudeSessionManager.StableTabId")

    /**
     * Marks a tab whose title is protected from OSC application-title takeover.
     *
     * Needed because the REWORKED terminal (2026.1) applies OSC titles directly
     * (TerminalViewImpl.listenApplicationTitleChanges, gated only by the IDE-wide advanced
     * setting "terminal.show.application.title") and does NOT honor
     * TerminalTitle.trackTerminalApplicationTitleChanges - that flag only guards the classic
     * JediTerm path. Protection is therefore enforced reactively: the per-tab title listener
     * clears applicationTitle whenever it reappears on a tab carrying this mark.
     */
    val TITLE_PROTECTED_KEY = Key.create<Boolean>("ClaudeSessionManager.TitleProtected")

    /**
     * True when [protectTabTitle] marked this tab's title as protected.
     */
    fun isTitleProtected(content: Content): Boolean = content.getUserData(TITLE_PROTECTED_KEY) == true

    /**
     * Checks if a terminal tab Content is managed by the team system.
     */
    fun isTeamTab(content: Content): Boolean {
        return content.getUserData(TEAM_TAB_KEY) != null
    }

    /**
     * Returns the stable tab id for a Content, generating and storing one if absent.
     * Safe to call repeatedly - the same id is returned for the life of the Content.
     */
    fun getOrCreateTabId(content: Content): String {
        content.getUserData(STABLE_TAB_KEY)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        content.putUserData(STABLE_TAB_KEY, id)
        logger.debug("Assigned stable tab id $id to tab '${content.displayName}'")
        return id
    }

    /**
     * Returns the stable tab id for a Content if one has been assigned, else null.
     * Does NOT generate a new id.
     */
    fun getTabId(content: Content): String? = content.getUserData(STABLE_TAB_KEY)

    /**
     * Finds the terminal tab Content carrying the given stable tab id.
     */
    fun findContentByTabId(project: Project, tabId: String): Content? {
        val toolWindow = getTerminalToolWindow(project) ?: return null
        return toolWindow.contentManager.contents.firstOrNull { getTabId(it) == tabId }
    }

    /**
     * Returns the stable ids of all terminal tabs that currently carry one. Non-creating:
     * tabs that have not been stamped yet are absent from the result.
     */
    fun liveTabIds(project: Project): Set<String> {
        val toolWindow = getTerminalToolWindow(project) ?: return emptySet()
        return toolWindow.contentManager.contents
            .mapNotNull { getTabId(it) }
            .toSet()
    }

    /**
     * Returns the tab name that the terminal plugin persists across IDE restarts for this tab:
     * TerminalTitle.userDefinedTitle if the user renamed the tab, else the default title
     * ("Local", "Local (2)", ...). Never the OSC application title, so Claude Code's title
     * takeover ("Claude Code", "* Doing X...") cannot pollute it. Restored tabs come back
     * with exactly this name, which makes it the cross-restart identity key.
     *
     * Returns null when no TerminalTitle is resolvable. Deliberately does NOT fall back to
     * Content.displayName: the display name may carry the OSC application title, and persisting
     * it as the restart-stable key would corrupt cross-restart matching. Callers must treat
     * null as "identity unknown" (TabIdentifier.effectiveName then falls back to the display
     * name for matching only, without persisting it as a stable name).
     */
    fun getPersistentName(project: Project, content: Content): String? {
        val title = findTerminalTitle(project, content) ?: return null
        return persistentNameOf(title)
    }

    /**
     * Derives the terminal-persisted (restart-stable) name from a TerminalTitle.
     * Single source of truth for this derivation - keep all call sites on it.
     */
    fun persistentNameOf(title: com.intellij.terminal.TerminalTitle): String? {
        return title.userDefinedTitle?.takeUnless { it.isBlank() }
            ?: title.defaultTitle?.takeUnless { it.isBlank() }
    }

    /**
     * Resolves the TerminalTitle for a tab Content, trying the reworked terminal first and
     * the classic ShellTerminalWidget as fallback.
     */
    private fun findTerminalTitle(project: Project, content: Content): com.intellij.terminal.TerminalTitle? {
        // Reworked terminal: match the tab by Content identity
        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            tabsManager.tabs.firstOrNull { it.content === content }?.let { return it.view.title }
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowTabsManager not available: ${ex.message}")
        }
        // Classic terminal: find the widget whose built title equals the displayed name
        try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val widget = terminalManager.terminalWidgets
                .filterIsInstance<ShellTerminalWidget>()
                .firstOrNull { it.terminalTitle.buildTitle() == content.displayName }
            if (widget != null) return widget.terminalTitle
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowManager not available: ${ex.message}")
        }
        return null
    }

    /**
     * Stops Claude Code's OSC title takeover from renaming this tab: clears any application
     * title already applied and disables tracking of future application-title changes.
     * IDE-side only - does not touch Claude Code settings. No-op if title info is unavailable.
     */
    fun protectTabTitle(project: Project, content: Content) {
        try {
            // The mark is what actually enforces protection on the reworked terminal (the
            // title listener clears reappearing application titles - see TITLE_PROTECTED_KEY).
            content.putUserData(TITLE_PROTECTED_KEY, true)
            val title = findTerminalTitle(project, content) ?: return
            title.change {
                applicationTitle = null
                // Honored by the classic JediTerm path only; kept for completeness.
                trackTerminalApplicationTitleChanges = false
            }
            logger.info("Protected tab title for '${content.displayName}'")
        } catch (ex: Exception) {
            logger.warn("Failed to protect tab title for '${content.displayName}': ${ex.message}", ex)
        }
    }

    /**
     * Reverts [protectTabTitle]: the tab follows OSC application-title changes again.
     */
    fun unprotectTabTitle(project: Project, content: Content) {
        try {
            content.putUserData(TITLE_PROTECTED_KEY, null)
            val title = findTerminalTitle(project, content) ?: return
            title.change {
                trackTerminalApplicationTitleChanges = true
            }
            logger.info("Unprotected tab title for '${content.displayName}'")
        } catch (ex: Exception) {
            logger.warn("Failed to unprotect tab title for '${content.displayName}': ${ex.message}", ex)
        }
    }

    /**
     * Applies title protection when the setting is enabled.
     */
    fun protectTabTitleIfEnabled(project: Project, content: Content) {
        if (SettingsService.getInstance().protectTabNames) {
            protectTabTitle(project, content)
        }
    }

    /**
     * Applies or reverts title protection on every tab of this project that currently has a
     * session binding. Called when the "protect tab names" setting is toggled, so the change
     * takes effect immediately instead of on the next session event. Must run on EDT.
     */
    fun updateTitleProtectionForBoundTabs(project: Project, enabled: Boolean) {
        val bindingService = com.github.jendalen.claudesessionmanager.services.BindingService.getInstance(project)
        for (binding in bindingService.getAllBindings()) {
            val tabUuid = binding.tabIdentifier.tabUuid ?: continue
            val content = findContentByTabId(project, tabUuid) ?: continue
            if (enabled) protectTabTitle(project, content) else unprotectTabTitle(project, content)
        }
    }

    /**
     * Returns the persistent (restart-stable) name of every terminal tab Content in one pass
     * over the reworked terminal's tab list. Contents without a resolvable TerminalTitle map
     * to null. Cheaper than calling [getPersistentName] per tab (which rescans the tab list).
     */
    fun persistentNamesSnapshot(project: Project): Map<Content, String?> {
        val result = mutableMapOf<Content, String?>()
        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            for (tab in tabsManager.tabs) {
                result[tab.content] = persistentNameOf(tab.view.title)
            }
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowTabsManager not available: ${ex.message}")
        }
        return result
    }

    /**
     * Finds a TerminalView by stable tab id using TerminalToolWindowTabsManager.
     */
    fun findTerminalViewByTabId(project: Project, tabId: String): TerminalView? {
        return try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            tabsManager.tabs.firstOrNull { getTabId(it.content) == tabId }?.view
        } catch (ex: Exception) {
            logger.debug("TerminalToolWindowTabsManager not available: ${ex.message}")
            null
        }
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

        // Stamp/read the stable id so this tab can be matched independently of its name
        val tabId = getOrCreateTabId(selectedContent)

        // Return context if we have at least a tab name
        return TerminalTabContext(
            tabName = tabName,
            terminalView = terminalView,
            shellWidget = shellWidget,
            content = selectedContent,
            tabId = tabId,
            persistentName = getPersistentName(project, selectedContent)
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
     * Creates a TabIdentifier for a terminal context, carrying the stable tab id when known.
     */
    fun createTabIdentifier(context: TerminalTabContext): TabIdentifier {
        return TabIdentifier(
            name = context.tabName,
            createdAt = System.currentTimeMillis(),
            shellPid = null,
            workingDirectory = null,
            tabUuid = context.tabId,
            persistentName = context.persistentName
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

        // APPROACH 1 (BEST): Match by stable tab id, falling back to name. Matching by name
        // alone is unreliable now that Claude Code renames every tab to "Claude Code".
        try {
            val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
            val tabs = tabsManager.tabs
            logger.debug("Found ${tabs.size} terminal tabs via TerminalToolWindowTabsManager")

            for (tab in tabs) {
                val tabContent = tab.content
                val matchesById = context.tabId != null && getTabId(tabContent) == context.tabId
                val matchesByName = context.tabId == null && tabContent.displayName == context.tabName

                if (matchesById || matchesByName) {
                    val terminalView = tab.view
                    try {
                        terminalView.createSendTextBuilder()
                            .shouldExecute()
                            .send(command)
                        logger.info("Executed command via TerminalToolWindowTabsManager (byId=$matchesById): $command")
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

    // Claude icon with the platform's green "live" badge, shown while Claude is actively
    // working in the tab. Falls back to the plain icon if the badge API is unavailable.
    private val claudeIconLive: Icon? by lazy {
        val base = claudeIcon ?: return@lazy null
        try {
            com.intellij.execution.runners.ExecutionUtil.getLiveIndicator(base)
        } catch (e: Throwable) {
            logger.warn("Live indicator icon unavailable, using plain icon: ${e.message}")
            base
        }
    }

    /**
     * Switches a bound tab's icon between the plain Claude icon and the "live" (activity)
     * variant. No-op when the tab does not currently carry one of our icons - never steals
     * the icon slot from tabs we did not decorate. Must run on EDT.
     */
    fun setClaudeIconActivity(content: Content, active: Boolean) {
        val plain = claudeIcon ?: return
        val live = claudeIconLive ?: plain
        try {
            val current = content.icon
            if (current !== plain && current !== live) return
            val target = if (active) live else plain
            if (current !== target) content.icon = target
        } catch (e: Exception) {
            logger.debug("Failed to update icon activity on '${content.displayName}': ${e.message}")
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
                    logger.warn("Failed to set icon on tab '$tabName': ${e.message}", e)
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
