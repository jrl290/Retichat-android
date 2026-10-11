package com.newendian.retichat.data.repository

import com.newendian.retichat.FilteredInvites
import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.data.db.entity.GroupMemberEntity
import com.newendian.retichat.service.DistroCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the groups-and-the-distro decisions are wired in (GROUPS-DISTRO-DESIGN.md
 * §18.2, the Android rows of §19.2). The decisions themselves are tested on
 * their own (DistroGroupSyncTest, GroupsDistroPolicyTest,
 * GroupStatusesDistroTest); the repository and the distro client need Room and
 * the native library, so what reaches them is pinned in their source here.
 */
class GroupsDistroWiringTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val repo = src("data/repository/ChatRepository.kt")
    private val client = src("service/RfedDistroClient.kt")
    private val gcm = src("service/GroupChatManager.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private fun count(text: String, what: String) = Regex(Regex.escape(what)).findAll(text).count()

    private fun inOrder(text: String, vararg marks: String) {
        var at = -1
        for (mark in marks) {
            val next = text.indexOf(mark, at + 1)
            assertTrue("'$mark' not found after the previous mark", next > at)
            at = next
        }
    }

    // ---- §6.1, §6.2: the distro path's routing and its repeat keys ----

    @Test
    fun theGroupBranchesComeAfterTheChannelSyncAndBeforeTheSourceAndTimestampKey() {
        val handle = body(client, "handleBlob")
        inOrder(
            handle,
            "handleChannelSync(context, srcHex, timestamp, channelSync",
            "DistroGroupSync.route(groupKeys.groupSync, groupKeys.hasGroupId, groupKeys.groupId)",
            "is DistroGroupSync.Route.Sync ->",
            "is DistroGroupSync.Route.Message ->",
            "DistroGroupSync.Route.BadGroupId ->",
            "DistroGroupSync.Route.Other -> Unit",
            "val key = DistroCodec.seenKey(srcHex, timestamp)",
            "UserPreferences.markDistroSeen(context, key)",
            "offerTransfer(srcHex, transferKey)",
            "if (isNotification) return",
            "app.repository.onDistroMessageReceived(",
        )
        // Each group branch ends there: group traffic never reaches the src:ts key nor the DM path.
        assertTrue(handle.contains("handleGroupSync(context, blob, groupKeys, route.read, srcHex, o.optBoolean(\"signature_validated\", false))\n                return"))
        assertTrue(handle.contains("handleGroupMessage(context, blob, groupKeys, route.groupId, srcHex, content, timestamp, fieldsRaw, nameField, unverifiedReason)\n                return"))
        assertTrue(handle.contains("with an unusable group id DROPPED (§6.1)\")\n                return"))
        // The keys read are the unwrap's, through the defensive reader.
        assertTrue(handle.contains("DistroGroupKeys.read { k -> if (o.has(k)) jsonValue(o.opt(k)) else null }"))
    }

    @Test
    fun aGroupArrivalsKeyIsTheHashCheckedFirstAndRecordedOnlyWhenItIsKept() {
        for ((name, key) in listOf("handleGroupSync" to "DistroGroupSync.seenKey(hash)", "handleGroupMessage" to "DistroGroupSync.messageSeenKey(hash)")) {
            val b = body(client, name)
            assertTrue(name, b.contains("val key = $key"))
            // Rule 0 and the record through the one gate (DistroGroupSync.throughRule0, run by
            // DistroGroupSyncRulesTest), never by hand here.
            inOrder(
                b,
                "groupLock.withLock {",
                "DistroGroupSync.throughRule0(key, seenRecord(context)) {",
                "app.repository.onDistroGroup",
            )
            assertEquals(name, 0, count(b, "markDistroSeen("))
            assertFalse(name, b.contains("hasDistroSeen"))
            assertFalse(name, b.contains("forgetDistroSeen"))
        }
        val record = body(client, "seenRecord")
        assertTrue(record.contains("override fun has(key: String): Boolean = UserPreferences.hasDistroSeen(context, key)"))
        assertTrue(record.contains("UserPreferences.markDistroSeen(context, key)"))
        // The outcome: a G or message held, or dropped, is not recorded (§6.2, §11.1).
        val sync = body(repo, "onDistroGroupSync")
        assertTrue(sync.contains("DistroGroupSync.Disposition.OwnEcho -> DistroGroupOutcome.KEPT"))
        assertEquals("rules 1, 2 and 4", 3, count(sync, "DistroGroupOutcome.DROPPED"))
        assertTrue(body(repo, "onDistroGroupMessage").contains("if (held) DistroGroupOutcome.HELD else DistroGroupOutcome.DROPPED"))
    }

    @Test
    fun whatWasHeldForAGroupIsFedAgainInArrivalOrderOnceItIsJoinedHere() {
        val replay = body(client, "replayHeldArrivals")
        inOrder(replay, "scope.launch {", "app.repository.takeHeldArrivals(groupId)", "for (blob in blobs) handleBlob(context, blob)")
        // Launched, never awaited under the lock the G's apply holds.
        assertTrue(body(client, "handleGroupSync").contains("replayHeldArrivals(context, groupId)"))
        // The user's accept, and an accept or create G that joins or holds the group, feed it.
        assertTrue(body(repo, "acceptGroupInvite").contains("RfedDistroClient.replayHeldArrivals(appContext, groupId)"))
        assertEquals(2, count(body(repo, "applyGroupSync"), "replay(sync.groupId)"))
        // In arrival order, and only what came through the distro held now.
        val take = body(repo, "takeHeldArrivals")
        inOrder(take, "groupStatusDao.arrivalsFor(groupId)", "groupStatusDao.dropArrivalsFor(groupId)", "rows.partition { it.distro == distro }")
    }

    // ---- §6.3, §6.4, §12.3: what the distro path admits ----

    @Test
    fun groupTrafficThroughTheDistroGoesToTheGroupHandlerNeverToADirectMessage() {
        val gm = body(repo, "onDistroGroupMessage")
        assertFalse(gm.contains("handleDirectMessage("))
        assertFalse(gm.contains("onDistroMessageReceived("))
        // The first rules, the relay request, the invite's list and filter, the group rule and
        // holding are DistroGroupAdmission.decide's (DistroGroupAdmissionTest runs them).
        inOrder(
            gm,
            "groupSender = fields.group(GroupEntry.SENDER),",
            "listed = if (isInvite) DeliveryPolicy.members(fields.group(GroupEntry.MEMBERS)) else emptyList(),",
            "sourceAllowed = isInvite && allowlisted(srcHex),",
            "is DistroGroupAdmission.Verdict.Drop -> {",
            "if (verdict.filterId) UserPreferences.filterInvite(appContext, groupId)",
            "return@withContext DistroGroupOutcome.DROPPED",
            "DistroGroupAdmission.Verdict.Hold -> {",
            "holdArrival(blob, groupId, srcHex, hash,",
            // §6.4: the sender's name row, as the direct path makes it, then the group handler.
            "ensureContact(srcHex)",
            "acceptMessageName(srcHex, nameField, unverifiedReason, timestamp)",
            "handleGroupMessage(hash, groupId, srcHex, content, timestamp, fields, here, signature, keys.senderKey, keys.packed, viaDistro = true)",
        )
        // The direct path runs the same three first rules before the group rule (§6.3).
        inOrder(body(repo, "onMessageReceived"), "DeliveryPolicy.firstRules(", "DeliveryPolicy.groupMessage(", "handleGroupMessage(")
    }

    @Test
    fun theLegacyStopTextMarksNobodyLeft() {
        // §6.9: it had no signature or group-action check.
        assertFalse(repo.contains("STOP received from"))
        assertFalse(repo.contains("findByGroupIdPrefix("))
        assertFalse(body(repo, "handleDirectMessage").contains("MemberStatus.LEFT"))
    }

    @Test
    fun anOlderSiblingsValidatedPostIsTheUsersOwnOutgoingPostAndAnyOtherSelfSourcePostIsDropped() {
        val handle = body(repo, "handleGroupMessage")
        // The post's kind is DeliveryPolicy.groupPost's (GroupMessageKeptTest runs it).
        inOrder(
            handle,
            "val post = DeliveryPolicy.groupPost(standing.isSelf(srcHex), signature,",
            "DeliveryPolicy.GroupPost.UNPROVEN_OWN -> {",
            "DROPPED: its signature did not validate",
            "DeliveryPolicy.GroupPost.OWN -> {",
            "isOutbound = true,",
            "state = RetichatBridge.MessageState.SENT,",
        )
    }

    // ---- §8.1, §8.4: who sends, and how ----

    @Test
    fun everyGroupSendGoesAsTheGroupsOwnAddressResolvedWhenItIsSent() {
        val send = body(repo, "sendGroupMessage")
        // Refused before anything is stored when the own address has no signer here (§8.7).
        inOrder(send, "val signer = groupSigner(chat) ?: run {", "return", "messageDao.upsert(")
        // The fan-out, for a post sent at once and one released from the queue at start-up.
        val fanOut = body(repo, "fanOutGroupPost")
        assertTrue(fanOut.contains("srcHash = signer.hash,"))
        assertTrue(fanOut.contains("identityHandle = signer.identity,"))
        assertTrue(fanOut.contains("GroupFields.set(handle, GroupEntry.SENDER, selfHex)"))
        assertTrue(fanOut.contains("val selfHex = signer.hex"))
        assertTrue(send.contains("senderHashHex = signer.hex,"))
        for (path in listOf(send, fanOut, body(repo, "holdGroupPost"), body(repo, "releaseHeldGroupPost"))) {
            assertFalse(path.contains("selfDestHash"))
        }
        // The control messages too: the manager holds no identity of its own.
        val checked = body(gcm, "sendChecked")
        assertTrue(checked.contains("srcHash = signer.hash,"))
        assertTrue(checked.contains("identityHandle = signer.identity,"))
        assertFalse(gcm.contains("selfDestHash"))
        assertFalse(gcm.contains("fanoutMessage"))
        // Resolved by the strict helper, compared with the own address; never the fallback that ignores the group.
        val signer = body(repo, "groupSigner")
        assertTrue(signer.contains("DistroManager.signing()"))
        assertTrue(signer.contains("if (state.deliveryHashHex != chat.ownMember) null"))
        // NOT_READY: the stack is not up (review of 8d330c4; GroupActionsReadyTest).
        assertTrue(signer.contains("GroupSelves.Signer.FORMER, GroupSelves.Signer.NOT_READY -> null"))
        assertFalse(signer.contains("sendingIdentity"))
        assertFalse(send.contains("sendingIdentity"))
        assertFalse(gcm.contains("DistroManager.sendingIdentity("))
        // Every user action and the creator's answer resolve it and stop without one.
        for (name in listOf("acceptGroupInvite", "quitGroup")) {
            inOrder(body(repo, name), "val signer = groupSigner(chat) ?: run {", "return", "groupChatManager?.send")
        }
        assertTrue(repo.contains("manager.sendStatus(signer, groupId, member, statuses)"))
    }

    @Test
    fun noAppLinkIsOpenedToAMemberThatIsADistro() {
        val checked = body(gcm, "sendChecked")
        inOrder(checked, "if (!DistroAddresses.isDistro(appContext, destBytes)) {", "RetichatBridge.appLinkOpen(routerHandle, destBytes, \"lxmf\", \"delivery\")")
        assertEquals(1, count(gcm, "RetichatBridge.appLinkOpen("))
        val send = body(repo, "fanOutGroupPost")
        inOrder(send, "if (!DistroAddresses.isDistro(appContext, destHash)) {", "RetichatBridge.appLinkOpen(routerHandle, destHash, \"lxmf\", \"delivery\")")
        assertEquals(1, count(send, "RetichatBridge.appLinkOpen("))
        val isDistro = gcm.substring(gcm.indexOf("object DistroAddresses"))
        assertTrue(isDistro.contains("RetichatBridge.peerIsDistro(destHash) || UserPreferences.isDistroContact(context, destHash.toHex())"))
    }

    @Test
    fun aDGroupPostOwesOnePostSyncOnceItsFirstEnvelopeLeftAndNoSentCopy() {
        val send = body(repo, "fanOutGroupPost")
        assertTrue(send.contains("val postSync: GroupSync? = if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {"))
        assertTrue(send.contains("GroupSync(DistroGroupSync.POST, groupIdHex, postedAt, text = content, left = UserPreferences.groupsLeftBy(appContext, signer.hex))"))
        // Owed once: after the router accepted the first envelope, or at once when no member is a target.
        assertEquals(2, count(send, "postSyncOwed.compareAndSet(false, true)"))
        assertEquals(2, count(send, "DistroOutboxRuntime.oweGroupSync(appContext, signer.hex, postSync)"))
        inOrder(send, "val sent = RetichatBridge.messageSendViaAppLinks(handle)", "if (sent) {", "postSyncOwed.compareAndSet(false, true)")
        assertTrue(send.contains("if (memberHexes.isEmpty() && postSync != null && postSyncOwed.compareAndSet(false, true)) {"))
        // A group post is never also a §17.11 copy.
        assertFalse(send.contains("sendDistroSentCopy("))
        assertFalse(send.contains("oweSentCopy("))
        // Never a target: any self address (§9.2).
        assertTrue(send.contains(".filter { !self.isSelf(it) }"))
    }

    // ---- §9.4, §10.6: accept, leave, delete ----

    @Test
    fun theAcceptSkipsEverySelfAddressSoADGroupNeverDefersAndOwesOneAcceptSync() {
        val accept = body(repo, "acceptGroupInvite")
        inOrder(
            accept,
            "val missingKeys = allMembers.filter { !self.isSelf(it) }",
            "messageDao.setGroupMemberStatus(chatId, selfHex, MemberStatus.ACCEPTED)",
            "val others = allMembers.filter { !self.isSelf(it) }",
            "groupChatManager?.sendAccept(signer, groupId, others)",
            "if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {",
            "DistroOutboxRuntime.oweGroupSync(",
            "op = DistroGroupSync.ACCEPT,",
        )
        assertEquals(1, count(accept, "DistroOutboxRuntime.oweGroupSync("))
        // Pending is the own address's row alone.
        assertTrue(body(repo, "isPendingGroupInvite").contains("GroupMemberStatuses.isPendingInvite(members, GroupSelves.ownMember(chat?.ownMember, selfHex()))"))
    }

    @Test
    fun aListNamingBothTheDeviceAndDAcceptedAsDReadsAsJoinedNotPending() {
        val device = "0123456789abcdef0123456789abcdef"
        val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
        val creator = "c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0"
        val rows = listOf(
            GroupMemberEntity(chatId = "g", destHashHex = creator, inviteStatus = MemberStatus.ACCEPTED),
            GroupMemberEntity(chatId = "g", destHashHex = device, inviteStatus = MemberStatus.INVITED),
            GroupMemberEntity(chatId = "g", destHashHex = d, inviteStatus = MemberStatus.ACCEPTED),
        )
        val own = GroupSelves.ownMember(d, device)
        assertEquals(d, own)
        assertEquals(DeliveryPolicy.Held.JOINED, GroupMemberStatuses.held(true, rows, own))
        assertFalse(GroupMemberStatuses.isPendingInvite(rows, own))
        // The device row stays invited for ever, and is not the user's (§9.2).
        assertTrue(GroupMemberStatuses.isPendingInvite(rows, device))
        // A group held at upgrade (own address null) is the device's.
        assertEquals(device, GroupSelves.ownMember(null, device))
    }

    @Test
    fun deletingADGroupLeavesAsDAndOwesOneLeaveSyncAndDeleteFromThisDeviceSendsNothing() {
        inOrder(body(repo, "deleteChat"), "if (chat.isGroup) return quitGroup(chatId, \"deleted\")", "deleteChatLocal(chat)")
        val quit = body(repo, "quitGroup")
        inOrder(
            quit,
            "UserPreferences.closeGroup(appContext, groupIdHex)",
            "groupChatManager?.sendLeave(signer, groupIdHex, targets)",
            "if (GroupSelves.isDistroGroup(chat.ownMember, selfHex())) {",
            "UserPreferences.recordGroupsLeft(appContext, signer.hex, listOf(groupIdHex))",
            "DistroOutboxRuntime.oweGroupSync(",
            "op = DistroGroupSync.LEAVE,",
            "deleteChatLocal(chat)",
        )
        assertEquals(1, count(quit, "sendLeave("))
        assertEquals(1, count(quit, "DistroOutboxRuntime.oweGroupSync("))
        // §8.7: the one path that removes a group without the user's leave. Alignment decision 4
        // (2026-10-11): its id is recorded closed on this device, so a later invite or group sync for
        // it does not bring it back here; it is not put in D's left list (the user did not leave).
        val local = body(repo, "deleteGroupFromThisDevice")
        inOrder(local, "UserPreferences.closeGroup(appContext, groupId)", "deleteChatLocal(chat)")
        for (never in listOf("sendLeave", "oweGroupSync", "quitGroup", "recordGroupsLeft", "groupChatManager")) {
            assertFalse(never, local.contains(never))
        }
    }

    // ---- §11.2: applying a G sends nothing ----

    @Test
    fun applyingAGroupSyncSendsNothingAndALeaveRemovesTheConversationRecordedClosed() {
        for (name in listOf("onDistroGroupSync", "applyGroupSync", "holdGroupFromSync", "joinFromSync", "closeFromSync", "storePostFromSync")) {
            val b = body(repo, name)
            assertFalse(name, b.contains("groupChatManager"))
            assertFalse(name, b.contains("oweGroupSync"))
            assertFalse(name, b.contains("MessageNotificationHelper"))
        }
        inOrder(body(repo, "closeFromSync"), "UserPreferences.closeGroup(appContext, groupId)", "deleteChatLocal(chat)")
        // A create or accept G lets the members through the filter by association, never a self address.
        assertTrue(body(repo, "holdGroupFromSync").contains("listed.filter { !self.isSelf(it) }.forEach { ensureAllowlistedContact(it) }"))
        // `left` is applied after the op, and the ids recorded in D's own list.
        val apply = body(repo, "applyGroupSync")
        assertTrue(apply.contains("DistroGroupSync.leftEffect("))
        assertTrue(apply.contains("UserPreferences.recordGroupsLeft("))
    }

    // ---- §9.1: known siblings ----

    @Test
    fun siblingsAreLearnedOnlyFromWhatDSignedAndThisDeviceAccepted() {
        val handle = body(client, "handleBlob")
        inOrder(handle, "is DistroCodec.SentCopy.Store -> {", "if (o.optBoolean(\"signature_validated\", false)) {", "DistroGroupSync.siblingOf(sentBy,", "app.repository.onDistroSentCopy(")
        inOrder(body(client, "handleChannelSync"), "if (held && signatureValidated && ownDistro != null && srcHex == ownDistro) {", "DistroGroupSync.siblingOf(marker.byHex,")
        // A G teaches its `by` past rules 1-3 (source D, signature D's, not this device).
        assertEquals(2, count(body(repo, "onDistroGroupSync"), "learnSibling(distro!!, disposition.sibling, \"a group sync\")"))
        // Given up, a distro's siblings go unless a group here still has it as its own address:
        // decided by DistroChanges.plan (DistroChangeTest runs it), from the groups' own addresses.
        inOrder(body(repo, "onDistroChanged"), "chatDao.countWithOwnMember(it) > 0", "DistroChanges.plan(holding, sets, ownMembers)", "for (d in plan.forgetSiblingsOf)", "UserPreferences.forgetDistroSiblings(appContext, d)")
    }

    // ---- §18.2 item 10: the placeholder ----

    @Test
    fun anEmptyCopiedTextIsShownWithThePlaceholder() {
        assertEquals("[Attachment not available via the distro address]", DistroCodec.copiedText(""))
        assertEquals("hi", DistroCodec.copiedText("hi"))
        assertTrue(body(repo, "onDistroSentCopy").contains("content = DistroCodec.copiedText(content),"))
        assertTrue(body(repo, "storePostFromSync").contains("content = DistroCodec.copiedText(sync.text.orEmpty()),"))
    }

    // ---- §6.5: the filtered-invite set ----

    @Test
    fun theFilteredInviteSetKeepsTheNewestSixtyFourIdsOnce() {
        var set = emptyList<String>()
        for (i in 0 until 70) set = FilteredInvites.record(set, "%032x".format(i))
        assertEquals(64, set.size)
        assertEquals("%032x".format(6), set.first())
        assertEquals("%032x".format(69), set.last())
        // A repeat moves to the newest end, once.
        set = FilteredInvites.record(set, "%032x".format(10))
        assertEquals(64, set.size)
        assertEquals("%032x".format(10), set.last())
        assertEquals(1, set.count { it == "%032x".format(10) })
    }
}
