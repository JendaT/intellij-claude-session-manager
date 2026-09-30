package com.github.jendalen.claudesessionmanager.util

/**
 * When a session's title may rename its tab. Pure, so the rule is unit tested.
 *
 * The title a session already had when the plugin first looked at it is not a rename
 * the user just made: on the first start of 0.8.0, 7 of 26 bound WebStorm tabs would
 * have taken titles given days earlier, overwriting names chosen on purpose ("Kafka
 * master", "Debugger"). So a first sighting renames only a tab that still has a default
 * name ("Local", "Local (2)"); every title change after that renames the tab.
 */
object TitleSyncRules {

    enum class Decision { RENAME, ALREADY_NAMED, KEEP_USER_NAME, PROFILE_TAB, NAME_TAKEN }

    /**
     * @param firstSighting the binding had never recorded a title for this session
     * @param userNamed the tab has a user-defined title (not a default "Local..." name)
     * @param profileTab the tab is a tab profile's tab; its name is configuration
     * @param nameTaken another binding already carries [title] as its tab name
     */
    fun decide(
        currentName: String?,
        title: String,
        firstSighting: Boolean,
        userNamed: Boolean,
        profileTab: Boolean,
        nameTaken: Boolean
    ): Decision = when {
        currentName == title -> Decision.ALREADY_NAMED
        profileTab -> Decision.PROFILE_TAB
        firstSighting && userNamed -> Decision.KEEP_USER_NAME
        nameTaken -> Decision.NAME_TAKEN
        else -> Decision.RENAME
    }
}
