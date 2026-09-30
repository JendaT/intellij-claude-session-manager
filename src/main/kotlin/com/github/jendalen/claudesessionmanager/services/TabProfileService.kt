package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.model.TabProfile
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.terminal.TerminalTabContext
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.github.jendalen.claudesessionmanager.util.TabProfiles
import com.github.jendalen.claudesessionmanager.util.TranscriptTail
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.io.File

/**
 * Tab profiles at run time: which directory a tab's sessions belong to, and opening the
 * profile tabs themselves.
 *
 * A profile tab is a proxy for a directory outside the project folder (a git worktree),
 * so every rule that used to say "under the project folder" asks [rootFor] instead. With
 * no profiles configured every answer is the project folder, which is the check each
 * detection channel made before profiles existed.
 */
@Service(Service.Level.PROJECT)
class TabProfileService(private val project: Project) {

    private val logger = Logger.getInstance(TabProfileService::class.java)

    companion object {
        fun getInstance(project: Project): TabProfileService = project.getService(TabProfileService::class.java)
    }

    private sealed class Decision {
        data class Resume(val binding: SessionBinding, val sessionId: String) : Decision()
        data class Fresh(val binding: SessionBinding?, val reason: String?) : Decision()
        data class Leave(val reason: String) : Decision()
    }

    fun profiles(): List<TabProfile> = ProjectSettingsService.getInstance(project).tabProfiles

    fun profileFor(persistentName: String?): TabProfile? = ProjectSettingsService.getInstance(project).profileFor(persistentName)

    /** The profile of a live tab, from its persistent name (never the display name). */
    fun profileFor(content: Content): TabProfile? = profileFor(TerminalHelper.getPersistentName(project, content))

    /** The directory a tab's Claude sessions must run under: its profile's, else the project's. */
    fun rootFor(content: Content): String? = TabProfiles.rootFor(project.basePath, profileFor(content))

    /** Project-level filter for sessions with no tab yet: the project folder or any profile directory. */
    fun isUnderAnyRoot(cwd: String): Boolean = TabProfiles.isUnderAnyRoot(project.basePath, profiles(), cwd)

    /**
     * Where the session last ran: the hook's registry `cwd` (current while it runs), else
     * the newest `cwd` in its transcript tail. File reads - not on the EDT.
     */
    fun lastCwdOf(sessionId: String): String? {
        SessionRegistryService.getInstance().read(sessionId)?.cwd?.takeIf { it.isNotBlank() }?.let { return it }
        val file = ClaudePathResolver.findSessionFile(sessionId) ?: return null
        return TranscriptTail.lastCwd(file)
    }

    /**
     * True when the session last ran under the profile's directory, or when that cannot
     * be told. A resume from another directory keeps the conversation (verified
     * 2026-09-30), so a session that ran in the main clone would continue in the worktree
     * with every absolute path in its context pointing at the main clone. The automatic
     * launches start fresh instead; a user-picked resume is not second-guessed.
     */
    fun sessionBelongsTo(profile: TabProfile, sessionId: String): Boolean {
        val cwd = lastCwdOf(sessionId) ?: return true
        val belongs = TabProfiles.isUnder(TabProfiles.expandHome(profile.workingDirectory), cwd)
        if (!belongs) {
            logger.info("Session $sessionId last ran in '$cwd', not under profile '${profile.name}' directory ${profile.workingDirectory}")
        }
        return belongs
    }

    /**
     * Where a SessionStart event whose cwd lies in exactly one profile's directory should
     * go, prepared on the hook watcher thread (it reads registry files): the profile, and
     * whether each session bound under the profile's name is still running.
     */
    data class AttributionHint(val profile: TabProfile, val liveSessions: Map<String, Boolean>)

    fun attributionHint(cwd: String): AttributionHint? {
        if (cwd.isBlank()) return null
        val profile = profiles().filter { TabProfiles.isUnder(TabProfiles.expandHome(it.workingDirectory), cwd) }
            .singleOrNull() ?: return null
        val registry = SessionRegistryService.getInstance()
        val live = BindingService.getInstance(project).getAllBindings()
            .filter { it.tabIdentifier.effectiveName == profile.name }
            .associate { it.currentSessionId.lowercase() to (registry.read(it.currentSessionId)?.isLive() == true) }
        return AttributionHint(profile, live)
    }

