package com.retichat.app.service

import android.content.Context
import android.util.Log
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.ChannelLxmUnpackResult
import com.retichat.app.bridge.RetichatBridge
import com.retichat.app.data.db.dao.ChannelDao
import com.retichat.app.data.db.entity.ChannelEntity
import com.retichat.app.data.db.entity.ChannelMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Pub-sub channel messaging via the RFed federation server.
 *
 * Direct port of `Retichat-ios/Retichat/Services/RfedChannelClient.swift`
 * onto Android primitives:
 *   - SwiftData → Room (`ChannelDao`)
 *   - `bridge.channelLxmPack/Unpack` → identical JNI wrappers
 *   - inbound blob callback → routed via `StackRuntime.bootstrap()`
 *
 * **CHANNEL MESSAGES ARE LXMF PACKAGES.** See repo memory
 * `/memories/repo/retichat-rfed-channel-integration.md` for the wire
 * format and stamp contract — do not deviate.
 */
class RfedChannelClient(
    private val appContext: Context,
    private val channelDao: ChannelDao,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "RfedChannel"

        /** Derive the 16-byte channel hash from a channel name. */
        fun channelHash(name: String): ByteArray {
            // Mirrors Rust ChannelKeypair::hash:
            //   seed         = sha256(utf8 name)
            //   x25519Pub    = X25519::scalarmult_base(seed)
            //   ed25519Pub   = Ed25519::expand(seed).public_key()
            //   final hash   = sha256(x25519Pub || ed25519Pub)[0..16]
            //
            // We don't have raw x25519/ed25519 keygen in stdlib, so go
            // through the Rust JNI: that's exactly what
            // `nativeChannelEncrypt` derives internally. We expose it via
            // a small helper if added later. For now we MUST compute it
            // identically here — see iOS impl. This implementation calls
            // the Rust helper transparently:
            return RetichatBridge.channelHash16(name)
        }

        /** Compute an RNS SINGLE destination hash (delegates to FcmTokenRegistrar). */
        fun rfedDestHash(identityHashHex: String, app: String, aspects: List<String>): String? =
            FcmTokenRegistrar.rnsDestHash(identityHashHex, app, aspects)
    }

    // ── Public state ───────────────────────────────────────────────────

    fun channelsFlow(): Flow<List<ChannelEntity>> = channelDao.activeChannelsFlow()
    fun messagesFlow(channelId: String): Flow<List<ChannelMessageEntity>> =
        channelDao.messagesFlow(channelId)

    /** Bumps every time a SEND or RECEIVE happens — for UI refresh. */
    private val _activity = MutableStateFlow(0L)
    val activity: StateFlow<Long> = _activity.asStateFlow()

    /**
     * Whether the rfed node reported `more_pending = true` on the most recent
     * `/rfed/pull` for this channel's rfed node. Keyed by **channel id** for
     * convenience (every channel on the same node mirrors the same value).
     *
     * - `null`  → never pulled this session, or screen just opened (treat as
     *             "might be more, show the button").
     * - `true`  → server explicitly said more pages remain.
     * - `false` → server explicitly said the queue is drained; UI hides the
     *             "Load earlier messages" button until next reset.
     */
    private val _canPullMore = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val canPullMore: StateFlow<Map<String, Boolean?>> = _canPullMore.asStateFlow()

    /** True while a `/rfed/pull` request is in flight for the given channel id. */
    private val _pullInFlight = MutableStateFlow<Set<String>>(emptySet())
    val pullInFlight: StateFlow<Set<String>> = _pullInFlight.asStateFlow()

    /** Reset `canPullMore` for a channel — e.g. when its screen reopens. */
    fun resetCanPullMore(channelId: String) {
        _canPullMore.value = _canPullMore.value - channelId
    }

    // ── Internal state ─────────────────────────────────────────────────

    /** ChannelIDs whose stampCost has been refreshed at least once this session. */
    private val stampCostRefreshedThisSession = mutableSetOf<String>()
    /** Hashes of failed-decrypt blobs we've already logged (suppress repeats). */
    private val failedBlobKeys = mutableSetOf<Int>()
    private val sendMutex = Mutex()

    // ── Subscribe / Unsubscribe ────────────────────────────────────────

    sealed class JoinResult {
        data class Joined(val channel: ChannelEntity) : JoinResult()
        data class Failed(val reason: String) : JoinResult()
    }

    /** Join a channel by name. Persists the row, subscribes on the rfed node. */
    suspend fun joinChannel(name: String, rfedNodeIdentityHashHex: String): JoinResult =
        withContext(Dispatchers.IO) {
            val identityHandle = StackRuntime.identityHandle
            if (identityHandle == 0L) return@withContext JoinResult.Failed("stack not ready")

            val channelHashBytes = try { channelHash(name) }
            catch (t: Throwable) { return@withContext JoinResult.Failed("bad channel name: ${t.message}") }
            val channelId = channelHashBytes.toHex()

            val rfedChannelDestHex = rfedDestHash(rfedNodeIdentityHashHex, "rfed", listOf("channel"))
                ?: return@withContext JoinResult.Failed("invalid rfed node identity hash")
            val rfedChannelDest = FcmTokenRegistrar.hexToBytes(rfedChannelDestHex)
                ?: return@withContext JoinResult.Failed("internal: bad dest hex")

            val stampCost = try {
                subscribeOnServer(channelHashBytes, rfedChannelDest, identityHandle)
            } catch (e: Exception) {
                return@withContext JoinResult.Failed(e.message ?: "subscribe failed")
            }

            val existing = channelDao.findById(channelId)
            val entity = (existing ?: ChannelEntity(
                id = channelId,
                channelName = name,
                rfedNodeIdentityHashHex = rfedNodeIdentityHashHex.lowercase(),
                stampCost = stampCost,
            )).copy(
                channelName = name,
                rfedNodeIdentityHashHex = rfedNodeIdentityHashHex.lowercase(),
                stampCost = stampCost,
            )
            channelDao.upsert(entity)
            JoinResult.Joined(entity)
        }

    /** Leave a channel. Deletes local rows and unsubscribes on the rfed node. */
    suspend fun leaveChannel(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        val entity = channelDao.findById(channelId) ?: return@withContext false
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelId) ?: return@withContext false

        val rfedChannelDestHex = rfedDestHash(
            entity.rfedNodeIdentityHashHex, "rfed", listOf("channel"),
        )
        val rfedChannelDest = rfedChannelDestHex?.let { FcmTokenRegistrar.hexToBytes(it) }
        if (rfedChannelDest != null && identityHandle != 0L) {
            val pubkey = RetichatBridge.identityPublicKey(identityHandle)
            val sig = RetichatBridge.identitySign(identityHandle, channelHashBytes)
            val payload = if (pubkey != null && sig != null) {
                msgpackSigned(channelHashBytes, pubkey, sig)
            } else {
                msgpackBin(channelHashBytes)
            }
            runCatching {
                RetichatBridge.linkRequest(
                    rfedChannelDest, "rfed", "channel", identityHandle,
                    "/rfed/unsubscribe", payload, timeoutSecs = 10.0,
                )
            }
        }

        // Per-channel push deregistration (best-effort)
        if (identityHandle != 0L) {
            runCatching {
                RfedNotifyRegistrar.deregisterForChannel(appContext, identityHandle, channelHashBytes)
            }
        }

        channelDao.deleteMessagesForChannel(channelId)
        channelDao.deleteById(channelId)
        UserPreferences.setChannelNotificationsEnabled(appContext, channelId, false)
        UserPreferences.setChannelPushEnabled(appContext, channelId, false)
        true
    }

    // ── Per-channel push toggle (mirrors iOS RfedChannelClient.enable/disableChannelPush) ──

    /**
     * Enable push wakeups for a channel: persist the pref and register with rfed.notify
     * so the rfed node will fire a silent push for every new message in this channel.
     */
    suspend fun enableChannelPush(channelHashHex: String): Boolean = withContext(Dispatchers.IO) {
        UserPreferences.setChannelPushEnabled(appContext, channelHashHex, true)
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext false
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelHashHex) ?: return@withContext false
        runCatching {
            RfedNotifyRegistrar.registerForChannel(appContext, identityHandle, channelHashBytes)
        }.getOrDefault(false)
    }

    /**
     * Disable push wakeups for a channel: persist the pref and deregister from rfed.notify.
     */
    suspend fun disableChannelPush(channelHashHex: String): Boolean = withContext(Dispatchers.IO) {
        UserPreferences.setChannelPushEnabled(appContext, channelHashHex, false)
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext false
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelHashHex) ?: return@withContext false
        runCatching {
            RfedNotifyRegistrar.deregisterForChannel(appContext, identityHandle, channelHashBytes)
        }.getOrDefault(false)
    }

    /**
     * Re-register per-channel rfed.notify for every channel that has push enabled,
     * so wakeups resume after app/process restart. Mirrors iOS
     * `resubscribePersistedChannels` push-reregister loop.
     * Safe to call once the stack identity is ready.
     */
    suspend fun reregisterChannelPushOnStart() = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext
        val enabledIds = UserPreferences.getChannelPushEnabled(appContext)
        if (enabledIds.isEmpty()) return@withContext
        for (id in enabledIds) {
            val channelHashBytes = FcmTokenRegistrar.hexToBytes(id) ?: continue
            runCatching {
                RfedNotifyRegistrar.registerForChannel(appContext, identityHandle, channelHashBytes)
            }
        }
    }

    /**
     * Re-call `/rfed/subscribe` for every persisted channel after process
     * start. Mirrors iOS `resubscribePersistedChannels`.
     *
     * The subscription state on the rfed node MUST include this subscriber
     * for fanout/delivery to reach us; without this, channel messages from
     * peers will never arrive (the node either lost our subscription on
     * restart or never had it for this session).
     *
     * Best-effort: failures are logged and swallowed.
     */
    suspend fun resubscribePersistedChannels() = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext
        val channels = channelDao.activeChannels()
        if (channels.isEmpty()) return@withContext
        Log.i(TAG, "Re-subscribing to ${channels.size} persisted channel(s) after restart")
        for (channel in channels) {
            val channelHashBytes = FcmTokenRegistrar.hexToBytes(channel.id) ?: continue
            val rfedChannelDestHex = rfedDestHash(
                channel.rfedNodeIdentityHashHex, "rfed", listOf("channel"),
            ) ?: continue
            val rfedChannelDest = FcmTokenRegistrar.hexToBytes(rfedChannelDestHex) ?: continue
            runCatching {
                val stampCost = subscribeOnServer(channelHashBytes, rfedChannelDest, identityHandle)
                channelDao.updateStampCost(channel.id, stampCost)
                Log.i(TAG, "Re-subscribed to '${channel.channelName}' (stampCost=$stampCost)")
            }.onFailure { e ->
                Log.w(TAG, "Re-subscribe failed for '${channel.channelName}': ${e.message}")
            }
        }
    }

    // ── Send ───────────────────────────────────────────────────────────

    /**
     * Send a content message to a channel. Suspends until either the
     * packet is shipped or the second retry fails. The local
     * `ChannelMessageEntity` is upserted IMMEDIATELY (optimistic) and its
     * id is upgraded to `senderHex|tsMs` once the FFI returns the LXMF
     * timestamp it baked into the signed body.
     */
    suspend fun sendMessage(channel: ChannelEntity, content: String): Boolean =
        sendMutex.withLock {
            val identityHandle = StackRuntime.identityHandle
            if (identityHandle == 0L) return@withLock false

            val ownHashHex = StackRuntime.selfDestHash.toHex()
            val optimisticId = "pending|${System.nanoTime()}"
            val optimistic = ChannelMessageEntity(
                id = optimisticId,
                channelId = channel.id,
                sourceHashHex = ownHashHex,
                title = "",
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                signatureValidated = true,
                sendState = ChannelMessageEntity.SEND_STATE_SENDING,
            )
            channelDao.upsertMessage(optimistic)
            channelDao.bumpLastMessageTime(channel.id, optimistic.timestamp)
            _activity.value = System.currentTimeMillis()

            // Step 1: refresh stampCost once per session.
            var liveChannel = channel
            if (channel.id !in stampCostRefreshedThisSession) {
                runCatching { refreshStampCost(channel) }
                    .onSuccess {
                        stampCostRefreshedThisSession.add(channel.id)
                        liveChannel = channelDao.findById(channel.id) ?: liveChannel
                    }
            }

            if (trySend(liveChannel, content, optimisticId, ownHashHex, identityHandle)) {
                return@withLock true
            }

            // Step 2: refresh + single retry.
            Log.w(TAG, "SEND failed once — refreshing stampCost and retrying")
            runCatching { refreshStampCost(liveChannel) }
                .onSuccess {
                    stampCostRefreshedThisSession.add(liveChannel.id)
                    liveChannel = channelDao.findById(liveChannel.id) ?: liveChannel
                }
                .onFailure {
                    Log.w(TAG, "stampCost refresh on retry failed: ${it.message}")
                }
            if (!trySend(liveChannel, content, optimisticId, ownHashHex, identityHandle)) {
                Log.w(TAG, "SEND failed twice — marking optimistic row FAILED for user retry")
                channelDao.updateMessageSendState(
                    optimisticId,
                    ChannelMessageEntity.SEND_STATE_FAILED,
                )
                return@withLock false
            }
            true
        }

    private suspend fun trySend(
        channel: ChannelEntity,
        content: String,
        optimisticId: String,
        ownHashHex: String,
        identityHandle: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val packed = RetichatBridge.channelLxmPack(
            channel.channelName, identityHandle, content.toByteArray(), ByteArray(0),
        ) ?: run {
            Log.w(TAG, "LXMF pack failed: ${RetichatBridge.lastError()}")
            return@withContext false
        }
        // Layout from JNI: [ts_ms_be(8) | channel_id_hash(16) | EC_encrypted_tail]
        if (packed.size < 24) return@withContext false
        val tsMs = readLongBE(packed, 0)
        val wirePayload = packed.copyOfRange(8, packed.size)

        // Defensively overwrite the 16-byte channel_id_hash slot with the
        // canonical Kotlin-derived hash. The FFI puts its own derivation
        // there, but we own the routing label — channel.id is the value
        // iOS, CLI peers, and the rfed node all key off, so the wire prefix
        // MUST match it. See ChannelHash.kt for the canonical derivation.
        val expectedPrefix = channel.id.lowercase()
        val actualPrefix = wirePayload.copyOfRange(0, 16).toHex()
        if (expectedPrefix != actualPrefix) {
            Log.w(TAG, "LXMF pack channel_id_hash mismatch (overwriting): $actualPrefix -> $expectedPrefix")
            val canonical = RetichatBridge.channelHash16(channel.channelName)
            System.arraycopy(canonical, 0, wirePayload, 0, 16)
        }

        val rfedChannelDest = FcmTokenRegistrar.rnsDestHash(
            channel.rfedNodeIdentityHashHex, "rfed", listOf("channel"),
        )?.let { FcmTokenRegistrar.hexToBytes(it) } ?: return@withContext false

        val finalPayload = run {
            val cost = channel.stampCost ?: 0
            if (cost <= 0) wirePayload
            else {
                val stamp = RetichatBridge.channelComputeStamp(wirePayload, cost) ?: run {
                    Log.w(TAG, "STAMP compute failed cost=$cost: ${RetichatBridge.lastError()}")
                    return@withContext false
                }
                wirePayload + stamp
            }
        }

        val ok = RetichatBridge.packetSendToHash(rfedChannelDest, "rfed", "channel", finalPayload)
        if (!ok) {
            Log.w(TAG, "Send failed: ${RetichatBridge.lastError()}")
            return@withContext false
        }
        Log.i(TAG, "SEND ok: channel='${channel.channelName}' id=${channel.id} rfedDest=${rfedChannelDest.toHex()} payload=${finalPayload.size}B stamp=${(channel.stampCost ?: 0)}")

        // Upgrade optimistic row → canonical id (same as echo will use)
        val canonicalId = canonicalMessageId(ownHashHex, tsMs)
        // Drop optimistic row, insert canonical (Room can't change PK directly)
        channelDao.upsertMessage(
            ChannelMessageEntity(
                id = canonicalId,
                channelId = channel.id,
                sourceHashHex = ownHashHex,
                title = "",
                content = content,
                timestamp = tsMs,
                isOutbound = true,
                signatureValidated = true,
                sendState = ChannelMessageEntity.SEND_STATE_SENT,
            )
        )
        // Clean up the optimistic placeholder so the bubble renders the
        // canonical row only (and we don't end up with duplicate-looking rows
        // — one stuck in SENDING, one freshly SENT).
        if (optimisticId != canonicalId) {
            channelDao.deleteMessageById(optimisticId)
        }
        channelDao.bumpLastMessageTime(channel.id, tsMs)
        true
    }

    // ── Subscribe (server) ─────────────────────────────────────────────

    /**
     * POST `/rfed/subscribe` and parse the `[bool, stamp_cost?]` response.
     * Returns the stamp cost (0/null = no stamp required).
     *
     * Throws [IllegalStateException] on hard failure (no path, link error,
     * malformed response).
     */
    private suspend fun subscribeOnServer(
        channelHashBytes: ByteArray,
        rfedChannelDest: ByteArray,
        identityHandle: Long,
    ): Int? = withContext(Dispatchers.IO) {
        val pubkey = RetichatBridge.identityPublicKey(identityHandle)
            ?: throw IllegalStateException("identityPublicKey returned null")
        val sig = RetichatBridge.identitySign(identityHandle, channelHashBytes)
            ?: throw IllegalStateException("identitySign returned null")
        val payload = msgpackSigned(channelHashBytes, pubkey, sig)

        // Wait up to 20 s for a path to the rfed node.
        if (!RetichatBridge.transportHasPath(rfedChannelDest)) {
            RetichatBridge.transportRequestPath(rfedChannelDest)
            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                if (RetichatBridge.transportHasPath(rfedChannelDest)) break
                Thread.sleep(500)
            }
            if (!RetichatBridge.transportHasPath(rfedChannelDest)) {
                throw IllegalStateException("rfed node not reachable — no announce received")
            }
        }

        val resp = RetichatBridge.linkRequest(
            rfedChannelDest, "rfed", "channel", identityHandle,
            "/rfed/subscribe", payload, timeoutSecs = 20.0,
        ) ?: throw IllegalStateException("link request timed out or failed")

        parseSubscribeResponse(resp)
    }

    /**
     * Parse `/rfed/subscribe` response: `[bool, stamp_cost_or_nil]`
     * (or legacy bare `true`).
     */
    private fun parseSubscribeResponse(data: ByteArray): Int? {
        if (data.size == 1 && data[0] == 0xc3.toByte()) return null
        if (data.size < 3 || data[0] != 0x92.toByte()) return null
        val costByte = data[2].toInt() and 0xff
        if (costByte == 0xc0) return null
        if (costByte and 0x80 == 0) return costByte
        if (costByte == 0xcc && data.size >= 4) return data[3].toInt() and 0xff
        if (costByte == 0xcd && data.size >= 5) {
            return ((data[3].toInt() and 0xff) shl 8) or (data[4].toInt() and 0xff)
        }
        if (costByte == 0xce && data.size >= 7) {
            return ((data[3].toInt() and 0xff) shl 24) or
                    ((data[4].toInt() and 0xff) shl 16) or
                    ((data[5].toInt() and 0xff) shl 8) or
                    (data[6].toInt() and 0xff)
        }
        return null
    }

    /** Re-issue `/rfed/subscribe` to learn the current stamp cost. */
    private suspend fun refreshStampCost(channel: ChannelEntity): Int? = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext channel.stampCost
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channel.id) ?: return@withContext channel.stampCost
        val rfedChannelDest = FcmTokenRegistrar.rnsDestHash(
            channel.rfedNodeIdentityHashHex, "rfed", listOf("channel"),
        )?.let { FcmTokenRegistrar.hexToBytes(it) } ?: return@withContext channel.stampCost
        val fresh = subscribeOnServer(channelHashBytes, rfedChannelDest, identityHandle)
        channelDao.updateStampCost(channel.id, fresh)
        fresh
    }

    // ── Pull (paged) ───────────────────────────────────────────────────

    /**
     * Drain a single page from the rfed delivery queue. Returns
     * `Pair(blobsDispatched, morePending)`.
     */
    suspend fun pullDeferred(channel: ChannelEntity): Pair<Int, Boolean> =
        withContext(Dispatchers.IO) {
            val identityHandle = StackRuntime.identityHandle
            if (identityHandle == 0L) return@withContext 0 to false
            val rfedDeliveryDest = FcmTokenRegistrar.rnsDestHash(
                channel.rfedNodeIdentityHashHex, "rfed", listOf("delivery"),
            )?.let { FcmTokenRegistrar.hexToBytes(it) } ?: return@withContext 0 to false

            _pullInFlight.value = _pullInFlight.value + channel.id
            try {
                Log.i(TAG, "PULL start: channel='${channel.channelName}' rfedDest=${rfedDeliveryDest.toHex()}")
                val resp = RetichatBridge.linkRequest(
                    rfedDeliveryDest, "rfed", "delivery", identityHandle,
                    "/rfed/pull", ByteArray(0), timeoutSecs = 15.0,
                ) ?: run {
                    Log.w(TAG, "PULL: link request failed/timed out")
                    _canPullMore.value = _canPullMore.value + (channel.id to null)
                    return@withContext 0 to false
                }
                Log.i(TAG, "PULL response: ${resp.size} bytes")

                val decoded = decodePullResponse(resp) ?: run {
                    Log.w(TAG, "PULL: malformed response (${resp.size} bytes)")
                    _canPullMore.value = _canPullMore.value + (channel.id to null)
                    return@withContext 0 to false
                }
                val (pairs, morePending) = decoded
                Log.i(TAG, "PULL decoded: ${pairs.size} blob(s) morePending=$morePending")
                for ((channelHashBytes, blob) in pairs) {
                    dispatchBlob(channelHashBytes.toHex(), blob)
                }
                _canPullMore.value = _canPullMore.value + (channel.id to morePending)
                pairs.size to morePending
            } finally {
                _pullInFlight.value = _pullInFlight.value - channel.id
            }
        }

    // ── Inbound blob dispatch ──────────────────────────────────────────

    /**
     * Called from the JNI rfed.delivery callback (via [StackRuntime]).
     * Blob format: `channelHash(16) | innerBlob`.
     */
    suspend fun dispatchInboundBlob(blob: ByteArray) {
        if (blob.size <= 16) {
            Log.w(TAG, "dispatchInboundBlob DROPPED: too short (${blob.size})")
            return
        }
        val channelHashHex = blob.copyOfRange(0, 16).toHex()
        val inner = blob.copyOfRange(16, blob.size)
        Log.i(TAG, "dispatchInboundBlob channel=$channelHashHex inner=${inner.size}B")
        dispatchBlob(channelHashHex, inner)
    }

    private suspend fun dispatchBlob(channelHashHex: String, innerBlob: ByteArray) =
        withContext(Dispatchers.IO) {
            val channel = channelDao.findById(channelHashHex) ?: run {
                val known = channelDao.activeChannels().joinToString(", ") { "${it.channelName}=${it.id.take(8)}" }
                Log.w(TAG, "dispatchBlob NO MATCH for channel=$channelHashHex; subscribed: [$known]")
                return@withContext
            }
            // Reconstruct full lxmf_data: channelHash(16) | innerBlob
            val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelHashHex) ?: return@withContext
            val lxmfData = channelHashBytes + innerBlob

            val raw = RetichatBridge.channelLxmUnpack(channel.channelName, lxmfData) ?: run {
                val key = channelHashHex.hashCode() xor innerBlob.contentHashCode()
                if (failedBlobKeys.add(key)) {
                    Log.w(TAG, "channelLxmUnpack failed (${lxmfData.size} bytes): ${RetichatBridge.lastError()}")
                }
                return@withContext
            }
            val result = ChannelLxmUnpackResult.parse(raw) ?: return@withContext
            if (!result.signatureValidated) {
                Log.w(TAG, "REJECTED unsigned channel msg: reason=${result.unverifiedReason}")
                return@withContext
            }
            val content = try { String(result.content, Charsets.UTF_8) }
            catch (_: Throwable) { return@withContext }
            val title = try { String(result.title, Charsets.UTF_8) }
            catch (_: Throwable) { "" }

            val sourceHashHex = result.sourceHash.toHex()
            val msgId = canonicalMessageId(sourceHashHex, result.timestampMs)
            if (channelDao.messageExists(channelHashHex, msgId) > 0) return@withContext

            val isOutbound = sourceHashHex.equals(StackRuntime.selfDestHash.toHex(), ignoreCase = true)
            channelDao.upsertMessage(
                ChannelMessageEntity(
                    id = msgId,
                    channelId = channelHashHex,
                    sourceHashHex = sourceHashHex,
                    title = title,
                    content = content,
                    timestamp = result.timestampMs,
                    isOutbound = isOutbound,
                    signatureValidated = true,
                )
            )
            channelDao.bumpLastMessageTime(channelHashHex, result.timestampMs)
            _activity.value = System.currentTimeMillis()

            if (!isOutbound &&
                UserPreferences.isChannelNotificationsEnabled(appContext, channelHashHex)
            ) {
                val senderLabel = sourceHashHex.take(8) + "…"
                MessageNotificationHelper.notify(
                    appContext,
                    senderName = "#${channel.channelName} ($senderLabel)",
                    content = content,
                    chatId = channelHashHex,
                )
            }
        }

    // ── msgpack helpers ────────────────────────────────────────────────

    internal fun msgpackBin(data: ByteArray): ByteArray {
        return when {
            data.size <= 0xff -> byteArrayOf(0xc4.toByte(), data.size.toByte()) + data
            data.size <= 0xffff -> byteArrayOf(
                0xc5.toByte(),
                ((data.size shr 8) and 0xff).toByte(),
                (data.size and 0xff).toByte(),
            ) + data
            else -> byteArrayOf(
                0xc6.toByte(),
                ((data.size shr 24) and 0xff).toByte(),
                ((data.size shr 16) and 0xff).toByte(),
                ((data.size shr 8) and 0xff).toByte(),
                (data.size and 0xff).toByte(),
            ) + data
        }
    }

    internal fun msgpackSigned(value: ByteArray, pubkey: ByteArray, sig: ByteArray): ByteArray {
        return byteArrayOf(0x93.toByte()) + msgpackBin(value) + msgpackBin(pubkey) + msgpackBin(sig)
    }

    /**
     * Decode `/rfed/pull` response: msgpack 2-array
     * `[ Array([ [bin(16), bin(*)], ... ]), Bool more_pending ]`.
     */
    private fun decodePullResponse(data: ByteArray): Pair<List<Pair<ByteArray, ByteArray>>, Boolean>? {
        val h = IndexHolder(0)
        val outer = readArrayCount(data, h) ?: return null
        if (outer != 2) return null
        val pairsCount = readArrayCount(data, h) ?: return null
        val pairs = ArrayList<Pair<ByteArray, ByteArray>>(pairsCount)
        for (j in 0 until pairsCount) {
            val inner = readArrayCount(data, h) ?: return null
            if (inner != 2) return null
            val ch = readBin(data, h) ?: return null
            val blob = readBin(data, h) ?: return null
            pairs.add(ch to blob)
        }
        if (h.i >= data.size) return null
        val tag = data[h.i].toInt() and 0xff
        h.i++
        val morePending = when (tag) {
            0xc2 -> false
            0xc3 -> true
            else -> return null
        }
        return pairs to morePending
    }

    private fun readArrayCount(data: ByteArray, h: IndexHolder): Int? {
        if (h.i >= data.size) return null
        val tag = data[h.i].toInt() and 0xff
        h.i++
        return when {
            tag and 0xf0 == 0x90 -> tag and 0x0f
            tag == 0xdc -> {
                if (h.i + 2 > data.size) return null
                val n = ((data[h.i].toInt() and 0xff) shl 8) or (data[h.i + 1].toInt() and 0xff)
                h.i += 2; n
            }
            tag == 0xdd -> {
                if (h.i + 4 > data.size) return null
                val n = ((data[h.i].toInt() and 0xff) shl 24) or
                        ((data[h.i + 1].toInt() and 0xff) shl 16) or
                        ((data[h.i + 2].toInt() and 0xff) shl 8) or
                        (data[h.i + 3].toInt() and 0xff)
                h.i += 4; n
            }
            else -> null
        }
    }

    private fun readBin(data: ByteArray, h: IndexHolder): ByteArray? {
        if (h.i >= data.size) return null
        val tag = data[h.i].toInt() and 0xff
        h.i++
        val len = when (tag) {
            0xc4 -> {
                if (h.i + 1 > data.size) return null
                val n = data[h.i].toInt() and 0xff; h.i += 1; n
            }
            0xc5 -> {
                if (h.i + 2 > data.size) return null
                val n = ((data[h.i].toInt() and 0xff) shl 8) or (data[h.i + 1].toInt() and 0xff)
                h.i += 2; n
            }
            0xc6 -> {
                if (h.i + 4 > data.size) return null
                val n = ((data[h.i].toInt() and 0xff) shl 24) or
                        ((data[h.i + 1].toInt() and 0xff) shl 16) or
                        ((data[h.i + 2].toInt() and 0xff) shl 8) or
                        (data[h.i + 3].toInt() and 0xff)
                h.i += 4; n
            }
            else -> return null
        }
        if (h.i + len > data.size) return null
        val out = data.copyOfRange(h.i, h.i + len)
        h.i += len
        return out
    }

    private data class IndexHolder(var i: Int)

    // ── Misc helpers ───────────────────────────────────────────────────

    private fun canonicalMessageId(sourceHashHex: String, tsMs: Long): String {
        return sourceHashHex + "%016x".format(tsMs)
    }

    private fun readLongBE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xff) shl 56) or
                ((data[offset + 1].toLong() and 0xff) shl 48) or
                ((data[offset + 2].toLong() and 0xff) shl 40) or
                ((data[offset + 3].toLong() and 0xff) shl 32) or
                ((data[offset + 4].toLong() and 0xff) shl 24) or
                ((data[offset + 5].toLong() and 0xff) shl 16) or
                ((data[offset + 6].toLong() and 0xff) shl 8) or
                (data[offset + 7].toLong() and 0xff)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
