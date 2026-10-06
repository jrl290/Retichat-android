package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AppLinkPacketCallback
import com.newendian.retichat.bridge.ChannelLxmUnpackResult
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.dao.ChannelDao
import com.newendian.retichat.data.db.dao.ContactDao
import com.newendian.retichat.data.db.entity.ChannelMembershipEntity
import com.newendian.retichat.data.db.entity.ChannelNameStateEntity
import com.newendian.retichat.data.db.entity.ChannelSenderEntity
import com.newendian.retichat.data.db.entity.names
import com.newendian.retichat.names.ContactNames
import com.newendian.retichat.names.DisplayNames
import com.newendian.retichat.names.NameField
import com.newendian.retichat.names.NameUpdate
import com.newendian.retichat.ui.channels.ChannelNameForm
import com.newendian.retichat.data.db.entity.ChannelEntity
import com.newendian.retichat.data.db.entity.ChannelMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    private val contactDao: ContactDao,
    private val scope: CoroutineScope,
) {

    init {
        scope.launch {
            val recovered = channelDao.failStaleOutboundSendingMessages()
            if (recovered > 0) {
                Log.w(TAG, "Recovered $recovered stale outbound channel message(s) stuck in SENDING")
            }
        }
    }

    companion object {
        private const val TAG = "RfedChannel"

        /**
         * Derive the 16-byte channel hash from a channel name: the channel
         * IDENTITY hash (RFed-rust SPEC.md §1), the wire prefix of every
         * post and RFed's key for subscribe, pull, fan-out and notify. Not
         * the channel's lxmf.delivery destination hash.
         */
        fun channelHash(name: String): ByteArray {
            // Mirrors Rust ChannelKeypair::hash / lxmf_rust::channel::channel_id_hash:
            //   seed         = sha256(utf8 name)
            //   x25519Pub    = X25519::scalarmult_base(seed)
            //   ed25519Pub   = Ed25519::expand(seed).public_key()
            //   final hash   = sha256(x25519Pub || ed25519Pub)[0..16]
            // Computed in Kotlin (crypto/ChannelHash.compute, BouncyCastle),
            // not through JNI.
            return RetichatBridge.channelHash16(name)
        }

        /** Compute an RNS SINGLE destination hash (delegates to FcmTokenRegistrar). */
        fun rfedDestHash(identityHashHex: String, app: String, aspects: String): String? =
            FcmTokenRegistrar.rnsDestHash(identityHashHex, app, aspects)

        /** Compute an RNS SINGLE destination hash (delegates to FcmTokenRegistrar). */
        fun rfedDestHash(identityHashHex: String, app: String, aspects: List<String>): String? =
            FcmTokenRegistrar.rnsDestHash(identityHashHex, app, aspects)

        /**
         * §5.3: a channel post's notification names its poster as the bubble
         * does, the main label then the secondary text ("Mum · Night Owl",
         * "Night Owl · 1a2b3c4d…"), so a channel name never stands alone.
         * Same format as iOS DisplayNames.channelNotificationTitle.
         */
        internal fun notificationSenderName(channelName: String?, names: ContactNames?, sourceHashHex: String): String {
            val label = DisplayNames.channelPost(channelName, names, sourceHashHex)
            return label.secondary?.let { "${label.name} · $it" } ?: label.name
        }

        internal fun pullStateKey(channelId: String): String =
            channelId.lowercase()

        internal fun pullStateKey(channel: ChannelEntity): String =
            pullStateKey(channel.id)

        internal fun channelsForWakePull(channels: List<ChannelEntity>): List<ChannelEntity> =
            channels.distinctBy { pullStateKey(it) }

        internal fun nextRfedLinkGeneration(
            currentGeneration: Int,
            previousStatus: Int,
            nextStatus: Int,
        ): Int = if (
            nextStatus == RetichatBridge.AppLinkStatus.ACTIVE &&
            previousStatus != RetichatBridge.AppLinkStatus.ACTIVE
        ) {
            currentGeneration + 1
        } else {
            currentGeneration
        }

        internal fun liveStreamFilterIds(
            channels: List<ChannelEntity>,
            nodeIdentityHashHex: String,
            openedChannelIds: Set<String>,
        ): List<String> {
            val normalizedNode = nodeIdentityHashHex.lowercase()
            val normalizedOpened = openedChannelIds.mapTo(mutableSetOf()) { it.lowercase() }
            return channels
                .asSequence()
                .filter { it.rfedNodeIdentityHashHex.equals(normalizedNode, ignoreCase = true) }
                .map { it.id.lowercase() }
                .filter { normalizedOpened.contains(it) }
                .toList()
        }
    }

    // ── Public state ───────────────────────────────────────────────────

    fun channelsFlow(): Flow<List<ChannelEntity>> = channelDao.activeChannelsFlow()
    fun messagesFlow(channelId: String): Flow<List<ChannelMessageEntity>> =
        channelDao.messagesFlow(channelId)

    /** The posters seen in [channelId], with their channel names (DISPLAY_NAMES.md §5.1). */
    fun sendersFlow(channelId: String): Flow<List<ChannelSenderEntity>> =
        channelDao.sendersFlow(channelId)

    /** Bumps every time a SEND or RECEIVE happens — for UI refresh. */
    private val _activity = MutableStateFlow(0L)
    val activity: StateFlow<Long> = _activity.asStateFlow()

    /** Bumps once per fresh rfed.node transition into ACTIVE. */
    private val _rfedLinkGeneration = MutableStateFlow(0)
    val rfedLinkGeneration: StateFlow<Int> = _rfedLinkGeneration.asStateFlow()

    /**
     * Whether the addressed channel reported `more_pending = true` on the most
     * recent `/rfed/pull`. Keyed by **channel id** because `rfed.channel.pull`
     * is channel-scoped.
     *
     * - `null`  → never pulled this session, or screen just opened (treat as
     *             "might be more, show the button").
     * - `true`  → server explicitly said more pages remain.
     * - `false` → server explicitly said the queue is drained; UI hides the
     *             "Load earlier messages" button until next reset.
     */
    private val _canPullMore = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val canPullMore: StateFlow<Map<String, Boolean?>> = _canPullMore.asStateFlow()

    /** True while a `/rfed/pull` request is in flight for the given channel. */
    private val _pullInFlight = MutableStateFlow<Set<String>>(emptySet())
    val pullInFlight: StateFlow<Set<String>> = _pullInFlight.asStateFlow()

    /** Reset `canPullMore` for a channel — e.g. when its screen reopens. */
    fun resetCanPullMore(channelId: String) {
        _canPullMore.value = _canPullMore.value - pullStateKey(channelId)
    }

    /** Retain the rfed.node status monitor while a UI surface needs reconnect generation. */
    fun retainRfedLinkMonitor() {
        var shouldStart = false
        synchronized(rfedLinkMonitorLock) {
            rfedLinkMonitorRetainCount += 1
            if (rfedLinkMonitorRetainCount == 1) {
                shouldStart = true
            }
        }
        if (!shouldStart) return
        refreshRfedNodeStatus()
        rfedLinkStatusMonitorJob?.cancel()
        rfedLinkStatusMonitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(3_000)
                refreshRfedNodeStatus()
            }
        }
    }

    /** Release the rfed.node status monitor. */
    fun releaseRfedLinkMonitor() {
        var shouldStop = false
        synchronized(rfedLinkMonitorLock) {
            if (rfedLinkMonitorRetainCount == 0) return
            rfedLinkMonitorRetainCount -= 1
            if (rfedLinkMonitorRetainCount == 0) {
                shouldStop = true
            }
        }
        if (!shouldStop) return
        rfedLinkStatusMonitorJob?.cancel()
        rfedLinkStatusMonitorJob = null
    }

    // ── Internal state ─────────────────────────────────────────────────

    /** ChannelIDs whose stampCost has been refreshed at least once this session. */
    private val stampCostRefreshedThisSession = mutableSetOf<String>()
    /** Hashes of failed-decrypt blobs we've already logged (suppress repeats). */
    private val failedBlobKeys = mutableSetOf<Int>()
    /** RFed node identity hashes whose channel.stream link we have already wired. */
    private val trackedChannelStreamNodes = mutableSetOf<String>()
    /** Runtime-only channel ids whose conversation screen opened in this process. */
    private val openedChannelStreamIds = mutableSetOf<String>()
    private val channelStreamMutex = Mutex()
    private val sendMutex = Mutex()
    private val rfedLinkMonitorLock = Any()
    private var rfedLinkMonitorRetainCount = 0
    private var rfedLinkStatusMonitorJob: Job? = null
    private var lastRfedNodeLinkStatus = RetichatBridge.AppLinkStatus.NONE

    private fun legacyChannelDest(identityHashHex: String): ByteArray? =
        rfedDestHash(identityHashHex, "rfed", listOf("channel"))
            ?.let { FcmTokenRegistrar.hexToBytes(it) }

    private fun refreshRfedNodeStatus() {
        val nextStatus = ConnectionStateManager.rfedNodeLinkStatus()
        synchronized(rfedLinkMonitorLock) {
            _rfedLinkGeneration.value = nextRfedLinkGeneration(
                currentGeneration = _rfedLinkGeneration.value,
                previousStatus = lastRfedNodeLinkStatus,
                nextStatus = nextStatus,
            )
            lastRfedNodeLinkStatus = nextStatus
        }
    }

    suspend fun markChannelOpenedForStreaming(channelId: String) {
        val normalized = channelId.lowercase()
        val inserted = channelStreamMutex.withLock {
            openedChannelStreamIds.add(normalized)
        }
        if (inserted) {
            reconfigureChannelStreams()
        }
    }

    // ── Subscribe / Unsubscribe ────────────────────────────────────────

    sealed class JoinResult {
        data class Joined(val channel: ChannelEntity) : JoinResult()
        data class Failed(val reason: String) : JoinResult()
    }

    /**
     * Join a channel by name. Persists the row, subscribes on the rfed node.
     * The user's own join of a channel not in the list is recorded for RFed
     * SPEC §17.12 rule 5 and, with a distro, owed to the other devices as a
     * membership message ([recordUsersAction]); a join of one already in the
     * list is no change. Applying a sibling's join is [applyDistroChannelSync],
     * which sends nothing.
     */
    suspend fun joinChannel(name: String, rfedNodeIdentityHashHex: String): JoinResult =
        withContext(Dispatchers.IO) {
            val identityHandle = StackRuntime.identityHandle
            if (identityHandle == 0L) return@withContext JoinResult.Failed("stack not ready")

            val channelHashBytes = try { channelHash(name) }
            catch (t: Throwable) { return@withContext JoinResult.Failed("bad channel name: ${t.message}") }
            val channelId = channelHashBytes.toHex()

            val rfedChannelDest = legacyChannelDest(rfedNodeIdentityHashHex)
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
            reconfigureChannelStreams()
            if (existing == null) recordUsersAction(DistroChannelSync.OP_JOIN, entity)
            JoinResult.Joined(entity)
        }

    /**
     * Leave a channel. Deletes local rows and unsubscribes on the rfed node.
     * The user's own leave is recorded for RFed SPEC §17.12 rule 5 and, with a
     * distro, owed to the other devices ([recordUsersAction]); the list changed
     * when the user acted, whatever becomes of the message.
     */
    suspend fun leaveChannel(channelId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = channelDao.findById(channelId) ?: return@withContext false
        if (!leaveHere(entity)) return@withContext false
        recordUsersAction(DistroChannelSync.OP_LEAVE, entity)
        true
    }

    /**
     * Leave [entity] on this device, by the user's action or a sibling's (§17.12
     * rule 6): unsubscribe with the device key, remove the channel's push
     * registration, its posts, its senders and its names, and drop it from the
     * stream's filter. Sends no membership message. False, with nothing done,
     * when the channel's id is not a hash.
     */
    private suspend fun leaveHere(entity: ChannelEntity): Boolean {
        val channelId = entity.id
        val identityHandle = StackRuntime.identityHandle
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelId) ?: return false

        val rfedChannelDest = legacyChannelDest(entity.rfedNodeIdentityHashHex)
        if (rfedChannelDest != null && identityHandle != 0L) {
            val pubkey = RetichatBridge.identityPublicKey(identityHandle)
            val sig = RetichatBridge.identitySign(identityHandle, channelHashBytes)
            val payload = if (pubkey != null && sig != null) {
                msgpackSigned(channelHashBytes, pubkey, sig)
            } else {
                msgpackBin(channelHashBytes)
            }
            runCatching {
                // Single-shot via the AppLinks readiness gate plus a
                // transient authenticated request — no path-poll, no retries.
                ConnectionStateManager.appLinkSend(
                    rfedChannelDest, "rfed", "channel",
                    "/rfed/unsubscribe", payload,
                )
            }
        }

        // Per-channel push deregistration (fire-and-forget).
        if (identityHandle != 0L) {
            RfedNotifyRegistrar.deregisterForChannel(appContext, identityHandle, channelHashBytes)
        }

        channelDao.deleteMessagesForChannel(channelId)
        channelDao.deleteSendersForChannel(channelId)
        channelDao.deleteNameState(channelId)
        channelDao.deleteById(channelId)
        UserPreferences.setChannelNotificationsEnabled(appContext, channelId, false)
        UserPreferences.setChannelPushEnabled(appContext, channelId, false)
        channelStreamMutex.withLock {
            openedChannelStreamIds.remove(channelId.lowercase())
        }
        reconfigureChannelStreams()
        return true
    }

    // ── Channels and the distro (RFed SPEC §17.12) ─────────────────────

    /**
     * The user's own join or leave of [entity] on this device: recorded for rule
     * 5, stamped after any record held (so an action made after this device took
     * a sibling's record is newer than it on every device, even with this
     * device's clock behind), with or without a distro. With a distro the change
     * is owed to its other devices as one membership message
     * ([DistroOutboxRuntime.oweChannelSync]), kept until the propagation node
     * proves it. Nothing else sends one: not applying a received message, not
     * the start's re-subscription, not a stamp refresh, not a join of a channel
     * already in the list.
     */
    private suspend fun recordUsersAction(op: String, entity: ChannelEntity) {
        try {
            val recorded = channelDao.membership(entity.id)?.let { DistroChannelSync.Record(it.op, it.atMs) }
            val atMs = DistroChannelSync.stampLocal(recorded, System.currentTimeMillis())
            channelDao.upsertMembership(ChannelMembershipEntity(entity.id, op, atMs))
            if (!DistroManager.hasDistro) return
            DistroOutboxRuntime.oweChannelSync(appContext, op, entity.channelName, entity.id, atMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The list changed when the user acted, whatever becomes of the message: said, never thrown at the screen.
            Log.w(TAG, "The $op of #${entity.channelName} was not recorded or sent to the distro (§17.12): ${e.message}")
        }
    }

    /**
     * A membership message C unwrapped from the distro (§17.12 "Receiving"):
     * rules 1-5 ([DistroChannelSync.disposition]), then rule 6
     * ([applyDistroChannelSync]). [fromDistro] and [signedByDistro] are rules 1
     * and 2's facts, which the caller knows from the unwrap. Returns true when
     * C is held (applied, this device's own echo, or stale: each recorded as
     * seen), false when it is dropped (rules 1, 2 and 4, each with a log line),
     * so a later copy of it is judged again.
     */
    suspend fun handleDistroChannelSync(
        marker: DistroChannelSync.Marker,
        fromDistro: Boolean,
        signedByDistro: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val record = marker.sync
            ?.let { channelDao.membership(channelHash(it.name).toHex()) }
            ?.let { DistroChannelSync.Record(it.op, it.atMs) }
        val name = marker.sync?.name ?: "?"
        when (val d = DistroChannelSync.disposition(
            marker = marker,
            fromDistro = fromDistro,
            signedByDistro = signedByDistro,
            ownDeviceHex = StackRuntime.selfDestHash.toHex(),
            joinNameAccepted = ChannelNameForm::joinNameAccepted,
            channelHashOf = { channelHash(it).toHex() },
            recordOf = { record },
        )) {
            is DistroChannelSync.Disposition.Drop -> {
                Log.w(TAG, "Channel membership message for #$name from device ${marker.byHex.take(12).ifEmpty { "?" }} dropped: ${d.reason} (§17.12 rule ${d.rule})")
                false
            }
            DistroChannelSync.Disposition.Echo -> {
                Log.i(TAG, "own channel ${marker.sync?.op ?: "membership"} echo for #$name — dropped (§17.12 rule 3)")
                true
            }
            is DistroChannelSync.Disposition.Stale -> {
                Log.i(TAG, "channel ${d.op} of #$name at ${d.atMs} is older than the ${d.recorded.op} at ${d.recorded.atMs} held here — dropped (§17.12 rule 5)")
                true
            }
            is DistroChannelSync.Disposition.Apply -> {
                applyDistroChannelSync(d, marker.byHex)
                true
            }
        }
    }

    /**
     * §17.12 rule 6: apply a sibling's join or leave on this device, with this
     * device's own key and its own configured RFed node, as the user's own join
     * or leave here would, then record it. No membership message is sent
     * (nothing loops), and nothing asks the user anything: the channel list
     * simply changes.
     *  - join of a channel not in the list: stored, then subscribed. It is stored
     *    first, so a subscription that cannot be made now is made by the start's
     *    subscription of stored channels ([resubscribePersistedChannels]), never
     *    by a retry (DESIGN_PRINCIPLES §3, §5). Not opened: its posts wait at RFed
     *    until the user opens it, as for any stored channel;
     *  - join of a channel already in the list: nothing but the record;
     *  - leave of a channel in the list: [leaveHere];
     *  - leave of a channel not in the list: nothing but the record.
     */
    private suspend fun applyDistroChannelSync(change: DistroChannelSync.Disposition.Apply, byHex: String) {
        val held = channelDao.findById(change.channelHash)
        var applied = "recorded"
        if (change.op == DistroChannelSync.OP_JOIN && held == null) {
            val node = UserPreferences.getEffectiveRfedNodeIdentityHash(appContext).trim().lowercase()
            if (node.length < 32) {
                // Not this device's to join without a node; the record still orders a later message.
                Log.w(TAG, "Channel join of #${change.name} from device ${byHex.take(12)}: no RFed node configured here — recorded, not joined")
            } else {
                val entity = ChannelEntity(
                    id = change.channelHash,
                    channelName = change.name,
                    rfedNodeIdentityHashHex = node,
                )
                channelDao.upsert(entity)
                applied = "joined"
                reconfigureChannelStreams()
                scope.launch(Dispatchers.IO) { subscribeStoredChannel(entity) }
            }
        } else if (change.op == DistroChannelSync.OP_LEAVE && held != null) {
            leaveHere(held)
            applied = "left"
        }
        channelDao.upsertMembership(ChannelMembershipEntity(change.channelHash, change.op, change.atMs))
        _activity.value = System.currentTimeMillis()
        Log.i(TAG, "Channel ${change.op} of #${change.name} at ${change.atMs} from device ${byHex.take(12).ifEmpty { "?" }}: $applied (§17.12)")
    }

    /**
     * Subscribe a channel the device has stored but not subscribed (a sibling's
     * join), with the device's own key, once: the readiness gate of the send is
     * the only wait, and a failure is logged, never retried. The start's
     * [resubscribePersistedChannels] subscribes it if this could not.
     */
    private suspend fun subscribeStoredChannel(channel: ChannelEntity) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channel.id) ?: return
        val rfedChannelDest = legacyChannelDest(channel.rfedNodeIdentityHashHex) ?: return
        runCatching {
            val stampCost = subscribeOnServer(channelHashBytes, rfedChannelDest, identityHandle)
            channelDao.updateStampCost(channel.id, stampCost)
            Log.i(TAG, "Subscribed to '${channel.channelName}' joined on another device (stampCost=$stampCost)")
        }.onFailure { e ->
            Log.w(TAG, "Subscribing to '${channel.channelName}', joined on another device, failed: ${e.message}")
        }
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
        // Fire-and-forget — RfedNotifyRegistrar drives the actual send via
        // the rfed.notify APP_LINK status callback (no blocking, no retry).
        RfedNotifyRegistrar.registerForChannel(appContext, identityHandle, channelHashBytes)
        true
    }

    /**
     * Disable push wakeups for a channel: persist the pref and deregister from rfed.notify.
     */
    suspend fun disableChannelPush(channelHashHex: String): Boolean = withContext(Dispatchers.IO) {
        UserPreferences.setChannelPushEnabled(appContext, channelHashHex, false)
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext false
        val channelHashBytes = FcmTokenRegistrar.hexToBytes(channelHashHex) ?: return@withContext false
        RfedNotifyRegistrar.deregisterForChannel(appContext, identityHandle, channelHashBytes)
        true
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
            RfedNotifyRegistrar.registerForChannel(appContext, identityHandle, channelHashBytes)
        }
    }

    /** Has the cold-start re-subscribe fired this process lifetime? */
    @Volatile private var didResubscribeOnActive = false

    /**
    * Re-subscribe to persisted channels once the rfed node is reachable.
    * The historical name is retained for call-site stability, but the
    * readiness gate now mirrors iOS: path reachability, not a held
    * rfed.channel APP_LINK.
     *
     * Direct port of iOS `scheduleResubscribeOnRfedChannelActive`.
     *
     * // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
     * Calling [resubscribePersistedChannels] eagerly at boot violates §1:
     * the inner [ConnectionStateManager.appLinkSend] would assert that
     * rfed.channel reaches ACTIVE inside 5 s, but multi-hop link
     * establishment routinely takes longer on cold start.  The fix is to
     * NOT send until the link is observed ACTIVE — the §1 budget then
     * always applies to a link that is already up.  No fail-state in code,
     * no app-level retries.
     */
    suspend fun scheduleResubscribeOnRfedChannelActive() = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext
        val channels = channelDao.activeChannels()
        if (channels.isEmpty()) return@withContext
        if (!ConnectionStateManager.waitForRfedReachable(timeoutMs = 5_000L)) return@withContext
        if (didResubscribeOnActive) return@withContext
        didResubscribeOnActive = true
        Log.i(TAG, "rfed reachable — re-subscribing persisted channels")
        resubscribePersistedChannels()
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
    * Caller MUST only invoke this when the rfed node is already reachable
    * (use [scheduleResubscribeOnRfedChannelActive] at boot). Best-effort:
    * failures are logged and swallowed.
     */
    suspend fun resubscribePersistedChannels() = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext
        val channels = channelDao.activeChannels()
        if (channels.isEmpty()) return@withContext
        Log.i(TAG, "Re-subscribing to ${channels.size} persisted channel(s) after restart")
        for (channel in channels) {
            val channelHashBytes = FcmTokenRegistrar.hexToBytes(channel.id) ?: continue
            val rfedChannelDest = legacyChannelDest(channel.rfedNodeIdentityHashHex) ?: continue
            runCatching {
                val stampCost = subscribeOnServer(channelHashBytes, rfedChannelDest, identityHandle)
                channelDao.updateStampCost(channel.id, stampCost)
                Log.i(TAG, "Re-subscribed to '${channel.channelName}' (stampCost=$stampCost)")
            }.onFailure { e ->
                Log.w(TAG, "Re-subscribe failed for '${channel.channelName}': ${e.message}")
            }
        }
        reconfigureChannelStreams()
    }

    private suspend fun reconfigureChannelStreams() = withContext(Dispatchers.IO) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return@withContext

        val activeChannels = channelDao.activeChannels()
        val openedChannelIds = channelStreamMutex.withLock {
            openedChannelStreamIds.toSet()
        }
        val openedChannelsByNode = activeChannels
            .filter { openedChannelIds.contains(it.id.lowercase()) }
            .groupBy { it.rfedNodeIdentityHashHex.lowercase() }
        val nodes = mutableSetOf<String>()
        nodes.addAll(activeChannels.map { it.rfedNodeIdentityHashHex.lowercase() })
        channelStreamMutex.withLock {
            nodes.addAll(trackedChannelStreamNodes)
        }

        for (nodeIdentityHashHex in nodes) {
            val streamDestHex = rfedDestHash(
                nodeIdentityHashHex,
                "rfed",
                listOf("channel", "stream"),
            ) ?: continue
            val streamDest = FcmTokenRegistrar.hexToBytes(streamDestHex) ?: continue
            ensureChannelStreamTracked(nodeIdentityHashHex, streamDest)

            val filters = openedChannelsByNode[nodeIdentityHashHex]
                .orEmpty()
                .mapNotNull { FcmTokenRegistrar.hexToBytes(it.id.lowercase()) }

            val status = ConnectionStateManager.appLinkStatus(streamDest)
            if (filters.isEmpty()) {
                if (status == RetichatBridge.AppLinkStatus.ACTIVE) {
                    sendChannelStreamConfig(nodeIdentityHashHex, streamDest, filters)
                }
                continue
            }

            if (status == RetichatBridge.AppLinkStatus.ACTIVE) {
                sendChannelStreamConfig(nodeIdentityHashHex, streamDest, filters)
            } else {
                ConnectionStateManager.primeAppLink(streamDest, "rfed", "channel.stream")
            }
        }
    }

    private suspend fun ensureChannelStreamTracked(
        nodeIdentityHashHex: String,
        streamDest: ByteArray,
    ) {
        val shouldRegister = channelStreamMutex.withLock {
            trackedChannelStreamNodes.add(nodeIdentityHashHex)
        }
        if (!shouldRegister) return

        ConnectionStateManager.registerAppLinkPacketCallback(
            streamDest,
            object : AppLinkPacketCallback {
                override fun onPacket(bytes: ByteArray) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { dispatchInboundBlob(bytes) }
                            .onFailure { Log.e(TAG, "channel.stream packet dispatch failed", it) }
                    }
                }
            },
        )

        ConnectionStateManager.setAppLinkStatusHandler(streamDest) { status ->
            if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
            scope.launch(Dispatchers.IO) {
                runCatching { sendChannelStreamConfigForNode(nodeIdentityHashHex) }
                    .onFailure { Log.e(TAG, "channel.stream config refresh failed", it) }
            }
        }
    }

    private suspend fun sendChannelStreamConfigForNode(nodeIdentityHashHex: String) {
        val streamDestHex = rfedDestHash(
            nodeIdentityHashHex,
            "rfed",
            listOf("channel", "stream"),
        ) ?: return
        val streamDest = FcmTokenRegistrar.hexToBytes(streamDestHex) ?: return
        val activeChannels = channelDao.activeChannels()
        val openedChannelIds = channelStreamMutex.withLock {
            openedChannelStreamIds.toSet()
        }
        val filters = liveStreamFilterIds(activeChannels, nodeIdentityHashHex, openedChannelIds)
            .mapNotNull { FcmTokenRegistrar.hexToBytes(it) }
        sendChannelStreamConfig(nodeIdentityHashHex, streamDest, filters)
    }

    private suspend fun sendChannelStreamConfig(
        nodeIdentityHashHex: String,
        streamDest: ByteArray,
        filters: List<ByteArray>,
    ) {
        val identityHandle = StackRuntime.identityHandle
        if (identityHandle == 0L) return

        val payload = buildChannelStreamPayload(identityHandle, filters) ?: run {
            Log.w(TAG, "channel.stream config build failed for node=$nodeIdentityHashHex")
            return
        }

        val response = ConnectionStateManager.appLinkSend(
            streamDest,
            "rfed",
            "channel.stream",
            "/rfed/channel/stream/open",
            payload,
        )
        if (!channelStreamResponseOk(response)) {
            Log.w(
                TAG,
                "channel.stream config rejected for node=$nodeIdentityHashHex filters=${filters.size} resp=${response?.toHex() ?: "null"}",
            )
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

            // RFed-spec/Channel.md "Distro holders" (James, 2026-10-03): a post
            // is signed by the posting identity, the distro when this device
            // holds one (as a DM is, DistroManager.sendingIdentity), otherwise
            // the device: its source, the key in its prelude and its signature
            // are that identity's, so readers see one sender for all of the
            // user's devices. Decided once, here: the record, the echo id, the
            // packing and the name state all take this one. Subscribing, the
            // stream, pulls and push registrations stay the device's
            // (`identityHandle`): RFed keys all of them by subscriber hash.
            val (postingHash, postingHandle) =
                DistroManager.sendingIdentity(StackRuntime.selfDestHash, identityHandle)
            val postingHex = postingHash.toHex()
            val optimisticId = "pending|${System.nanoTime()}"
            val optimistic = ChannelMessageEntity(
                id = optimisticId,
                channelId = channel.id,
                sourceHashHex = postingHex,
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

            try {
                // Step 1: refresh stampCost once per session.
                var liveChannel = channel
                if (channel.id !in stampCostRefreshedThisSession) {
                    runCatching { refreshStampCost(channel) }
                        .onSuccess {
                            stampCostRefreshedThisSession.add(channel.id)
                            liveChannel = channelDao.findById(channel.id) ?: liveChannel
                        }
                }

                // DISPLAY_NAMES.md §4.2: whether this post carries the
                // Channel Display Name, decided once for the post (the retry
                // below sends the same decision).
                val postName = withContext(Dispatchers.IO) { channelPostName(channel.id, postingHex) }

                if (trySend(liveChannel, content, optimisticId, postingHex, postingHandle, postName)) {
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
                if (!trySend(liveChannel, content, optimisticId, postingHex, postingHandle, postName)) {
                    Log.w(TAG, "SEND failed twice — marking optimistic row FAILED for user retry")
                    channelDao.updateMessageSendState(
                        optimisticId,
                        ChannelMessageEntity.SEND_STATE_FAILED,
                    )
                    return@withLock false
                }
                true
            } catch (cancelled: CancellationException) {
                Log.w(TAG, "SEND cancelled — marking optimistic row FAILED", cancelled)
                withContext(NonCancellable) {
                    channelDao.updateMessageSendState(
                        optimisticId,
                        ChannelMessageEntity.SEND_STATE_FAILED,
                    )
                }
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "SEND aborted unexpectedly — marking optimistic row FAILED", t)
                withContext(NonCancellable) {
                    channelDao.updateMessageSendState(
                        optimisticId,
                        ChannelMessageEntity.SEND_STATE_FAILED,
                    )
                }
                false
            }
        }

    /**
     * DISPLAY_NAMES.md §4.2 for a post to [channelId] now: the Channel Display
     * Name (only that name; it never falls back to another) when it changed,
     * a poster not seen before has posted since it was last included, or 24
     * hours have passed; an empty 0xD1 once after it was unset; otherwise
     * nothing.
     */
    private suspend fun channelPostName(channelId: String, postingHex: String): NameField {
        adoptLegacyNameState()
        val name = ownChannelName()
        val state = channelDao.nameState(channelId, postingHex)
        val newSender = state != null && channelDao.countSendersSeenAfter(channelId, state.lastIncludedAt) > 0
        return DisplayNames.channelPostName(
            name = name,
            lastDigestHex = state?.lastDigestHex,
            lastIncludedAt = state?.lastIncludedAt,
            newSenderSinceInclude = newSender,
            nowMs = System.currentTimeMillis(),
        )
    }

    /** This device's Channel Display Name, cleaned, or null while it is unset (§4.2). */
    private fun ownChannelName(): String? {
        val stored = UserPreferences.getChannelDisplayName(appContext)
        // Stored cleaned since 2026-09-27; clean again for a value saved before.
        return stored.takeIf { it.isNotEmpty() }?.let { DisplayNameSettings.clean(it, DisplayNameSettings.Kind.CHANNEL) }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * §4.2 "Learning from the channel": a post this device received in
     * [channelId] from its current posting identity [postingHex], key binding
     * and signature passed, with 0xD1 [field] and the post's time [postAtMs].
     * What it carries is recorded only when it is the value this device would
     * send now ([DisplayNames.learnedPostName]); a name it does not hold is
     * never recorded, so its own clear never follows a sibling's name.
     */
    private suspend fun learnPostName(channelId: String, postingHex: String, field: NameField, postAtMs: Long) {
        adoptLegacyNameState()
        val state = channelDao.nameState(channelId, postingHex)
        val learned = DisplayNames.learnedPostName(ownChannelName(), field, postAtMs, state?.lastIncludedAt)
            ?: return
        channelDao.upsertNameState(ChannelNameStateEntity(channelId, postingHex, learned.digestHex, learned.at))
    }

    /** The post carrying [postName] was handed to RFed: remember what the readers got (§4.2). */
    private suspend fun recordPostName(channelId: String, postingHex: String, postName: NameField) {
        val digest = when (postName) {
            NameField.Absent -> return
            NameField.Clear -> DisplayNames.EMPTY_DIGEST_HEX
            is NameField.Name -> DisplayNames.digestHex(postName.name)
        }
        channelDao.upsertNameState(ChannelNameStateEntity(channelId, postingHex, digest, System.currentTimeMillis()))
    }

    /**
     * The name state rows from before database 13 were the device's, the only
     * posting identity then: the device takes them as its own, once per
     * process, before it first reads its state ([ChannelDao.adoptLegacyNameState]).
     */
    @Volatile private var legacyNameStateAdopted = false

    private suspend fun adoptLegacyNameState() {
        if (legacyNameStateAdopted) return
        val deviceHex = StackRuntime.selfDestHash.toHex()
        if (deviceHex.isEmpty()) return
        channelDao.adoptLegacyNameState(deviceHex)
        channelDao.dropLegacyNameState()
        legacyNameStateAdopted = true
    }

    private suspend fun trySend(
        channel: ChannelEntity,
        content: String,
        optimisticId: String,
        postingHex: String,
        postingHandle: Long,
        postName: NameField,
    ): Boolean = withContext(Dispatchers.IO) {
        val packed = RetichatBridge.channelLxmPack(
            channel.channelName, postingHandle, content.toByteArray(), ByteArray(0), postName,
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

        val rfedChannelDest = legacyChannelDest(channel.rfedNodeIdentityHashHex)
            ?: return@withContext false

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

        val ok = ConnectionStateManager.appLinkSendData(
            rfedChannelDest, "rfed", "channel", finalPayload,
        )
        if (!ok) {
            Log.w(TAG, "Send failed: AppLinks DATA delivery failed")
            return@withContext false
        }
        Log.i(TAG, "SEND ok: channel='${channel.channelName}' id=${channel.id} rfedDest=${rfedChannelDest.toHex()} payload=${finalPayload.size}B stamp=${(channel.stampCost ?: 0)} name=${postName.state}")
        recordPostName(channel.id, postingHex, postName)

        // Upgrade optimistic row → canonical id (same as echo will use)
        val canonicalId = canonicalMessageId(postingHex, tsMs)
        // Drop optimistic row, insert canonical (Room can't change PK directly)
        channelDao.upsertMessage(
            ChannelMessageEntity(
                id = canonicalId,
                channelId = channel.id,
                sourceHashHex = postingHex,
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

        // AppLinks owns path readiness; the request itself runs on a
        // transient identified link so wake-style flows do not retain it.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        val resp = ConnectionStateManager.appLinkSend(
            rfedChannelDest, "rfed", "channel",
            "/rfed/subscribe", payload,
        ) ?: throw IllegalStateException("rfed.channel compatibility request failed after AppLinks readiness gate")

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
        val rfedChannelDest = legacyChannelDest(channel.rfedNodeIdentityHashHex)
            ?: return@withContext channel.stampCost
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
            val channelKey = pullStateKey(channel)
            val channelHashBytes = FcmTokenRegistrar.hexToBytes(channel.id)
                ?: return@withContext 0 to false
            val rfedChannelPullDest = FcmTokenRegistrar.rnsDestHash(
                channel.rfedNodeIdentityHashHex, "rfed", listOf("channel", "pull"),
            )?.let { FcmTokenRegistrar.hexToBytes(it) } ?: return@withContext 0 to false

            _pullInFlight.value = _pullInFlight.value + channelKey
            try {
                Log.i(TAG, "PULL start: channel='${channel.channelName}' rfedDest=${rfedChannelPullDest.toHex()}")
                // Single-shot via AppLinks readiness plus a transient
                // identified request on rfed.channel.pull.
                // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                val resp = ConnectionStateManager.appLinkSend(
                    rfedChannelPullDest, "rfed", "channel.pull",
                    "/rfed/pull", msgpackBin(channelHashBytes),
                ) ?: run {
                    Log.w(TAG, "PULL: rfed.channel.pull request failed after AppLinks readiness gate")
                    _canPullMore.value = _canPullMore.value + (channelKey to null)
                    return@withContext 0 to false
                }
                Log.i(TAG, "PULL response: ${resp.size} bytes")

                val decoded = decodePullResponse(resp) ?: run {
                    Log.w(TAG, "PULL: malformed response (${resp.size} bytes)")
                    _canPullMore.value = _canPullMore.value + (channelKey to null)
                    return@withContext 0 to false
                }
                val (pairs, morePending) = decoded
                Log.i(TAG, "PULL decoded: ${pairs.size} blob(s) morePending=$morePending")
                for ((channelHashBytes, blob) in pairs) {
                    dispatchBlob(channelHashBytes.toHex(), blob)
                }
                _canPullMore.value = _canPullMore.value + (channelKey to morePending)
                pairs.size to morePending
            } finally {
                _pullInFlight.value = _pullInFlight.value - channelKey
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
        // Distro fan-out shares rfed.delivery with channels: the prefix is the
        // distro's lxmf.delivery hash and the rest is `dest(16) | encrypted`
        // (Retichat-js app.js _handleChannelPacket → _handleDistroBlob).
        if (channelHashHex == DistroManager.deliveryHashHex) {
            Log.i(TAG, "dispatchInboundBlob distro fan-out inner=${inner.size}B")
            RfedDistroClient.handleBlob(appContext, inner)
            return
        }
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

            // RFed-spec/Channel.md "Own posts": a post is the user's own when
            // its source is this device's lxmf.delivery hash or the hash of
            // the distro this device holds now, whichever of the user's
            // devices sent it. It is stored and shown as outgoing, SENT (it
            // came from RFed, so RFed has it; a channel post has no per-reader
            // confirmation, so never DELIVERED), never notified, never counted
            // as a sender seen in the channel. A device that gave up its
            // distro no longer holds it: that distro's later posts are another
            // poster's to it.
            val deviceHex = StackRuntime.selfDestHash.toHex()
            val distroHex = DistroManager.deliveryHashHex
            val isOutbound = DistroCodec.isOwnChannelPost(sourceHashHex, deviceHex, distroHex)
            // DISPLAY_NAMES.md §4.2 "Learning from the channel": a post from
            // the current posting identity (the echo of this device's own
            // post, or one a sibling signed with the same distro), after the
            // key binding and signature passed, tells this device what readers
            // last got from it, but only a value equal to the one it would
            // send itself.
            val postingHex = DistroCodec.postingHex(deviceHex, distroHex)
            if (isOutbound && sourceHashHex.equals(postingHex, ignoreCase = true)) {
                learnPostName(channelHashHex, postingHex, result.displayName, result.timestampMs)
            }
            // DISPLAY_NAMES.md §4.2 / §5.1: the poster is seen in this channel,
            // and its 0xD1 (reported only after the key binding and signature
            // checks) sets or clears its channel name here. Never the
            // contact's messageName.
            val channelName = if (isOutbound) null else {
                recordChannelSender(channelHashHex, sourceHashHex, result.displayName, result.timestampMs)
            }
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
                    sendState = ChannelMessageEntity.SEND_STATE_SENT,
                )
            )
            channelDao.bumpLastMessageTime(channelHashHex, result.timestampMs)
            _activity.value = System.currentTimeMillis()

            if (!isOutbound &&
                UserPreferences.isChannelNotificationsEnabled(appContext, channelHashHex)
            ) {
                val names = contactDao.findByHash(sourceHashHex)?.names()
                MessageNotificationHelper.notify(
                    appContext,
                    senderName = notificationSenderName(channelName, names, sourceHashHex),
                    content = content,
                    chatId = channelHashHex,
                    conversationTitle = "#${channel.channelName}",
                )
            }
        }

    /**
     * Record [senderHex] as seen in [channelId] and apply the name field of
     * its post from [postTimestampMs]; a post older than the newest one that
     * named or cleared it (history pulled late) leaves it. Returns the poster's
     * channel name there afterwards.
     */
    private suspend fun recordChannelSender(
        channelId: String,
        senderHex: String,
        field: NameField,
        postTimestampMs: Long,
    ): String? {
        val hex = senderHex.lowercase()
        channelDao.insertSenderIfAbsent(
            ChannelSenderEntity(channelId = channelId, senderHex = hex, firstSeenAt = System.currentTimeMillis())
        )
        val sender = channelDao.findSender(channelId, hex)
        val current = sender?.channelName
        return when (val update = DisplayNames.acceptChannelName(current, sender?.nameAt ?: 0L, field, postTimestampMs)) {
            NameUpdate.Unchanged -> current
            is NameUpdate.Set -> {
                // The write repeats the order guard; 0 rows means a newer post landed first.
                if (channelDao.setSenderChannelName(channelId, hex, update.name, postTimestampMs) > 0) update.name
                else channelDao.findSender(channelId, hex)?.channelName
            }
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

    internal fun msgpackArrayOfBins(elements: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        when {
            elements.size <= 0x0f -> out.write(0x90 or elements.size)
            elements.size <= 0xffff -> {
                out.write(0xdc)
                out.write((elements.size shr 8) and 0xff)
                out.write(elements.size and 0xff)
            }
            else -> throw IllegalArgumentException("too many channel stream filters")
        }
        for (element in elements) {
            out.write(msgpackBin(element))
        }
        return out.toByteArray()
    }

    private fun buildChannelStreamPayload(
        identityHandle: Long,
        filters: List<ByteArray>,
    ): ByteArray? {
        val pubkey = RetichatBridge.identityPublicKey(identityHandle) ?: return null
        val value = msgpackArrayOfBins(filters)
        val sig = RetichatBridge.identitySign(identityHandle, value) ?: return null
        return msgpackSigned(value, pubkey, sig)
    }

    private fun channelStreamResponseOk(response: ByteArray?): Boolean {
        if (response == null || response.size < 2) return false
        return (response[0].toInt() and 0xff) == 0x92 &&
            (response[1].toInt() and 0xff) == 0xc3
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
