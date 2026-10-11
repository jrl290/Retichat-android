package com.newendian.retichat.data.repository

/**
 * Who the user is in one group (GROUPS-DISTRO-DESIGN.md §8.1, §9.1): the
 * group's own address [own], the address the user acts as there, and every
 * self address [all]. [distro] is X, the distro this group's self rests on
 * (its own address when that is a distro, else the distro this device holds
 * when the list names it), or null.
 *
 * Every self address is never a send target, never allowlisted or kept as a
 * contact, shown as "You", passes the creator test, has its key skipped and
 * never remembered, and a status element about it is skipped (§9.2). Pending
 * or joined is read from [own]'s row alone.
 */
data class GroupSelf(val own: String, val distro: String?, val all: Set<String>) {
    fun isSelf(hex: String?): Boolean = hex != null && hex in all
}

object GroupSelves {
    /** The group's own address: the one persisted, or the device for a group held before database 16 (held at upgrade, §8.1). */
    fun ownMember(persisted: String?, device: String): String = persisted?.takeIf { it.isNotEmpty() } ?: device

    /** A D-group (§5): its own address is a distro, not the device — this D or a former one. */
    fun isDistroGroup(persisted: String?, device: String): Boolean =
        persisted != null && persisted.isNotEmpty() && persisted != device

    /**
     * The own address of a group admitted from an invite listing [listed]
     * (§8.1, §9.3): the distro [heldD] this device holds when the list names it
     * (a list naming both the device and D gives D), else the device when the
     * list names it, else null: the invite is not for this user.
     */
    fun ownForInvite(listed: Collection<String>, device: String, heldD: String?): String? = when {
        heldD != null && heldD in listed -> heldD
        device in listed -> device
        else -> null
    }

    /**
     * §9.1 for a group whose persisted own address is [persisted] and whose list
     * holds [listed]: X is the own address when it is a distro, else [heldD] when
     * the list names it. Where X exists and is listed, self is {X, the device,
     * the known siblings of X ([siblingsOf])}; otherwise {the device} (with the
     * own address, which is the device there).
     */
    fun of(
        persisted: String?,
        listed: Collection<String>,
        device: String,
        heldD: String?,
        siblingsOf: (String) -> Set<String>,
    ): GroupSelf {
        val own = ownMember(persisted, device)
        val x = (if (own != device) own else heldD)?.takeIf { it in listed }
        val all = if (x != null) setOf(x, device, own) + siblingsOf(x) else setOf(device, own)
        return GroupSelf(own, x, all)
    }

    /**
     * Self for a group not held here (nothing about its list is known): the
     * device and, when this device holds a distro, that distro and its known
     * siblings. Used only to drop a message that names the user while not
     * coming from the user (§6.3), never to target or allowlist.
     */
    fun unheld(device: String, heldD: String?, siblingsOf: (String) -> Set<String>): Set<String> =
        if (heldD != null) setOf(device, heldD) + siblingsOf(heldD) else setOf(device)

    /** Who signs for a group (§8.1 `groupSigner`), as decided from what is held. */
    enum class Signer {
        /** The group's own address is the device: the device identity. */
        DEVICE,
        /** The own address is the distro this device holds, loaded: `DistroManager.signing()`. */
        DISTRO,
        /** The own address is a distro, none is loaded yet but one is stored (start-up): nothing is offered until it is (§8.7). */
        NOT_LOADED,
        /** The own address is a distro this device no longer holds (forgotten or replaced): nothing, ever, from here (§8.7). */
        FORMER,
        /**
         * The stack is not up (starting, failed to start, or stopped): no group
         * send can be signed or go, so nothing is offered until it is up, as for
         * [NOT_LOADED] (§8.7, DESIGN_PRINCIPLES §5); nothing is queued. Which
         * group is the device's cannot be told then either.
         */
        NOT_READY,
    }

    /**
     * [Signer] for a group whose persisted own address is [persisted], with
     * [loadedD] the distro loaded now (its `lxmf.delivery` hash, from the strict
     * helper, or null) and [distroStored] whether a distro key is stored though
     * not loaded. Never the device for a D-group: a group send never falls back
     * (members would drop it as unlisted, and the user would be shown an action
     * that did not happen). [device] is empty while the stack is not up:
     * [Signer.NOT_READY], whatever the group.
     */
    fun signer(persisted: String?, device: String, loadedD: String?, distroStored: Boolean): Signer = when {
        device.isEmpty() -> Signer.NOT_READY
        !isDistroGroup(persisted, device) -> Signer.DEVICE
        loadedD == persisted -> Signer.DISTRO
        loadedD == null && distroStored -> Signer.NOT_LOADED
        else -> Signer.FORMER
    }

    /**
     * Why a group cannot be created now (§8.5, §8.7), or null: [Signer.NOT_READY]
     * while the stack is not up ([device] empty), [Signer.NOT_LOADED] while a
     * distro is stored but none is loaded ([loadedD] null): created then, the
     * group would be the device's, and the user's other devices would never
     * hear of it. Nothing is written or sent for a refused create.
     */
    fun createRefusal(device: String, loadedD: String?, distroStored: Boolean): Signer? = when {
        device.isEmpty() -> Signer.NOT_READY
        loadedD == null && distroStored -> Signer.NOT_LOADED
        else -> null
    }

    /** Targets of a group send: [members] without any self address (§8.2, §9.2). */
    fun targets(members: Collection<String>, self: GroupSelf): List<String> =
        members.filter { !self.isSelf(it) }.distinct()
}

/**
 * What the UI shows of a group's acting address (GROUPS-DISTRO-DESIGN.md §8.7,
 * §13): whether its own address is a distro, whether a send as it can be signed
 * from this device now, its own address, and every self address ("You").
 */
data class GroupActing(
    val distroGroup: Boolean,
    val signer: GroupSelves.Signer,
    val own: String,
    val selves: Set<String>,
) {
    /**
     * The group's own address cannot be signed for here now, or the stack is
     * not up: no post, accept, decline or leave is offered (§8.7). Nothing is
     * queued for later.
     */
    val readOnly: Boolean get() = signer == GroupSelves.Signer.NOT_LOADED || signer == GroupSelves.Signer.FORMER ||
        signer == GroupSelves.Signer.NOT_READY

    /** Its own address is a distro this device no longer holds: only "Delete from this device" is offered (§8.7, §13). */
    val former: Boolean get() = signer == GroupSelves.Signer.FORMER
}

/** What `ChatRepository.createGroupChat` did: the group [Created], or [Refused] with nothing written or sent (§8.7). */
sealed class GroupCreation {
    data class Created(val chatId: String) : GroupCreation()

    /** [why] is [GroupSelves.Signer.NOT_READY] or [GroupSelves.Signer.NOT_LOADED] ([GroupSelves.createRefusal]). */
    data class Refused(val why: GroupSelves.Signer) : GroupCreation()
}

/**
 * A group post refused with nothing stored, sent or queued (§8.7): the group's
 * own address [own] has no signer here now, as [signer] says (the stack
 * stopped, or D went or has not loaded, since the composer was enabled). The
 * screen puts the draft back and says why (alignment decision 5, 2026-10-11).
 */
data class GroupPostRefused(val signer: GroupSelves.Signer, val own: String)
