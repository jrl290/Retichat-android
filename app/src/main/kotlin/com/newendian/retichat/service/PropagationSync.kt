package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.delay

/**
 * One-shot LXMF propagation pull.
 *
 * Mirrors iOS [`ChatRepository.pollPropagationNode()`] +
 * `LxmfClient.sync(nodeHash:)` — i.e. set the user-configured (or randomly
 * chosen) propagation node, ask the router to request messages, and monitor
 * the propagation state machine until it reaches a terminal state or the
 * deadline expires.
 *
 * Designed to be called from both the FCM/expedited-WakeWorker path and the
 * foreground 5-minute polling timer so both share identical behaviour.
 *
 * The caller is responsible for ensuring the Reticulum stack is up
 * ([StackRuntime.acquire]).
 *
 * Returns `true` if the request reached PR_COMPLETE, `false` otherwise.
 */
object PropagationSync {
    private const val TAG = "PropagationSync"
    private const val DEADLINE_MS = 20_000L

    suspend fun runOnce(context: Context): Boolean {
        val identityHandle = StackRuntime.identityHandle
        val routerHandle = StackRuntime.routerHandle
        if (identityHandle == 0L || routerHandle == 0L) {
            Log.d(TAG, "stack not ready; skipping")
            return false
        }

        val nodeManager = PropagationNodeManager(
            userConfiguredHash = resolvePropagationOverride(context)
        )
        val nodeHash = nodeManager.primaryNode

        if (!RetichatBridge.routerSetPropagationNode(routerHandle, nodeHash)) {
            Log.w(TAG, "setPropagationNode failed: ${RetichatBridge.lastError()}")
            return false
        }
        if (!RetichatBridge.routerRequestMessages(routerHandle, identityHandle)) {
            Log.w(TAG, "requestMessages failed: ${RetichatBridge.lastError()}")
            return false
        }

        val deadline = System.currentTimeMillis() + DEADLINE_MS
        var lastState = -1
        while (System.currentTimeMillis() < deadline) {
            val state = RetichatBridge.routerGetPropagationState(routerHandle)
            if (state != lastState) {
                Log.d(TAG, "propagation state=0x${"%02X".format(state)}")
                lastState = state
            }
            when {
                state == RetichatBridge.PropagationState.PR_COMPLETE -> return true
                state == RetichatBridge.PropagationState.PR_IDLE && lastState > 0 -> return false
                state >= 0xF0 -> return false
            }
            delay(500)
        }
        runCatching { RetichatBridge.routerCancelPropagation(routerHandle) }
        return false
    }

    /**
     * Resolve the user-configured propagation node hash. RFed-derived
     * `rfedLxmfPropOverride` takes precedence over the manually-entered
     * `lxmfPropagationHash`; if neither is set we derive `lxmf.propagation`
     * directly from the configured RFed node identity (mirrors the RFed
     * config blob's `destinations.lxmf.propagation` value).
     * Returns `null` only if no RFed identity is configured either.
     */
    fun resolvePropagationOverride(context: Context): String? {
        val rfedOverride = UserPreferences.getRfedLxmfPropOverride(context)
        if (rfedOverride.isNotEmpty()) return rfedOverride
        val manual = UserPreferences.getLxmfPropagationHash(context)
        if (manual.isNotEmpty()) return manual
        val rfedId = UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        if (rfedId.length == 32) {
            FcmTokenRegistrar.rnsDestHash(rfedId, "lxmf", listOf("propagation"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }
}