    /**
     * The profile tab a session belongs to, when that can be told without guessing. Hook
     * events carry no terminal identity and go to the selected tab; a session started in
     * a profile tab the user is not looking at (keep-open at project open, "Open Profile
     * Tabs") would then be refused by the selected tab's cwd guard and never bound. Its
     * cwd names the profile, so it goes there - unless that tab holds a different session
     * that is still running: a second Claude started in the same worktree from somewhere
     * else must not take the profile tab's binding. EDT.
     */
    @RequiresEdt
    fun profileTabFor(hint: AttributionHint, sessionId: String): Content? {
        val content = TerminalHelper.findContentByPersistentName(project, hint.profile.name) ?: return null
        val tabId = TerminalHelper.getTabId(content) ?: return content
        val binding = BindingService.getInstance(project).findBindingByTabUuid(tabId) ?: return content
        val current = binding.currentSessionId.lowercase()
        if (current == sessionId.lowercase()) return content
        val live = hint.liveSessions[current] ?: return null
        return if (live) null else content
    }

    /**
     * Opens every profile tab that is not open. Tabs already open are left exactly as
     * they are, running or not. EDT.
     */
    @RequiresEdt
    fun openMissingProfileTabs(profiles: List<TabProfile> = profiles(), reportIfNone: Boolean = true): Int {
        val missing = profiles.filter { TerminalHelper.findContentByPersistentName(project, it.name) == null }
        if (missing.isEmpty()) {
            if (reportIfNone && profiles.isNotEmpty()) {
                NotificationHelper.show(project, "All profile tabs are already open", NotificationType.INFORMATION)
            }
            return 0
        }
        missing.forEach { openProfileTab(it, activate = false) }
        return missing.size
    }

    /**
     * Profile tabs marked "keep open" that the IDE did not restore (closed by the user,
     * or never opened). Called once at project open, after the resume loop. EDT.
     */
    @RequiresEdt
    fun openKeptTabs() {
        val kept = profiles().filter { it.keepOpen }
        if (kept.isEmpty()) return
        val opened = openMissingProfileTabs(kept, reportIfNone = false)
        if (opened > 0) logger.info("Opened $opened keep-open profile tab(s) at project open")
    }

