package com.newendian.retichat.service

/**
 * Which networks the OS has blocked for this app
 * (ConnectivityManager.NetworkCallback.onBlockedStatusChanged). The callback
 * also reports "not blocked" right after every onAvailable, so only a block
 * that lifts is an event: [update] says true exactly then.
 */
internal class NetworkBlocks {
    private val blocked = mutableSetOf<Any>()

    /** Record [network]'s status; true when a block on it has just lifted. */
    @Synchronized
    fun update(network: Any, isBlocked: Boolean): Boolean =
        if (isBlocked) {
            blocked.add(network)
            false
        } else {
            blocked.remove(network)
        }

    /** The network is gone; its block with it. */
    @Synchronized
    fun forget(network: Any) {
        blocked.remove(network)
    }
}
