package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.ClaudePathResolver
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.github.jendalen.claudesessionmanager.util.SessionNameValidator
import com.github.jendalen.claudesessionmanager.util.SessionTitleReader
import com.github.jendalen.claudesessionmanager.util.TitleSyncRules
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a bound tab's name and its Claude session's title (`/rename`, `--name`) equal in
 * both directions.
 *
 * Inbound (Claude -> tab): a 5 s poll over bindings with a live tab reads the session's
 * title through [SessionTitleReader] whenever the transcript or its sidecar moved, records
 * it on the binding, publishes it to the registry, and renames the tab through its
 * user-defined (persistent) title so the name survives a restart.
 *
 * Outbound (tab -> Claude): a rename made in the IDE is typed into the session as
 * `/rename <name>` only when the registry says the session is idle. Anything else -
 * busy, waiting on a permission prompt, not running, no registry entry, bound in another
 * IDE project - leaves a pending push on the binding, delivered by `--name` on the next
 * auto-resume of that session or by the "Push now" action. Nothing is ever typed into a
 * busy or waiting session (Enter would answer the prompt), and nothing is deferred to
 * "next idle" in the background (the user may be typing then).
 *
 * Threading: files are read on pooled threads; every binding mutation and every terminal
 * call happens on the EDT after re-reading the binding by id, like the other binding
 * writers. The pooled snapshot is only used to decide whether there is anything to do.
 *
 * The one hazard the feature accepts: a `/rename` typed into a session whose prompt box
 * holds an unsent draft submits the draft with it. Documented in the settings comment.
 */
