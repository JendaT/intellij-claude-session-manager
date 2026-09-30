package com.github.jendalen.claudesessionmanager.services

import com.github.jendalen.claudesessionmanager.model.TabProfile
import com.github.jendalen.claudesessionmanager.util.LaunchArgsValidator
import com.github.jendalen.claudesessionmanager.util.TabProfiles
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection

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

        @XCollection(style = XCollection.Style.v2)
        var tabProfiles: MutableList<TabProfileEntry> = mutableListOf()
    }

    /** XmlSerializer form of [TabProfile]: plain class, var + defaults. */
    class TabProfileEntry {
        var name: String = ""
        var workingDirectory: String = ""
        var launchArgs: String = ""
        var keepOpen: Boolean = false
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

    /**
     * The tab profiles as stored, for the settings page (which shows invalid entries so
     * they can be fixed). Everything that launches or matches uses [tabProfiles].
     */
    var storedTabProfiles: List<TabProfile>
        get() = state.tabProfiles.map { TabProfile(it.name, it.workingDirectory, it.launchArgs, it.keepOpen) }
        set(value) {
            state.tabProfiles = value.map { p ->
                TabProfileEntry().apply {
                    name = p.name
                    workingDirectory = p.workingDirectory.trim()
                    launchArgs = LaunchArgsValidator.normalize(p.launchArgs) ?: p.launchArgs
                    keepOpen = p.keepOpen
                }
            }.toMutableList()
        }

    /**
     * The usable tab profiles. Re-validated on read like [extraLaunchArgs]: the list comes
     * from `workspace.xml`, the directory is typed into a shell as `cd '<dir>'` and the
     * arguments after it, so an entry that fails validation is dropped here, not only
     * refused by the settings page.
     */
    val tabProfiles: List<TabProfile>
        get() {
            val stored = storedTabProfiles
            if (stored.isEmpty()) return emptyList()
            val usable = TabProfiles.usable(stored)
            if (usable.size != stored.size) {
                logger.warn("Ignoring ${stored.size - usable.size} invalid tab profile(s) from the workspace file")
            }
            return usable
        }

    /** The profile for a tab whose persistent name is [persistentName], or null. */
    fun profileFor(persistentName: String?): TabProfile? = TabProfiles.find(tabProfiles, persistentName)

    companion object {
        fun getInstance(project: Project): ProjectSettingsService {
            return project.getService(ProjectSettingsService::class.java)
        }
    }
}