    /**
     * Opens the profile's tab (or selects it when it is already open), in the profile's
     * directory, and launches Claude there: the session of the binding that carries the
     * profile's name when it belongs to that directory and is not running elsewhere,
     * otherwise a fresh session. EDT; the file checks run on a pooled thread.
     */
    @RequiresEdt
    fun openProfileTab(profile: TabProfile, activate: Boolean) {
        TerminalHelper.findContentByPersistentName(project, profile.name)?.let { existing ->
            if (activate) {
                TerminalHelper.getTerminalToolWindow(project)?.let { tw ->
                    tw.contentManager.setSelectedContent(existing, true)
                    tw.activate(null, true)
                }
            }
            logger.info("Profile tab '${profile.name}' is already open")
            warnIfRunningElsewhere(profile, existing)
            return
        }
        val directory = TabProfiles.expandHome(profile.workingDirectory)
        if (!File(directory).isDirectory) {
            logger.warn("Profile tab '${profile.name}' not opened: directory $directory does not exist")
            NotificationHelper.show(
                project, "Profile tab '${profile.name}' not opened",
                "Directory $directory does not exist.", NotificationType.WARNING
            )
            return
        }
        val bindingService = BindingService.getInstance(project)
        // A binding whose tab is gone and which carries the profile's name: the tab's
        // last session. findStaleBindingByName only answers when exactly one matches.
        val binding = bindingService.findStaleBindingByName(profile.name, TerminalHelper.liveTabIds(project))

        ApplicationManager.getApplication().executeOnPooledThread {
            val decision = decide(profile, binding)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) launch(profile, directory, decision, activate)
            }
        }
    }

    /**
     * An open tab that got its profile after the fact (a tab already named like the
     * profile) keeps running whatever it ran. Nothing is typed into it - it may be a
     * running Claude - but the user is told, since that is exactly the case where the
     * tab does not do what the profile says.
     */
    private fun warnIfRunningElsewhere(profile: TabProfile, content: Content) {
        val tabId = TerminalHelper.getTabId(content) ?: return
        val binding = BindingService.getInstance(project).findBindingByTabUuid(tabId) ?: return
        val sessionId = binding.currentSessionId
        ApplicationManager.getApplication().executeOnPooledThread {
            val entry = SessionRegistryService.getInstance().read(sessionId) ?: return@executeOnPooledThread
            val cwd = entry.cwd ?: return@executeOnPooledThread
            if (!entry.isLive() || TabProfiles.isUnder(TabProfiles.expandHome(profile.workingDirectory), cwd)) return@executeOnPooledThread
            logger.info("Profile tab '${profile.name}' is open but its Claude runs in '$cwd'")
            NotificationHelper.show(
                project, "Profile tab '${profile.name}' runs Claude outside its directory",
                "Its session runs in $cwd. Exit Claude and close the tab, then open the profile tab again " +
                    "(or reopen the project) to start it in ${profile.workingDirectory}.",
                NotificationType.INFORMATION
            )
        }
    }

    private fun decide(profile: TabProfile, binding: SessionBinding?): Decision {
        if (binding == null) return Decision.Fresh(null, null)
        val sessionId = binding.resumableSessionId { ClaudePathResolver.sessionFileExists(it) }
            ?: return Decision.Fresh(binding, "no session of the tab has a transcript")
        val owner = SessionRegistryService.getInstance().read(sessionId)
        if (owner != null && owner.isLive()) {
            return Decision.Leave(
                "session $sessionId is running elsewhere (pid ${owner.pid} in ${owner.cwd ?: "an unknown directory"})"
            )
        }
        if (!sessionBelongsTo(profile, sessionId)) {
            return Decision.Fresh(binding, "its last session ran outside ${profile.workingDirectory}")
        }
        return Decision.Resume(binding, sessionId)
    }

    @RequiresEdt
    private fun launch(profile: TabProfile, directory: String, decision: Decision, activate: Boolean) {
        // The tab may have been opened meanwhile (a second click, keep-open racing the action).
        if (TerminalHelper.findContentByPersistentName(project, profile.name) != null) return
        val content = TerminalHelper.createProfileTab(project, profile.name, directory, activate) ?: run {
            NotificationHelper.show(project, "Profile tab '${profile.name}' could not be created", "See idea.log.", NotificationType.WARNING)
            return
        }
        val bindingService = BindingService.getInstance(project)
        val coordinator = TabBindingCoordinator.getInstance(project)
        val tabId = TerminalHelper.getOrCreateTabId(content)
        val context = TerminalTabContext(
            tabName = profile.name,
            terminalView = TerminalHelper.findTerminalViewByTabId(project, tabId),
            shellWidget = null,
            content = content,
            tabId = tabId,
            persistentName = profile.name
        )
        when (decision) {
            is Decision.Resume -> {
                bindingService.attachTabUuid(decision.binding.id, tabId, profile.name)
                val sent = try {
                    TerminalHelper.executeCommandInTerminal(project, context, decision.sessionId)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Resume in profile tab '${profile.name}' rejected: ${e.message}")
                    false
                }
                if (sent) {
                    bindingService.markBindingResumed(decision.binding.id)
                    coordinator.applyTabSideEffects(content)
                    logger.info("Opened profile tab '${profile.name}' and resumed session ${decision.sessionId}")
                }
            }
            is Decision.Fresh -> {
                // Attach the tab's old binding first, so the new session's first event
                // updates it (its history keeps the old session) instead of creating a
                // second binding for the same name.
                decision.binding?.let { bindingService.attachTabUuid(it.id, tabId, profile.name) }
                coordinator.expectFreshSession(tabId)
                if (TerminalHelper.executeCommand(context, TerminalHelper.buildStartCommand(project, profile.name))) {
                    coordinator.applyTabSideEffects(content)
                    logger.info("Opened profile tab '${profile.name}' with a new session" + (decision.reason?.let { " - $it" } ?: ""))
                    decision.reason?.let {
                        NotificationHelper.show(
                            project, "Profile tab '${profile.name}': new session",
                            "Started a new session in ${profile.workingDirectory}: $it.", NotificationType.INFORMATION
                        )
                    }
                }
            }
            is Decision.Leave -> {
                logger.info("Opened profile tab '${profile.name}' without launching Claude: ${decision.reason}")
                NotificationHelper.show(
                    project, "Profile tab '${profile.name}' opened without Claude",
                    "Not launched: ${decision.reason}.", NotificationType.INFORMATION
                )
            }
        }
    }
}
