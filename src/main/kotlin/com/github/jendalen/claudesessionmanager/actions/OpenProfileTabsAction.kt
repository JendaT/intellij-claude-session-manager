package com.github.jendalen.claudesessionmanager.actions

import com.github.jendalen.claudesessionmanager.services.TabProfileService
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Opens every tab profile of the project that has no open tab. Hidden in projects
 * without tab profiles, so their menus look exactly as before.
 */
class OpenProfileTabsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TabProfileService.getInstance(project).openMissingProfileTabs()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            TabProfileService.getInstance(project).profiles().isNotEmpty()
    }
}

/**
 * "Open Profile Tab" submenu: one entry per tab profile. Opening a profile whose tab is
 * already open selects that tab. Hidden in projects without tab profiles.
 */
class OpenProfileTabGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        return TabProfileService.getInstance(project).profiles()
            .map { OpenProfileTabAction(it.name, "${it.name}  (${it.workingDirectory})") }
            .toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            TabProfileService.getInstance(project).profiles().isNotEmpty()
    }
}

/**
 * Opens (or selects) one profile tab. Looks the profile up by name when clicked: the
 * menu may have been built before the settings changed.
 */
class OpenProfileTabAction(private val profileName: String, text: String) : AnAction(text), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = TabProfileService.getInstance(project)
        val profile = service.profiles().firstOrNull { it.name == profileName } ?: return
        service.openProfileTab(profile, activate = true)
    }
}
