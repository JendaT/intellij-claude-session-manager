package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.util.LaunchArgsValidator
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Project-level settings, persisted in the workspace file next to the bindings.
 *
 * The launch profile lives here and not in the application-level [SettingsService]
 * because the flags are per project (a development channel loaded in one worktree must
 * not be loaded in another) and because the workspace file is per user and unshared,
 * which is where a machine-local launch profile belongs.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ClaudeSessionManagerProject",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class ProjectSettingsService : PersistentStateComponent<ProjectSettingsService.State> {

    private val logger = Logger.getInstance(ProjectSettingsService::class.java)

    /**
     * Regular class with var + defaults - required by the IntelliJ XML serializer.
     */
    class State {
        var extraLaunchArgs: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Extra arguments inserted between the Claude command and `--resume`. Re-validated
     * on read: loadState() takes the persisted XML as-is, and this value is typed into the
     * user's shell, so the allowlist must hold here and not only in the settings UI.
     */
    var extraLaunchArgs: String
        get() = LaunchArgsValidator.normalize(state.extraLaunchArgs) ?: run {
            logger.warn("Persisted extraLaunchArgs rejected by validator - ignoring them")
            ""
        }
        set(value) {
            state.extraLaunchArgs = LaunchArgsValidator.normalize(value) ?: ""
        }

    companion object {
        fun getInstance(project: Project): ProjectSettingsService {
            return project.getService(ProjectSettingsService::class.java)
        }
    }
}
