package com.newendian.retichat.ui.conversation

import com.newendian.retichat.names.DisplayNames

/**
 * The texts a group whose own address is the user's distro D shows
 * (GROUPS-DISTRO-DESIGN.md §8.7, §13). A decline, leave or delete there is
 * signed as D, so it is final at every member and, through the group sync, on
 * every device of D; a group whose D this device no longer holds can only be
 * deleted from this device. A device-listed group keeps today's texts.
 *
 * James, 2026-10-11: no text describes an internal state. At start-up nothing
 * is said (a post waits as a DM does; the other actions are greyed), and a post
 * refused after Send says nothing either (its draft stays). [formerDistro] is the
 * only text for a group that cannot act here, the same words on iOS, Android
 * and the web.
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

    /**
     * §8.7: the bar of a group whose own address is the former distro
     * [distroHex] (forgotten, replaced, or a stored key that cannot be loaded),
     * with "Delete from this device". [distroHex] is shown as its first 8 hex
     * characters and "…" (U+2026).
     */
    fun formerDistro(distroHex: String): String =
        "This group uses your distro address ${DisplayNames.shortHash(distroHex)}, which is no longer on this device. " +
            "New messages in it don't reach this device, and you can't post, join or leave from here. " +
            "Import that distro again to use the group here."
}
