package com.newendian.retichat.data.repository

/**
 * Where a sent message's delivery report lands, by LXMF hash hex.
 *
 * A delivery proof can arrive after the state poll has let go of the
 * message: the receipt timed out and the bubble shows FAILED, or the
 * propagated fallback took the bubble over. A valid proof still means the
 * recipient has the message, so the router reports DELIVERED late (LXMF-rust
 * `report_late_delivery`) and [ChatRepository.onMessageState] looks the hash
 * up here to upgrade the right row. Bounded: the oldest sends are forgotten
 * first, and a proof for one of those is only logged by the stack.
 */
internal class DeliveryTargets(private val capacity: Int = 256) {
    sealed interface Target {
        /** A 1:1 bubble, by its row id. */
        data class Row(val messageId: String) : Target

        /** One member's copy of a group message. */
        data class GroupMember(val groupMsgId: String, val chatId: String, val memberHex: String) : Target
    }

    private val targets = object : LinkedHashMap<String, Target>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Target>) = size > capacity
    }

    @Synchronized
    fun remember(hashHex: String, target: Target) {
        targets[hashHex] = target
    }

    /** The target a DELIVERED report for [hashHex] upgrades, taken once. */
    @Synchronized
    fun take(hashHex: String): Target? = targets.remove(hashHex)
}
