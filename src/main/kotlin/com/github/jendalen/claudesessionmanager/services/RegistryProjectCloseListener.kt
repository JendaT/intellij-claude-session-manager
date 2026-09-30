package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.TabProfiles
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/**
 * Removes the project's sessions from the registry when the project closes. The
 * terminals die with the project and Claude's SessionEnd hook does not run for a
 * killed process, so without this an external reader would see the sessions as live
 * (with a stale pid) until the 24 h sweep.
 */
class RegistryProjectCloseListener : ProjectCloseListener {

    private val logger = Logger.getInstance(RegistryProjectCloseListener::class.java)

    override fun projectClosing(project: Project) {
        try {
            val sessionIds = BindingService.getInstance(project).getAllBindings().map { it.currentSessionId }
            if (sessionIds.isEmpty()) return
            val registry = SessionRegistryService.getInstance()
            val basePath = project.basePath
            val profiles = ProjectSettingsService.getInstance(project).tabProfiles
            var removed = 0
            var kept = 0
            for (id in sessionIds) {
                // A binding here does not mean the session runs here: the same session can
                // be bound in two projects (a stale binding survives in the project it was
                // moved out of). Deleting by binding id took the file the LIVE IDE had
                // published, and the double-resume guard then read nothing on the next
                // start. Remove only what this project published, and never a file whose
                // process is alive somewhere else.
                val entry = registry.read(id) ?: continue
                val ownedElsewhere = entry.project != null && entry.project != project.name
                // "Here" is the project folder or one of its tab profiles' directories.
                val liveElsewhere = entry.isLive() && entry.cwd != null && basePath != null &&
                    !TabProfiles.isUnderAnyRoot(basePath, profiles, entry.cwd)
                if (ownedElsewhere || liveElsewhere) {
                    kept++
                    logger.info("Session registry: keeping $id on close of '${project.name}' - " +
                        (if (ownedElsewhere) "published by project '${entry.project}'" else "process ${entry.pid} is alive in ${entry.cwd}"))
                    continue
                }
                registry.remove(id)
                removed++
            }
            logger.info("Session registry: removed $removed session(s) of closing project '${project.name}', kept $kept owned or live elsewhere")
        } catch (e: Exception) {
            logger.warn("Session registry cleanup on project close failed: ${e.message}")
        }
    }
}
