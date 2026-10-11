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
        /**
         * The own address is a distro this device does not hold now: forgotten,
         * replaced, or a stored key that cannot be loaded. The stored key is
         * loaded before the stack is up (StackRuntime.bootstrap), so one not
         * loaded by then never loads by itself: it is a former distro, never
         * "not loaded yet" (James, 2026-10-11). Nothing, ever, from here; only
         * "Delete from this device" is offered (§8.7).
         */
        FORMER,
        /**
         * Start-up: the stack has not finished starting (or failed to, or is
         * being restarted from Settings), so which group is the device's cannot
         * be told yet. A post waits as a DM does ([post]); accept, decline,
         * leave and create are greyed; nothing says why (§8.7; James, 2026-10-11).
         */
        NOT_READY,
    }

    /**
     * [Signer] for a group whose persisted own address is [persisted], with
     * [loadedD] the distro loaded now (its `lxmf.delivery` hash, from the strict
     * helper, or null). Never the device for a D-group: a group send never falls
     * back (members would drop it as unlisted, and the user would be shown an
     * action that did not happen). [device] is empty until the start has
     * finished: [Signer.NOT_READY], whatever the group.
     */
    fun signer(persisted: String?, device: String, loadedD: String?): Signer = when {
        device.isEmpty() -> Signer.NOT_READY
        !isDistroGroup(persisted, device) -> Signer.DEVICE
        loadedD == persisted -> Signer.DISTRO
        else -> Signer.FORMER
    }

    /** What a group post does when the user sends it ([post]; §8.7, James, 2026-10-11). */
    enum class Post {
        /** Sent now, as the group's own address. */
        SEND,
        /**
         * Start-up: it waits exactly as a DM does, with the same pending clock;
         * it is sent when the start finishes, as the group's own address resolved
         * then, or shown failed, the DM failed state, if it cannot be signed then.
         * Ordering (DESIGN_PRINCIPLES §5), not a retry.
         */
        WAIT,
        /** A former distro: nothing is stored or sent; no notice, and the draft stays in the composer. */
        REFUSE,
    }

    /**
     * [Post] for a group post whose signer is [signer] now, [started] whether
     * the start has finished by the DM queue's own test (`ChatRepository.stackReady`).
     */
    fun post(signer: Signer, started: Boolean): Post = when {
        signer == Signer.FORMER -> Post.REFUSE
        signer == Signer.NOT_READY || !started -> Post.WAIT
        else -> Post.SEND
    }

    /**
     * Why a group cannot be created now (§8.5, §8.7), or null: [Signer.NOT_READY]
     * until the start has finished ([device] empty); Create is greyed then, with
     * no text. Otherwise it is created as the distro this device holds, else as
     * the device (§8.1): a stored key that cannot be loaded is no distro held.
     * Nothing is written or sent for a refused create.
     */
    fun createRefusal(device: String): Signer? = if (device.isEmpty()) Signer.NOT_READY else null

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
     * Accept, decline and leave can be signed and sent now. Greyed otherwise,
     * with no text: at start-up until the start finishes ([GroupSelves.Signer.NOT_READY]);
     * in a former distro the former-distro bar replaces them ([former]). The
     * composer is never greyed for this: a post at start-up waits as a DM does
     * ([GroupSelves.post]); James, 2026-10-11.
     */
    val canAct: Boolean get() = signer == GroupSelves.Signer.DEVICE || signer == GroupSelves.Signer.DISTRO

    /**
     * Its own address is a distro this device no longer holds: the bar with
     * §8.7's text and "Delete from this device" replaces the composer, accept,
     * decline and leave. Never at start-up.
     */
    val former: Boolean get() = signer == GroupSelves.Signer.FORMER
}

/** What `ChatRepository.createGroupChat` did: the group [Created], or [Refused] with nothing written or sent (§8.7). */
sealed class GroupCreation {
    data class Created(val chatId: String) : GroupCreation()

    /** The start has not finished, or stopped since Create was offered; [why] is for the log ([GroupSelves.createRefusal]). */
    data class Refused(val why: GroupSelves.Signer) : GroupCreation()
}

/**
 * A group post refused with nothing stored, sent or queued (§8.7): the group's
 * own address [own] cannot be signed for here, as [signer] says (its distro went
 * since the screen was drawn). The screen keeps the draft and says nothing: the
 * former-distro bar shows (James, 2026-10-11).
 */
data class GroupPostRefused(val signer: GroupSelves.Signer, val own: String)
