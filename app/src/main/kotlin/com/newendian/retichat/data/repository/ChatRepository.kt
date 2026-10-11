package com.newendian.retichat.data.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.PhotoLocationNotRemoved
import com.newendian.retichat.PhotoMetadata
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.JniGroupNative
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.service.DistroAddresses
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.DistroGroupKeys
import com.newendian.retichat.service.DistroGroupSync
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.service.DistroOutboxRuntime
import com.newendian.retichat.service.GroupSync
import com.newendian.retichat.service.RfedDistroClient
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.GroupChatManager
import com.newendian.retichat.service.GroupSigner
import com.newendian.retichat.service.GroupFields
import com.newendian.retichat.service.MessageNotificationHelper
import com.newendian.retichat.service.NetworkMonitor
import com.newendian.retichat.service.PropagationNodeManager
import com.newendian.retichat.service.StackRuntime
import com.newendian.retichat.service.UserPreferences
import com.newendian.retichat.data.db.dao.ChatDao
import com.newendian.retichat.data.db.dao.ChatPreview
import com.newendian.retichat.data.db.dao.ContactDao
import com.newendian.retichat.data.db.dao.GroupStatusDao
import com.newendian.retichat.data.db.dao.MessageDao
import com.newendian.retichat.data.db.entity.*
import com.newendian.retichat.data.model.*
import com.newendian.retichat.names.DisplayNames
import com.newendian.retichat.names.NameBook
import com.newendian.retichat.names.NameField
import com.newendian.retichat.names.NameUpdate
import com.newendian.retichat.names.Signature
import com.newendian.retichat.names.SystemText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for chats, messages, and contacts.
 *
 * Bridges the Room database with the Rust native layer via [RetichatBridge].
 */
