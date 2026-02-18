package com.github.jendalen.claudesessionmanager.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.plugins.terminal.TerminalOptionsProvider

/**
 * Watches the terminal "Use Option as Meta Key" setting and reverts it to off
 * when [SettingsService.forceOptionAsMetaKeyOff] is enabled.
 *
 * Works around Claude Code repeatedly enabling this macOS-specific terminal setting.
 */
@Service(Service.Level.APP)
class OptionAsMetaKeyGuardService : Disposable {

    private val logger = Logger.getInstance(OptionAsMetaKeyGuardService::class.java)
    private val settingsService = SettingsService.getInstance()

    @Volatile
    private var reverting = false

    init {
        val terminalOptions = ApplicationManager.getApplication().getService(TerminalOptionsProvider::class.java)
        terminalOptions.addListener(this) {
            if (reverting) return@addListener
            if (!settingsService.forceOptionAsMetaKeyOff) return@addListener
            if (!terminalOptions.useOptionAsMetaKey) return@addListener

            logger.info("Detected 'Use Option as Meta Key' turned on - reverting to off (guard enabled)")
            reverting = true
            try {
                terminalOptions.useOptionAsMetaKey = false
            } finally {
                reverting = false
            }
        }

        // Apply immediately on startup if the guard is on and the setting is currently on
        if (settingsService.forceOptionAsMetaKeyOff && terminalOptions.useOptionAsMetaKey) {
            logger.info("Guard enabled and 'Use Option as Meta Key' is on at startup - turning off")
            terminalOptions.useOptionAsMetaKey = false
        }
    }

    /**
     * Called when the guard setting is toggled in preferences.
     * If just enabled, immediately check and revert.
     */
    fun onGuardSettingChanged(enabled: Boolean) {
        if (!enabled) return
        val terminalOptions = ApplicationManager.getApplication().getService(TerminalOptionsProvider::class.java)
        if (terminalOptions.useOptionAsMetaKey) {
            logger.info("Guard just enabled - reverting 'Use Option as Meta Key' to off")
            terminalOptions.useOptionAsMetaKey = false
        }
    }

    override fun dispose() {
        // Listener cleanup handled by Disposable parent registration in addListener
    }

    companion object {
        fun getInstance(): OptionAsMetaKeyGuardService {
            return ApplicationManager.getApplication().getService(OptionAsMetaKeyGuardService::class.java)
        }
    }
}
