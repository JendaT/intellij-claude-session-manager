package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.model.TabIdentifier
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.github.jendalen.claudesessionmanager.util.NotificationHelper
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * The single write path for tab-session bindings.
 *
 * Every detection channel (SessionStart hook events, terminal output scanning, sessions-index
 * watching) funnels its "session X is running in tab Y" observation through [bindOrUpdate].
 * Centralizing the write path keeps the identity rules in one place:
 *
 * - identifier construction (stable tab id + terminal-persisted name),
 * - the auto-resume suppression window (no binding updates while multi-tab auto-resume is
 *   sending resume commands - hook events fired during that storm may bind to the wrong tab),
 * - self-healing of legacy/stale identity fields,
 * - adoption of restart-stale bindings (re-attach instead of creating a duplicate),
 * - icon and tab-title protection side effects, and notifications.
 *
 * Previously three services carried drifting copies of this logic; one of them was never
 * migrated to the stable-id model, which caused cross-tab contamination.
 */
@Service(Service.Level.PROJECT)
class TabBindingCoordinator(private val project: Project) {

    private val logger = Logger.getInstance(TabBindingCoordinator::class.java)

    // Suppression window: during auto-resume, don't update existing bindings
    // (auto-resume already has the correct binding from persistence).
    @Volatile
    private var autoResumeSuppressUntil: Long = 0

    // Tabs the user explicitly started a fresh Claude session in (e.g. the "Start new
    // session" recovery action). The next fresh-session event on such a tab bypasses the
    // suppression window - it is a user action, not resume-storm noise. Keyed by stable
    // tab id, values are expiry timestamps.
    private val pendingFreshTabs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        private const val AUTO_RESUME_SUPPRESS_WINDOW_MS = 15000L
        private const val PENDING_FRESH_WINDOW_MS = 60000L

