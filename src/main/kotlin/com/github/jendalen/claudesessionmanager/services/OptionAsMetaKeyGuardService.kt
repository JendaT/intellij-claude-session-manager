package com.github.jendalen.claudesessionmanager.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.Alarm
import org.jetbrains.plugins.terminal.TerminalOptionsProvider

/**
 * Watches the terminal "Use Option as Meta Key" setting and reverts it to off
 * when [SettingsService.forceOptionAsMetaKeyOff] is enabled.
 *
 * Works around Claude Code repeatedly enabling this macOS-specific terminal setting.
 *
 * Three layers of defense:
 * 1. Property change listener - catches immediate changes
 * 2. Session change trigger - called by [ClaudeHookEventService] on session events,
 *    with a delayed recheck to catch late re-enabling during session startup
 * 3. Periodic poll - fallback every [POLL_INTERVAL_MS] to catch anything else
 */
@Service(Service.Level.APP)
class OptionAsMetaKeyGuardService : Disposable {

    private val logger = Logger.getInstance(OptionAsMetaKeyGuardService::class.java)
    private val settingsService = SettingsService.getInstance()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var reverting = false

    companion object {
        /** Delay before the second check after a session change event */
        private const val RECHECK_DELAY_MS = 2000
        /** Periodic fallback poll interval */
        private const val POLL_INTERVAL_MS = 5000

        fun getInstance(): OptionAsMetaKeyGuardService {
            return ApplicationManager.getApplication().getService(OptionAsMetaKeyGuardService::class.java)
        }
    }

    init {
        val terminalOptions = ApplicationManager.getApplication().getService(TerminalOptionsProvider::class.java)

        // Layer 1: Property change listener
        terminalOptions.addListener(this) {
            if (reverting) return@addListener
            if (!settingsService.forceOptionAsMetaKeyOff) return@addListener
            if (!terminalOptions.useOptionAsMetaKey) return@addListener

            logger.info("Detected 'Use Option as Meta Key' turned on - reverting (listener)")
            revert(terminalOptions)
        }

        // Apply immediately on startup
        if (settingsService.forceOptionAsMetaKeyOff && terminalOptions.useOptionAsMetaKey) {
            logger.info("Guard enabled and 'Use Option as Meta Key' is on at startup - turning off")
            revert(terminalOptions)
        }

        // Layer 3: Start periodic poll
        schedulePeriodicPoll()
    }

    /**
     * Called by [ClaudeHookEventService] when a session change is detected.
     * Checks immediately and schedules a delayed recheck to catch late re-enabling.
     */
    fun onSessionChangeDetected() {
        if (!settingsService.forceOptionAsMetaKeyOff) return
        // Ensure UI component access runs on EDT
        ApplicationManager.getApplication().invokeLater { checkAndRevert("session-change") }
        // Cancel any prior delayed rechecks to avoid accumulation, then schedule one
        alarm.cancelAllRequests()
        periodicPollPending = false
        alarm.addRequest({ checkAndRevert("session-change-recheck") }, RECHECK_DELAY_MS)
        // Re-schedule periodic poll since cancelAllRequests cleared it (a poll runnable
        // executing at this instant re-arms too - the pending flag makes that a no-op)
        schedulePeriodicPoll()
    }

    /**
     * Called when the guard setting is toggled in preferences.
     */
    fun onGuardSettingChanged(enabled: Boolean) {
        if (!enabled) return
        checkAndRevert("setting-enabled")
    }

    private fun checkAndRevert(source: String) {
        val terminalOptions = ApplicationManager.getApplication().getService(TerminalOptionsProvider::class.java)
        if (terminalOptions.useOptionAsMetaKey) {
            logger.info("Reverting 'Use Option as Meta Key' to off (source=$source)")
            revert(terminalOptions)
        }
    }

    private fun revert(terminalOptions: TerminalOptionsProvider) {
        reverting = true
        try {
            terminalOptions.useOptionAsMetaKey = false
        } finally {
            reverting = false
        }
    }

    // The periodic poll re-arms itself from inside its own runnable. cancelAllRequests()
    // in onSessionChangeDetected() cannot cancel a runnable that is already executing, so
    // that runnable re-armed AND the session-change path re-armed: two chains, each
    // self-perpetuating for the life of the IDE, one more per resume-storm event that
    // hit the window. A single pending request is the invariant.
    @Volatile
    private var periodicPollPending = false

    private fun schedulePeriodicPoll() {
        if (periodicPollPending) return
        periodicPollPending = true
        alarm.addRequest({
            periodicPollPending = false
            if (settingsService.forceOptionAsMetaKeyOff) {
                val terminalOptions = ApplicationManager.getApplication().getService(TerminalOptionsProvider::class.java)
                if (terminalOptions.useOptionAsMetaKey) {
                    logger.info("Reverting 'Use Option as Meta Key' to off (source=periodic-poll)")
                    revert(terminalOptions)
                }
            }
            schedulePeriodicPoll()
        }, POLL_INTERVAL_MS)
    }

    override fun dispose() {
        // Alarm disposed via parent Disposable; listener via addListener registration
    }
}
