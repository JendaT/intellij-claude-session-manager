package com.github.jendalen.claudesessionmanager.teams.services

import com.github.jendalen.claudesessionmanager.teams.model.TeamEvent
import com.github.jendalen.claudesessionmanager.teams.model.TeamModel

/**
 * Watches for agent team lifecycle events in the filesystem.
 */
interface TeamWatcher {
    fun start()
    fun stop()
    fun getActiveTeams(): List<TeamModel>
    fun addListener(listener: TeamEventListener)
    fun removeListener(listener: TeamEventListener)
}

/**
 * Listener for team lifecycle events.
 */
fun interface TeamEventListener {
    fun onTeamEvent(event: TeamEvent)
}
