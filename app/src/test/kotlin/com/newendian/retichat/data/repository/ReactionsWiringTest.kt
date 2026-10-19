package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REACTIONS-DESIGN.md §3 on every receive path, as the repository is wired:
 * a reaction is taken before anything that would store a bubble, count
 * unread, or make a chat or a contact; `find_target` looks only in the chat it
 * came in on; someone else's reaction to the user's message is notified,
 * citing it, and the user's own never is; and the user's own reaction is
 * applied at once and sent off the caller's thread. The repository is Android (Room, the JNI): its
 * source is what is pinned; the rules are [Reactions]'s (ReactionsTest), the
 * lookups' SQL ReactionsMigrationTest's.
 */
class ReactionsWiringTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val repo = src("data/repository/ChatRepository.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(decl: String): String {
        val start = repo.indexOf(decl)
        assertTrue("$decl not found", start >= 0)
        val end = nextMember.find(repo, start + decl.length)?.range?.first ?: repo.length
        return repo.substring(start, end)
    }

    /** Each of [parts] in [text], in this order. */
    private fun inOrder(text: String, vararg parts: String) {
        var at = -1
        for (part in parts) {
            val next = text.indexOf(part, at + 1)
            assertTrue("\"$part\" after the one before it", next > at)
            at = next
        }
    }

    @Test
    fun aDirectReactionIsTakenAfterTheFilterAndBeforeAnyContactOrBubble() {
        val received = body("fun onMessageReceived(")
        inOrder(
            received,
            "DROPPED by the privacy filter",
            "val reaction = if (groupId == null || groupAction == null) fields.reaction() else LxmfFields.Reaction.None",
            "takeDirectReaction(directChatId(srcHash), srcHex, reaction, (timestamp * 1000).toLong(), \"direct\")",
            "return@launch",
            "if (reaction == LxmfFields.Reaction.None) ensureContact(srcHex)",
            "handleDirectMessage(",
        )
        val distro = body("fun onDistroMessageReceived(")
        inOrder(distro, "val reaction = fields.reaction()", "takeDirectReaction(directChatId(srcHash), srcHex,", "return@launch", "ensureContact(srcHex)", "handleDirectMessage(")
    }

    @Test
    fun theDistroSyncOfASendAppliesAReactionAsTheDistroInTheChatWithTheRecipientOnly() {
        val copy = body("fun onDistroSentCopy(")
        inOrder(
            copy,
            "val reaction = fields.reaction()",
            "takeDirectReaction(directChatId(recipient), distroHex, reaction, (timestamp * 1000).toLong(), \"a Distro Sync of a send\", byUser = true)",
            "return@launch",
            "ensureContact(recipientHex)",
            "chatDao.upsert(",
            "lxmfHash = fields.sentHash(),",
        )
    }

    @Test
    fun findTargetLooksOnlyInTheReactionsOwnChatAndEveryDropIsOneLine() {
        val direct = body("private suspend fun takeDirectReaction(")
        assertTrue(direct.contains("messageDao.findByLxmfHash(chatId, reaction.toHex)"))
        assertTrue(direct.contains("\"reaction dropped: target "))
        assertTrue(direct.contains("\"reaction dropped: \${reaction.why}"))
        assertFalse(direct.contains("ensureContact("))
        assertFalse(direct.contains("chatDao.upsert("))
        val group = body("private suspend fun takeGroupReaction(")
        assertTrue(group.contains("messageDao.findByPostId(chat.id, valid.toHex)"))
        assertTrue(group.contains("if (valid.to.size != 16) {"))
        // Attributed as a post is: a self address's only when its signature validated, else GROUP_SENDER's under the member rules.
        inOrder(group, "DeliveryPolicy.groupPost(standing.isSelf(srcHex), signature, hasContent = true)", "UNPROVEN_OWN", "OWN -> srcHex", "groupAuthor(fields, srcHex, standing, signature)")
        assertFalse(group.contains("ensureContact("))
        assertFalse(group.contains("MessageNotificationHelper"))
        // Notified only once applied, and never the user's own.
        inOrder(direct, "val applied = applyReaction(", "if (applied && !byUser) notifyReaction(target, reactor, reaction.content, chatId)")
        inOrder(group, "val applied = applyReaction(", "if (applied && !standing.isSelf(reactor)) notifyReaction(target, reactor, valid.content, chat.id, conversationTitle = chat.name)")
        assertFalse(body("fun onMessageReceived(").contains("byUser = true"))
        assertFalse(body("fun onDistroMessageReceived(").contains("byUser = true"))
        assertFalse(body("private suspend fun applyGroupSync(").contains("notifyReaction("))
        // Applied under one lock, the reactions column only: the row's rowid, so the chat list's order, stays.
        val apply = body("private suspend fun applyReaction(")
        inOrder(apply, "reactionLock.withLock {", "Reactions.apply(row.reactionsJson, reactor, emoji, atMs)", "messageDao.setReactionsJson(targetId, json)")
        assertFalse(apply.contains("upsert("))
        assertFalse(apply.contains("MessageNotificationHelper"))
    }

    @Test
    fun aReactionNotifiesOnlyOnTheUsersOwnMessageCitingItOutsideTheOpenChat() {
        val notify = body("private suspend fun notifyReaction(")
        inOrder(
            notify,
            // Outside the chat on screen: MessageNotificationHelper.notify's check
            // since 2026-10-19 (ChatOnScreenNotificationTest).
            "if (emoji.isEmpty() || !target.isOutbound) return",
            "MessageNotificationHelper.notify(",
            "resolvedName(reactor), Reactions.notificationText(emoji, target.content), chatId,",
            "conversationTitle = conversationTitle,",
        )
    }

    @Test
    fun aGroupReactionIsTakenWhereAPostIsBeforeAnyBubbleAndRelaysCarryIt() {
        val handler = body("private suspend fun handleGroupMessage(")
        inOrder(
            handler,
            "val reaction = fields.reaction()",
            "return takeGroupReaction(msgId, chat, srcHex, reaction, timestamp, fields, standing, signature)",
            "val post = DeliveryPolicy.groupPost(",
        )
        // Posts keep their id; a copy arriving again keeps the reactions its row has.
        assertEquals(2, Regex(Regex.escape("postId = fields.postId(),")).findAll(handler).count())
        assertTrue(handler.contains("reactionsJson = messageDao.findById(msgId)?.reactionsJson,"))
        // A relay carries key 11 and the 0x40 along; a malformed reaction is not relayed.
        inOrder(handler, "val relayedReaction = when (val r = fields.reaction()) {", "not relayed)", "postId                 = fields.postId(),", "reaction               = relayedReaction,")
        // Through the distro too, and with no contact for a reaction.
        val distro = body("suspend fun onDistroGroupMessage(")
        assertTrue(distro.contains("if (action != null || fields.reaction() == LxmfFields.Reaction.None) ensureContact(srcHex)"))
    }

    @Test
    fun theDistroSyncOfGroupActionsAppliesAReactAsTheDistroAndFilesAPostsId() {
        val apply = body("private suspend fun applyGroupSync(")
        inOrder(apply, "DistroGroupSync.Effect.ApplyReact -> {", "messageDao.findByPostId(chat!!.id, postId)", "reaction dropped: target", "applyReaction(target.id, distro, sync.text.orEmpty(), sync.atMs,")
        assertTrue(body("private suspend fun storePostFromSync(").contains("postId = sync.postId,"))
    }

    @Test
    fun theUsersReactionIsAppliedAtOnceAndSentOffTheCallersThreadWithNoBubble() {
        val direct = body("private suspend fun sendDirectReaction(")
        inOrder(
            direct,
            "if (!stackReady()) {",
            "Reactions.toggled(Reactions.of(msg.reactionsJson, reactor), picked)",
            "applyReaction(msg.id, reactor, emoji, atMs,",
            "scope.launch(Dispatchers.IO) {",
            "dispatch(",
            "reaction = Reactions.Outgoing(target, emoji))",
        )
        assertFalse(direct.contains("messageDao.upsert("))
        val group = body("private suspend fun sendGroupReaction(")
        inOrder(group, "val postId = msg.postId ?: return", "GroupSelves.Post.WAIT", "groupSigner(chat)", "applyReaction(msg.id, signer.hex, emoji, atMs,", "reaction = GroupReaction(postId, emoji))")
        // No row for a reaction: nothing tracked, polled or marked.
        val fanOut = body("private suspend fun fanOutGroupPost(")
        assertTrue(fanOut.contains("if (groupMsgId != null) memberHexes.forEach { memberHex ->"))
        assertTrue(fanOut.contains("if (groupMsgId != null) messageDao.updateState(groupMsgId, RetichatBridge.MessageState.SENT)"))
        val dispatch = body("private suspend fun dispatch(")
        assertTrue(dispatch.contains("bubble = reaction == null,"))
        assertTrue(dispatch.contains("PropagationFallbacks.Send(localId, directHandle = msgHandle, bubble = false)"))
        // A post's id from the start.
        assertTrue(body("private suspend fun holdGroupPost(").contains("postId = Reactions.newPostId(),"))
        assertTrue(body("suspend fun sendGroupMessage(").contains("Reactions.newPostId()"))
    }
}
