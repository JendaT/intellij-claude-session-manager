package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.model.SessionHistoryEntry
import com.github.jendalen.claudesessionmanager.model.TabIdentifier
import com.github.jendalen.claudesessionmanager.util.SessionIdValidator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.util.Alarm
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level service for managing session bindings.
 *
 * Bindings are stored in the project's workspace file (.idea/workspace.xml),
 * which is per-machine and not intended for VCS sharing.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ClaudeSessionBindings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class BindingService(private val project: Project) :
    PersistentStateComponent<BindingService.BindingsState>, com.intellij.openapi.Disposable {

    private val logger = Logger.getInstance(BindingService::class.java)
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /**
     * Serializable state for XML persistence.
     */
    class BindingsState {
        @XCollection(style = XCollection.Style.v2)
        var bindings: MutableList<BindingEntry> = mutableListOf()
    }

    /**
     * XML-serializable binding entry.
     * Flat structure for simpler XML serialization.
     */
    class BindingEntry {
        var id: String = ""
        var tabName: String = ""
        var tabCreatedAt: Long = 0
        var tabShellPid: Long? = null
        var tabWorkingDirectory: String? = null
        var tabUuid: String? = null   // Stable per-tab id; null for legacy (pre-v2) bindings
        var tabPersistentName: String? = null  // Terminal-persisted tab name; survives restart
        var currentSessionId: String = ""
        var autoResume: Boolean = true
        var createdAt: Long = 0
        var lastResumedAt: Long? = null
        var resumeCount: Int = 0

        @XCollection(style = XCollection.Style.v2)
        var history: MutableList<HistoryEntry> = mutableListOf()
    }

    class HistoryEntry {
        var sessionId: String = ""
        var replacedAt: Long = 0
    }

    private var state = BindingsState()

    // In-memory cache for fast access
    private val bindingsCache = ConcurrentHashMap<String, SessionBinding>()

    override fun getState(): BindingsState = state

    override fun loadState(loadedState: BindingsState) {
        XmlSerializerUtil.copyBean(loadedState, state)
        rebuildCache()
        logger.debug("Loaded ${state.bindings.size} bindings from storage")
    }

    private fun rebuildCache() {
        bindingsCache.clear()
        for (entry in state.bindings) {
            val binding = entryToBinding(entry)
            bindingsCache[binding.id] = binding
        }
    }

    /**
     * Returns all bindings for this project.
     */
    fun getAllBindings(): List<SessionBinding> {
        return bindingsCache.values.toList()
    }

    /**
     * Finds a binding by its ID.
     */
    fun findBindingById(bindingId: String): SessionBinding? {
        return bindingsCache[bindingId]
    }

    /**
     * Finds a binding by session ID.
     */
    fun findBindingBySessionId(sessionId: String): SessionBinding? {
        return bindingsCache.values.find { it.currentSessionId.equals(sessionId, ignoreCase = true) }
    }

    /**
     * Finds a binding for a terminal tab using fuzzy matching.
     * Returns the best match if confidence is above threshold.
     */
    fun findBindingForTab(tabIdentifier: TabIdentifier): SessionBinding? {
        var bestMatch: SessionBinding? = null
        var bestConfidence = 0.0

        for (binding in bindingsCache.values) {
            val confidence = binding.tabIdentifier.matchConfidence(tabIdentifier)
            if (confidence > bestConfidence && confidence >= TabIdentifier.MEDIUM_CONFIDENCE_THRESHOLD) {
                bestMatch = binding
                bestConfidence = confidence
            }
        }

        if (bestMatch != null) {
            logger.debug("Found binding for tab '${tabIdentifier.name}' with confidence $bestConfidence")
        }

        return bestMatch
    }

    /**
     * Creates a new binding between a terminal tab and a Claude session.
     */
    fun createBinding(
        tabIdentifier: TabIdentifier,
        sessionId: String,
        autoResume: Boolean = true
    ): SessionBinding {
        require(SessionIdValidator.isValid(sessionId)) { "Invalid session ID: $sessionId" }

        val binding = SessionBinding(
            id = SessionIdValidator.generateId(),
            tabIdentifier = tabIdentifier,
            currentSessionId = sessionId,
            autoResume = autoResume,
            createdAt = System.currentTimeMillis()
        )

        bindingsCache[binding.id] = binding
        syncToState()

        logger.info("Created binding ${binding.id} for tab '${tabIdentifier.name}' -> session $sessionId")
        return binding
    }

    /**
     * Updates an existing binding.
     */
    fun updateBinding(binding: SessionBinding): SessionBinding {
        require(bindingsCache.containsKey(binding.id)) { "Binding not found: ${binding.id}" }

        bindingsCache[binding.id] = binding
        syncToState()

        logger.debug("Updated binding ${binding.id}")
        return binding
    }

    /**
     * Updates a binding's session ID, moving the old one to history.
     */
    fun updateBindingSession(bindingId: String, newSessionId: String): SessionBinding? {
        require(SessionIdValidator.isValid(newSessionId)) { "Invalid session ID: $newSessionId" }

        val binding = bindingsCache[bindingId] ?: return null
        val updated = binding.withNewSession(newSessionId)

        bindingsCache[bindingId] = updated
        syncToState()

        logger.info("Updated binding $bindingId session: ${binding.currentSessionId} -> $newSessionId")
        return updated
    }

    /**
     * Marks a binding as successfully resumed.
     */
    fun markBindingResumed(bindingId: String): SessionBinding? {
        val binding = bindingsCache[bindingId] ?: return null
        val updated = binding.markResumed()

        bindingsCache[bindingId] = updated
        syncToState()

        logger.debug("Marked binding $bindingId as resumed (count: ${updated.resumeCount})")
        return updated
    }

    /**
     * Deletes a binding.
     */
    fun deleteBinding(bindingId: String): Boolean {
        val removed = bindingsCache.remove(bindingId)
        if (removed != null) {
            syncToState()
            logger.info("Deleted binding $bindingId")
            return true
        }
        return false
    }

    /**
     * Attaches/replaces the stable tab UUID (and optionally the terminal-persisted tab name)
     * on a binding's identifier, preserving all other identifier fields. Used by migration to
     * re-attach bindings to live tabs - both for legacy (name-only) bindings and for bindings
     * whose UUID went stale after an IDE restart.
     */
    fun attachTabUuid(bindingId: String, tabUuid: String, persistentName: String? = null): SessionBinding? {
        val binding = bindingsCache[bindingId] ?: return null
        val updated = binding.copy(
            tabIdentifier = binding.tabIdentifier.copy(
                tabUuid = tabUuid,
                persistentName = persistentName ?: binding.tabIdentifier.persistentName
            )
        )
        bindingsCache[bindingId] = updated
        syncToState()
        logger.info("Attached stable tab id $tabUuid to binding $bindingId" +
                (persistentName?.let { " (persistentName='$it')" } ?: ""))
        return updated
    }

    /**
     * Finds a binding by the stable tab UUID currently stored on its identifier.
     */
    fun findBindingByTabUuid(tabUuid: String): SessionBinding? {
        return bindingsCache.values.find { it.tabIdentifier.tabUuid == tabUuid }
    }

    /**
     * Finds the single binding that is not attached to any live tab (its tabUuid is null or
     * matches none of [liveTabIds]) and whose restart-stable name equals [effectiveName].
     * Returns null when there is no such binding or when several match (ambiguous - never guess).
     *
     * Used for adoption: when a session event arrives on a tab that has no binding yet, a
     * matching restart-stale binding is re-attached instead of creating a duplicate.
     */
    fun findStaleBindingByName(effectiveName: String, liveTabIds: Set<String>): SessionBinding? {
        val candidates = bindingsCache.values.filter {
            (it.tabIdentifier.tabUuid == null || it.tabIdentifier.tabUuid !in liveTabIds) &&
                it.tabIdentifier.effectiveName == effectiveName
        }
        return candidates.singleOrNull()
    }

    /**
     * Repairs a binding's tab identity in place: attaches the stable id when missing and
     * keeps the terminal-persisted name in sync. No-op when nothing changed.
     */
    fun selfHealIdentity(bindingId: String, tabUuid: String, persistentName: String?): SessionBinding? {
        val binding = bindingsCache[bindingId] ?: return null
        return if (binding.tabIdentifier.tabUuid == null) {
            attachTabUuid(bindingId, tabUuid, persistentName)
        } else if (persistentName != null && binding.tabIdentifier.persistentName != persistentName) {
            updatePersistentName(bindingId, persistentName)
        } else {
            binding
        }
    }

    /**
     * Updates the terminal-persisted tab name on a binding (e.g. after the user renames the
     * tab). Keeps cross-restart matching in sync with what the terminal will persist.
     */
    fun updatePersistentName(bindingId: String, persistentName: String): SessionBinding? {
        val binding = bindingsCache[bindingId] ?: return null
        if (binding.tabIdentifier.persistentName == persistentName) return binding
        val updated = binding.copy(
            tabIdentifier = binding.tabIdentifier.copy(persistentName = persistentName)
        )
        bindingsCache[bindingId] = updated
        syncToState()
        logger.info("Updated persistent name of binding $bindingId to '$persistentName'")
        return updated
    }

    /**
     * Returns all bindings with auto-resume enabled.
     */
    fun getAutoResumeBindings(): List<SessionBinding> {
        return bindingsCache.values.filter { it.autoResume }
    }

    /**
     * Synchronizes in-memory cache to persistent state and triggers save.
     */
    private fun syncToState() {
        state.bindings = bindingsCache.values.map { bindingToEntry(it) }.toMutableList()
        // Debounced flush to disk to prevent binding loss on IDE crash
        // without causing N saves during multi-tab auto-resume
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({
            if (!project.isDisposed) project.save()
        }, SAVE_DEBOUNCE_MS)
    }

    private fun bindingToEntry(binding: SessionBinding): BindingEntry {
        return BindingEntry().apply {
            id = binding.id
            tabName = binding.tabIdentifier.name
            tabCreatedAt = binding.tabIdentifier.createdAt
            tabShellPid = binding.tabIdentifier.shellPid
            tabWorkingDirectory = binding.tabIdentifier.workingDirectory
            tabUuid = binding.tabIdentifier.tabUuid
            tabPersistentName = binding.tabIdentifier.persistentName
            currentSessionId = binding.currentSessionId
            autoResume = binding.autoResume
            createdAt = binding.createdAt
            lastResumedAt = binding.lastResumedAt
            resumeCount = binding.resumeCount
            history = binding.sessionHistory.map { h ->
                HistoryEntry().apply {
                    sessionId = h.sessionId
                    replacedAt = h.replacedAt
                }
            }.toMutableList()
        }
    }

    private fun entryToBinding(entry: BindingEntry): SessionBinding {
        return SessionBinding(
            id = entry.id,
            tabIdentifier = TabIdentifier(
                name = entry.tabName,
                createdAt = entry.tabCreatedAt,
                shellPid = entry.tabShellPid,
                workingDirectory = entry.tabWorkingDirectory,
                tabUuid = entry.tabUuid,
                persistentName = entry.tabPersistentName
            ),
            currentSessionId = entry.currentSessionId,
            sessionHistory = entry.history.map { h ->
                SessionHistoryEntry(
                    sessionId = h.sessionId,
                    replacedAt = h.replacedAt
                )
            },
            autoResume = entry.autoResume,
            createdAt = entry.createdAt,
            lastResumedAt = entry.lastResumedAt,
            resumeCount = entry.resumeCount
        )
    }

    override fun dispose() {
        // saveAlarm is disposed automatically (parented to this service)
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 200

        fun getInstance(project: Project): BindingService {
            return project.getService(BindingService::class.java)
        }
    }
}