@Service(Service.Level.PROJECT)
class SessionTitleSyncService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(SessionTitleSyncService::class.java)

    companion object {
        private const val POLL_INTERVAL_SECONDS = 5L
        private const val KICK_DELAY_MS = 1000L

        fun getInstance(project: Project): SessionTitleSyncService =
            project.getService(SessionTitleSyncService::class.java)
    }

    sealed class PushOutcome {
        object Sent : PushOutcome()
        data class Deferred(val reason: String) : PushOutcome()
        data class Invalid(val reason: String) : PushOutcome()
    }

    // Per binding: the session id the fingerprint was taken for and the fingerprint
    // itself; a session change on the binding invalidates it.
    private val fingerprints = ConcurrentHashMap<String, Pair<String, SessionTitleReader.Fingerprint>>()

    // Bindings whose transcript could not be located, logged once per session id so a
    // fresh session (no transcript until the first message) does not log every 5 s.
    private val missingTranscriptLogged = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var pollTask: ScheduledFuture<*>? = null

    private val pollRunning = AtomicBoolean(false)
    private val kickPending = AtomicBoolean(false)

    /** Idempotent; called next to the other watchers once the terminal is ready. */
    fun start() {
        if (pollTask != null) return
        pollTask = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::pollSafely, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        logger.info("Session title sync started (poll every ${POLL_INTERVAL_SECONDS}s)")
    }

    /**
     * Runs one poll shortly, coalescing bursts. Called after every successful bind so a
     * fresh bind or a SessionStart applies the title within a second, not five.
     */
    fun requestSync() {
        if (pollTask == null) return
        if (!kickPending.compareAndSet(false, true)) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            kickPending.set(false)
            pollSafely()
        }, KICK_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * One poll: bindings on this thread, their open tabs on the UI thread, file reads back
     * on a pooled thread, each hop by invokeLater / executeOnPooledThread. No thread ever
     * waits for the UI thread: the first version used invokeAndWait here, and the
     * 2026-09-30 WebStorm hang caught two poll threads parked in it while the UI thread
     * sat in a modal save. [pollRunning] is released at the end of whichever hop ends the
     * poll, so a poll that cannot proceed (modal dialog open) simply delays the next one.
     */
    private fun pollSafely() {
        if (project.isDisposed) return
        if (!pollRunning.compareAndSet(false, true)) return
        var handedOff = false
        try {
            if (TabBindingCoordinator.getInstance(project).isSuppressed()) return
            val bindings = BindingService.getInstance(project).getAllBindings()
            if (bindings.isEmpty()) return
            ApplicationManager.getApplication().invokeLater({ resolveTabs(bindings) }, project.disposed)
            handedOff = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Session title poll failed: ${e.message}", e)
        } finally {
            if (!handedOff) pollRunning.set(false)
        }
    }

    /** UI thread: which bindings have an open tab. */
    private fun resolveTabs(bindings: List<SessionBinding>) {
        var handedOff = false
        try {
            val liveContents = HashMap<String, Content>()
            for (binding in bindings) {
                val tabUuid = binding.tabIdentifier.tabUuid ?: continue
                TerminalHelper.findContentByTabId(project, tabUuid)?.let { liveContents[binding.id] = it }
            }
            if (liveContents.isEmpty()) return
            ApplicationManager.getApplication().executeOnPooledThread { readTitles(bindings, liveContents) }
            handedOff = true
        } catch (e: Exception) {
            logger.warn("Session title poll failed resolving tabs: ${e.message}", e)
        } finally {
            if (!handedOff) pollRunning.set(false)
        }
    }

    /** Pooled thread: file reads only. Anything that changes state goes to the UI thread. */
    private fun readTitles(bindings: List<SessionBinding>, liveContents: Map<String, Content>) {
        try {
            for (binding in bindings) {
                if (project.isDisposed) return
                val content = liveContents[binding.id] ?: continue
                val sessionId = binding.currentSessionId
                val sessionFile = ClaudePathResolver.findSessionFile(sessionId)
                if (sessionFile == null) {
                    if (missingTranscriptLogged.add(sessionId)) {
                        logger.info("Title sync: no transcript yet for session $sessionId (binding ${binding.id})")
                    }
                    continue
                }
                val fingerprint = SessionTitleReader.fingerprint(sessionFile)
                val previous = fingerprints[binding.id]
                if (previous != null && previous.first == sessionId && previous.second == fingerprint) continue
                fingerprints[binding.id] = sessionId to fingerprint

                val title = SessionTitleReader.read(sessionFile)
                if ((title ?: "") == binding.sessionTitle) continue
                ApplicationManager.getApplication().invokeLater({
                    applyInbound(binding.id, sessionId, content, title)
                }, project.disposed)
            }
        } catch (e: Exception) {
            logger.warn("Session title poll failed reading titles: ${e.message}", e)
        } finally {
            pollRunning.set(false)
        }
    }

    /**
     * EDT. The poll's snapshot is stale by now: the binding is re-read by id and the
     * update is dropped when the tab moved to another session or the tab is gone, so a
     * title read for session A is never recorded on, or applied to, a tab that now
     * holds session B.
     */
    @RequiresEdt
    private fun applyInbound(bindingId: String, sessionId: String, content: Content, title: String?) {
        if (project.isDisposed) return
        val bindingService = BindingService.getInstance(project)
        val binding = bindingService.findBindingById(bindingId) ?: return
        if (!binding.currentSessionId.equals(sessionId, ignoreCase = true)) return
        val tabUuid = binding.tabIdentifier.tabUuid ?: return
        if (TerminalHelper.findContentByTabId(project, tabUuid) !== content) return
        if ((title ?: "") == binding.sessionTitle) return

        // "" records "looked, no title", so a /rename made later is told apart from a
        // title the session already had when the plugin first saw it (TitleSyncRules).
        val firstSighting = binding.sessionTitle == null
        bindingService.recordSessionTitle(binding.id, title ?: "")
        if (title == null) return
        logger.info("Title sync: session $sessionId reports title '$title'" +
            (if (firstSighting) " (first sighting)" else " (was '${binding.sessionTitle}')"))

        // The registry carries Claude's title next to the tab name; external readers see
        // both and can tell whether they agree. Off the EDT like every registry write.
        val projectName = project.name
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionRegistryService.getInstance().upsert(sessionId, projectName, null, null, title)
        }
        // A newly observed title either confirms our own push or is a rename Claude made
        // after it; either way the last change wins and nothing is pending.
        if (binding.pendingTitlePush != null) bindingService.setPendingTitlePush(binding.id, null)
        if (SettingsService.getInstance().syncTabNames) renameTabToTitle(binding.id, content, title, firstSighting)
    }

    /**
     * Renames the tab unless it already carries the title or another binding does.
     * BindingMigrationService re-attaches restored tabs by persistent name and only when
     * the name is unique; two tabs named alike would both come back unbound.
     */
    @RequiresEdt
    private fun renameTabToTitle(bindingId: String, content: Content, title: String, firstSighting: Boolean): Boolean {
        val persistentName = TerminalHelper.getPersistentName(project, content)
        val label = persistentName ?: content.displayName
        val decision = TitleSyncRules.decide(
            currentName = persistentName,
            title = title,
            firstSighting = firstSighting,
            userNamed = TerminalHelper.hasUserDefinedTitle(project, content),
            // A profile tab's name is its configuration: renaming it would end the
            // profile (a renamed tab is no longer a profile tab), silently, on a /rename
            // typed into the session. The title is still recorded and published.
            profileTab = ProjectSettingsService.getInstance(project).profileFor(persistentName) != null,
            // BindingMigrationService re-attaches restored tabs by persistent name and
            // only when the name is unique; two tabs named alike would both come back
            // unbound.
            nameTaken = BindingService.getInstance(project).getAllBindings().any {
                it.id != bindingId && it.tabIdentifier.persistentName == title
            }
        )
        when (decision) {
            TitleSyncRules.Decision.ALREADY_NAMED -> return true
            TitleSyncRules.Decision.PROFILE_TAB ->
                logger.info("Title sync: not renaming profile tab '$label' to '$title'")
            TitleSyncRules.Decision.KEEP_USER_NAME ->
                logger.info("Title sync: keeping user-named tab '$label'; its session already had title '$title' when first seen")
            TitleSyncRules.Decision.NAME_TAKEN ->
                logger.info("Title sync: not renaming tab '$label' to '$title' - another binding already carries that name")
            TitleSyncRules.Decision.RENAME -> {
                val renamed = TerminalHelper.setUserDefinedTitle(project, content, title)
                if (renamed) logger.info("Applied Claude session title '$title' to tab '$label'")
                return renamed
            }
        }
        return false
    }

    /**
     * Applies every recorded title to its tab. Called when inbound sync is switched on:
     * titles observed while it was off are already recorded, so the poll would never
     * apply them (it only reacts to a title changing). Tabs with a pending push keep the
     * user's newer name.
     */
    fun reapplyTitles() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            for (binding in BindingService.getInstance(project).getAllBindings()) {
                val title = binding.sessionTitle?.takeIf { it.isNotEmpty() } ?: continue
                if (binding.pendingTitlePush != null) continue
                val tabUuid = binding.tabIdentifier.tabUuid ?: continue
                val content = TerminalHelper.findContentByTabId(project, tabUuid) ?: continue
                // Switching sync on is the user asking for titles on tabs: not a first sighting.
                renameTabToTitle(binding.id, content, title, firstSighting = false)
            }
        }
    }

    /**
     * Called by the title listener after a rename in the IDE has been recorded on the
     * binding. [userDefined] is false for a default title ("Local (2)") becoming known -
     * nobody asked for that name and it must not become the session's title. The
     * inbound rename above arrives here too: its name equals the recorded session
     * title, which is how the echo is told apart from a user rename.
     */
    @RequiresEdt
    fun onTabRenamed(tabUuid: String, newName: String, userDefined: Boolean) {
        if (!userDefined) return
        val bindingService = BindingService.getInstance(project)
        val binding = bindingService.findBindingByTabUuid(tabUuid) ?: return
        if (newName == binding.sessionTitle) {
            // Renamed back to what the session already has: an earlier push that never
            // got delivered (A -> B while busy, then B -> A) would otherwise ride along
            // with the next resume as --name 'B' and undo the user's last choice.
            if (binding.pendingTitlePush != null) bindingService.setPendingTitlePush(binding.id, null)
            return
        }
        if (!SettingsService.getInstance().pushTabNameToSession) return
        val content = TerminalHelper.findContentByTabId(project, tabUuid) ?: return
        pushTabName(binding, content, newName) { outcome ->
            when (outcome) {
                is PushOutcome.Sent -> Unit
                is PushOutcome.Deferred -> {
                    val notification = NotificationHelper.create(
                        "Tab name not pushed to the Claude session",
                        "${outcome.reason}. '$newName' will be sent with the next resume, or push it now once the session is idle.",
                        NotificationType.INFORMATION
                    )
                    notification.addAction(NotificationAction.createSimpleExpiring("Push now") {
                        val again = BindingService.getInstance(project).findBindingById(binding.id) ?: return@createSimpleExpiring
                        val pending = again.pendingTitlePush ?: return@createSimpleExpiring
                        pushTabName(again, content, pending) { report(it, pending) }
                    })
                    notification.notify(project)
                }
                is PushOutcome.Invalid ->
                    NotificationHelper.show(project, "Tab name cannot be pushed to the Claude session", outcome.reason, NotificationType.WARNING)
            }
        }
    }

    /**
     * Types `/rename <name>` into the session when it is safe, else records the push as
     * pending. Never types into a busy or waiting session. The registry read happens on
     * a pooled thread; the decision and the terminal call happen on the EDT against a
     * re-read binding, and [onOutcome] is called there.
     */
    @RequiresEdt
    fun pushTabName(binding: SessionBinding, content: Content, name: String, onOutcome: (PushOutcome) -> Unit) {
        val bindingService = BindingService.getInstance(project)
        SessionNameValidator.rejectionReason(name)?.let { reason ->
            // Not recorded as pending: --name could never deliver it either.
            logger.info("Title push rejected for binding ${binding.id}: $reason")
            onOutcome(PushOutcome.Invalid(reason))
            return
        }
        val bindingId = binding.id
        val sessionId = binding.currentSessionId
        val projectName = project.name
        ApplicationManager.getApplication().executeOnPooledThread {
            val entry = SessionRegistryService.getInstance().read(sessionId)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val fresh = bindingService.findBindingById(bindingId)
                if (fresh == null || !fresh.currentSessionId.equals(sessionId, ignoreCase = true)) {
                    logger.info("Title push dropped for binding $bindingId: the tab moved to another session meanwhile")
                    onOutcome(PushOutcome.Deferred("The tab moved to another session"))
                    return@invokeLater
                }
                val tabUuid = fresh.tabIdentifier.tabUuid
                if (tabUuid == null || TerminalHelper.findContentByTabId(project, tabUuid) !== content) {
                    onOutcome(PushOutcome.Deferred("The tab is gone"))
                    return@invokeLater
                }
                val deferral = when {
                    entry == null -> "No registry entry for the session (are the hooks installed?)"
                    !entry.isLive() -> "The session is not running"
                    entry.state != "idle" -> "The session is ${entry.state ?: "in an unknown state"}"
                    // The registry names the IDE project the session was bound in. The
                    // binding says this tab holds the session, the registry says another
                    // IDE does; that tab is then a shell prompt or something else, and
                    // nothing gets typed into it.
                    entry.project != null && entry.project != projectName ->
                        "The session is bound in project '${entry.project}'"
                    else -> null
                }
                if (deferral != null) {
                    bindingService.setPendingTitlePush(bindingId, name)
                    logger.info("Title push deferred for binding $bindingId ('$name'): $deferral")
                    onOutcome(PushOutcome.Deferred(deferral))
                    return@invokeLater
                }
                // CR, not LF: LF only inserts a newline into Claude's prompt box.
                val sent = TerminalHelper.sendTextToTab(project, content, "/rename ${name.trim()}\r")
                // Kept pending until the inbound poll sees the new title in the transcript;
                // a push that Claude did not accept then still rides along with the next
                // resume.
                bindingService.setPendingTitlePush(bindingId, name)
                if (!sent) {
                    onOutcome(PushOutcome.Deferred("The terminal could not be typed into"))
                    return@invokeLater
                }
                logger.info("Pushed '/rename $name' into session $sessionId (binding $bindingId)")
                requestSync()
                onOutcome(PushOutcome.Sent)
            }
        }
    }

    /** Balloon for an interactive push (context-menu action, "Push now"). */
    fun report(outcome: PushOutcome, name: String) {
        when (outcome) {
            is PushOutcome.Sent ->
                NotificationHelper.show(project, "Pushed '$name' to the Claude session", NotificationType.INFORMATION)
            is PushOutcome.Deferred ->
                NotificationHelper.show(project, "Tab name not pushed", "${outcome.reason}. It stays pending for the next resume.", NotificationType.INFORMATION)
            is PushOutcome.Invalid ->
                NotificationHelper.show(project, "Tab name cannot be pushed", outcome.reason, NotificationType.WARNING)
        }
    }

    override fun dispose() {
        pollTask?.cancel(false)
        pollTask = null
        fingerprints.clear()
    }
}
