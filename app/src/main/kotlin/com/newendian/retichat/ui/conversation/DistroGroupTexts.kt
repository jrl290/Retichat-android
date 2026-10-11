package com.newendian.retichat.ui.conversation

import com.newendian.retichat.data.repository.GroupSelves
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

    /** §8.7: the group view while the stack is not running ([GroupSelves.Signer.NOT_READY]). */
    const val NOT_RUNNING = "Retichat isn't running on this device right now. You can post and change your membership once it is."

    /** §8.7: the group view of a D-group while D is stored but not loaded yet ([GroupSelves.Signer.NOT_LOADED]). */
    const val NOT_LOADED = "Your distro address hasn't loaded on this device yet. You can post and change your membership once it has."

    /**
     * Why nothing that would send (post, accept, decline, leave) is offered in
     * a group whose own address is [own], acting as [signer] (§8.7), or null
     * when it can act. The words are the same on iOS, Android and the web
     * (alignment decision 3, 2026-10-11).
     */
    fun unavailable(signer: GroupSelves.Signer, own: String): String? = when (signer) {
        GroupSelves.Signer.NOT_READY -> NOT_RUNNING
        GroupSelves.Signer.NOT_LOADED -> NOT_LOADED
        GroupSelves.Signer.FORMER -> formerDistro(own)
        GroupSelves.Signer.DEVICE, GroupSelves.Signer.DISTRO -> null
    }
}