class ChatRepository(
    private val appContext: Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val groupStatusDao: GroupStatusDao,
    private val attachmentDir: File,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ChatRepo"
    }
    // ---- Own identity (set by ReticulumService after init) ----

    var selfDestHash: ByteArray = ByteArray(0)
        private set
    var routerHandle: Long = 0L
        private set
    var identityHandle: Long = 0L
        private set

    /** Lazily initialised after configure(); non-null only when routerHandle != 0. */
    private var groupChatManager: GroupChatManager? = null

    private val _groupActionsReady = MutableStateFlow(false)
    /**
     * The stack is up ([configure] gave a router, the device identity and its
     * address): a group send can be signed and go. False while it starts,
     * after it failed to start, and once it is stopped; no group action is
     * offered then ([GroupSelves.Signer.NOT_READY], GROUPS-DISTRO-DESIGN.md
     * §8.7), and the screens re-read what they offer when this changes.
     */
    val groupActionsReady: StateFlow<Boolean> = _groupActionsReady

    /**
     * Member statuses and keys (RFed-spec Group.md, 2026-10-06): what an accept
     * or leave is worth by its signature, what it changes, the creator's copies
     * and its `status` answer, and the entries held for a key. See
     * [GroupStatuses]; the decisions are the engine's, the storage Room's.
     */
    private val groupStatuses = GroupStatusEngine(
        store = RoomGroupStatusStore(
            chatDao, messageDao, groupStatusDao,
            allowContact = { ensureAllowlistedContact(it) },
            keepContactKey = { hex, key ->
                ensureContact(hex)
                contactDao.setPublicKey(hex, key.toHex())
            },
        ),
        native = JniGroupNative,
        selfHex = { selfHex() },
        answer = { chat, member, statuses ->
            val groupId = chat.groupIdHex
            val manager = groupChatManager
            // As the group's own address, resolved now (GROUPS-DISTRO-DESIGN.md §8.1, §12.1).
            val signer = groupSigner(chat)
            if (groupId == null || manager == null || signer == null) {
                Log.w(TAG, "status for ${chat.id} to ${member.take(8)} not sent: ${if (groupId == null) "no group id" else if (manager == null) "stack offline" else "no signer for its own address (${signerKind(chat)}, §8.7)"}")
            } else {
                manager.sendStatus(signer, groupId, member, statuses)
            }
        },
        log = { Log.i(TAG, it) },
        selves = { chat -> groupSelf(chat) },
        isSelfAnywhere = { isSelfAnywhere(it) },
    )

    fun configure(selfHash: ByteArray, router: Long, identity: Long) {
        selfDestHash = selfHash
        routerHandle = router
        identityHandle = identity

        groupChatManager = if (router != 0L) {
            GroupChatManager(appContext, scope, router)
        } else null
        _groupActionsReady.value = router != 0L && identity != 0L && selfHash.isNotEmpty()

        // Queued messages wait for onStackReady(), not for this: bootstrap
        // calls configure() before the message-state callback and
        // ConnectionStateManager are registered.
        // A stopped router reports nothing more on the sends it had.
        if (router == 0L) propagationFallbacks.clear()

        // Mark any stale GENERATING/OUTBOUND/SENDING messages as FAILED.
        // These are leftovers from a previous session that never completed.
        scope.launch(Dispatchers.IO) {
            messageDao.failStaleOutbound()
        }
    }

    /**
     * StackRuntime's bootstrap has finished, so the messages queued while it
     * ran go out now (§5: the queue waits for this signal). Not from
     * [configure], which bootstrap calls before the message-state callback
     * and ConnectionStateManager are registered: a message flushed from there
     * lost its router reports, its propagated fallback among them.
     */
    fun onStackReady() {
        scope.launch(Dispatchers.IO) { flushPendingMessages() }
    }

    fun onMessageState(hash: ByteArray, state: Int) {
        val hashHex = hash.toHex()
        if (state == RetichatBridge.MessageState.DELIVERED) {
            deliveryTargets.take(hashHex)?.let { target ->
                scope.launch(Dispatchers.IO) { recordDelivered(target) }
            }
        }
        // The router reports 0x10 and FAILED holding its lock and this
        // message's, so the copy is sent from IO: a message call made on this
        // thread would wait on those locks forever.
        propagationFallbacks.onState(hashHex, state)?.let(::startPropagatedCopy)
        groupChatManager?.onMessageState(hash, state)
    }

    /**
     * The recipient proved [target]'s message. Reached from the router's
     * DELIVERED report, which can come after the poll let go: the receipt
     * timed out first (the bubble said FAILED) or the propagated fallback
     * took the bubble over. Delivery outranks every other state.
     */
    private suspend fun recordDelivered(target: DeliveryTargets.Target) {
        when (target) {
            is DeliveryTargets.Target.Row -> {
                val row = messageDao.findById(target.messageId) ?: return
                if (row.state == RetichatBridge.MessageState.DELIVERED) return
                Log.i(TAG, "delivery: ${target.messageId} proved delivered (was state=${row.state})")
                messageDao.updateStateAndProgress(target.messageId, RetichatBridge.MessageState.DELIVERED, 1f)
            }
            is DeliveryTargets.Target.GroupMember ->
                recordGroupMemberDelivered(target.groupMsgId, target.chatId, target.memberHex)
        }
    }

    /** Remember where [handle]'s delivery report lands (see [DeliveryTargets]). */
    private fun rememberDeliveryTarget(handle: Long, target: DeliveryTargets.Target) {
        val hash = RetichatBridge.messageGetHash(handle) ?: return
        deliveryTargets.remember(hash.toHex(), target)
    }

    /**
     * Keep the router's own stranger filter off: the privacy filter is the
     * app's, applied in [onMessageReceived] as iOS applies it
     * ([DeliveryPolicy]). Called once at stack startup after [configure],
     * before the delivery callback is registered. Until 2026-09-27 the
     * preference was pushed to the router, whose allowlist nothing filled,
     * and every router-delivered message was dropped.
     */
    fun primeCoreDeliveryPrivacy() {
        if (routerHandle == 0L) return
        if (!RetichatBridge.routerSetFilterStrangers(routerHandle, false)) {
            Log.e(TAG, "router stranger filter not turned off: ${RetichatBridge.lastError()}")
        }
        Log.d(TAG, "primeCoreDeliveryPrivacy: app-side filterStrangers=${UserPreferences.isFilterStrangersEnabled(appContext)}")
    }

    /**
     * The Settings "Privacy filter" toggle: persisted and read at every
     * delivery ([DeliveryPolicy]), so it applies at once.
     */
    fun setCoreFilterStrangers(enabled: Boolean) {
        UserPreferences.setFilterStrangersEnabled(appContext, enabled)
    }

    /**
     * One [flushPendingMessages] at a time; see [SerialFlush]. Declared before
     * the init block below: that block hands [networkListener] to
     * NetworkMonitor, whose callback thread may run a flush at once.
     */
    private val pendingFlush = SerialFlush()

    /**
     * Where each sent message's DELIVERED report lands; see [DeliveryTargets].
     * Before the init block too: a flush started from there records targets.
     */
    private val deliveryTargets = DeliveryTargets()

    /** When a DIRECT send's propagated copy starts; see [PropagationFallbacks]. */
    private val propagationFallbacks = PropagationFallbacks()

    /** Network-available listener, registered once. */
    private val networkListener: () -> Unit = {
        scope.launch(Dispatchers.IO) { flushPendingMessages() }
    }

    init {
        NetworkMonitor.addOnAvailableListener(networkListener)
        // Every change of what the device holds of a distro (looked at once the
        // stack loads it, generated, imported, forgotten): held arrivals of
        // another distro and the siblings of a distro given up are forgotten
        // (GROUPS-DISTRO-DESIGN.md §6.5, §9.1). Not the loaded state: this
        // repository is made before the stack loads the stored key, and a null
        // there at start-up is D held, not loaded, which forgets nothing (§8.7).
        scope.launch(Dispatchers.IO) {
            DistroManager.holding.collect { onDistroChanged(it) }
        }
    }

    // ---- Contacts ----

    /**
     * The contacts the user added (Add Contact, New Conversation, QR code,
     * lxma:// link), each with its resolved name, sorted by it. Never a
     * hidden row: group members and channel posters are accepted by
     * association, and a stranger's or a distro sender's chat is a
     * conversation, not a contact ([com.newendian.retichat.data.db.ContactSql]).
     */
    fun contacts(): Flow<List<Contact>> =
        contactDao.listedContacts().map { list ->
            list.map { it.toDomain() }.sortedBy { it.displayName.lowercase() }
        }

    /** Every peer this app holds a row for, hidden ones included (a conversation's own name slot). */
    fun peers(): Flow<List<Contact>> =
        contactDao.allContacts().map { list -> list.map { it.toDomain() } }

    /**
     * The resolver every surface reads names through (DISPLAY_NAMES.md §5.3),
     * live: a new name, a rename or an announce re-emits it.
     */
    fun nameBook(): Flow<NameBook> =
        contactDao.allContacts().map { list -> nameBookOf(list) }

    private fun nameBookOf(list: List<ContactEntity>): NameBook =
        NameBook(list.associate { it.destHashHex.lowercase() to it.names() }, selfDestHash.toHex())

    /** One contact's resolved name, for a notification posted now. */
    suspend fun resolvedName(hashHex: String): String =
        DisplayNames.contact(contactDao.findByHash(hashHex)?.names(), hashHex)

    /**
     * The user added [destHash] (QR code, link, hash entry, New Chat): an
     * allowlisted contact, listed from now on (a hidden row becomes a
     * contact). The one place a row becomes a contact. An existing row keeps
     * its names and key (until 2026-09-27 an upsert here reset them, and
     * group invite acceptance lost the key); a [publicKey] given here is
     * stored.
     */
    suspend fun addContact(destHash: ByteArray, publicKey: ByteArray? = null) {
        val hex = destHash.toHex()
        contactDao.insertIfAbsent(ContactEntity(destHashHex = hex, isAllowlisted = true, isContact = true))
        contactDao.setAllowlisted(hex)
        contactDao.setContact(hex)
        publicKey?.let { contactDao.setPublicKey(hex, it.toHex()) }
        watchAnnounces(hex)
    }

    /**
     * Let [hex]'s announces through Transport's drop filter (on by default),
     * so its announce name reaches [onAnnounceReceived] (DISPLAY_NAMES.md
     * §5.1 `announceName`). iOS watches the same contacts. On IO: it takes
     * Transport's lock, and the add-contact flows start on the main thread.
     */
    private suspend fun watchAnnounces(hex: String) {
        if (!RetichatBridge.isLoaded || hex.length != 32) return
        withContext(Dispatchers.IO) { runCatching { RetichatBridge.watchAnnounce(hex.hexToBytes()) } }
    }

    /**
     * At stack start: watch every contact's announces. Transport's watch list
     * lives in memory, so it is rebuilt here rather than only as contacts are
     * added.
     */
    suspend fun watchContactAnnounces() {
        contactDao.allContactsSnapshot().forEach { watchAnnounces(it.destHashHex) }
    }

    suspend fun findContact(destHash: ByteArray): Contact? =
        contactDao.findByHash(destHash.toHex())?.toDomain()

    /**
     * The user's own name for a contact (§5.1 `localName`). Saving an empty
     * name clears it, and the contact shows its provided name again.
     */
    suspend fun renameContact(destHashHex: String, newName: String) {
        val hex = destHashHex.lowercase()
        // §3 cleaning by the one Rust cleaner, as for the own names; text
        // that cleans to nothing clears the local name.
        val name = withContext(Dispatchers.IO) {
            DisplayNames.localName(newName) { raw ->
                if (RetichatBridge.isLoaded) RetichatBridge.displayNameClean(raw, announce = false) else raw.trim()
            }
        }
        contactDao.insertIfAbsent(ContactEntity(destHashHex = hex))
        contactDao.setLocalName(hex, name)
    }

    /**
     * A hidden row for [hex] (not allowlisted, no contact) so a received name
     * or key has a place to go. An existing row is left as it is.
     */
    private suspend fun ensureContact(hex: String) {
        if (contactDao.insertIfAbsent(ContactEntity(destHashHex = hex)) != -1L) watchAnnounces(hex)
    }

    /**
     * [hex] passes the privacy filter from now on: a member of a group the
     * user created or accepted (RFed-spec Group.md, "The privacy filter": the
     * members pass from the user's accept, or from creating the group, not
     * from the invite's arrival; a member's own accept in a joined group
     * likewise), accepted by association. A new row is hidden (no contact); an
     * existing one keeps whether it is a contact.
     */
    private suspend fun ensureAllowlistedContact(hex: String) {
        contactDao.insertIfAbsent(ContactEntity(destHashHex = hex, isAllowlisted = true))
        contactDao.setAllowlisted(hex)
        watchAnnounces(hex)
    }

    /**
     * DISPLAY_NAMES.md §5.2: apply a received 0xD1 to the LXMF source
     * [srcHex]'s `messageName`. [unverifiedReason] decides (validated, source
     * unknown, invalid), and only a message newer than `messageNameAt` is
     * accepted ([messageAt] is its LXMF timestamp, seconds); accepting drops
     * `legacyName` and, for a validated message only, records [messageAt]
     * ([DisplayNames.recordsMessageTime]). The caller has already let the message
     * through and made sure the contact row exists.
     */
    private suspend fun acceptMessageName(srcHex: String, field: NameField, unverifiedReason: Int, messageAt: Double) {
        if (field == NameField.Absent) return
        val contact = contactDao.findByHash(srcHex)
        when (val update = DisplayNames.acceptMessageName(
            contact?.messageName, contact?.messageNameAt, field, unverifiedReason, messageAt,
        )) {
            NameUpdate.Unchanged ->
                Log.i(TAG, "name: ${srcHex.take(8)} 0xD1 not accepted (reason=$unverifiedReason, at=$messageAt, held at ${contact?.messageNameAt})")
            is NameUpdate.Set -> {
                // The write repeats the order guard (NameSql), so a concurrent
                // older delivery cannot land last.
                val rows = contactDao.acceptMessageName(
                    srcHex, update.name, messageAt, onlyIfNone = !DisplayNames.recordsMessageTime(unverifiedReason),
                )
                Log.i(TAG, "name: ${srcHex.take(8)} messageName ${if (update.name == null) "cleared" else "set"} (reason=$unverifiedReason, at=$messageAt, rows=$rows)")
            }
        }
    }

    /** The privacy filter's answer for a direct message or invite from [srcHex] (iOS `allowlistDecision`). */
    private suspend fun allowlisted(srcHex: String): Boolean {
        val contact = contactDao.findByHash(srcHex)
        return DeliveryPolicy.allowlisted(
            filterStrangers = UserPreferences.isFilterStrangersEnabled(appContext),
            contactExists = contact != null,
            allowlisted = contact?.isAllowlisted == true,
        )
    }

    suspend fun renameGroup(chatId: String, newName: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        chatDao.updateChatName(chatId, newName)
    }

    /** Group members for a given chat (reactive Flow). */
    fun groupMembers(chatId: String): Flow<List<GroupMemberEntity>> =
        messageDao.groupMembers(chatId)

    // ---- Chats ----

    fun chatPreviews(): Flow<List<ChatPreview>> = chatDao.chatPreviews()

    fun chatById(chatId: String): Flow<ChatEntity?> = chatDao.chatByIdFlow(chatId)

    suspend fun archiveChat(chatId: String) {
        chatDao.archiveChat(chatId)
    }

    /**
     * Delete a conversation. A group's is the user's leave (James,
     * 2026-10-05: deleting a group conversation means leaving it): the leave
     * goes to the members and the group is closed for good, as
     * [leaveGroupChat] does ([quitGroup]). Until 2026-10-05 a group's chat
     * was dropped here alone: its members went on counting the user, and a
     * later invite brought it back.
     */
    /** Delete [chatId]: a group's is the user's leave ([quitGroup]). True when it is gone. */
    suspend fun deleteChat(chatId: String): Boolean {
        val chat = chatDao.findById(chatId) ?: return false
        if (chat.isGroup) return quitGroup(chatId, "deleted")
        deleteChatLocal(chat)
        return true
    }

    /**
     * "Delete from this device" for a group whose own address is a distro this
     * device no longer holds (GROUPS-DISTRO-DESIGN.md §8.7): the group and its
     * messages go from this device only. No leave is sent and no group sync is
     * owed: the user stays in the group on the other devices and for the other
     * members. The one path that removes a group without the user's leave.
     *
     * Its id is recorded closed on this device, as a leave records it
     * (alignment decision 4, 2026-10-11): a later invite or group sync for it
     * does not bring the group back here. It is not put in D's left list: the
     * user did not leave, and the other devices keep it.
     */
    suspend fun deleteGroupFromThisDevice(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        chat.groupIdHex?.let { groupId ->
            UserPreferences.closeGroup(appContext, groupId)
            forgetHeldArrivals(groupId, "the group was deleted from this device")
        }
        deleteChatLocal(chat)
        Log.i(TAG, "Group ${chat.id} deleted from this device only (own address ${chat.ownMember?.take(8)}, ${signerKind(chat)}): recorded closed here, no leave sent, no group sync owed (§8.7)")
    }

    fun messagesForChatPaged(chatId: String) = messageDao.messagesForChatPaged(chatId)

    /**
     * The DM chat with [destHash], created if needed. The user chose this
     * peer, so it is an allowlisted contact (iOS `createDirectChat`). A DM's
     * title is resolved from the contact when shown; `chats.name` is unused
     * for DMs.
     */
    suspend fun getOrCreateDirectChat(destHash: ByteArray): String {
        addContact(destHash)
        val chatId = directChatId(destHash)
        val existing = chatDao.findById(chatId)
        if (existing == null) {
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = false,
                    name = "",
                    memberHashes = destHash.toHex(),
                )
            )
        } else if (existing.isArchived) {
            chatDao.unarchiveChat(chatId)
        }
        return chatId
    }

    /**
     * Create a group of [members] and the user. On a device that holds a
     * distro D, loaded, the group is created as D (GROUPS-DISTRO-DESIGN.md
     * §8.5): the user's own entry is D, never the device; the recorded creator
     * and the group's own address are D; the invite chunks are signed by D with
     * GROUP_SENDER D, D's chunk carrying D's key (from the distro, not the
     * contacts); and one `create` group sync is owed once the chunks are built
     * (§10.6), so every other device of D holds the group too. Otherwise as the
     * device, as before.
     *
     * Refused, with nothing written or sent, while the stack is not up or a
     * distro is stored but has not loaded yet ([GroupSelves.createRefusal],
     * §8.7): the screen says why ([GroupCreation.Refused]). Until 2026-10-11 the
     * group was written anyway: with the stack down, one whose invites were
     * never sent; with D not loaded, one as the device that the user's other
     * devices never hear of.
     */
    suspend fun createGroupChat(
        name: String,
        members: List<ByteArray>,
    ): GroupCreation {
        // The user's own entry: the distro held now, else the device (§8.1).
        val distro = DistroManager.signing()?.first
        GroupSelves.createRefusal(if (groupActionsReady.value) selfHex() else "", distro?.deliveryHashHex, DistroManager.isStored(appContext))?.let { why ->
            Log.w(TAG, "createGroupChat refused: $why (§8.7) — nothing written or sent")
            return GroupCreation.Refused(why)
        }

        // Generate a random group ID (16 bytes → 32-char hex)
        val groupIdBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val groupIdHex = groupIdBytes.toHex()

        val own = distro?.deliveryHashHex ?: selfHex()
        val ownHash = DistroCodec.hexToBytes(own) ?: selfDestHash
        // The user's own entry in the canonical member list
        val allMembers = (members + ownHash).distinctBy { it.toHex() }
        val sorted = allMembers.sortedBy { it.toHex() }
        val hashes = sorted.joinToString(",") { it.toHex() }
        val chatId = "group_${groupIdHex.take(16)}"

        val chat = ChatEntity(
            id = chatId,
            isGroup = true,
            name = name,
            memberHashes = hashes,
            groupIdHex = groupIdHex,
            creatorHex = own,
            ownMember = own,
        )
        // As the group's own address, resolved before anything is written: a
        // stack that stopped (or a D that went) since the check above refuses
        // the same way, rather than leaving a group whose invites never left.
        val signer = groupSigner(chat)
        val manager = groupChatManager
        if (signer == null || manager == null) {
            val kind = signerKind(chat)
            val why = if (kind == GroupSelves.Signer.NOT_LOADED) kind else GroupSelves.Signer.NOT_READY
            Log.w(TAG, "createGroupChat refused: ${if (manager == null) "stack offline" else "no signer for ${own.take(8)} ($kind)"} (§8.7) — nothing written or sent")
            return GroupCreation.Refused(why)
        }
        chatDao.upsert(chat)

        val allMemberHexes = sorted.map { it.toHex() }
        val self = GroupSelves.of(own, allMemberHexes, selfHex(), DistroManager.deliveryHashHex, ::siblingsOf)
        sorted.forEach { hash ->
            val hexHash = hash.toHex()
            val isSelf = self.isSelf(hexHash)
            // Co-members pass the privacy filter (iOS createGroupChat); no self address does.
            if (!isSelf) ensureAllowlistedContact(hexHash)
            messageDao.upsertGroupMember(
                GroupMemberEntity(
                    chatId = chatId,
                    destHashHex = hexHash,
                    inviteStatus = if (hexHash == own) MemberStatus.ACCEPTED else MemberStatus.INVITED,
                )
            )
        }

        // Invites to every member that is not a self address, as the group's own address.
        val memberPublicKeys = buildMap {
            sorted.forEach { hash ->
                val hashHex = hash.toHex()
                val publicKey = when {
                    hashHex == own && distro != null -> distro.publicKeyHex
                    hashHex == selfHex() -> RetichatBridge.identityPublicKey(identityHandle)?.toHex()
                    else -> contactDao.findByHash(hashHex)?.publicKeyHex
                }
                if (publicKey?.length == 128) put(hashHex, publicKey.lowercase())
            }
        }
        val built = manager.sendInvites(signer, groupIdHex, name, allMemberHexes, memberPublicKeys, GroupSelves.targets(allMemberHexes, self))
        // §10.6: one `create` group sync, after the invite chunks are built, for a group created as D.
        if (built && distro != null) {
            val keys = allMemberHexes.mapNotNull { hex ->
                memberPublicKeys[hex]?.let { DistroCodec.hexToBytes(it) }?.let { GroupStatuses.keyEntry(hex, it) }
            }
            DistroOutboxRuntime.oweGroupSync(
                appContext, own,
                GroupSync(
                    op = DistroGroupSync.CREATE, groupId = groupIdHex, atMs = System.currentTimeMillis(),
                    name = name, members = hashes, keys = keys, left = UserPreferences.groupsLeftBy(appContext, own),
                ),
            )
        }

        Log.i(TAG, "createGroupChat: id=$chatId, groupId=$groupIdHex, ${sorted.size} members, as ${if (distro != null) "the distro ${own.take(8)}" else "the device"}")
        return GroupCreation.Created(chatId)
    }

    // ---- Messages ----

    fun messagesForChat(chatId: String): Flow<List<MessageEntity>> =
        messageDao.messagesForChat(chatId)

    /**
     * Send all messages that were queued while offline or while the stack was
     * starting (state == OUTBOUND, no native handle). Called when the stack is
     * ready ([onStackReady]), when network becomes available, and by a send
     * that queued while the stack was starting. These can fire together, so
     * flushes are serialized and a flush that waited re-reads the queue (and
     * readiness) after the one before it has ended.
     */
    private suspend fun flushPendingMessages() = pendingFlush.run(
        pending = {
            if (!stackReady() || !NetworkMonitor.isOnline.value) {
                emptyList()
            } else {
                messageDao.pendingOutbound()
            }
        },
    ) { pending ->
        Log.i(TAG, "flushPending: ${pending.size} message(s) queued")

        for (msg in pending) {
            val chat = chatDao.findById(msg.chatId)
            if (chat == null) {
                Log.w(TAG, "flushPending: no chat ${msg.chatId} for queued msg ${msg.id} — left queued")
                continue
            }
            if (chat.isGroup) {
                // Group messages are fan-out; re-sending properly would need
                // the original member list.  For now, mark failed so the user
                // can resend manually.
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
                continue
            }

            try {
                // The same dispatch as a message sent with the stack up: a
                // queued message still goes PROPAGATED to a distro recipient,
                // keeps its attachments, and has the propagated fallback
                // behind its DIRECT attempt.
                dispatch(
                    localId = msg.id,
                    destHash = chat.memberHashes.hexToBytes(),
                    content = msg.content,
                    attachments = queuedAttachments(msg.id),
                    fromQueue = true,
                )
                Log.d(TAG, "flushPending: sent queued msg ${msg.id}")
            } catch (e: Exception) {
                Log.e(TAG, "flushPending: failed ${msg.id}: ${e.message}")
                messageDao.updateState(msg.id, RetichatBridge.MessageState.FAILED)
            }
        }
    }

    /**
     * A queued message's attachments, read back from where
     * [saveOutboundAttachments] stored them. A file that can not be read
     * throws, and the flush shows the message failed rather than send it
     * without the file.
     */
    private suspend fun queuedAttachments(msgId: String): List<Pair<String, ByteArray>> =
        messageDao.attachmentsFor(msgId).map { it.filename to File(it.localPath).readBytes() }

    /**
     * Send a text message in a 1:1 or group chat.
     *
     * The one entry for what the user composes (direct, group and distro
     * sends alike), and so the one place every attachment is cleaned: a photo
     * leaves without where it was taken (James, 2026-10-05). Before anything is
     * stored or sent, each attachment goes through [PhotoMetadata]; one that
     * cannot be made safe stops the whole send with [PhotoLocationNotRemoved].
     * No other path needs it: a group relay forwards text only, and a queued
     * message's attachments are the files stored by the send below, cleaned.
     */
    suspend fun sendMessage(chatId: String, content: String, attachmentFiles: List<Pair<String, ByteArray>> = emptyList()): GroupPostRefused? {
        Log.d(TAG, "sendMessage: chatId=$chatId, content='${content.take(40)}', routerHandle=$routerHandle, selfDest=${selfDestHash.toHex().take(16)}")
        val attachments = attachmentFiles.map { (filename, data) ->
            filename to (PhotoMetadata.withoutLocation(data) ?: throw PhotoLocationNotRemoved(filename))
        }
        val chat = chatDao.findById(chatId)
        if (chat == null) {
            Log.e(TAG, "sendMessage: chat not found for id=$chatId")
            return null
        }

        return if (chat.isGroup) {
            sendGroupMessage(chat, content, attachments)
        } else {
            val destHash = chat.memberHashes.hexToBytes()
            Log.d(TAG, "sendMessage: direct to ${chat.memberHashes.take(16)}, destHash ${destHash.size} bytes")
            sendDirectMessage(chatId, destHash, content, attachments)
            null
        }
    }

    private suspend fun sendDirectMessage(
        chatId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ) {
        Log.d(TAG, "sendDirect: dest=${destHash.toHex().take(16)}, src=${selfDestHash.toHex().take(16)}, router=$routerHandle, identity=$identityHandle")

        // 1) Insert the bubble immediately so the user sees it right away
        val localId = "out_${System.currentTimeMillis()}"
        messageDao.upsert(
            MessageEntity(
                id = localId,
                chatId = chatId,
                senderHashHex = selfDestHash.toHex(),
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.GENERATING,
            )
        )

        // Save outbound attachments to disk + DB so they display in the bubble
        if (attachments.isNotEmpty()) {
            saveOutboundAttachments(localId, attachments)
        }

        // 2) Attempt native send in the background
        scope.launch(Dispatchers.IO) {
            try {
                // Not initialized: the stack is down (e.g. app briefly
                // backgrounded) or still starting. The message is queued
                // until StackRuntime says it is ready (onStackReady), which
                // is after the message-state callback and ConnectionStateManager
                // are registered; configure() comes before both.
                if (!stackReady()) {
                    messageDao.updateState(localId, RetichatBridge.MessageState.OUTBOUND)
                    // The ready signal may have flushed the queue between the
                    // check above and the write: look again now that the row
                    // is queued, or it waits for the next trigger.
                    if (StackRuntime.isReady) flushPendingMessages()
                    if (!NetworkMonitor.isOnline.value) {
                        Log.i(TAG, "sendDirect: offline — queued for later")
                        return@launch
                    }
                    // A bootstrap already running flushes the queue when it
                    // finishes.
                    if (StackRuntime.isStarting) {
                        Log.i(TAG, "sendDirect: stack starting — queued until it is ready")
                        return@launch
                    }
                    Log.i(TAG, "sendDirect: stack not ready — queued, acquiring")
                    // Held only while it starts and the queue goes out: the
                    // stack then stays up (D7). Until 2026-09-25 this hold was
                    // never given back.
                    StackRuntime.holding(appContext) { ready ->
                        if (!ready) {
                            Log.e(TAG, "sendDirect: acquire failed — message stays queued")
                            return@holding
                        }
                        // A stack that was already up sends no ready signal.
                        if (StackRuntime.isReady) flushPendingMessages()
                    }
                    return@launch
                }
                dispatch(localId, destHash, content, attachments, fromQueue = false)
            } catch (e: Exception) {
                Log.e(TAG, "sendDirect: exception: ${e.message}", e)
                messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            }
        }
    }

    /** The stack has finished starting and its handles are live. */
    private fun stackReady(): Boolean =
        StackRuntime.isReady && routerHandle != 0L && identityHandle != 0L

    /**
     * Send the 1:1 message [localId], just written by [sendDirectMessage] or
     * taken from the queue by [flushPendingMessages] ([fromQueue]). One path
     * for both: a distro recipient gets the PROPAGATED send; anyone else the
     * DIRECT attempt, with the propagated copy behind it started by the
     * router's reports ([PropagationFallbacks]). Returns once the message is
     * out; its state is followed on a coroutine of its own, so a flush waits
     * for the dispatches and not for every queued message's delivery.
     */
    private suspend fun dispatch(
        localId: String,
        destHash: ByteArray,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
        fromQueue: Boolean,
    ) {
        // A distro address has no device behind it to prove a direct
        // link; RFed fans the message out from the propagation node
        // (Retichat-js: propagationDelay 0 and no _sendPacket for
        // isDistro contacts). The address says so in its announce
        // (RFed SPEC §17.10); the preference is what past announces taught us.
        if (RetichatBridge.peerIsDistro(destHash) ||
            UserPreferences.isDistroContact(appContext, destHash.toHex())
        ) {
            Log.i(TAG, "dispatch: ${destHash.toHex().take(16)} is a distro address — propagating")
            val (sendSrc, sendIdentity) = DistroManager.sendingIdentity(selfDestHash, identityHandle)
            sendPropagatedCopy(
                localId,
                directHandle = 0L,
                createCopy = {
                    RetichatBridge.messageCreate(
                        destHash = destHash,
                        srcHash = sendSrc,
                        content = content,
                        method = RetichatBridge.DeliveryMethod.PROPAGATED,
                        identityHandle = sendIdentity,
                    ).also { handle ->
                        if (handle != 0L) {
                            attachments.forEach { (name, data) ->
                                RetichatBridge.messageAddAttachment(handle, name, data)
                            }
                        }
                    }
                },
                // No DIRECT attempt here, so the propagated send is this
                // message's only dispatch and carries the §17.11 sent-copy.
                onSent = { sendDistroSentCopy(destHash, sendSrc, title = "", content = content) },
            )
            return
        }

        // Register the AppLinks spec synchronously on this IO thread
        // BEFORE messageSendViaAppLinks triggers process_outbound.
        // openConversation() is fire-and-forget via a separate coroutine
        // scope and does NOT guarantee the spec is registered before the
        // POB loop checks AppLinks::contains().  If contains() is false
        // and Transport::has_path() is also false (fresh install, expired
        // cache), the legacy DIRECT path only requests a path without
        // creating a link — no LINKREQUEST is ever sent.  The synchronous
        // call below closes that race.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        RetichatBridge.appLinkOpen(routerHandle, destHash, "lxmf", "delivery")
        Log.d(TAG, "dispatch: appLinkOpen OK, submitting DIRECT")

        // Send as the distro identity when this device holds one
        // (Retichat-js sendingIdentity()): replies then reach every device.
        val (sendSrc, sendIdentity) = DistroManager.sendingIdentity(selfDestHash, identityHandle)
        val msgHandle = RetichatBridge.messageCreate(
            destHash = destHash,
            srcHash = sendSrc,
            content = content,
            method = RetichatBridge.DeliveryMethod.DIRECT,
            identityHandle = sendIdentity,
        )
        if (msgHandle == 0L) {
            Log.e(TAG, "dispatch: messageCreate FAILED for $localId: ${RetichatBridge.lastError()}")
            messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            return
        }
        Log.d(TAG, "dispatch: messageCreate OK handle=$msgHandle")

        attachments.forEach { (name, data) ->
            RetichatBridge.messageAddAttachment(msgHandle, name, data)
        }

        // Take a queued row before it goes out: the handle is recorded only
        // if the row is still queued, and from then on it is out of
        // pendingOutbound(), so no later flush sends it or its §17.11
        // copy again, even if this send throws below.
        if (fromQueue && messageDao.claimPendingOutbound(localId, msgHandle) == 0) {
            Log.w(TAG, "dispatch: queued msg $localId already taken — not sent again")
            RetichatBridge.messageDestroy(msgHandle)
            return
        }

        val sent = RetichatBridge.messageSendViaAppLinks(msgHandle)
        Log.d(TAG, "dispatch: messageSendViaAppLinks result=$sent")
        if (!sent) {
            Log.e(TAG, "dispatch: messageSendViaAppLinks FAILED for $localId: ${RetichatBridge.lastError()}")
            messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
            return
        }
        val hashHex = RetichatBridge.messageGetHash(msgHandle)?.toHex()
        hashHex?.let { deliveryTargets.remember(it, DeliveryTargets.Target.Row(localId)) }

        // RFed SPEC §17.11: the message is out, so tell our other
        // devices. Sent here, where the DIRECT send is accepted, and
        // never from the propagated copy behind it: DIRECT plus
        // fallback is still one message and gets one copy.
        sendDistroSentCopy(destHash, sendSrc, title = "", content = content)

        val state = RetichatBridge.messageGetState(msgHandle)
        Log.d(TAG, "dispatch: post-send state=$state")

        // Point the bubble at the DIRECT handle before the fallback is
        // tracked: from then on a propagated copy can take the bubble over,
        // and this write must not undo that.
        messageDao.updateHandle(localId, msgHandle)
        if (hashHex == null) {
            // Nothing matches the router's reports to this send, so no
            // propagated copy is behind it and the bubble shows its outcome.
            Log.w(TAG, "dispatch: no hash for $localId — no propagated fallback")
            messageDao.updateState(localId, state)
        } else {
            // A DIRECT failure is not the bubble's outcome: the propagated
            // copy is (see pollMessageState).
            if (!isFailureState(state)) messageDao.updateState(localId, state)
            propagationFallbacks.track(
                hashHex, PropagationFallbacks.Send(localId, directHandle = msgHandle),
            )?.let(::startPropagatedCopy)
            if (isFailureState(state)) {
                propagationFallbacks.onPolledState(hashHex, state)?.let(::startPropagatedCopy)
                return
            }
        }

        // 3) Poll until the native message reaches a terminal state
        //    (SENT, DELIVERED, FAILED, REJECTED, CANCELLED).
        //    The proof arrives async via the link; we need to notice it.
        if (!isTerminalState(state)) {
            scope.launch(Dispatchers.IO) {
                try {
                    pollMessageState(localId, msgHandle, directHashHex = hashHex)
                } catch (e: Exception) {
                    Log.e(TAG, "dispatch: state poll failed $localId: ${e.message}")
                    failUnlessSucceeded(localId)
                }
            }
        }
    }

    /** Terminal states that won't change further. */
    private fun isTerminalState(state: Int): Boolean = state in intArrayOf(
        RetichatBridge.MessageState.SENT,
        RetichatBridge.MessageState.DELIVERED,
        RetichatBridge.MessageState.FAILED,
        RetichatBridge.MessageState.REJECTED,
        RetichatBridge.MessageState.CANCELLED,
    )

    /**
     * Success states (SENT=4, DELIVERED=8) are sticky — a direct delivery win
     * must never be overwritten by a concurrent propagation failure or any
     * other non-success state.  DELIVERED can upgrade SENT but nothing else
     * can downgrade it.
     */
    private fun isSuccessState(state: Int) =
        state == RetichatBridge.MessageState.SENT ||
        state == RetichatBridge.MessageState.DELIVERED

    /** Terminal failure states: FAILED, REJECTED, CANCELLED. */
    private fun isFailureState(state: Int) =
        state == RetichatBridge.MessageState.FAILED ||
        state == RetichatBridge.MessageState.REJECTED ||
        state == RetichatBridge.MessageState.CANCELLED

    /** Show [localId] failed, unless it already succeeded (success is sticky). */
    private suspend fun failUnlessSucceeded(localId: String) {
        messageDao.updateStateUnlessSucceeded(localId, RetichatBridge.MessageState.FAILED)
    }

    /**
     * Poll the native message handle until it reaches a terminal state.
     * Uses exponential back-off: 200ms, 300ms, 450ms, … capped at 5s.
     *
     * The poll marks the send FAILED only once it has gone [quietMs] without
     * its progress rising ([SendPollDeadline]): every rise starts the window
     * again, so a transfer that keeps moving is never failed here however
     * long it takes. [quietMs] is [SendPollDeadline.DIRECT_QUIET_MS] (60 s)
     * for a DIRECT send and [SendPollDeadline.PROPAGATED_QUIET_MS] (600 s)
     * for a PROPAGATED one, until the payload moves; then
     * [SendPollDeadline.TRANSFER_QUIET_MS] (600 s). Until 2026-09-29 the 60 s
     * ran from the poll's start unless progress passed 0.05, which the core
     * never reported on the AppLinks path.
     *
     * [directHashHex] is set when [msgHandle] is a DIRECT send with the
     * propagated fallback behind it. Its failure starts the propagated copy,
     * which is then the bubble's outcome, unless the router's report started
     * it first ([PropagationFallbacks]). A failure with no copy holding the
     * bubble is written: a copy that swaps in later replaces it.
     *
     * [directHandle] is set when [msgHandle] is that propagated copy. Both
     * attempts share one hash, so the copy's failure is not the message's
     * while the DIRECT attempt is still in flight: the bubble goes back to it
     * ([PropagationFallbacks.directKeepsTheBubble]).
     */
    private suspend fun pollMessageState(
        localId: String,
        msgHandle: Long,
        quietMs: Long = SendPollDeadline.DIRECT_QUIET_MS,
        directHashHex: String? = null,
        directHandle: Long = 0L,
    ) {
        var interval = 200L          // start at 200ms for snappy LAN feedback
        val maxInterval = 5_000L     // cap at 5s
        // Counts only time without progress (see SendPollDeadline).
        val deadline = SendPollDeadline(quietMs, startMs = System.currentTimeMillis())

        while (!deadline.expired(System.currentTimeMillis())) {
            delay(interval)

            // If sendPropagatedCopy has taken over this message
            // (replaced the DB handle), this poll is stale. Exit without
            // touching state so the new poll is the sole owner.
            // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
            val dbHandle = messageDao.findById(localId)?.nativeHandle ?: 0L
            if (dbHandle != msgHandle) {
                Log.d(TAG, "pollState: handle changed for $localId ($msgHandle→$dbHandle) — exiting stale poll")
                return
            }

            val newState = RetichatBridge.messageGetState(msgHandle)
            val progress = RetichatBridge.messageGetProgress(msgHandle)

            // A rise in progress starts the quiet window again: a transfer
            // that is moving is not stuck.
            deadline.onProgress(progress, System.currentTimeMillis())

            Log.d(TAG, "pollState: id=$localId handle=$msgHandle state=$newState progress=$progress")

            // Never let a concurrent propagation FAILED (or any non-success state)
            // overwrite a direct SENT/DELIVERED that already landed in the DB.
            // DELIVERED may upgrade SENT; nothing else may downgrade success.
            val dbState = messageDao.findById(localId)?.state ?: 0
            if (dbState == RetichatBridge.MessageState.DELIVERED) {
                // Nothing outranks delivery. The router's DELIVERED report
                // (onMessageState) can upgrade the row while this poll
                // follows the propagated copy, whose SENT must not undo it.
                Log.d(TAG, "pollState: $localId already DELIVERED — stopping poll of handle=$msgHandle")
                return
            }
            if (directHashHex != null && isFailureState(newState)) {
                val copy = propagationFallbacks.onPolledState(directHashHex, newState)
                if (copy != null) {
                    Log.i(TAG, "pollState: DIRECT $localId ended in state=$newState — propagated copy takes over")
                    startPropagatedCopy(copy)
                } else if (messageDao.updateStateOnHandleUnlessSucceeded(localId, msgHandle, newState) == 1) {
                    // The copy started earlier (or never will: untracked) and
                    // does not hold the bubble. One that could not go out left
                    // the bubble to this attempt (propagatedCopyNotSent); one
                    // still on its way replaces this when it takes over.
                    Log.i(TAG, "pollState: DIRECT $localId ended in state=$newState, no copy holds the bubble")
                }
                return
            }
            if (directHandle != 0L && isFailureState(newState)) {
                propagatedCopyFailed(localId, directHandle, newState)
                return
            }
            if (isSuccessState(dbState)) {
                if (newState == RetichatBridge.MessageState.DELIVERED &&
                    dbState == RetichatBridge.MessageState.SENT) {
                    // DELIVERED upgrades SENT — allow it
                    messageDao.updateStateAndProgress(localId, newState, progress)
                } else if (!isSuccessState(newState)) {
                    // Non-success (e.g. prop FAILED) must not overwrite direct success
                    Log.d(TAG, "pollState: $localId DB=$dbState wins over handle=$msgHandle state=$newState — stopping")
                    return
                } else {
                    messageDao.updateStateAndProgress(localId, newState, progress)
                }
            } else {
                messageDao.updateStateAndProgress(localId, newState, progress)
            }

            if (isTerminalState(newState)) {
                Log.d(TAG, "pollState: terminal state $newState for $localId")
                return
            }
            // Gentle backoff: ×1.5 keeps checks frequent for the first few seconds
            interval = (interval * 3 / 2).coerceAtMost(maxInterval)
        }
        // The send went its whole quiet window without progress — mark it
        // failed so the user isn't left in limbo, unless the row succeeded
        // meanwhile by another report.
        if (isSuccessState(messageDao.findById(localId)?.state ?: 0)) return
        Log.w(TAG, "pollState: $localId made no progress for ${deadline.windowMs() / 1000} s, marking FAILED")
        messageDao.updateState(localId, RetichatBridge.MessageState.FAILED)
    }

    /**
     * Start [send]'s propagated copy on IO; see [PropagationFallbacks]. Not on
     * the router's callback thread: the clone locks the DIRECT message, which
     * the router holds while it reports.
     */
    private fun startPropagatedCopy(send: PropagationFallbacks.Send) {
        scope.launch(Dispatchers.IO) {
            sendPropagatedCopy(
                send.messageId,
                directHandle = send.directHandle,
                // The DIRECT message itself, sent PROPAGATED: its fields,
                // attachments and packed timestamp, so its LXMF hash, and the
                // recipient drops whichever of the two arrives second. Until
                // 2026-09-24 the copy was a new message with a new timestamp,
                // and a recipient given both showed the message twice.
                createCopy = { RetichatBridge.messageClonePropagated(send.directHandle) },
            )
        }
    }

    /**
     * Send [localId] PROPAGATED through the user-configured (or
     * randomly-chosen) LXMF propagation node, and point the bubble at it.
     * Mirrors iOS `ChatRepository.retrySendViaPropNode`.
     *
     * Either a distro recipient's only send ([directHandle] 0), or the fallback
     * behind the DIRECT attempt [directHandle], started when the router asked
     * for it (0x10; AppLinks Timer P, which is 0 s when the link status is
     * already DISCONNECTED) or when the DIRECT attempt failed first
     * ([PropagationFallbacks]). The DIRECT attempt keeps running beside it.
     * Until 2026-09-24 Android waited 5 s on its own clock and then skipped
     * the copy when the message had already failed.
     *
     * [createCopy] makes the propagated message once the checks below pass: a
     * new message for a distro recipient, a clone of the DIRECT message for
     * the fallback. [onSent] runs once it is out; the distro send gives its
     * §17.11 sent-copy there. The DIRECT path sends its own, so a fallback
     * behind it must not send a second one.
     *
     * A copy that can not go out shows the message failed (iOS does the
     * same) once no DIRECT attempt is left ([propagatedCopyNotSent]); a DIRECT
     * success can still replace that.
     */
    private suspend fun sendPropagatedCopy(
        localId: String,
        directHandle: Long,
        createCopy: () -> Long,
        onSent: suspend () -> Unit = {},
    ) {
        try {
            val current = messageDao.findById(localId) ?: return
            // Delivered already (a late proof of the DIRECT attempt): a copy
            // would only be a duplicate for the recipient to drop.
            if (isSuccessState(current.state)) return

            if (!stackReady()) {
                Log.w(TAG, "propagated: stack not ready — $localId not sent")
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            val nodeHash = selectPropagationNode()
            if (nodeHash == null) {
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            Log.i(
                TAG,
                "propagated: sending $localId (state=${current.state}) " +
                    "via ${nodeHash.toHex().take(16)}"
            )

            val propHandle = createCopy()
            if (propHandle == 0L) {
                Log.e(TAG, "propagated: no message for $localId: ${RetichatBridge.lastError()}")
                propagatedCopyNotSent(localId, directHandle)
                return
            }

            val sent = RetichatBridge.messageSendViaAppLinks(propHandle)
            if (!sent) {
                Log.e(TAG, "propagated: messageSendViaAppLinks failed: ${RetichatBridge.lastError()}")
                RetichatBridge.messageDestroy(propHandle)
                propagatedCopyNotSent(localId, directHandle)
                return
            }
            // The copy's own late proof upgrades the same bubble.
            rememberDeliveryTarget(propHandle, DeliveryTargets.Target.Row(localId))
            onSent()

            // Re-point the bubble at the propagated handle and continue polling,
            // BUT only if the direct send hasn't already succeeded.  If direct
            // won while we were creating the prop copy, discard the prop handle
            // and leave the success state untouched. One conditional UPDATE:
            // a DELIVERED written between a read and a write would be lost.
            // Until 2026-09-24 a copy that was already SENT here was skipped
            // and the bubble stayed on the direct attempt's SENDING arrow; the
            // bubble now shows the copy's state, unless the copy has already
            // failed: then it never takes the bubble, as if it had not gone out.
            val newState = RetichatBridge.messageGetState(propHandle)
            if (isFailureState(newState)) {
                Log.w(TAG, "propagated: copy of $localId failed at once (state=$newState)")
                propagatedCopyNotSent(localId, directHandle)
                return
            }
            if (messageDao.takeOverUnlessSucceeded(localId, propHandle, newState) == 0) {
                Log.d(TAG, "propagated: direct already succeeded for $localId — discarding prop handle")
                RetichatBridge.messageDestroy(propHandle)
                return
            }
            if (!isTerminalState(newState)) {
                // Followed on its own coroutine: a flush that sent this (a
                // queued message to a distro recipient) must not wait for it.
                scope.launch(Dispatchers.IO) {
                    try {
                        // PROPAGATED delivery can legitimately take several minutes
                        // (the propagation node buffers and re-delivers to the
                        // recipient when they next announce).  Use the long deadline
                        // so the poll doesn't mark FAILED before Rust delivers it.
                        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                        pollMessageState(
                            localId, propHandle, quietMs = SendPollDeadline.PROPAGATED_QUIET_MS,
                            directHandle = directHandle,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "propagated: state poll failed $localId: ${e.message}")
                        failUnlessSucceeded(localId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "propagated: exception for $localId: ${e.message}", e)
            propagatedCopyNotSent(localId, directHandle)
        }
    }

    /**
     * [localId]'s propagated copy could not go out, so it never took the
     * bubble. The message has failed only if the DIRECT attempt [directHandle]
     * (0: a distro recipient's only send, there is none) has ended without
     * success. While it is in flight the bubble stays on it and its poll,
     * still running, writes the outcome ([PropagationFallbacks.directKeepsTheBubble]).
     * Until 2026-09-24 the bubble showed FAILED with the DIRECT attempt still
     * running.
     */
    private suspend fun propagatedCopyNotSent(localId: String, directHandle: Long) {
        if (directHandle != 0L &&
            PropagationFallbacks.directKeepsTheBubble(RetichatBridge.messageGetState(directHandle))
        ) {
            Log.i(TAG, "propagated: copy of $localId not sent — the DIRECT attempt keeps the bubble")
            return
        }
        failUnlessSucceeded(localId)
    }

    /**
     * The propagated copy of [localId], which held the bubble, ended in
     * [copyState]. Both attempts share one hash, so this is the message's
     * failure only if the DIRECT attempt [directHandle] has ended without
     * success too. Otherwise the bubble goes back to it (one conditional
     * UPDATE: never over a success) and a plain poll follows it, whose failure
     * is written: its own poll left when the copy took the bubble over.
     * Until 2026-09-24 the copy's failure was shown while the DIRECT attempt
     * was still running.
     */
    private suspend fun propagatedCopyFailed(localId: String, directHandle: Long, copyState: Int) {
        val directState = RetichatBridge.messageGetState(directHandle)
        if (!PropagationFallbacks.directKeepsTheBubble(directState)) {
            Log.i(TAG, "pollState: propagated copy of $localId ended in state=$copyState, DIRECT in state=$directState — failed")
            messageDao.updateStateUnlessSucceeded(localId, copyState)
            return
        }
        if (messageDao.takeOverUnlessSucceeded(localId, directHandle, directState) == 0) return
        Log.i(TAG, "pollState: propagated copy of $localId ended in state=$copyState — back to the DIRECT attempt (state=$directState)")
        pollMessageState(localId, directHandle)
    }

    /**
     * Point the router at the propagation node and return it, or null when
     * the router refused it (logged). Shared by the propagated fallback and
     * the §17.11 sent-copy, which both go out PROPAGATED.
     */
    private fun selectPropagationNode(): ByteArray? {
        // Pick the propagation node deterministically.  Priority:
        //   1. explicit user override (Settings)
        //   2. legacy `lxmf_propagation_hash` pref
        //   3. derived `lxmf.propagation` destination of the configured
        //      RFed node identity (mirrors the RFed config blob's
        //      `destinations.lxmf.propagation` value)
        //   4. random pick from PropagationNodeManager's bundled list
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1: a random
        // node almost always has no path on a fresh install, so the
        // first PROPAGATED send hangs at state=1 until §1 fires.  The
        // RFed-derived hash is the only one we know is reachable
        // because we already have a path to its rfed.notify aspect.
        val derivedFromRfed: String = run {
            val rfedId = UserPreferences.getEffectiveRfedNodeIdentityHash(appContext)
            if (rfedId.length == 32) {
                com.newendian.retichat.service.FcmTokenRegistrar
                    .rnsDestHash(rfedId, "lxmf", listOf("propagation"))
                    .orEmpty()
            } else ""
        }
        val override = UserPreferences.getRfedLxmfPropOverride(appContext)
            .ifEmpty { UserPreferences.getLxmfPropagationHash(appContext) }
            .ifEmpty { derivedFromRfed }
        val nodeMgr = PropagationNodeManager(
            userConfiguredHash = override.ifEmpty { null }
        )
        val nodeHash = nodeMgr.primaryNode

        if (!RetichatBridge.routerSetPropagationNode(routerHandle, nodeHash)) {
            Log.w(TAG, "setPropagationNode failed: ${RetichatBridge.lastError()}")
            return null
        }
        return nodeHash
    }

    /**
     * RFed SPEC §17.11 "Sent-message sync": after the user's message to
     * [recipient] went out as the distro ([sentAs] is the source it actually
     * used), owe the distro one copy so every sibling device can file it as
     * sent. Destination and source are the distro, signed with its key; text
     * only (attachments are not copied); 0xFB/0xFC/0xFD carry the marker, the
     * recipient and this device's own address, so this device recognises and
     * drops its own echo of the fan-out. PROPAGATED, because only RFed answers
     * for the distro address. iOS counterpart: ChatRepository.swift.
     *
     * The copy is packed and signed once, here, and kept in the distro outbox
     * until the propagation node proves it ([DistroOutboxRuntime.oweSentCopy],
     * "The phones' outbox"): uploaded at once when the propagation link is up,
     * otherwise when it comes up, and again when it next comes up after an
     * upload that was not proved (James's decided exception, 2026-10-03). Until
     * then it handed the copy to the LXMF router once and kept nothing, so one
     * whose upload was not proved, or that was made while no propagation link
     * could be had, was lost. No bubble, and the copy never touches the user's
     * message state.
     */
    private suspend fun sendDistroSentCopy(recipient: ByteArray, sentAs: ByteArray, title: String, content: String) {
        val recipientHex = recipient.toHex()
        if (!DistroCodec.shouldSendSentCopy(sentAs.toHex(), DistroManager.deliveryHashHex, recipientHex)) return
        DistroOutboxRuntime.oweSentCopy(appContext, recipient, title, content)
    }

    /**
     * A post in group [chat], as the group's own address resolved now
     * ([groupSigner], GROUPS-DISTRO-DESIGN.md §8.1): refused, with nothing
     * stored, sent or queued, when it resolves to nothing (a D-group whose D
     * this device no longer holds, or has not loaded yet: §8.7, the composer is
     * disabled there). The targets are the accepted members without any self
     * address (§9.2); a member that is a distro gets no app link (§8.4). In a
     * D-group one `post` group sync is owed per post (§10.6), once the router
     * has accepted the first member envelope, or when the post is stored if no
     * member besides the user has accepted; never for a send that throws.
     *
     * A refusal is returned ([GroupPostRefused]): the composer was enabled when
     * the user tapped, and the stack stopped or D went since, so the screen
     * puts the draft back and says why (alignment decision 5, 2026-10-11).
     */
    private suspend fun sendGroupMessage(
        chat: ChatEntity,
        content: String,
        attachments: List<Pair<String, ByteArray>>,
    ): GroupPostRefused? {
        val groupIdHex = chat.groupIdHex ?: return null
        val signer = groupSigner(chat) ?: run {
            Log.w(TAG, "sendGroupMessage: ${chat.id} refused: its own address ${chat.ownMember?.take(8)} has no signer on this device (${signerKind(chat)}, §8.7) — nothing stored or sent; the draft goes back")
            return GroupPostRefused(signerKind(chat), GroupSelves.ownMember(chat.ownMember, selfHex()))
        }
        val selfHex = signer.hex
        val members = messageDao.groupMembersList(chat.id)
        val self = groupSelf(chat, members)
        val memberHexes = GroupMemberStatuses.acceptedMemberHexes(members = members, selfHex = selfHex)
            .filter { !self.isSelf(it) }
        val postedAt = System.currentTimeMillis()
        // §10.6: the post's group sync, in a D-group only, owed once.
        val postSync: GroupSync? = if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {
            GroupSync(DistroGroupSync.POST, groupIdHex, postedAt, text = content, left = UserPreferences.groupsLeftBy(appContext, signer.hex))
        } else null
        val postSyncOwed = java.util.concurrent.atomic.AtomicBoolean(postSync == null)

        // Create the outbound message record once (shared ID for the group msg)
        val groupMsgId = "grp_${System.currentTimeMillis()}_${(0..999).random()}"

        messageDao.upsert(
            MessageEntity(
                id = groupMsgId,
                chatId = chat.id,
                senderHashHex = selfHex,
                content = content,
                timestamp = postedAt,
                isOutbound = true,
                state = RetichatBridge.MessageState.SENDING,
            )
        )
        // Nothing to submit: no member besides the user has accepted. The post's sync is owed now.
        if (memberHexes.isEmpty() && postSync != null && postSyncOwed.compareAndSet(false, true)) {
            DistroOutboxRuntime.oweGroupSync(appContext, signer.hex, postSync)
        }

        // Save outbound attachments to disk + DB so they display in the bubble
        if (attachments.isNotEmpty()) {
            saveOutboundAttachments(groupMsgId, attachments)
        }

        // Track delivery per member
        memberHexes.forEach { memberHex ->
            messageDao.upsertTracking(
                DeliveryTrackingEntity(
                    messageId = groupMsgId,
                    chatId = chat.id,
                    memberHashHex = memberHex,
                )
            )
        }

        // Fanout: send individually to each member with group metadata fields
        var allSent = true
        memberHexes.forEach { memberHex ->
            scope.launch(Dispatchers.IO) {
                val destHash = memberHex.hexToBytes()
                // Register the AppLinks spec synchronously before send so
                // the POB loop takes the AppLinks-owned DIRECT path; never for
                // a member that is a distro, which the router propagates to at
                // once (GROUPS-DISTRO-DESIGN.md §8.4).
                if (!DistroAddresses.isDistro(appContext, destHash)) {
                    RetichatBridge.appLinkOpen(routerHandle, destHash, "lxmf", "delivery")
                }

                val handle = RetichatBridge.messageCreate(
                    destHash = destHash,
                    srcHash = signer.hash,
                    content = content,
                    method = RetichatBridge.DeliveryMethod.DIRECT,
                    identityHandle = signer.identity,
                )
                if (handle != 0L) {
                    // Attach group metadata
                    GroupFields.set(handle, GroupEntry.ID, groupIdHex)
                    GroupFields.set(handle, GroupEntry.SENDER, selfHex)

                    attachments.forEach { (name, data) ->
                        RetichatBridge.messageAddAttachment(handle, name, data)
                    }

                    val sent = RetichatBridge.messageSendViaAppLinks(handle)
                    if (sent) {
                        // The router accepted the first member envelope: the post left, so its sync is owed, once.
                        if (postSync != null && postSyncOwed.compareAndSet(false, true)) {
                            DistroOutboxRuntime.oweGroupSync(appContext, signer.hex, postSync)
                        }
                        rememberDeliveryTarget(handle, DeliveryTargets.Target.GroupMember(groupMsgId, chat.id, memberHex))
                        // Poll for delivery proof per-member
                        pollGroupMemberDelivery(groupMsgId, chat.id, memberHex, handle)
                    } else {
                        Log.e(TAG, "sendGroupMessage: failed to send to $memberHex")
                    }
                }
            }
        }

        // Optimistically mark as SENT (individual proofs update tracking)
        messageDao.updateState(groupMsgId, RetichatBridge.MessageState.SENT)
        return null
    }

    /**
     * Poll a single group-member send until delivered or timed out.
     * When delivered, update tracking and check if all members are done.
     */
    private suspend fun pollGroupMemberDelivery(
        groupMsgId: String, chatId: String, memberHex: String, handle: Long,
    ) {
        var interval = 200L
        val maxInterval = 5_000L
        val deadline = System.currentTimeMillis() + 60_000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val state = RetichatBridge.messageGetState(handle)
            if (isTerminalState(state)) {
                if (state == RetichatBridge.MessageState.DELIVERED) {
                    recordGroupMemberDelivered(groupMsgId, chatId, memberHex)
                }
                return
            }
            interval = (interval * 3 / 2).coerceAtMost(maxInterval)
        }
    }

    private suspend fun recordGroupMemberDelivered(groupMsgId: String, chatId: String, memberHex: String) {
        messageDao.upsertTracking(
            DeliveryTrackingEntity(
                messageId = groupMsgId,
                chatId = chatId,
                memberHashHex = memberHex,
                delivered = true,
                deliveredAt = System.currentTimeMillis(),
            )
        )
        // Check if ALL members delivered
        val undelivered = messageDao.undeliveredFor(groupMsgId)
        if (undelivered.isEmpty()) {
            messageDao.updateState(groupMsgId, RetichatBridge.MessageState.DELIVERED)
            Log.i(TAG, "Group msg $groupMsgId: all members delivered")
        }
    }

    /**
     * Called from the Rust delivery callback (background thread): direct,
     * opportunistic, propagated and stream-ingested messages all arrive here.
     * [unverifiedReason] is 0 validated, 1 source unknown, 2 invalid; it
     * decides whether the message's 0xD1 name is accepted (§5.2). An accept or
     * leave carries its sender's own key: [senderKey] is that key when it binds
     * to the source (a source nobody held a key for is then checked under it, so
     * [unverifiedReason] is 0 or 2 for it), and [packed] is the message exactly
     * as received, which a creator keeps (RFed-spec Group.md, "Member statuses
     * and keys"). Both are null for any other message.
     */
    fun onMessageReceived(
        hash: ByteArray,
        srcHash: ByteArray,
        destHash: ByteArray,
        title: String,
        content: String,
        timestamp: Double,
        signatureValid: Boolean,
        unverifiedReason: Int,
        fieldsRaw: ByteArray = ByteArray(0),
        senderKey: ByteArray? = null,
        packed: ByteArray? = null,
    ) {
        val received = LxmfFields.receive(fieldsRaw, onFailure = {
            Log.w(TAG, "onMessageReceived: fields from ${srcHash.toHex().take(8)} unreadable (${it::class.java.simpleName}: ${it.message}) — storing the message without them")
        })
        // What it is (DeliveryPolicy.directRoute). A message whose payload repeats a key
        // is refused by the router whole, with its one log line, and never gets here
        // (RFed SPEC §17.14 "Repeated keys"); one that does all the same (a native
        // library built before LXMF-rust 16a9902) is refused here, never a DM.
        val route = DeliveryPolicy.directRoute(received)
        if (route is DeliveryPolicy.DirectRoute.Refused) {
            Log.w(TAG, "onMessageReceived: REFUSED the message from ${srcHash.toHex().take(8)} whole: ${route.why} (RFed SPEC §17.14 \"Repeated keys\")")
            return
        }
        val fields = (received as? LxmfFields.Received.Read)?.fields ?: return
        // Field 0xD1, decoded by the one Rust decoder (bin or str; §3).
        val nameField = NameField.fromTrailer(RetichatBridge.displayNameDecode(fieldsRaw))
        val groupId = (route as? DeliveryPolicy.DirectRoute.Group)?.groupId
        Log.i(TAG, "onMessageReceived: src=${srcHash.toHex().take(16)}, groupId=$groupId, content='${content.take(40)}'")
        when (route) {
            // RFed SPEC §17.11: a distro sent copy reaches this device only through
            // the distro fan-out (RfedDistroClient), never on its own address.
            // Dropped (after a malformed GROUP_ID is refused, §17.14), whoever sent
            // it and filter on or off, as iOS and the web drop it: kept, it would show the user's own message as someone's
            // incoming one. Until 2026-10-05 it went on as a DM from the distro.
            DeliveryPolicy.DirectRoute.SentCopy -> {
                Log.i(TAG, "onMessageReceived: DROPPED distro sent-copy marker outside fan-out src=${srcHash.toHex().take(8)}")
                return
            }
            // RFed SPEC §17.14, "On the direct path too": a GROUP_ID of any type in either
            // form makes it a group message whatever its 0xFB, and one that is not 32 hex is
            // refused here, never a DM or a group. Until 2026-10-11 a bin id went on as a DM.
            DeliveryPolicy.DirectRoute.BadGroupId -> {
                Log.i(TAG, "onMessageReceived: REFUSED group message from ${srcHash.toHex().take(8)}: its GROUP_ID is not a str of 32 hex characters (RFed SPEC §17.14)")
                return
            }
            else -> Unit
        }
        // A distro identity transfer from another of our devices (RFed SPEC §17.9):
        // LXMF's custom pair with our type string; not a message to display.
        // Offered below only when the privacy filter lets its sender through.
        val transferKey = (route as? DeliveryPolicy.DirectRoute.Transfer)?.key
        val signature = Signature.reason(signatureValid, unverifiedReason)
        scope.launch(Dispatchers.IO) {
            val srcHex = srcHash.toHex()
            val msgId = hash.toHex()

            // A transfer is offered only from a sender the privacy filter lets
            // through, as the web offers it (James, 2026-10-05), whatever else
            // it carries; it is never a message. Until 2026-10-05 it was offered
            // from anyone.
            if (transferKey != null) {
                if (allowlisted(srcHex)) {
                    RfedDistroClient.offerTransfer(srcHex, transferKey)
                } else {
                    Log.i(TAG, "onMessageReceived: DROPPED distro identity transfer from ${srcHex.take(8)}: the privacy filter does not let it through")
                }
                return@launch
            }

            // The privacy filter and the group rule (DeliveryPolicy), before
            // any write, so a stranger leaves nothing behind. The source is
            // the packet's own, never GROUP_SENDER.
            var standing: GroupStanding? = null
            if (groupId != null) {
                val action = fields.group(GroupEntry.ACTION)
                val here = groupStanding(groupId, srcHex).also { standing = it }
                // GROUPS-DISTRO-DESIGN.md §6.3: the three first rules, on this path too.
                DeliveryPolicy.firstRules(signature, srcHex, fields.group(GroupEntry.SENDER), here.selves, here.echoes)?.let { why ->
                    Log.i(TAG, "onMessageReceived: DROPPED group ${action ?: "message"} for ${groupId.take(8)} from ${srcHex.take(8)}: $why")
                    return@launch
                }
                val allowed = DeliveryPolicy.groupMessage(
                    action = action,
                    sourceAllowed = action == GroupChatManager.Action.INVITE && allowlisted(srcHex),
                    held = here.held,
                    sourceStatus = here.sourceStatus,
                    namesOther = DeliveryPolicy.namesOther(fields.group(GroupEntry.SENDER), srcHex),
                    closed = here.closed,
                    signature = signature,
                    sourceIsCreator = here.sourceIsCreator,
                )
                if (!allowed) {
                    Log.i(TAG, "onMessageReceived: DROPPED group ${action ?: "message"} for ${groupId.take(8)} from ${srcHex.take(8)} (group ${here.held}, source ${here.sourceStatus ?: "not listed"}, closed=${here.closed}, signature=$signature)")
                    return@launch
                }
            } else if (!allowlisted(srcHex)) {
                Log.i(TAG, "onMessageReceived: DROPPED by the privacy filter src=${srcHex.take(8)}")
                return@launch
            }

            // 0xD1 always names the LXMF source, group messages and relayed
            // copies included (there it names the relayer). A hidden row:
            // nobody becomes a contact by sending (ContactSql). Never for a
            // self address (GROUPS-DISTRO-DESIGN.md §9.2).
            val group = standing
            if (group == null || !group.isSelf(srcHex)) {
                ensureContact(srcHex)
                acceptMessageName(srcHex, nameField, unverifiedReason, timestamp)
            }

            if (groupId != null && group != null) {
                handleGroupMessage(msgId, groupId, srcHex, content, timestamp, fields, group, signature, senderKey, packed, viaDistro = false)
            } else {
                handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, fields)
            }
        }
    }

    /**
     * A message that reached us through the distro fan-out (already decrypted
     * with the distro key by RfedDistroClient). The fan-out never hands over
     * the LXMF hash, so the id is derived from source, timestamp and content;
     * RfedDistroClient has already deduplicated on source+timestamp.
     * [fieldsRaw] is the message's LXMF fields map as msgpack (the unwrap
     * JSON's `fields`, DistroCodec.fieldsBytes), null when it had none or it
     * was unreadable; it is decoded as [onMessageReceived] decodes a direct
     * message's, so the attachments are saved the same way. Required, with
     * no default: until 2026-09-29 this path passed no fields and every
     * attachment sent to a distro address was dropped.
     */
    fun onDistroMessageReceived(
        srcHash: ByteArray,
        title: String,
        content: String,
        timestamp: Double,
        fieldsRaw: ByteArray?,
        nameField: NameField = NameField.Absent,
        unverifiedReason: Int = Signature.INVALID,
    ) {
        val srcHex = srcHash.toHex()
        val msgId = DistroCodec.messageId(srcHex, timestamp, content)
        val fields = when (val received = LxmfFields.receive(fieldsRaw, onFailure = {
            Log.w(TAG, "onDistroMessageReceived: fields from ${srcHex.take(8)} unreadable (${it::class.java.simpleName}: ${it.message}) — storing the message without its attachments")
        })) {
            // The distro unwrap refuses it first (RFed SPEC §17.14 "Repeated keys"); here
            // only from a native library built before LXMF-rust 16a9902. Never a DM.
            is LxmfFields.Received.Refused -> {
                Log.w(TAG, "onDistroMessageReceived: REFUSED the message from ${srcHex.take(8)} whole: ${received.why} (RFed SPEC §17.14 \"Repeated keys\")")
                return
            }
            is LxmfFields.Received.Read -> received.fields
        }
        Log.i(TAG, "onDistroMessageReceived: src=${srcHex.take(16)} via=distro content='${content.take(40)}' fields=${fieldsRaw?.let { "${it.size}B" } ?: "none"} attachments=${fields.getFileAttachments().size}")
        // No privacy filter: mail to the distro is mail to this person (iOS
        // storeIncomingDirect via distro fan-out does not filter either).
        scope.launch(Dispatchers.IO) {
            ensureContact(srcHex)
            acceptMessageName(srcHex, nameField, unverifiedReason, timestamp)
            handleDirectMessage(msgId, srcHash, srcHex, content, timestamp, fields)
        }
    }

    /**
     * RFed SPEC §17.11: a message another of our devices sent as the distro
     * to [recipientHex], reaching us as the distro's sent-copy (RfedDistroClient
     * has already unwrapped it, deduplicated on source+timestamp, dropped our
     * own echo and checked the recipient). Filed as OUR outgoing message in
     * the direct chat with the recipient, the way iOS ChatRepository.swift
     * files it: sender is the distro (it is "me"), state SENT and never
     * DELIVERED (only the sending device could learn that), and the id is
     * derived like any distro fan-out message (DistroCodec.sentCopyMessageId)
     * so a copy that arrives both live and via /rfed/pull is stored once. No
     * notification is posted: the user wrote this message. The recipient
     * gets a plain contact row (not allowlisted) with its announces watched,
     * as iOS `handleDistroSentCopy` does, so the chat's title can pick up the
     * recipient's names (DISPLAY_NAMES.md §5.1).
     */
    fun onDistroSentCopy(recipientHex: String, title: String, content: String, timestamp: Double) {
        val distroHex = DistroManager.deliveryHashHex ?: run {
            Log.w(TAG, "onDistroSentCopy: no distro loaded — dropped copy for ${recipientHex.take(8)}")
            return
        }
        val recipient = DistroCodec.hexToBytes(recipientHex) ?: return
        val msgId = DistroCodec.sentCopyMessageId(distroHex, timestamp, content)
        Log.i(TAG, "onDistroSentCopy: to=${recipientHex.take(16)} content='${content.take(40)}'")
        scope.launch(Dispatchers.IO) {
            if (messageDao.findById(msgId) != null) {
                Log.d(TAG, "onDistroSentCopy: dup msgId=${msgId.take(16)}, skipping")
                return@launch
            }
            ensureContact(recipientHex)
            val chatId = directChatId(recipient)
            val existingChat = chatDao.findById(chatId)
            if (existingChat == null) {
                // A DM's title is resolved from the contact when shown.
                chatDao.upsert(
                    ChatEntity(
                        id = chatId,
                        isGroup = false,
                        name = "",
                        memberHashes = recipientHex,
                    )
                )
            } else if (existingChat.isArchived) {
                chatDao.unarchiveChat(chatId)
            }
            messageDao.upsert(
                MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderHashHex = distroHex,
                    // An attachment-only message copies an empty text: shown with the placeholder (§18.2 item 10).
                    content = DistroCodec.copiedText(content),
                    timestamp = (timestamp * 1000).toLong(),
                    isOutbound = true,
                    state = RetichatBridge.MessageState.SENT,
                )
            )
        }
    }

    // ---- Groups through the distro (GROUPS-DISTRO-DESIGN.md §6, §11) ----

    /** What became of a group message or group sync that reached this device through the distro. */
    enum class DistroGroupOutcome {
        /** Applied or stored: its seen key is recorded. */
        KEPT,
        /** Held until its group is joined here (§6.5): not recorded, so its replay is judged as a new arrival. */
        HELD,
        /** Dropped, with a log line: not recorded, so a later copy is judged again. */
        DROPPED,
    }

    /**
     * A group message (§6.1 step 2) from [srcHex] that reached this device
     * through the distro D: [blob] as the fan-out carried it (kept if the
     * message is held), [keys] the unwrap's group keys, [groupId] its well-formed
     * id. The same admission as the direct path, with the packet's own source and
     * signature verdict ([unverifiedReason]): the three first rules (§6.3), a
     * relay request never acted on (§12.3), an invite only listing D and only
     * past the privacy filter (an invite to D the filter drops for a group not
     * held here puts its id in the filtered-invite set), then the group rule.
     * What the rule drops only because the group is not joined here is held
     * (§6.5). The decision is [DistroGroupAdmission.decide]'s. An admitted message
     * makes the sender's name row and nothing of a DM (§6.4), then goes to the
     * group handler with the LXMF hash as its id. Runs inline: the caller
     * records the seen key from the outcome.
     */
    suspend fun onDistroGroupMessage(
        blob: ByteArray,
        keys: DistroGroupKeys,
        groupId: String,
        srcHex: String,
        content: String,
        timestamp: Double,
        fieldsRaw: ByteArray?,
        nameField: NameField,
        unverifiedReason: Int,
    ): DistroGroupOutcome = withContext(Dispatchers.IO) {
        val hash = keys.hash ?: run {
            Log.w(TAG, "distro group message for ${groupId.take(8)} from ${srcHex.take(8)} DROPPED: the unwrap gave no hash")
            return@withContext DistroGroupOutcome.DROPPED
        }
        val distro = DistroManager.deliveryHashHex ?: run {
            Log.w(TAG, "distro group message ${hash.take(12)} DROPPED: no distro loaded")
            return@withContext DistroGroupOutcome.DROPPED
        }
        val fields = when (val received = LxmfFields.receive(fieldsRaw, onFailure = {
            Log.w(TAG, "onDistroGroupMessage: fields from ${srcHex.take(8)} unreadable (${it::class.java.simpleName}: ${it.message})")
        })) {
            // The distro unwrap refuses it first; here only from an older native library.
            is LxmfFields.Received.Refused -> {
                Log.w(TAG, "distro group message ${hash.take(12)} from ${srcHex.take(8)} REFUSED whole: ${received.why} (RFed SPEC §17.14 \"Repeated keys\")")
                return@withContext DistroGroupOutcome.DROPPED
            }
            is LxmfFields.Received.Read -> received.fields
        }
        val signature = unverifiedReason
        val action = fields.group(GroupEntry.ACTION)
        val what = "group ${action ?: "message"} ${hash.take(12)} for ${groupId.take(8)} from ${srcHex.take(8)} via the distro"
        val here = groupStanding(groupId, srcHex)
        val isInvite = action == GroupChatManager.Action.INVITE
        val verdict = DistroGroupAdmission.decide(
            DistroGroupAdmission.Facts(
                action = action,
                signature = signature,
                srcHex = srcHex,
                groupSender = fields.group(GroupEntry.SENDER),
                listed = if (isInvite) DeliveryPolicy.members(fields.group(GroupEntry.MEMBERS)) else emptyList(),
                distro = distro,
                heldHere = here.chat != null,
                held = here.held,
                sourceStatus = here.sourceStatus,
                closed = here.closed,
                sourceIsCreator = here.sourceIsCreator,
                selves = here.selves,
                echoes = here.echoes,
                sourceAllowed = isInvite && allowlisted(srcHex),
                filtered = { UserPreferences.isInviteFiltered(appContext, groupId) },
            )
        )
        when (verdict) {
            is DistroGroupAdmission.Verdict.Drop -> {
                if (verdict.filterId) UserPreferences.filterInvite(appContext, groupId)
                Log.i(TAG, "$what DROPPED: ${verdict.why}")
                return@withContext DistroGroupOutcome.DROPPED
            }
            DistroGroupAdmission.Verdict.Hold -> {
                val held = holdArrival(blob, groupId, srcHex, hash, signature == Signature.VALIDATED, isPostSync = false, distro)
                return@withContext if (held) DistroGroupOutcome.HELD else DistroGroupOutcome.DROPPED
            }
            DistroGroupAdmission.Verdict.Admit -> Unit
        }
        // §6.4: the sender's name row, as the direct path makes it; nothing of a DM.
        if (!here.isSelf(srcHex)) {
            ensureContact(srcHex)
            acceptMessageName(srcHex, nameField, unverifiedReason, timestamp)
        }
        // §6.2: kept only when the handler applied or stored it; otherwise its key is not
        // recorded and a later copy (one whose signature validates, say) is judged again.
        val kept = handleGroupMessage(hash, groupId, srcHex, content, timestamp, fields, here, signature, keys.senderKey, keys.packed, viaDistro = true)
        if (kept) DistroGroupOutcome.KEPT else {
            Log.i(TAG, "$what not kept by the group handler: its key is not recorded, and its next copy is judged again (§6.2)")
            DistroGroupOutcome.DROPPED
        }
    }

    /**
     * Hold [blob] for group [groupId] (§6.5) when the bounds let it: a copy
     * already held is held once, a validated copy replaces unverified ones, and
     * every eviction and refusal is said with the group, the source and the
     * bound. True when it is held (now or already).
     */
    private suspend fun holdArrival(
        blob: ByteArray, groupId: String, srcHex: String, hash: String, validated: Boolean, isPostSync: Boolean, distro: String,
    ): Boolean {
        val heads = groupStatusDao.arrivalHeads()
        val pending = HashMap<String, Boolean>()
        suspend fun pendingHere(id: String): Boolean = pending.getOrPut(id) { chatDao.findByGroupId(id)?.isGroup == true }
        (heads.map { it.groupId } + groupId).distinct().forEach { pendingHere(it) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob).toHex()
        val what = "${if (isPostSync) "post sync" else "group message"} ${hash.take(12)} for ${groupId.take(8)} from ${srcHex.take(8)}"
        val candidate = HeldArrivals.Candidate(groupId, srcHex, hash, digest, validated, isPostSync, blob.size)
        return when (val decision = HeldArrivals.decide(heads, candidate, { pending[it] == true }, distro)) {
            is HeldArrivals.Decision.Refuse -> {
                Log.w(TAG, "$what DROPPED: not held (${decision.reason}) (§6.5)")
                false
            }
            is HeldArrivals.Decision.Duplicate -> {
                Log.i(TAG, "$what held already: ${decision.reason}")
                true
            }
            is HeldArrivals.Decision.Hold -> {
                val gone = decision.replaced.map { it.rowId } + decision.evicted.map { it.first.rowId }
                if (gone.isNotEmpty()) groupStatusDao.dropArrivals(gone)
                decision.replaced.forEach { Log.i(TAG, "held ${it.msgHash.take(12)} for ${it.groupId.take(8)} from ${it.srcHex.take(8)} replaced by a copy whose signature validated") }
                decision.evicted.forEach { (h, bound) -> Log.w(TAG, "held ${h.msgHash.take(12)} for ${h.groupId.take(8)} from ${h.srcHex.take(8)} EVICTED: $bound (§6.5)") }
                groupStatusDao.holdArrival(
                    GroupArrivalEntity(
                        groupId = groupId, srcHex = srcHex, msgHash = hash, blobDigest = digest,
                        validated = validated, distro = distro, isPostSync = isPostSync, blob = blob,
                    )
                )
                Log.i(TAG, "$what HELD until the group is joined here (${if (pending[groupId] == true) "pending" else "not held"} here, §6.5)")
                true
            }
        }
    }

    /**
     * The held arrivals of [groupId], in arrival order, taken out of the store
     * to be fed to the distro path again now that the group is joined here
     * (§6.5): ordering, not a retry. Only those for the distro held now; any
     * other is dropped, said.
     */
    suspend fun takeHeldArrivals(groupId: String): List<ByteArray> = withContext(Dispatchers.IO) {
        val rows = groupStatusDao.arrivalsFor(groupId)
        if (rows.isEmpty()) return@withContext emptyList()
        groupStatusDao.dropArrivalsFor(groupId)
        val distro = DistroManager.deliveryHashHex
        val (mine, other) = rows.partition { it.distro == distro }
        if (other.isNotEmpty()) Log.w(TAG, "${other.size} held arrival(s) for ${groupId.take(8)} came through another distro: dropped")
        Log.i(TAG, "replaying ${mine.size} held arrival(s) for ${groupId.take(8)} in arrival order (§6.5)")
        mine.map { it.blob }
    }

    /** Forget the held arrivals of [groupId] ([why]: it was closed or deleted here), with one line giving the count (§6.5). */
    private suspend fun forgetHeldArrivals(groupId: String, why: String) {
        val n = groupStatusDao.dropArrivalsFor(groupId)
        if (n > 0) Log.i(TAG, "forgot $n held arrival(s) for ${groupId.take(8)}: $why")
    }

    /**
     * What this device holds of a distro changed ([DistroChanges.plan]): when D
     * is given up or replaced, the held arrivals that came through another
     * distro are forgotten, with one line giving the count (§6.5), and the known
     * siblings of a distro given up are dropped unless a group here still has it
     * as its own address (§8.7, §9.1). At start-up (D held, not loaded yet)
     * nothing is. Group syncs already owed stay owed (§10.5).
     */
    suspend fun onDistroChanged(holding: DistroHolding) = withContext(Dispatchers.IO) {
        val sets = UserPreferences.distrosWithSiblings(appContext)
        val ownMembers = sets.filter { chatDao.countWithOwnMember(it) > 0 }.toSet()
        val plan = DistroChanges.plan(holding, sets, ownMembers)
        val dropped = when (val arrivals = plan.arrivals) {
            DistroChanges.Arrivals.Keep -> {
                Log.i(TAG, "distro $holding: held arrivals and known siblings kept (§8.7)")
                0
            }
            DistroChanges.Arrivals.All -> groupStatusDao.dropAllArrivals()
            is DistroChanges.Arrivals.AllBut -> groupStatusDao.dropArrivalsNotFor(arrivals.distro)
        }
        if (dropped > 0) Log.i(TAG, "forgot $dropped held arrival(s) that came through a distro this device no longer holds (distro $holding, §6.5)")
        for (d in plan.forgetSiblingsOf) {
            UserPreferences.forgetDistroSiblings(appContext, d)
            Log.i(TAG, "forgot the known siblings of ${d.take(8)}: given up, and no group here has it as its own address (§9.1)")
        }
    }

    /**
     * A group sync G (§10, §11) from [srcHex] through the distro, already past
     * rule 0 (its `gsync:` key not seen): rules 1-4 ([DistroGroupSync.disposition]),
     * then rule 5 and the apply table of §11.2, then `left`. Applying sends
     * nothing to the members and owes no G, so nothing loops; it asks nothing of
     * the user. A `post` G for a group pending or not held here is held (§6.5).
     * [blob] is kept for that; [replay] is called with a group id whose held
     * arrivals must be fed again (the group is joined here now).
     */
    suspend fun onDistroGroupSync(
        blob: ByteArray,
        hash: String,
        srcHex: String,
        signatureValidated: Boolean,
        read: com.newendian.retichat.service.GroupSyncRead,
        replay: (String) -> Unit,
    ): DistroGroupOutcome = withContext(Dispatchers.IO) {
        val distro = DistroManager.deliveryHashHex
        val device = selfHex()
        val label = "group sync ${hash.take(12)}"
        when (val disposition = DistroGroupSync.disposition(srcHex, distro, signatureValidated, read, device)) {
            is DistroGroupSync.Disposition.NotFromDistro -> {
                Log.w(TAG, "$label IGNORED: its source ${srcHex.take(8)} is not the distro ${distro?.take(8)} (§11.1 rule 1)")
                DistroGroupOutcome.DROPPED
            }
            DistroGroupSync.Disposition.Forged -> {
                Log.w(TAG, "$label DROPPED: its signature is not the distro's (§11.1 rule 2)")
                DistroGroupOutcome.DROPPED
            }
            DistroGroupSync.Disposition.OwnEcho -> DistroGroupOutcome.KEPT
            is DistroGroupSync.Disposition.BadForm -> {
                learnSibling(distro!!, disposition.sibling, "a group sync")
                Log.w(TAG, "$label DROPPED: ${disposition.problem} (§11.1 rule 4)")
                DistroGroupOutcome.DROPPED
            }
            is DistroGroupSync.Disposition.Apply -> {
                learnSibling(distro!!, disposition.sibling, "a group sync")
                applyGroupSync(blob, hash, disposition.sync, distro, replay)
            }
        }
    }

    /** [sibling] sent a D-signed sync for [distro] ([what]): it is a known sibling from now on (§9.1). */
    fun learnSibling(distro: String, sibling: String?, what: String) {
        if (sibling == null) return
        if (UserPreferences.learnDistroSibling(appContext, distro, sibling)) {
            Log.i(TAG, "learned sibling ${sibling.take(8)} of the distro ${distro.take(8)} from $what (§9.1)")
        }
    }

    /** The group [groupId] as this device holds it, for the apply table (§11.2). */
    private suspend fun syncLocal(groupId: String, distro: String): Pair<DistroGroupSync.Local, ChatEntity?> {
        if (UserPreferences.isGroupClosed(appContext, groupId)) return DistroGroupSync.Local.Closed to null
        val chat = chatDao.findByGroupId(groupId)?.takeIf { it.isGroup } ?: return DistroGroupSync.Local.NotHeld to null
        val members = messageDao.groupMembersList(chat.id)
        val own = GroupSelves.ownMember(chat.ownMember, selfHex())
        return DistroGroupSync.Local.Held(
            ownIsD = own == distro,
            joined = GroupMemberStatuses.held(true, members, own) == DeliveryPolicy.Held.JOINED,
            creator = chat.creatorHex,
            members = members.map { it.destHashHex }.toSet(),
            inviter = chat.creatorHex,
        ) to chat
    }

    /** §11.1 rules 5-6 and §11.2 for [sync], which passed rules 0-4. */
    private suspend fun applyGroupSync(
        blob: ByteArray, hash: String, sync: GroupSync, distro: String, replay: (String) -> Unit,
    ): DistroGroupOutcome {
        val label = "${sync.label} ${hash.take(12)}"
        val (local, chat) = syncLocal(sync.groupId, distro)
        val leftNow = mutableListOf<String>()
        val outcome = when (val effect = DistroGroupSync.effect(sync, local, distro)) {
            is DistroGroupSync.Effect.HoldFromG -> {
                if (effect.discarded && chat != null) {
                    Log.w(TAG, "$label: the pending invite for ${sync.groupId.take(8)} from ${chat.creatorHex?.take(8)} is discarded, nothing sent: the user's own sync outranks it (§11.2)")
                    deleteChatLocal(chat)
                }
                holdGroupFromSync(sync, effect.creator, distro)
                replay(sync.groupId)
                Log.i(TAG, "$label APPLIED: the group is held from it, joined, as ${distro.take(8)}, creator ${effect.creator.take(8)}")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.JoinPending -> {
                joinFromSync(chat!!, distro)
                replay(sync.groupId)
                Log.i(TAG, "$label APPLIED: the pending invite it describes is joined, nothing sent")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.RecordClosed -> {
                UserPreferences.closeGroup(appContext, sync.groupId)
                forgetHeldArrivals(sync.groupId, "a leave sync recorded it closed")
                leftNow += sync.groupId
                Log.i(TAG, "$label APPLIED: ${sync.groupId.take(8)} is not held here and is recorded closed")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.Close -> {
                closeFromSync(chat!!, "a leave sync")
                leftNow += sync.groupId
                Log.i(TAG, "$label APPLIED: the group is closed and removed here, nothing sent")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.StorePost -> {
                storePostFromSync(chat!!, sync, hash, distro)
                Log.i(TAG, "$label APPLIED: stored as the user's own post, SENT")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.HoldPost -> {
                if (UserPreferences.isInviteFiltered(appContext, sync.groupId)) {
                    Log.i(TAG, "$label DROPPED: its group is in the filtered-invite set, whose traffic is never held (§6.5)")
                    DistroGroupOutcome.DROPPED
                } else if (holdArrival(blob, sync.groupId, distro, hash, validated = true, isPostSync = true, distro)) {
                    DistroGroupOutcome.HELD
                } else DistroGroupOutcome.DROPPED
            }
            DistroGroupSync.Effect.DropPost -> {
                Log.i(TAG, "$label DROPPED: the group is closed here")
                DistroGroupOutcome.DROPPED
            }
            DistroGroupSync.Effect.Nothing -> {
                if (local == DistroGroupSync.Local.Closed && sync.op == DistroGroupSync.LEAVE) leftNow += sync.groupId
                Log.i(TAG, "$label: nothing to change (${if (local == DistroGroupSync.Local.Closed) "closed here, final" else "already held"})")
                DistroGroupOutcome.KEPT
            }
            DistroGroupSync.Effect.IgnoreOwnAddress -> {
                Log.i(TAG, "$label IGNORED: the group is held here as the device (held at upgrade), not as the distro (§11.1 rule 5)")
                DistroGroupOutcome.KEPT
            }
        }
        // §11.2: `left` after the op, on every G that passed rules 0-4 (rule 5's included).
        val changed = mutableListOf<String>()
        for (id in sync.left) {
            if (id == sync.groupId) continue
            val (l, c) = syncLocal(id, distro)
            when (DistroGroupSync.leftEffect(l)) {
                DistroGroupSync.LeftEffect.CLOSE -> { closeFromSync(c!!, "the left list of ${sync.label}"); changed += id; leftNow += id }
                DistroGroupSync.LeftEffect.RECORD_CLOSED -> {
                    UserPreferences.closeGroup(appContext, id)
                    forgetHeldArrivals(id, "the left list of a group sync recorded it closed")
                    changed += id; leftNow += id
                }
                DistroGroupSync.LeftEffect.ALREADY_CLOSED -> leftNow += id
                DistroGroupSync.LeftEffect.IGNORE -> Unit
            }
        }
        if (changed.isNotEmpty()) Log.i(TAG, "$label: its left list closed ${changed.joinToString { it.take(8) }} here")
        UserPreferences.recordGroupsLeft(appContext, distro, leftNow)
        return outcome
    }

    /**
     * "Hold the group from G" (§11.2): list = `members` (verbatim), the creator,
     * own address D, joined. The creator and D are accepted, every other member
     * invited; the keys are bound and remembered as an invite chunk's; every
     * member but a self address passes the privacy filter by association; the
     * id leaves the filtered-invite set.
     */
    private suspend fun holdGroupFromSync(sync: GroupSync, creator: String, distro: String) {
        val groupId = sync.groupId
        val listed = DeliveryPolicy.members(sync.members)
        val chatId = "group_${groupId.take(16)}"
        val chat = ChatEntity(
            id = chatId,
            isGroup = true,
            name = sync.name ?: "Group",
            memberHashes = sync.members.orEmpty(),
            groupIdHex = groupId,
            creatorHex = creator,
            ownMember = distro,
        )
        chatDao.upsert(chat)
        val self = GroupSelves.of(distro, listed, selfHex(), distro, ::siblingsOf)
        listed.forEach { hex ->
            messageDao.upsertGroupMember(
                GroupMemberEntity(
                    chatId = chatId,
                    destHashHex = hex,
                    inviteStatus = if (hex == creator || hex == distro) MemberStatus.ACCEPTED else MemberStatus.INVITED,
                )
            )
        }
        rememberKeyEntries(sync.keys.orEmpty(), listed, self)
        listed.filter { !self.isSelf(it) }.forEach { ensureAllowlistedContact(it) }
        UserPreferences.unfilterInvite(appContext, groupId)
        groupStatuses.decideHeld()
        messageDao.upsert(
            MessageEntity(
                id = "joined_${groupId.take(16)}",
                chatId = chatId,
                senderHashHex = distro,
                content = if (sync.op == DistroGroupSync.CREATE) "You created \"${chat.name}\"" else "You joined \"${chat.name}\"",
                timestamp = sync.atMs,
                isOutbound = true,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )
    }

    /** An accept G that describes the pending invite held here: joined as the user's own accept would make it, without sending (§11.2). */
    private suspend fun joinFromSync(chat: ChatEntity, distro: String) {
        val members = messageDao.groupMembersList(chat.id)
        val self = groupSelf(chat, members)
        groupStatuses.decideHeld()
        messageDao.setGroupMemberStatus(chat.id, distro, MemberStatus.ACCEPTED)
        members.map { it.destHashHex }.distinct().filter { !self.isSelf(it) }.forEach { ensureAllowlistedContact(it) }
        chat.groupIdHex?.let { UserPreferences.unfilterInvite(appContext, it) }
        messageDao.upsert(
            MessageEntity(
                id = "joined_${chat.groupIdHex.orEmpty().take(16)}",
                chatId = chat.id,
                senderHashHex = distro,
                content = "You joined \"${chat.name}\"",
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )
    }

    /** Closed as the local decline or leave closes it, without sending and without owing a G (§11.2): recorded closed, the conversation removed. */
    private suspend fun closeFromSync(chat: ChatEntity, why: String) {
        val groupId = chat.groupIdHex ?: return deleteChatLocal(chat)
        UserPreferences.closeGroup(appContext, groupId)
        forgetHeldArrivals(groupId, "$why closed it")
        deleteChatLocal(chat)
    }

    /** A post G in a joined group: the user's own outgoing post, sender D, SENT, at `at_ms`, id the G's hash, no notification (§11.2). */
    private suspend fun storePostFromSync(chat: ChatEntity, sync: GroupSync, hash: String, distro: String) {
        if (messageDao.findById(hash) != null) return
        messageDao.upsert(
            MessageEntity(
                id = hash,
                chatId = chat.id,
                senderHashHex = distro,
                content = DistroCodec.copiedText(sync.text.orEmpty()),
                timestamp = sync.atMs,
                isOutbound = true,
                state = RetichatBridge.MessageState.SENT,
            )
        )
    }

    /**
     * Keep each GROUP_MEMBER_KEYS entry of [entries] that binds as an invite
     * chunk's does (the derived `lxmf.delivery` hash is the claimed one, the
     * claimed hash listed in [listed]), never a self address's (§9.2).
     */
    private suspend fun rememberKeyEntries(entries: List<String>, listed: List<String>, self: GroupSelf) {
        entries.forEach { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) return@forEach
            val memberHash = DeliveryPolicy.hash(parts[0]) ?: return@forEach
            if (memberHash !in listed || self.isSelf(memberHash)) return@forEach
            val hashBytes = memberHash.hexToBytes() ?: return@forEach
            val publicKey = runCatching { java.util.Base64.getDecoder().decode(parts[1]) }.getOrNull()
                ?.takeIf { it.size == 64 } ?: return@forEach
            if (!RetichatBridge.identityRememberLxmfDelivery(hashBytes, publicKey)) {
                Log.w(TAG, "Ignored mismatched group member key for ${memberHash.take(8)}")
                return@forEach
            }
            ensureContact(memberHash)
            contactDao.setPublicKey(memberHash, publicKey.toHex())
        }
    }

    // ---- Group message handling ----

    /**
     * What the group rule asks about a message naming group [groupId] from
     * the packet source [srcHex]: the group as held here ([chat], its
     * [members]), where the user stands in it, the source's status on its
     * list, whether the user rejected or left it, and whether the source is
     * the group's creator (a `status` counts only from it; false for a group
     * whose creator is not recorded, one held before database 14).
     */
    private data class GroupStanding(
        val chat: ChatEntity?,
        val members: List<GroupMemberEntity>,
        val held: DeliveryPolicy.Held,
        val sourceStatus: String?,
        val closed: Boolean,
        val sourceIsCreator: Boolean,
        /** Who the user is in the group held here (§9.1); null for a group not held here. */
        val self: GroupSelf?,
        /** The self addresses the first rules ask about: the group's, or for a group not held here the device's and its distro's (§6.3). */
        val selves: Set<String>,
        /** A group message from one of these is the user's own echo: the distro held now and the group's own distro (§6.3). */
        val echoes: Set<String>,
    ) {
        fun isSelf(hex: String): Boolean = hex in selves
    }

    /**
     * [GroupStanding] of group [groupId] for a message from [srcHex]. Pending or
     * joined is read from the group's own address's row alone
     * (GROUPS-DISTRO-DESIGN.md §9.2): in a list naming both the device and D,
     * the device row stays invited and the group is joined once D's is.
     */
    private suspend fun groupStanding(groupId: String, srcHex: String): GroupStanding {
        val chat = chatDao.findByGroupId(groupId)?.takeIf { it.isGroup }
        val members = chat?.let { messageDao.groupMembersList(it.id) } ?: emptyList()
        val self = chat?.let { groupSelf(it, members) }
        val heldD = DistroManager.deliveryHashHex
        return GroupStanding(
            chat = chat,
            members = members,
            held = GroupMemberStatuses.held(chat != null, members, self?.own ?: selfHex()),
            sourceStatus = GroupMemberStatuses.statusOf(members, srcHex),
            closed = UserPreferences.isGroupClosed(appContext, groupId),
            sourceIsCreator = chat?.creatorHex != null && chat.creatorHex == srcHex,
            self = self,
            selves = self?.all ?: GroupSelves.unheld(selfHex(), heldD, ::siblingsOf),
            echoes = setOfNotNull(heldD, self?.distro),
        )
    }

    // ---- Who the user is, and who signs (GROUPS-DISTRO-DESIGN.md §8.1, §9.1) ----

    private fun siblingsOf(distroHex: String): Set<String> = UserPreferences.distroSiblings(appContext, distroHex)

    /** Who the user is in [chat]'s group: its own address and every self address (§9.1). */
    private suspend fun groupSelf(chat: ChatEntity, members: List<GroupMemberEntity>? = null): GroupSelf {
        val listed = (members ?: messageDao.groupMembersList(chat.id)).map { it.destHashHex }
        return GroupSelves.of(chat.ownMember, listed, selfHex(), DistroManager.deliveryHashHex, ::siblingsOf)
    }

    /** A self address of this device in any group: the device, the distro held now, or a known sibling of it. */
    private fun isSelfAnywhere(hex: String): Boolean {
        if (hex == selfHex()) return true
        val d = DistroManager.deliveryHashHex ?: return false
        return hex == d || hex in siblingsOf(d)
    }

    /**
     * What [groupSigner] resolves to for [chat] now, for the UI and the log
     * (§8.1, §8.7): [GroupSelves.Signer.NOT_READY] while the stack is not up.
     */
    fun signerKind(chat: ChatEntity): GroupSelves.Signer =
        GroupSelves.signer(chat.ownMember, if (groupActionsReady.value) selfHex() else "", DistroManager.signing()?.first?.deliveryHashHex, DistroManager.isStored(appContext))

    /**
     * `groupSigner(ownMember)` (GROUPS-DISTRO-DESIGN.md §8.1): who every send
     * in [chat]'s group goes as, resolved now, never captured at configure. The
     * device for a group whose own address is the device; D, from the strict
     * helper and only when its `lxmf.delivery` hash is the group's own address,
     * for a D-group; otherwise null, and the action is refused (§8.7). Never the
     * fallback `DistroManager.sendingIdentity`, which ignores the group.
     */
    private fun groupSigner(chat: ChatEntity): GroupSigner? = when (signerKind(chat)) {
        GroupSelves.Signer.DEVICE ->
            if (identityHandle == 0L || selfDestHash.isEmpty()) null
            else GroupSigner(selfHex(), selfDestHash, identityHandle, RetichatBridge.identityPublicKey(identityHandle))
        GroupSelves.Signer.DISTRO -> DistroManager.signing()?.let { (state, handle) ->
            if (state.deliveryHashHex != chat.ownMember) null
            else DistroCodec.hexToBytes(state.deliveryHashHex)?.let { hash ->
                GroupSigner(state.deliveryHashHex, hash, handle, DistroCodec.hexToBytes(state.publicKeyHex))
            }
        }
        GroupSelves.Signer.NOT_LOADED, GroupSelves.Signer.FORMER, GroupSelves.Signer.NOT_READY -> null
    }

    /**
     * A group message the rule kept ([DeliveryPolicy.groupMessage], applied
     * in [onMessageReceived] with [standing]). James's group model: the
     * members are the creator's list, fixed; an accept or a leave is the
     * packet source's own; a leave (a decline's included) is final; a pending
     * group allows nobody and relays for nobody. [groupId] is the id the path
     * read (the direct path's [DeliveryPolicy.directRoute], the distro path's
     * unwrap), never read again here: the two read a distro marker differently.
     *
     * Returns whether the message was kept: applied or stored. The distro path
     * records its `grp:` key only then; a dropped one is judged again on its
     * next copy (GROUPS-DISTRO-DESIGN.md §6.2).
     */
    private suspend fun handleGroupMessage(
        msgId: String,
        groupId: String,
        srcHex: String,
        content: String,
        timestamp: Double,
        fields: LxmfFields,
        standing: GroupStanding,
        signature: Int,
        senderKey: ByteArray?,
        packed: ByteArray?,
        viaDistro: Boolean,
    ): Boolean {
        val groupAction = fields.group(GroupEntry.ACTION)

        // What any group message the rule kept brings: the key an accept or
        // leave carries is remembered (after the rule, never for a message it
        // dropped), and a message its source signed shows that source's key is
        // here, so what was held for it is decided now, before this message.
        groupStatuses.onKept(srcHex, groupAction, signature, senderKey)

        if (groupAction == GroupChatManager.Action.INVITE) {
            return handleGroupInvite(srcHex, groupId, timestamp, fields, standing, viaDistro)
        }
        val chat = standing.chat ?: run {
            Log.w(TAG, "group ${groupAction ?: "post"} ${msgId.take(12)} for ${groupId.take(8)} from ${srcHex.take(8)} DROPPED: the group is not held here")
            return false
        }

        return when (groupAction) {
            GroupChatManager.Action.ACCEPT, GroupChatManager.Action.LEAVE -> {
                // The source's own accept or leave (the rule took it only from
                // a current member naming nobody else, not forged). Verified
                // (under a key held, or the one it carries), it counts; one no
                // key can verify counts until the switch and is held from it.
                // What it changes (a member that left stays left; a decline
                // arrives as a leave from a member still invited), the
                // creator's copy and answer are the engine's.
                // Kept when counted or held for its key; an ignored one is not.
                groupStatuses.takeDirect(
                    Arrival(
                        msgId = msgId,
                        src = srcHex,
                        chat = chat,
                        action = groupAction,
                        timestampMs = (timestamp * 1000).toLong(),
                        signature = signature,
                        senderKey = senderKey,
                        packed = packed,
                    )
                ).kept
            }

            GroupChatManager.Action.STATUS -> {
                // The creator's answer to the user's accept (the rule took it
                // only from the group's creator, for a group the user joined,
                // signed by it): each element is a member's own accept or
                // leave, checked on its own.
                groupStatuses.takeStatuses(chat, fields.groupStatuses())
                true
            }

            GroupChatManager.Action.RELAY_REQUEST -> {
                // The rule took it only from a member that accepted a group the
                // user joined, signed by it.
                val alreadySeen = fields.group(GroupEntry.RELAY_SEEN)
                    ?.split(",")?.mapNotNull { DeliveryPolicy.hash(it) } ?: emptyList()
                val acceptedMembers = GroupMemberStatuses.acceptedMemberHexes(
                    members = standing.members,
                    selfHex = standing.self?.own ?: selfHex(),
                ).filter { !standing.isSelf(it) }
                val signer = groupSigner(chat) ?: run {
                    Log.w(TAG, "relay_req for ${chat.id}: no signer for its own address (${signerKind(chat)}, §8.7) — not relayed")
                    return false
                }
                groupChatManager?.performRelay(
                    signer    = signer,
                    groupId   = groupId,
                    groupName = chat.name,
                    content   = content,
                    originalSender         = groupAuthor(fields, srcHex, standing, signature),
                    alreadySeen            = alreadySeen,
                    requesterHex           = srcHex,
                    allAcceptedMembers     = acceptedMembers,
                ) ?: Log.w(TAG, "relay_req: stack offline, cannot relay")
                true
            }

            GroupChatManager.Action.RELAY_DONE -> {
                Log.i(TAG, "Relay done confirmed for group ${chat.id} by ${srcHex.take(8)}")
                true
            }

            null -> {
                // GROUPS-DISTRO-DESIGN.md §9.2: a post whose packet source is a
                // known sibling (an older device of the user acting as its listed
                // device entry) is the user's own: stored as an outgoing post,
                // SENT, no notification, when its signature validated; otherwise
                // it is not the user's to show, and is dropped. A plain post is
                // kept for a group held here whoever sent it. It is shown as its
                // GROUP_SENDER's only when a current member of a joined group
                // relays a listed member's post; anyone else's is its own (a
                // stranger's never takes a member's name).
                val attachments = fields.getFileAttachments()
                val post = DeliveryPolicy.groupPost(standing.isSelf(srcHex), signature, content.isNotBlank() || attachments.isNotEmpty())
                when (post) {
                    DeliveryPolicy.GroupPost.UNPROVEN_OWN -> {
                        Log.i(TAG, "group post ${msgId.take(12)} for ${chat.id} from the self address ${srcHex.take(8)} DROPPED: its signature did not validate")
                    }
                    DeliveryPolicy.GroupPost.OWN -> {
                        if (messageDao.findById(msgId) == null) {
                            messageDao.upsert(
                                MessageEntity(
                                    id = msgId,
                                    chatId = chat.id,
                                    senderHashHex = srcHex,
                                    content = content,
                                    timestamp = (timestamp * 1000).toLong(),
                                    isOutbound = true,
                                    state = RetichatBridge.MessageState.SENT,
                                )
                            )
                            saveInboundAttachments(msgId, fields)
                            Log.i(TAG, "group post ${msgId.take(12)} for ${chat.id} from the user's own device ${srcHex.take(8)}: stored as the user's own, SENT")
                        }
                    }
                    DeliveryPolicy.GroupPost.EMPTY -> {
                        Log.i(TAG, "group post ${msgId.take(12)} for ${chat.id} from ${srcHex.take(8)} DROPPED: no text and no attachment")
                    }
                    DeliveryPolicy.GroupPost.MEMBER -> {
                        val author = groupAuthor(fields, srcHex, standing, signature)
                        messageDao.upsert(
                            MessageEntity(
                                id = msgId,
                                chatId = chat.id,
                                senderHashHex = author,
                                content = content,
                                timestamp = (timestamp * 1000).toLong(),
                                isOutbound = false,
                                state = RetichatBridge.MessageState.DELIVERED,
                            )
                        )
                        saveInboundAttachments(msgId, fields)

                        if (RetichatApp.activeChatId != chat.id) {
                            val notifText = if (content.isNotBlank()) content
                                            else "📎 ${attachments.size} attachment(s)"
                            MessageNotificationHelper.notify(
                                appContext, resolvedName(author), notifText, chat.id,
                                conversationTitle = chat.name,
                            )
                        }
                    }
                }
                post.kept
            }

            else -> {
                Log.w(TAG, "handleGroupMessage: unrecognised action=$groupAction from ${srcHex.take(8)}: DROPPED")
                false
            }
        }
    }

    /** [DeliveryPolicy.groupAuthor] for a message of the group in [standing]. */
    private fun groupAuthor(fields: LxmfFields, srcHex: String, standing: GroupStanding, signature: Int): String {
        val named = DeliveryPolicy.hash(fields.group(GroupEntry.SENDER))
        return DeliveryPolicy.groupAuthor(
            groupSender = named,
            srcHex = srcHex,
            held = standing.held,
            sourceStatus = standing.sourceStatus,
            senderListed = named != null && GroupMemberStatuses.statusOf(standing.members, named) != null,
            signature = signature,
        )
    }

    /**
     * An invite from [srcHex] the rule kept (a source the privacy filter
     * allows, for a group the user never rejected or left). Each listed
     * member whose key checks out is kept on a hidden row (the user's accept
     * needs every key). Nobody passes the privacy filter by it: the members
     * pass from the user's accept (James, 2026-10-01: "If the invite is
     * accepted, the other group members are considered allowed"; RFed-spec
     * Group.md, "The privacy filter", 2026-10-06), and the creator's answer to
     * that accept (`status`) brings back the accepts the filter dropped before.
     * Between 2026-10-05 and that change the members were let through as the
     * invite arrived, as v0.1.8 does. A group held here keeps the list its
     * creation or first invite gave it (James, 2026-10-01: "There are no
     * membership changes for a group"): another invite brings keys only. The
     * invite's source is recorded as the group's creator, once, with the
     * group: a `status` counts only from it.
     *
     * GROUPS-DISTRO-DESIGN.md §8.1, §9.3: the user's own entry is the group's
     * own address, which the list must name: the distro held now when it is
     * listed (a list naming both the device and D gives D), else the device; an
     * invite naming neither is not for this user and is dropped. The user is
     * never added to a list that does not name them. Through the distro
     * ([viaDistro]) the invite must list D. The list is kept verbatim (a group
     * sync carries it as the invite did), and no key of a self address is kept.
     * Returns whether the invite was kept (a group made, or a held one's keys
     * taken), for the distro path's seen key (§6.2).
     */
    private suspend fun handleGroupInvite(
        srcHex: String,
        groupId: String,
        timestamp: Double,
        fields: LxmfFields,
        standing: GroupStanding,
        viaDistro: Boolean,
    ): Boolean {
        if (standing.chat == null && fields.group(GroupEntry.MEMBERS) == null) {
            Log.w(TAG, "invite for ${groupId.take(8)} from ${srcHex.take(8)} lists no members: ignored")
            return false
        }
        val membersCsv = fields.group(GroupEntry.MEMBERS).orEmpty()
        val listed = DeliveryPolicy.members(membersCsv)
        val heldD = DistroManager.deliveryHashHex
        // The own address of a new group (§8.1); null: the invite is not for this user (§9.3).
        val own = if (standing.chat != null) null else GroupSelves.ownForInvite(listed, selfHex(), heldD)
        if (standing.chat == null) {
            if (own == null) {
                Log.i(TAG, "invite for ${groupId.take(8)} from ${srcHex.take(8)} DROPPED: its list names neither this device nor the distro held here (§9.3)")
                return false
            }
            if (viaDistro && own != heldD) {
                Log.i(TAG, "invite for ${groupId.take(8)} from ${srcHex.take(8)} through the distro DROPPED: its list does not name the distro (§6.3)")
                return false
            }
        }
        // Whose keys this invite may bring: the held list, or for a new group
        // the list it gives (with its inviter).
        val members = if (standing.chat != null) standing.members.map { it.destHashHex }.distinct()
                      else (listed + srcHex).distinct()
        val self = standing.self ?: GroupSelves.of(own, members, selfHex(), heldD, ::siblingsOf)
        fields.group(GroupEntry.MEMBER_KEYS)?.split(",")?.forEach { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) return@forEach
            val memberHash = DeliveryPolicy.hash(parts[0]) ?: return@forEach
            if (memberHash !in members || self.isSelf(memberHash)) return@forEach
            val hashBytes = memberHash.hexToBytes() ?: return@forEach
            val publicKey = runCatching { Base64.decode(parts[1], Base64.DEFAULT) }.getOrNull()
                ?.takeIf { it.size == 64 } ?: return@forEach
            if (!RetichatBridge.identityRememberLxmfDelivery(hashBytes, publicKey)) {
                Log.w(TAG, "Ignored mismatched group member key for ${memberHash.take(8)}")
                return@forEach
            }
            // The key is kept on a hidden row (the user's accept needs every
            // one). Nobody passes the privacy filter by an invite: the members
            // pass from the user's accept (RFed-spec Group.md, "The privacy
            // filter"), and the creator's answer to that accept brings back what
            // the filter dropped before.
            ensureContact(memberHash)
            contactDao.setPublicKey(memberHash, publicKey.toHex())
        }
        // A key this invite brought may be one an accept or leave is held for
        // (from the switch): each such one is decided now.
        groupStatuses.decideHeld()

        if (standing.chat != null) {
            Log.i(TAG, "invite for held group ${standing.chat.id} from ${srcHex.take(8)}: member keys only")
            return true
        }

        // A new group, PENDING (the user's own row invited) until the user
        // accepts or declines it. The inviter started it, so it is accepted.
        // The user's own row is the list's: never added (§9.3).
        val name = fields.group(GroupEntry.NAME) ?: "Group"
        val chatId = "group_${groupId.take(16)}"
        val all = (listed + srcHex).distinct()
        chatDao.upsert(
            ChatEntity(
                id = chatId,
                isGroup = true,
                name = name,
                // The GROUP_MEMBERS csv verbatim: an accept sync carries it as the invite did (§10.2).
                memberHashes = membersCsv,
                groupIdHex = groupId,
                creatorHex = srcHex,
                ownMember = own,
            )
        )
        all.forEach { memberHex ->
            messageDao.upsertGroupMember(
                GroupMemberEntity(
                    chatId = chatId,
                    destHashHex = memberHex,
                    inviteStatus = if (memberHex == srcHex) MemberStatus.ACCEPTED else MemberStatus.INVITED,
                )
            )
        }
        // The privacy filter admitted this group (its inviter is allowed): any id an earlier
        // invite from another source put in the filtered-invite set leaves it, so the group's
        // traffic is held while it is pending (GROUPS-DISTRO-DESIGN.md §6.5).
        if (UserPreferences.isInviteFiltered(appContext, groupId)) {
            UserPreferences.unfilterInvite(appContext, groupId)
            Log.i(TAG, "group ${groupId.take(8)} left the filtered-invite set: an invite the filter allowed made it held here (§6.5)")
        }
        Log.i(TAG, "Created PENDING group from invite: chatId=$chatId, groupId=$groupId, own address ${own?.take(8)}${if (viaDistro) " (through the distro)" else ""}")

        // A system invite line (the inviter's hash; the name resolved when shown).
        messageDao.upsert(
            MessageEntity(
                id = "inv_${groupId.take(16)}",
                chatId = chatId,
                senderHashHex = srcHex,
                content = "invited you to \"$name\" — tap Accept or Decline below",
                timestamp = (timestamp * 1000).toLong(),
                isOutbound = false,
                state = RetichatBridge.MessageState.DELIVERED,
                systemKind = SystemText.MEMBER,
            )
        )
        if (RetichatApp.activeChatId != chatId) {
            MessageNotificationHelper.notify(
                appContext,
                "Group invite",
                "${resolvedName(srcHex)} invited you to \"$name\"",
                chatId,
            )
        }
        return true
    }

    // ---- Group invite accept / decline ----

    /**
     * Reactive flow indicating whether [chatId] is a group with an
     * unaccepted invite (self member's inviteStatus is invited).
     */
    fun isPendingGroupInvite(chatId: String): Flow<Boolean> {
        // The own address's row alone (GROUPS-DISTRO-DESIGN.md §9.2): in a list
        // naming both the device and D, the device row stays invited for ever.
        return chatDao.chatByIdFlow(chatId).combine(messageDao.groupMembers(chatId)) { chat, members ->
            GroupMemberStatuses.isPendingInvite(members, GroupSelves.ownMember(chat?.ownMember, selfHex()))
        }
    }

    /**
     * Who acts in [chat]'s group from this device, for the UI (§8.7, §13):
     * whether it is a D-group, whether its own address can be signed for now,
     * its own address, and every self address (shown as "You").
     */
    suspend fun groupActing(chat: ChatEntity): GroupActing {
        val self = groupSelf(chat)
        return GroupActing(
            // While the stack is not up the device's address is not known: only the distro loaded tells.
            distroGroup = if (groupActionsReady.value) GroupSelves.isDistroGroup(chat.ownMember, selfHex())
                          else chat.ownMember != null && chat.ownMember == DistroManager.deliveryHashHex,
            signer = signerKind(chat),
            own = self.own,
            selves = self.all,
        )
    }

    /**
     * Accept a pending group invite: mark self accepted, broadcast
     * ACCEPT to all members, and insert a confirmation system message.
     */
    suspend fun acceptGroupInvite(chatId: String) {
        val chat = chatDao.findById(chatId) ?: return
        if (!chat.isGroup) return
        val groupId = chat.groupIdHex ?: return
        // As the group's own address, resolved now; nothing when it cannot be signed for (§8.1, §8.7).
        val signer = groupSigner(chat) ?: run {
            Log.w(TAG, "acceptGroupInvite: ${chat.id} refused: its own address ${chat.ownMember?.take(8)} has no signer on this device (${signerKind(chat)}, §8.7)")
            return
        }
        val selfHex = signer.hex
        val memberRows = messageDao.groupMembersList(chatId)
        val self = groupSelf(chat, memberRows)

        val allMembers = memberRows
            .map { it.destHashHex }
            .filter { it.isNotEmpty() }
            .distinct()
        // Every self address is skipped (§9.4): D is never a contact, so a
        // D-group would otherwise defer for ever.
        val missingKeys = allMembers.filter { !self.isSelf(it) }
            .filter { contactDao.findByHash(it)?.publicKeyHex?.length != 128 }
        if (missingKeys.isNotEmpty()) {
            Log.i(TAG, "acceptGroupInvite deferred: still receiving ${missingKeys.size} member key(s)")
            return
        }

        // Every member's key is here now, so an accept or leave held for its
        // key (from the switch) is decided before the group fans out.
        groupStatuses.decideHeld()
        messageDao.setGroupMemberStatus(chatId, selfHex, MemberStatus.ACCEPTED)
        // Every member passes the privacy filter (iOS acceptGroupInvite): from
        // the user's accept, not from the invite's arrival. No self address does.
        val others = allMembers.filter { !self.isSelf(it) }
        others.forEach { ensureAllowlistedContact(it) }
        groupChatManager?.sendAccept(signer, groupId, others)
            ?: Log.w(TAG, "acceptGroupInvite: stack offline, accept will not be broadcast")
        // §10.6: in a D-group, one `accept` group sync, in the same step.
        if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {
            // One entry per listed member whose key this device holds (§17.14 `keys`), D's own included.
            val keys = DeliveryPolicy.members(chat.memberHashes).mapNotNull { hex ->
                val key = if (hex == signer.hex) signer.publicKey
                          else if (self.isSelf(hex)) null
                          else contactDao.findByHash(hex)?.publicKeyHex?.let { DistroCodec.hexToBytes(it) }
                key?.takeIf { it.size == 64 }?.let { GroupStatuses.keyEntry(hex, it) }
            }
            val creator = chat.creatorHex
            if (creator == null) {
                Log.w(TAG, "acceptGroupInvite: ${chat.id} has no recorded creator: no accept sync owed")
            } else {
                DistroOutboxRuntime.oweGroupSync(
                    appContext, signer.hex,
                    GroupSync(
                        op = DistroGroupSync.ACCEPT, groupId = groupId, atMs = System.currentTimeMillis(),
                        name = chat.name, members = chat.memberHashes, keys = keys, creator = creator,
                        left = UserPreferences.groupsLeftBy(appContext, signer.hex),
                    ),
                )
            }
        }
        // The group is joined here now: what was held for it is fed again, in arrival order (§6.5).
        RfedDistroClient.replayHeldArrivals(appContext, groupId)

        messageDao.upsert(
            MessageEntity(
                id = "joined_${groupId.take(16)}",
                chatId = chatId,
                senderHashHex = selfHex,
                content = "You joined \"${chat.name}\"",
                timestamp = System.currentTimeMillis(),
                isOutbound = true,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )
        Log.i(TAG, "acceptGroupInvite: accepted ${chat.id} (${allMembers.size} members)")
    }

    /**
     * Decline a pending group invite: the user's leave (James, 2026-10-02:
     * "Make the decline message the same as the leave message"), through the
     * one path leaving takes ([quitGroup]), so every member records the user
     * as a member who left, for good. Until 2026-10-05 a decline sent nothing
     * and only this device knew.
     */
    suspend fun declineGroupInvite(chatId: String): Boolean = quitGroup(chatId, "declined")

    private suspend fun handleDirectMessage(
        msgId: String,
        srcHash: ByteArray,
        srcHex: String,
        content: String,
        timestamp: Double,
        fields: LxmfFields,
    ) {
        // Dedup: the same LXMF message (deterministic hash) can arrive via
        // multiple transports in quick succession — PROPAGATED first, then
        // the sender's DIRECT backchannel.  The message hash is the primary
        // key; if we've already stored this message, skip silently to avoid
        // a duplicate bubble and a second notification.
        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        if (messageDao.findById(msgId) != null) {
            Log.d(TAG, "handleDirectMessage: dup msgId=${msgId.take(16)}, skipping")
            return
        }

        // No "stop <group>" text marks anyone left any more (GROUPS-DISTRO-DESIGN.md
        // §6.9): it had no signature or group-action check, so anyone could mark a
        // member left for good. A leave is the `leave` action, verified.

        val chatId = directChatId(srcHash)
        val existingChat = chatDao.findById(chatId)
        if (existingChat == null) {
            // A DM's title is resolved from the contact when shown.
            chatDao.upsert(
                ChatEntity(
                    id = chatId,
                    isGroup = false,
                    name = "",
                    memberHashes = srcHex,
                )
            )
        } else if (existingChat.isArchived) {
            chatDao.unarchiveChat(chatId)
        }

        messageDao.upsert(
            MessageEntity(
                id = msgId,
                chatId = chatId,
                senderHashHex = srcHex,
                content = content,
                timestamp = (timestamp * 1000).toLong(),
                isOutbound = false,
                state = RetichatBridge.MessageState.DELIVERED,
            )
        )

        // Save file attachments to disk + DB
        saveInboundAttachments(msgId, fields)

        // Show notification unless the user is viewing this chat
        if (RetichatApp.activeChatId != chatId) {
            val senderName = resolvedName(srcHex)
            Log.i(TAG, "Posting notification: sender=$senderName")
            MessageNotificationHelper.notify(appContext, senderName, content, chatId)
        }
    }

    /** Leave a group: the user's leave to its members, and for good ([quitGroup]). */
    suspend fun leaveGroupChat(chatId: String): Boolean = quitGroup(chatId, "left")

    /**
     * A decline or a leave ([how], for the log) of group [chatId], one path
     * for both: the user's leave goes to every listed member that has not
     * left ([GroupMemberStatuses.leaveTargets]: a still-invited member too),
     * as GROUP_ACTION `leave` ([GroupChatManager.sendLeave]); the group is
     * recorded as closed (James, 2026-10-01: "Once the group is
     * rejected/left, that person cannot rejoin": a later invite to it is
     * ignored, so nothing offers it again); then its chat goes. The sends
     * neither wait for nor undo the close. Until 2026-10-05 a leave was a
     * plain post "left the group" with no action (audit L5), sent to the
     * accepted members only, and nothing was recorded.
     *
     * Returns whether it was done; false when refused (nothing changed), and
     * the screen stays.
     */
    private suspend fun quitGroup(chatId: String, how: String): Boolean {
        val chat = chatDao.findById(chatId) ?: return false
        if (!chat.isGroup) return false
        // As the group's own address, resolved now (GROUPS-DISTRO-DESIGN.md
        // §8.1): refused when it cannot be signed for (§8.7: only "Delete from
        // this device" is offered there, [deleteGroupFromThisDevice]) or the
        // stack is not up (NOT_READY: nothing is offered until it is).
        val signer = groupSigner(chat) ?: run {
            Log.w(TAG, "quitGroup: ${chat.id} $how refused: its own address ${chat.ownMember?.take(8)} has no signer on this device (${signerKind(chat)}, §8.7)")
            return false
        }
        val groupIdHex = chat.groupIdHex
        val members = messageDao.groupMembersList(chatId)
        val self = groupSelf(chat, members)
        val targets = GroupMemberStatuses.leaveTargets(members, self.own).filter { !self.isSelf(it) }
        if (groupIdHex != null) {
            UserPreferences.closeGroup(appContext, groupIdHex)
            groupChatManager?.sendLeave(signer, groupIdHex, targets)
                ?: Log.w(TAG, "quitGroup: stack offline, the leave of ${chat.id} is not sent")
            // §10.6: in a D-group, one `leave` group sync in the same step, so every
            // other device of D closes it too (§13); the id goes in D's left list.
            if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {
                UserPreferences.recordGroupsLeft(appContext, signer.hex, listOf(groupIdHex))
                DistroOutboxRuntime.oweGroupSync(
                    appContext, signer.hex,
                    GroupSync(
                        op = DistroGroupSync.LEAVE, groupId = groupIdHex, atMs = System.currentTimeMillis(),
                        left = UserPreferences.groupsLeftBy(appContext, signer.hex),
                    ),
                )
            }
            forgetHeldArrivals(groupIdHex, "the user $how it")
        }
        deleteChatLocal(chat)
        Log.i(TAG, "Group ${chat.id} $how (leave to ${targets.size} member(s), as ${signer.hex.take(8)})")
        return true
    }

    private fun selfHex(): String = selfDestHash.toHex()

    private suspend fun deleteChatLocal(chat: ChatEntity) {
        messageDao.attachmentsForChat(chat.id).forEach { attachment ->
            runCatching {
                if (attachment.localPath.isNotBlank()) {
                    File(attachment.localPath).delete()
                }
            }
        }
        messageDao.deleteAttachmentsForChat(chat.id)
        messageDao.deleteDeliveryTrackingForChat(chat.id)
        messageDao.deleteMessagesForChat(chat.id)
        messageDao.deleteGroupMembersForChat(chat.id)
        // The creator's copies and the entries held for a key go with the group.
        groupStatusDao.deleteCopies(chat.id)
        groupStatusDao.deleteHeld(chat.id)
        chatDao.delete(chat)
        UserPreferences.setChatMuted(appContext, chat.id, false)
        Log.i(TAG, "Deleted chat ${chat.id}")
    }

    /**
     * Called when we receive a delivery announce from the network.
     * DISPLAY_NAMES.md §5.1: the announce name (cleaned by the router,
     * "Anonymous Peer" as none) replaces the contact's `announceName` on every
     * announce, and an announce without a name clears it. One carrying a
     * name also drops a migrated `legacyName`. The other slots are never
     * touched. Announces from destinations that are not contacts are
     * ignored.
     */
    fun onAnnounceReceived(destHash: ByteArray, displayName: String?) {
        val hex = destHash.toHex()
        // RFed SPEC §17.10: the announce is the one place an address says it is
        // a distro. Remember it so the send path needs no lookup later.
        if (RetichatBridge.peerIsDistro(destHash) && !UserPreferences.isDistroContact(appContext, hex)) {
            UserPreferences.setDistroContact(appContext, hex, true)
            Log.i(TAG, "contact ${hex.take(8)} announced as a distro address")
        }
        scope.launch(Dispatchers.IO) {
            // The key an announce brings may be one an accept or leave is held for.
            groupStatuses.decideHeld(hex)
            val existing = contactDao.findByHash(hex)
            if (existing != null) {
                // §5.4: an old web build can still announce "<name> (<12 hex>)".
                val announced = displayName?.let { DisplayNames.stripOwnHashSuffix(it, hex) }
                when (val update = DisplayNames.acceptAnnounceName(existing.announceName, announced, existing.legacyName)) {
                    NameUpdate.Unchanged -> Unit
                    is NameUpdate.Set -> {
                        contactDao.setAnnounceName(hex, update.name)
                        Log.i(TAG, "name: ${hex.take(8)} announceName ${if (update.name == null) "cleared" else "set"}")
                    }
                }
            } else {
                // Ignore announces from unknown destinations – never auto-create contacts
                Log.d(TAG, "Ignored announce from unknown destination: $hex")
            }
        }
    }

    // ---- Attachments ----

    /**
     * Extract file attachments from LXMF fields, save each to disk,
     * and insert [AttachmentEntity] records in the database.
     */
    private suspend fun saveInboundAttachments(msgId: String, fields: LxmfFields) {
        val attachments = fields.getFileAttachments()
        if (attachments.isEmpty()) return

        for ((filename, data) in attachments) {
            try {
                val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destFile = File(attachmentDir, "${msgId}_$safeFilename")
                destFile.writeBytes(data)

                val mimeType = guessMimeType(filename)
                messageDao.insertAttachment(
                    AttachmentEntity(
                        messageId = msgId,
                        filename = filename,
                        mimeType = mimeType,
                        localPath = destFile.absolutePath,
                    )
                )
                Log.d(TAG, "Saved attachment: $filename (${data.size} bytes) for msg $msgId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save attachment: $filename", e)
            }
        }
    }

    /**
     * Save outbound attachments to disk + DB so they display in the conversation.
     */
    suspend fun saveOutboundAttachments(msgId: String, attachments: List<Pair<String, ByteArray>>) {
        for ((filename, data) in attachments) {
            try {
                val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destFile = File(attachmentDir, "${msgId}_$safeFilename")
                destFile.writeBytes(data)

                val mimeType = guessMimeType(filename)
                messageDao.insertAttachment(
                    AttachmentEntity(
                        messageId = msgId,
                        filename = filename,
                        mimeType = mimeType,
                        localPath = destFile.absolutePath,
                    )
                )
                Log.d(TAG, "Saved outbound attachment: $filename (${data.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save outbound attachment: $filename", e)
            }
        }
    }

    /** Get attachments for a message (for UI display). */
    suspend fun getAttachments(msgId: String): List<AttachmentEntity> =
        messageDao.attachmentsFor(msgId)

    /** Get attachments as a reactive flow. */
    fun attachmentsForFlow(msgId: String) = messageDao.attachmentsForFlow(msgId)

    /** Guess MIME type from file extension. */
    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    // ---- Helpers ----

    private fun directChatId(peerHash: ByteArray): String = "dm_${peerHash.toHex()}"

    private fun ContactEntity.toDomain() = Contact(
        destHash = destHashHex.hexToBytes(),
        displayName = DisplayNames.contact(names(), destHashHex),
        publicKey = publicKeyHex?.hexToBytes(),
        addedAt = addedAt,
        localName = localName,
        isAllowlisted = isAllowlisted,
    )
}
