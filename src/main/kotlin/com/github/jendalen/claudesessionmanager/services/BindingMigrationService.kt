package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content

/**
 * Idempotent re-attachment of session bindings to live terminal tabs. Runs on every startup
 * (and is safe to re-run at any time). Two phases:
 *
 * 1. LEGACY: bindings with no [tabUuid] (created before the stable-id model) are matched to
 *    open tabs by name and stamped with a stable id.
 *
 * 2. RESTART REBIND: the stable id lives in the tab's Content UserData, which does NOT
 *    survive an IDE restart. Bindings whose tabUuid matches no open tab are re-attached by
 *    the terminal-persisted tab name ([TerminalHelper.getPersistentName]) - the name the
 *    terminal plugin itself stores in TerminalTabsStorage and restores tabs with. That name
 *    is never polluted by Claude Code's OSC title takeover, unlike the displayName.
 *
 * Matching is only performed when unambiguous (exactly one candidate tab, not claimed by
 * another binding). Ambiguous or missing tabs are left untouched; the name-based fallback in
 * [com.github.jendalen.claudesessionmanager.model.TabIdentifier.matchConfidence] still applies.
 */
@Service(Service.Level.PROJECT)
class BindingMigrationService(private val project: Project) {

    private val logger = Logger.getInstance(BindingMigrationService::class.java)

    companion object {
        fun getInstance(project: Project): BindingMigrationService {
            return project.getService(BindingMigrationService::class.java)
        }
    }

    /**
     * Runs both phases. Cheap and idempotent; safe to call on every startup. Must run on EDT
     * (touches terminal tab Content); callers should wrap with invokeLater if needed.
     */
    fun migrate() {
        val bindingService = BindingService.getInstance(project)
        val allBindings = bindingService.getAllBindings()
        if (allBindings.isEmpty()) {
            bindingService.markSchemaCurrent()
            return
        }

        val toolWindow = TerminalHelper.getTerminalToolWindow(project)
        if (toolWindow == null) {
            logger.info("Binding migration deferred: terminal tool window not available yet (${allBindings.size} bindings)")
            return
        }

        val openTabs: List<Content> = toolWindow.contentManager.contents
            .filterNot { TerminalHelper.isTeamTab(it) }
            .toList()
        if (openTabs.isEmpty()) {
            logger.info("Binding migration deferred: no terminal tabs open yet (${allBindings.size} bindings)")
            return
        }

        val liveTabIds = openTabs.mapNotNull { TerminalHelper.getTabId(it) }.toSet()
        val claimedTabIds = allBindings
            .mapNotNull { it.tabIdentifier.tabUuid }
            .filter { it in liveTabIds }
            .toMutableSet()

        // Persistent names of open tabs (the identity that survives restarts). Names are
        // unique among open tabs in practice (the terminal's UniqueNameGenerator), but count
        // them anyway so we never guess on duplicates.
        val tabPersistentNames: Map<Content, String?> = openTabs.associateWith {
            TerminalHelper.getPersistentName(project, it)
        }
        val nameCounts = tabPersistentNames.values.filterNotNull().groupingBy { it }.eachCount()

        var reattached = 0
        var legacy = 0

        for (binding in allBindings) {
            val tabUuid = binding.tabIdentifier.tabUuid
            if (tabUuid != null && tabUuid in liveTabIds) continue  // already attached to a live tab

            val targetName = binding.tabIdentifier.persistentName ?: binding.tabIdentifier.name
            if (nameCounts[targetName] != 1) {
                logger.info(
                    "Binding ${binding.id} ('$targetName') not re-attached: " +
                        "${nameCounts[targetName] ?: 0} matching tabs open"
                )
                continue
            }

            val content = tabPersistentNames.entries.firstOrNull { it.value == targetName }?.key ?: continue
            val liveId = TerminalHelper.getOrCreateTabId(content)
            if (liveId in claimedTabIds) {
                logger.info("Binding ${binding.id} ('$targetName') not re-attached: tab already claimed")
                continue
            }

            bindingService.attachTabUuid(binding.id, liveId, targetName)
            claimedTabIds.add(liveId)
            if (tabUuid == null) legacy++ else reattached++
            logger.info("Re-attached binding ${binding.id} ('$targetName') to live tab id $liveId (was ${tabUuid ?: "legacy"})")
        }

        logger.info(
            "Binding migration: $legacy legacy + $reattached restart-stale bindings re-attached " +
                "(${allBindings.size} total, ${openTabs.size} open tabs)"
        )
        bindingService.markSchemaCurrent()
    }

    /**
     * Returns bindings that still are not attached to any open tab (used by auto-resume to
     * decide whether to keep waiting for tab restoration).
     */
    fun unattachedBindings(): List<SessionBinding> {
        val bindingService = BindingService.getInstance(project)
        val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return bindingService.getAllBindings()
        val liveTabIds = toolWindow.contentManager.contents
            .mapNotNull { TerminalHelper.getTabId(it) }
            .toSet()
        return bindingService.getAllBindings().filter { it.tabIdentifier.tabUuid !in liveTabIds }
    }

    /** Convenience wrapper that ensures the migration runs on the EDT. */
    fun migrateOnEdt() {
        ApplicationManager.getApplication().invokeLater { migrate() }
    }
}
