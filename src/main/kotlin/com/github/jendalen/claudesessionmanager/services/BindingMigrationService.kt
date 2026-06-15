package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.terminal.TerminalHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content

/**
 * One-time, idempotent migration of legacy (name-only) session bindings onto the stable
 * per-tab id model introduced alongside Claude Code's terminal-title takeover.
 *
 * Background: bindings used to be matched to terminal tabs by the tab's displayName. Recent
 * JetBrains builds honor the OSC-0 escape sequence Claude Code emits, which renames every
 * Claude tab to "Claude Code". That collapses distinct tabs to one name, breaking name-based
 * matching. The new model keys on a stable UUID stored in the tab's Content UserData
 * (see [TerminalHelper.STABLE_TAB_KEY]).
 *
 * A binding is "legacy" if its [tabUuid] is null. For each legacy binding this migration tries
 * to find the currently-open terminal tab it refers to (by displayName, unambiguously) and
 * stamps a freshly-generated stable id onto both the live tab's Content and the persisted
 * binding, so subsequent matching is name-independent.
 *
 * Bindings that cannot be matched unambiguously are left untouched and keep working via the
 * name-based fallback in [com.github.jendalen.claudesessionmanager.model.TabIdentifier.matchConfidence].
 * Because the trigger is "tabUuid == null", the migration safely re-runs on later startups for
 * any binding it could not match yet (e.g. its tab was not open at the time).
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
     * Runs the migration. Cheap and idempotent; safe to call on every startup. Must run on EDT
     * (touches terminal tab Content); callers should wrap with invokeLater if needed.
     */
    fun migrate() {
        val bindingService = BindingService.getInstance(project)

        val legacyBindings = bindingService.getAllBindings().filter { it.tabIdentifier.tabUuid == null }
        if (legacyBindings.isEmpty()) {
            bindingService.markSchemaCurrent()
            return
        }

        val toolWindow = TerminalHelper.getTerminalToolWindow(project)
        if (toolWindow == null) {
            logger.info("Binding migration deferred: terminal tool window not available yet (${legacyBindings.size} legacy bindings)")
            return
        }

        // Tabs not yet claimed by any stable id are migration candidates.
        val openTabs: List<Content> = toolWindow.contentManager.contents
            .filterNot { TerminalHelper.isTeamTab(it) }
            .toList()

        // Count names so we only auto-match when a name is unambiguous among open tabs.
        val nameCounts = openTabs.groupingBy { it.displayName }.eachCount()
        val claimedTabIds = bindingService.getAllBindings()
            .mapNotNull { it.tabIdentifier.tabUuid }
            .toMutableSet()

        var migrated = 0
        for (binding in legacyBindings) {
            val name = binding.tabIdentifier.name
            if (nameCounts[name] != 1) {
                // Zero matches (tab gone) or duplicates (e.g. several "Claude Code" tabs):
                // cannot safely disambiguate. Leave legacy; name fallback still applies.
                continue
            }
            val content = openTabs.firstOrNull {
                it.displayName == name && (TerminalHelper.getTabId(it)?.let { id -> id !in claimedTabIds } ?: true)
            } ?: continue

            val tabId = TerminalHelper.getOrCreateTabId(content)
            if (tabId in claimedTabIds) continue  // already taken by another binding this pass
            bindingService.attachTabUuid(binding.id, tabId)
            claimedTabIds.add(tabId)
            migrated++
            logger.info("Migrated binding ${binding.id} ('${name}') to stable tab id $tabId")
        }

        logger.info("Binding migration: $migrated of ${legacyBindings.size} legacy bindings matched to open tabs")
        bindingService.markSchemaCurrent()
    }

    /** Convenience wrapper that ensures the migration runs on the EDT. */
    fun migrateOnEdt() {
        ApplicationManager.getApplication().invokeLater { migrate() }
    }
}