        fun getInstance(project: Project): TabBindingCoordinator {
            return project.getService(TabBindingCoordinator::class.java)
        }
    }

    /**
     * Activates the suppression window: until it expires, [bindOrUpdate] will not update or
     * adopt existing bindings (only create genuinely new ones). Prevents binding flip-flop
     * while multi-tab auto-resume interleaves startup/resume hook events across tabs.
     */
    fun suppressForAutoResume() {
        autoResumeSuppressUntil = System.currentTimeMillis() + AUTO_RESUME_SUPPRESS_WINDOW_MS
        logger.info("Auto-resume suppression window active for ${AUTO_RESUME_SUPPRESS_WINDOW_MS}ms")
    }

    fun isSuppressed(): Boolean = System.currentTimeMillis() < autoResumeSuppressUntil

    /**
     * Marks a tab as expecting a fresh Claude session started by explicit user action.
     * The next session event on this tab whose session no binding owns yet is applied even
     * inside the suppression window (which otherwise swallowed it - observed live: the
     * "Start new session" recovery click 8s after startup left the binding on the dead
     * session forever).
     */
    fun expectFreshSession(tabUuid: String) {
        val now = System.currentTimeMillis()
        pendingFreshTabs.entries.removeIf { it.value < now }
        pendingFreshTabs[tabUuid] = now + PENDING_FRESH_WINDOW_MS
        logger.info("Expecting fresh session in tab $tabUuid within ${PENDING_FRESH_WINDOW_MS}ms")
    }

    /**
     * Consumes a pending fresh-session expectation for the tab. True only when the mark is
     * present and unexpired AND [sessionId] is genuinely fresh (no binding owns it) - the
     * resume storm fires events for sessions that all have owners, so those can never
     * consume the mark even if they land on this tab.
     */
    private fun consumePendingFresh(tabUuid: String, sessionId: String, bindingService: BindingService): Boolean {
        val expiry = pendingFreshTabs[tabUuid] ?: return false
        if (System.currentTimeMillis() > expiry) {
            pendingFreshTabs.remove(tabUuid)
            return false
        }
        if (bindingService.findBindingBySessionId(sessionId) != null) return false
        pendingFreshTabs.remove(tabUuid)
        logger.info("Fresh-session expectation consumed for tab $tabUuid by session $sessionId")
        return true
    }

    /**
     * Binds [sessionId] to the terminal tab [content], reusing/healing an existing binding
     * when one covers the tab, adopting a restart-stale binding when the tab's persistent
     * name identifies one, and creating a new binding otherwise (unless [allowCreate] is
     * false - used by low-confidence channels that should never create bindings on their own).
     *
     * @param source short label of the detection channel, used in logs and notifications
     * @param displayInfo human-readable session summary for notifications (defaults to a
     *        truncated session id)
     * @param force explicit user action: bypasses the suppression window (never the
     *        validation and ambiguity rules)
     * @return the binding now covering the tab, or null when skipped
     */
    @RequiresEdt
    fun bindOrUpdate(
        content: Content,
        sessionId: String,
        source: String,
        workingDirectory: String? = null,
        displayInfo: String? = null,
        allowCreate: Boolean = true,
        force: Boolean = false
    ): SessionBinding? {
        try {
            // Fail closed before any mutation: sessionId comes from untrusted channels
            // (hook files, terminal output) and downstream validation inside BindingService
            // would otherwise fire after identity mutations have already been persisted.
            if (!com.github.jendalen.claudesessionmanager.util.SessionIdValidator.isValid(sessionId)) {
                logger.warn("Bind rejected: invalid session ID format (source=$source)")
                return null
            }
            val tabName = content.displayName
            if (tabName.isNullOrBlank()) {
                logger.info("Bind skipped: tab has no display name (session=$sessionId, source=$source)")
                return null
            }

            val tabId = TerminalHelper.getOrCreateTabId(content)
            val persistentName = TerminalHelper.getPersistentName(project, content)
            val identifier = TabIdentifier(
                name = tabName,
                createdAt = System.currentTimeMillis(),
                shellPid = null,
                workingDirectory = workingDirectory,
                tabUuid = tabId,
                persistentName = persistentName
            )

            val bindingService = BindingService.getInstance(project)
            val settings = SettingsService.getInstance()
            val info = displayInfo ?: "${sessionId.take(8)}..."
            val suppressed = isSuppressed() && !force &&
                !consumePendingFresh(tabId, sessionId, bindingService)

            // Hook events carry no terminal identity - they are attributed to the SELECTED
            // tab, which is wrong whenever several sessions start at once (the resume storm
            // fires each tab's event while the user looks at one tab; events arrive 60-90s
            // after launch, far outside any suppression window). A session that is already
            // bound to a DIFFERENT live tab therefore never moves on a hook event; only the
            // tab-accurate output channel and explicit user actions may move it.
            if (!force && source != "output") {
                val owner = bindingService.findBindingBySessionId(sessionId)
                val ownerTabUuid = owner?.tabIdentifier?.tabUuid
                if (owner != null && ownerTabUuid != null && ownerTabUuid != tabId &&
                    TerminalHelper.findContentByTabId(project, ownerTabUuid) != null
                ) {
                    logger.info(
                        "Bind skipped: session $sessionId already bound to live tab " +
                            "'${owner.tabIdentifier.effectiveName}' - not moving it to '$tabName' (source=$source)"
                    )
                    return owner
                }
            }

            val existing = bindingService.findBindingForTab(identifier)
            if (existing != null) {
                if (suppressed) {
                    logger.info("Bind suppressed during auto-resume window: session=$sessionId, tab='$tabName', source=$source")
                    // Suppression stops a binding being CHANGED during the resume storm.
                    // Publishing the binding that already exists is not a change, and for
                    // a session whose only confirmation lands inside the window this is
                    // the only chance it gets: 2 of 4 worktree sessions never got
                    // project/tab in the registry because of this early return.
                    if (existing.currentSessionId.equals(sessionId, ignoreCase = true)) {
                        registerSession(sessionId, persistentName ?: tabName, workingDirectory)
                    }
                    return existing
                }
                bindingService.selfHealIdentity(existing.id, tabId, persistentName)
                // Decorate here too, not only on create/adopt: a tab that reuses an existing
                // binding (a reopened terminal matched by name, a session change in a bound
                // tab) is just as bound, and without this it kept neither the Claude icon nor
                // title protection - Claude's OSC title then renamed the tab freely.
                applyTabSideEffects(content)
                if (!existing.currentSessionId.equals(sessionId, ignoreCase = true)) {
                    bindingService.updateBindingSession(existing.id, sessionId)
                    logger.info("Auto-updated binding for '$tabName' from ${existing.currentSessionId} to $sessionId (source=$source)")
                    if (settings.notifyOnSessionChange) {
                        notify("Session changed in '$tabName'", "Updated to: $info (via $source)")
                    }
                } else {
                    logger.info("Session $sessionId already bound to '$tabName' (source=$source)")
                }
                registerSession(sessionId, persistentName ?: tabName, workingDirectory)
                return bindingService.findBindingById(existing.id)
            }

            // Low-confidence channels may neither create nor adopt (adoption rewrites an
            // existing binding's session - a strictly stronger operation than creating).
            if (!allowCreate) {
                logger.info("Bind skipped: no existing binding and channel '$source' may not create one (session=$sessionId, tab='$tabName')")
                return null
            }

            // Adoption: a binding whose tab id went stale (IDE restart) and whose persistent
            // name identifies this tab gets re-attached instead of duplicated. Skipped during
            // the suppression window - the focused tab may not be the event's tab then, and
            // startup migration performs the same re-attachment safely.
            if (persistentName != null && !suppressed) {
                val liveTabIds = TerminalHelper.liveTabIds(project)
                val stale = bindingService.findStaleBindingByName(persistentName, liveTabIds)
                if (stale != null) {
                    bindingService.attachTabUuid(stale.id, tabId, persistentName)
                    if (!stale.currentSessionId.equals(sessionId, ignoreCase = true)) {
                        bindingService.updateBindingSession(stale.id, sessionId)
                    }
                    applyTabSideEffects(content)
                    registerSession(sessionId, persistentName, workingDirectory)
                    logger.info("Adopted stale binding ${stale.id} ('$persistentName') for tab '$tabName' -> session $sessionId (source=$source)")
                    if (settings.notifyOnAutoDetection) {
                        notify("Claude session reconnected", "Re-attached '$persistentName' to: $info (via $source)")
                    }
                    return bindingService.findBindingById(stale.id)
                }
            }

            // Session-ownership guard: during the suppression window the focused tab is an
            // unreliable attribution, so never create a binding for a session that some
            // binding already owns (the resume storm fires events for exactly those), and
            // never create when a same-named stale binding exists (adoption was deferred -
            // creating now would make that name permanently ambiguous).
            if (suppressed) {
                val owner = bindingService.findBindingBySessionId(sessionId)
                if (owner != null) {
                    logger.info("Bind suppressed: session $sessionId already owned by binding ${owner.id} (source=$source)")
                    return owner
                }
                if (persistentName != null &&
                    bindingService.findStaleBindingByName(persistentName, TerminalHelper.liveTabIds(project)) != null) {
                    logger.info("Bind suppressed: stale binding exists for '$persistentName' - deferring to migration (source=$source)")
                    return null
                }
            }

            val binding = bindingService.createBinding(identifier, sessionId)
            logger.info("Auto-bound tab '$tabName' to session $sessionId (source=$source)")
            applyTabSideEffects(content)
            registerSession(sessionId, persistentName ?: tabName, workingDirectory)
            if (settings.notifyOnAutoDetection) {
                notify("Claude session detected", "Auto-bound '$tabName' to: $info (via $source)")
            }
            return binding
        } catch (e: Exception) {
            logger.warn("Failed to bind session $sessionId (source=$source): ${e.message}", e)
            return null
        }
    }

    /**
     * Publishes project and tab for the session to the registry read by external tools.
     * The tab is the persistent name (what the user named it), never the OSC-polluted
     * display name. Off the EDT: the write is a small file operation but the output
     * channel re-confirms bindings every few seconds and the EDT must not pay for it.
     */
    private fun registerSession(sessionId: String, tab: String, cwd: String?) {
        val projectName = project.name
        val basePath = cwd ?: project.basePath
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            SessionRegistryService.getInstance().upsert(sessionId, projectName, tab, basePath)
        }
    }

    /**
     * Decorates a bound tab once per IDE session (icon, title protection) and records
     * that it happened. Internal, not private, because auto-resume decorates tabs it
     * has just typed into; three sites there called the two helpers directly, never set
     * the mark, and the first event on every resumed tab re-ran the icon alarms.
     */
    internal fun applyTabSideEffects(content: Content) {
        // The output channel re-confirms live bindings every few seconds; re-running the icon
        // alarms and title change on each confirmation is pure waste. The mark lives in
        // Content UserData, so decoration is re-applied once per IDE session.
        if (TerminalHelper.isDecorated(content)) return
        TerminalHelper.markDecorated(content)
        TerminalHelper.setClaudeIconOnContent(content)
        TerminalHelper.protectTabTitleIfEnabled(project, content)
    }

    private fun notify(title: String, message: String) {
        NotificationHelper.show(project, title, message, NotificationType.INFORMATION)
    }
}
