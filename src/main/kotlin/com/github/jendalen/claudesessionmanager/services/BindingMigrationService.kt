package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.SessionBinding
import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content

/**
 * Idempotent re-attachment of session bindings to live terminal tabs. Runs on every startup
 * (and is safe to re-run at any time). Two cases, handled uniformly:
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
 *
 * This runs during startup, before auto-resume launches Claude in any tab, so the restored
 * tabs' display names have not yet been overwritten by OSC titles; using the display name as
 * a matching fallback for tabs without a resolvable TerminalTitle is safe in this window.
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
     * Runs the re-attachment pass. Cheap and idempotent; safe to call repeatedly. Must run
     * on the EDT (touches terminal tab Content and the tabs manager).
     *
     * @return the number of bindings re-attached in this pass
     */
    fun migrate(): Int {
        val bindingService = BindingService.getInstance(project)
        val allBindings = bindingService.getAllBindings()
        if (allBindings.isEmpty()) return 0

        val toolWindow = TerminalHelper.getTerminalToolWindow(project)
        if (toolWindow == null) {
            logger.debug("Binding migration deferred: terminal tool window not available yet (${allBindings.size} bindings)")
            return 0
        }

        val openTabs: List<Content> = toolWindow.contentManager.contents
            .filterNot { TerminalHelper.isTeamTab(it) }
            .toList()
        if (openTabs.isEmpty()) {
            logger.debug("Binding migration deferred: no terminal tabs open yet (${allBindings.size} bindings)")
            return 0
        }

        val liveTabIds = openTabs.mapNotNull { TerminalHelper.getTabId(it) }.toSet()
        val claimedTabIds = allBindings
            .mapNotNull { it.tabIdentifier.tabUuid }
            .filter { it in liveTabIds }
            .toMutableSet()

        // Persistent names of open tabs, resolved in a single pass over the tabs manager.
        // Tabs without a resolvable TerminalTitle fall back to their display name - safe
        // here because this runs before Claude's OSC titles can appear (see class KDoc).
        val titleNames = TerminalHelper.persistentNamesSnapshot(project)
        val tabNames: Map<Content, String?> = openTabs.associateWith {
            titleNames[it] ?: it.displayName
        }
        // Unique-name index: only unambiguous names are used for re-attachment.
        val nameCounts = tabNames.values.filterNotNull().groupingBy { it }.eachCount()
        val uniqueNameToContent: Map<String, Content> = tabNames.entries
            .filter { it.value != null && nameCounts[it.value] == 1 }
            .associate { it.value!! to it.key }

        var reattached = 0
        var legacy = 0

        for (binding in allBindings) {
            val tabUuid = binding.tabIdentifier.tabUuid
            if (tabUuid != null && tabUuid in liveTabIds) continue  // already attached to a live tab

            val targetName = binding.tabIdentifier.effectiveName
            val content = uniqueNameToContent[targetName]
            if (content == null) {
                logger.debug(
                    "Binding ${binding.id} ('$targetName') not re-attached: " +
                        "${nameCounts[targetName] ?: 0} matching tabs open"
                )
                continue
            }

            val liveId = TerminalHelper.getOrCreateTabId(content)
            if (liveId in claimedTabIds) {
                logger.debug("Binding ${binding.id} ('$targetName') not re-attached: tab already claimed")
                continue
            }

            bindingService.attachTabUuid(binding.id, liveId, targetName)
            claimedTabIds.add(liveId)
            if (tabUuid == null) legacy++ else reattached++
            logger.info("Re-attached binding ${binding.id} ('$targetName') to live tab id $liveId (was ${tabUuid ?: "legacy"})")
        }

        val total = legacy + reattached
        if (total > 0) {
            logger.info(
                "Binding migration: $legacy legacy + $reattached restart-stale bindings re-attached " +
                    "(${allBindings.size} total, ${openTabs.size} open tabs)"
            )
        }
        return total
    }

    /**
     * Returns bindings that still are not attached to any open tab (used by auto-resume to
     * decide whether to keep waiting for tab restoration). Must run on the EDT.
     */
    fun unattachedBindings(): List<SessionBinding> {
        val bindingService = BindingService.getInstance(project)
        val toolWindow = TerminalHelper.getTerminalToolWindow(project) ?: return bindingService.getAllBindings()
        val liveTabIds = toolWindow.contentManager.contents
            .mapNotNull { TerminalHelper.getTabId(it) }
            .toSet()
        return bindingService.getAllBindings().filter { it.tabIdentifier.tabUuid !in liveTabIds }
    }
}
