package com.newendian.retichat.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that monitors the default network — the one the stack's sockets
 * use — via [ConnectivityManager.NetworkCallback].
 *
 * Exposes [isOnline] as a [StateFlow] that UI and service layers
 * can observe. Also provides a listener list so [ReticulumService]
 * can flush queued messages when connectivity is restored.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var registered = false

    /** Networks the OS reports as blocked for this app; see onBlockedStatusChanged. */
    private val blocks = NetworkBlocks()

    /** Listeners invoked on the connectivity-manager callback thread. */
    private val onAvailableListeners = mutableListOf<() -> Unit>()

    fun addOnAvailableListener(listener: () -> Unit) {
        synchronized(onAvailableListeners) { onAvailableListeners.add(listener) }
    }

    fun removeOnAvailableListener(listener: () -> Unit) {
        synchronized(onAvailableListeners) { onAvailableListeners.remove(listener) }
    }

    /**
     * Register the system callback. Idempotent — safe to call from
     * [android.app.Application.onCreate].
     */
    fun register(context: Context) {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed with the current state
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        _isOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        Log.d(TAG, "Initial network state: online=${_isOnline.value}")

        // The default network, not every network with INTERNET. On
        // 2026-09-25 Wi-Fi came back while cellular was still the default:
        // onAvailable(Wi-Fi) woke the TCP interface 2 s before Wi-Fi became
        // the default, Android aborted that connect when it did
        // (ECONNABORTED), and the doubled backoff kept RFed down 160 s more.
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                _isOnline.value = true
                synchronized(onAvailableListeners) {
                    onAvailableListeners.forEach { it() }
                }
            }

            // Android blocks a backgrounded app's network (no foreground
            // service) without losing the network, so onAvailable does not
            // fire again when the block lifts. Until 2026-09-25 nothing told
            // the app: the TCP interface slept out its reconnect backoff and
            // the RFed links stayed down after the user opened it.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (blocks.update(network, blocked)) {
                    Log.d(TAG, "Network access unblocked")
                    synchronized(onAvailableListeners) {
                        onAvailableListeners.forEach { it() }
                    }
                }
            }

            override fun onLost(network: Network) {
                blocks.forget(network)
                // Check if there's still another active network
                val stillActive = cm.activeNetwork
                val stillCaps = if (stillActive != null) cm.getNetworkCapabilities(stillActive) else null
                val still = stillCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (!still) {
                    Log.d(TAG, "Network lost — offline")
                    _isOnline.value = false
                }
            }
        })
        registered = true
    }
}
