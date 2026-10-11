package com.newendian.retichat.data.repository

import com.newendian.retichat.service.GroupChatManager

/**
 * The admission of a group message that reached this device through its
 * distro D (GROUPS-DISTRO-DESIGN.md §6.3, §6.5, §12.3), apart from Room and
 * the JNI: the three first rules, a relay request never acted on, an invite
 * only listing D and only past the privacy filter, then the group rule; what
 * the rule drops only because the group is not joined here is held.
 * `ChatRepository.onDistroGroupMessage` reads the [Facts] and acts on the
 * [Verdict].
 */
object DistroGroupAdmission {
    data class Facts(
        val action: String?,
        /** The packet's [com.newendian.retichat.names.Signature] verdict. */
        val signature: Int,
        val srcHex: String,
        /** Its GROUP_SENDER, as carried. */
        val groupSender: String?,
        /** The members an invite lists (its GROUP_MEMBERS); empty for any other action. */
        val listed: Collection<String>,
        /** D, the distro this device holds, loaded. */
        val distro: String,
        /** The group is held here (pending or joined). */
        val heldHere: Boolean,
        val held: DeliveryPolicy.Held,
        val sourceStatus: String?,
        val closed: Boolean,
        val sourceIsCreator: Boolean,
        /** The self addresses the first rules ask about (§6.3, §9.1). */
        val selves: Set<String>,
        /** A group message from one of these is the user's own echo (§6.3). */
        val echoes: Set<String>,
        /** The privacy filter lets this source's invite through; false for any other action. */
        val sourceAllowed: Boolean,
        /** The group id is in the filtered-invite set; asked only when holding is weighed. */
        val filtered: () -> Boolean,
    )

    sealed class Verdict {
        /** Dropped, with [why] for the log line; [filterId]: its group id goes in the filtered-invite set (§6.5). */
        data class Drop(val why: String, val filterId: Boolean = false) : Verdict()
        /** Held until the group is joined here (§6.5). */
        object Hold : Verdict() { override fun toString() = "Hold" }
        /** Kept: the sender's name row, then the group handler (§6.4). */
        object Admit : Verdict() { override fun toString() = "Admit" }
    }

    fun decide(f: Facts): Verdict {
        DeliveryPolicy.firstRules(f.signature, f.srcHex, f.groupSender, f.selves, f.echoes)?.let { return Verdict.Drop(it) }
        if (f.action == GroupChatManager.Action.RELAY_REQUEST) {
            return Verdict.Drop("a relay request through the distro is never acted on, by any device of it (§12.3)")
        }
        if (f.action == GroupChatManager.Action.INVITE) {
            // Every invite through D must list D (§6.3, §9.3), held here or not.
            if (f.distro !in f.listed) return Verdict.Drop("the invite does not list the distro ${f.distro.take(8)} (§6.3, §9.3)")
            if (!f.sourceAllowed) {
                // The id goes in the filtered-invite set only when the filter decided about
                // a group not held here. One held here was admitted (pending from an inviter
                // the filter allowed): another source's invite for it decides nothing about
                // the group, and its traffic stays holdable (§6.5).
                return if (f.heldHere) {
                    Verdict.Drop("by the privacy filter; its group is held here, so its id stays out of the filtered-invite set (§6.5)")
                } else {
                    Verdict.Drop("by the privacy filter: its id joins the filtered-invite set, and its group's traffic is not held (§6.5)", filterId = true)
                }
            }
        }
        val namesOther = DeliveryPolicy.namesOther(f.groupSender, f.srcHex)
        val allowed = DeliveryPolicy.groupMessage(
            action = f.action,
            sourceAllowed = f.sourceAllowed,
            held = f.held,
            sourceStatus = f.sourceStatus,
            namesOther = namesOther,
            closed = f.closed,
            signature = f.signature,
            sourceIsCreator = f.sourceIsCreator,
        )
        if (allowed) return Verdict.Admit
        val keptIfJoined = DeliveryPolicy.groupMessage(
            f.action, false, DeliveryPolicy.Held.JOINED, f.sourceStatus, namesOther, false, f.signature, f.sourceIsCreator,
        )
        if (DeliveryPolicy.holdable(f.action, f.held, f.closed, f.filtered(), f.signature, f.srcHex in f.selves, keptIfJoined)) {
            return Verdict.Hold
        }
        return Verdict.Drop("group ${f.held}, source ${f.sourceStatus ?: "not listed"}, closed=${f.closed}, signature=${f.signature}")
    }
}
