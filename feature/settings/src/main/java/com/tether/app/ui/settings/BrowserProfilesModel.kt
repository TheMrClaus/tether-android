package com.tether.app.ui.settings

import com.tether.app.client.BrowserProfilesReply
import com.tether.app.protocol.BrowserProfileInfo

/** Settings > Browser > Profiles: the words and the pure checks (components/browser-profiles-settings.tsx, lib/browser-profiles-ui.ts). */
object BrowserProfilesCopy {
    const val TITLE = "Profiles"
    const val CAPTION = "Named browser profiles keep logins and cookies between sessions. Each session picks one in its browser pane."
    const val EMPTY = "Profiles keep a browser login between sessions. Until you create one, every session uses a throwaway profile that is erased when its browser closes."
    const val TRY_AGAIN = "Try again"
    const val RENAME = "Rename"
    const val DELETE = "Delete"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val DELETE_PROFILE = "Delete profile"
    const val KEEP_IT = "Keep it"
    const val NEW_PROFILE = "New profile"
    const val NAME_PLACEHOLDER = "Profile name"
    const val NEW_NAME_LABEL = "New profile name"
    const val CREATE = "Create profile"
    const val NAME_LIMIT = 60

    fun renameLabel(name: String) = "New name for $name"
    fun deleteLabel(name: String) = "Delete $name"
    fun use(profile: BrowserProfileInfo) = if (profile.heldBy != null) "In use by ${profile.heldBy}" else "Not in use"
    fun deleteConfirm(name: String) = "Delete \"$name\"? The logins and cookies stored in this profile are removed and cannot be recovered."

    /** The client-side name check shared by create and rename; the server stays the judge. */
    fun nameProblem(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Give the profile a name."
        if (name.length > NAME_LIMIT) return "Use $NAME_LIMIT characters or fewer."
        return null
    }

    /** The sentence under a refused action: the server's own, else a plain fallback by code. */
    fun errorText(reply: BrowserProfilesReply): String {
        reply.error?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (reply.code) {
            "bad_name" -> "That name cannot be used."
            "name_taken" -> "A profile with that name already exists."
            "limit" -> "There are too many profiles. Delete one first."
            "no_such_profile" -> "That profile no longer exists."
            "PROFILE_IN_USE" -> "A session is using that profile. Close its browser or pick another profile there first."
            "grade_refused" -> "Only the owner can change profiles."
            else -> "The profile change did not go through."
        }
    }
}
