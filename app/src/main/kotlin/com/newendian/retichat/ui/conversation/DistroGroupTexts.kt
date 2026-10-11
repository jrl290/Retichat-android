package com.newendian.retichat.ui.conversation

import com.newendian.retichat.names.DisplayNames

/**
 * The texts a group whose own address is the user's distro D shows
 * (GROUPS-DISTRO-DESIGN.md §8.7, §13). A decline, leave or delete there is
 * signed as D, so it is final at every member and, through the group sync, on
 * every device of D; a group whose D this device no longer holds can only be
 * read, or deleted from this device. A device-listed group keeps today's texts.
 */
object DistroGroupTexts {
    const val DECLINE_TITLE = "Decline this invitation?"
    const val DECLINE_TEXT = "This declines it on all your devices, and you cannot join this group later."

    const val LEAVE_TITLE = "Leave this group?"
    const val LEAVE_TEXT = "You will leave it on all your devices, its messages are deleted from all of them, and you cannot rejoin."

    const val DELETE_TITLE = "Delete this group?"
    const val DELETE_TEXT = "This leaves it and deletes its messages on all your devices, and you cannot rejoin."

    const val LOCAL_DELETE = "Delete from this device"
    const val LOCAL_DELETE_TEXT = "This removes the group from this device only. You stay in the group on your other devices and for the other members."

    /** §8.7: shown in a group whose own address is the former distro [distroHex]. */
    fun formerDistro(distroHex: String): String =
        "This group uses your distro address ${DisplayNames.shortHash(distroHex)}, which this device no longer holds. " +
            "You can read it here, but cannot post or change your membership from this device."
}
