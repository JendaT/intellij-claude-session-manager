package com.github.jendalen.claudesessionmanager.services

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
            SessionRegistryService.getInstance().removeAll(sessionIds)
            logger.info("Session registry: removed ${sessionIds.size} session(s) of closing project '${project.name}'")
        } catch (e: Exception) {
            logger.warn("Session registry cleanup on project close failed: ${e.message}")
        }
    }
}
