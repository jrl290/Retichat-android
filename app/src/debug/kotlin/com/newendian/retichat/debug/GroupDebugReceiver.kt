package com.newendian.retichat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.newendian.retichat.GroupMemberStatuses
import com.newendian.retichat.MemberStatus
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.data.db.entity.ChatEntity
import com.newendian.retichat.data.repository.GroupCreation
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.UserPreferences
import com.newendian.retichat.ui.conversation.OutgoingText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook for groups (RFed-spec Group.md, "Member statuses and
 * keys"), for test-harnesses/staging/probe_phone_group_statuses.mjs. Each op
 * calls what the screen calls and logs its answer under the "GroupDebug" tag:
 *
 *   adb shell am broadcast -a com.newendian.retichat.DEBUG_GROUP \
 *     -n com.newendian.retichat/.debug.GroupDebugReceiver --es op <op> [...]
 *
 *   whoami                         this device's lxmf.delivery address
 *   filter_status                  the Settings "Privacy filter" switch: on or off
 *   filter_set --ez on true|false  the switch, as Settings makes it (setCoreFilterStrangers)
 *   contact_add --es hash <hex>    the Add Contact flow (ChatRepository.addContact): a listed, allowlisted contact
 *   contact_status --es hash <hex> whether the device has a row for it, listed, allowlisted, with a key
 *   contact_forget --es hash <hex> DEBUG ONLY, no screen does this: removes a contact row (the probe's own throwaway
 *                                  identities, once it is done with them)
 *   group_list                     the groups the chat list shows: id, name, the user's state, the recorded creator,
 *                                  each member's status
 *   group_accept --es group <id>   the invite's Accept button (ConversationViewModel.acceptInvite)
 *   group_decline --es group <id>  the invite's Decline button (ConversationViewModel.declineInvite)
 *   group_leave --es group <id>    the chat menu's Leave (ConversationScreen onLeave: leaveGroupChat)
 *   group_create --es name <name> --es members <hex>[,<hex>...]
 *                                  the New Group screen's Create (NavGraph createGroup: ChatRepository.createGroupChat),
 *                                  the members picked from the contacts (GROUPS-DISTRO-DESIGN.md §8.5)
 *   group_post --es group <id> --es text <text>
 *                                  the composer's Send in the group (ConversationViewModel.send: OutgoingText.of, then
 *                                  sendMessage). No spaces in the text: adb passes it through the device's shell.
 *
 * <id> is the group's id as the wire carries it (32 hex), a prefix of it of 8 or more, or the chat's own id
 * (`group_<16 hex>`). Answers:
 *
 *   whoami -> address=<32 hex>
 *   filter_status -> on|off
 *   filter_set -> on|off
 *   contact_add -> true hash=<hex>
 *   contact_status -> contact=<bool> allowlisted=<bool> key=<bool> row=<bool> hash=<hex>
 *   contact_forget -> true|NO_ROW hash=<hex>
 *   group_list: group=<id> chat=<chat id> name="<name>" me=<invited|accepted|left|-> held=<NONE|PENDING|JOINED> creator=<hex|-> members=<hex>=<status>,...
 *       (one line for each group, then)
 *   group_list -> <n> group(s)
 *   group_accept -> Accepted|Deferred|REFUSED|NOT_HELD group=<id> ...
 *   group_decline -> Declined|REFUSED|NOT_HELD group=<id> ...
 *   group_leave -> Left|STILL_HELD|REFUSED|NOT_HELD group=<id> ...
 *   group_create -> Created group=<id> chat=<chat id> own=<hex> | REFUSED why=<signer> | REFUSED reason=<why>
 *   group_post -> Sent|REFUSED|NOT_HELD group=<id> ...
 *
 * Accept is Deferred while a member's key has not arrived (the screen then does nothing: the user taps again once
 * the invite's other chunks are here), so a harness repeats the op until it answers Accepted.
 *
 * What the app itself logs for a group (tag ChatRepo unless noted), for the probe to read:
 *   Created PENDING group from invite: chatId=..., groupId=...        an invite held
 *   onMessageReceived: DROPPED group <action> for <gid8> from <src8> (group NONE|PENDING|JOINED, ...)
 *                                                                      what the group rule or the filter dropped
 *   Group <gid8>: <action> of <src12> arriving directly|in a status counted, verified|unverified (signature=N)
 *   Status for group <gid8>: <n> element(s), <c> counted, <h> held, <s> skipped   a status received
 *   Element <i> of the status for group <gid8>: counted | held ... | Skipped element <i> ...: <why>
 *   Status for group <chat id> with <n> element(s) to <member12>      the status this device sends as the creator
 *   Accept|Leave for group <gid8> to <n> member(s), own key carried   (tag GroupChatManager)
 */
class GroupDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RetichatApp
        val op = intent.getStringExtra("op") ?: "whoami"
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { run(app, op, intent) }
                .onFailure { Log.w(TAG, "$op -> ERROR ${it::class.java.simpleName}: ${it.message}") }
        }
    }

    private suspend fun run(app: RetichatApp, op: String, intent: Intent) {
        val repo = app.repository
        when (op) {
            "whoami" -> Log.i(TAG, "whoami -> address=${repo.selfDestHash.hex()}")

            "filter_status" -> Log.i(TAG, "filter_status -> ${onOff(UserPreferences.isFilterStrangersEnabled(app))}")

            // Settings > Privacy filter: ChatRepository.setCoreFilterStrangers, read back from the preference.
            "filter_set" -> {
                repo.setCoreFilterStrangers(intent.getBooleanExtra("on", true))
                Log.i(TAG, "filter_set -> ${onOff(UserPreferences.isFilterStrangersEnabled(app))}")
            }

            "contact_add" -> {
                val hash = hashArg(intent)
                if (hash == null) { Log.w(TAG, "contact_add -> REFUSED reason=hash is not 32 hex"); return }
                repo.addContact(DistroCodec.hexToBytes(hash)!!)
                Log.i(TAG, "contact_add -> true hash=$hash")
            }

            "contact_status" -> {
                val hash = hashArg(intent)
                if (hash == null) { Log.w(TAG, "contact_status -> REFUSED reason=hash is not 32 hex"); return }
                val row = app.database.contactDao().findByHash(hash)
                Log.i(
                    TAG,
                    "contact_status -> contact=${row?.isContact == true} allowlisted=${row?.isAllowlisted == true} " +
                        "key=${row?.publicKeyHex?.length == 128} row=${row != null} hash=$hash",
                )
            }

            "contact_forget" -> {
                val hash = hashArg(intent)
                if (hash == null) { Log.w(TAG, "contact_forget -> REFUSED reason=hash is not 32 hex"); return }
                val dao = app.database.contactDao()
                val row = dao.findByHash(hash)
                if (row == null) { Log.i(TAG, "contact_forget -> NO_ROW hash=$hash"); return }
                dao.delete(row)
                runCatching { RetichatBridge.unwatchAnnounce(DistroCodec.hexToBytes(hash)!!) }
                Log.i(TAG, "contact_forget -> true hash=$hash")
            }

            // The groups the chat list draws (chatPreviews), each with the members' statuses as this device holds them.
            "group_list" -> {
                val self = repo.selfDestHash.hex()
                val chats = app.database.chatDao().chatPreviews().first().filter { it.isGroup }
                for (preview in chats) {
                    val chat = app.database.chatDao().findById(preview.id) ?: continue
                    val members = app.database.messageDao().groupMembersList(chat.id)
                    val statuses = members.map { it.destHashHex }.filter { it.isNotEmpty() }.distinct().sorted()
                        .joinToString(",") { "$it=${GroupMemberStatuses.statusOf(members, it)}" }
                    Log.i(
                        TAG,
                        "group_list: group=${chat.groupIdHex ?: "-"} chat=${chat.id} name=\"${chat.name}\" " +
                            "me=${GroupMemberStatuses.statusOf(members, self) ?: "-"} " +
                            "held=${GroupMemberStatuses.held(true, members, self)} creator=${chat.creatorHex ?: "-"} members=$statuses",
                    )
                }
                Log.i(TAG, "group_list -> ${chats.size} group(s)")
            }

            // The invite's Accept button: ConversationViewModel.acceptInvite -> acceptGroupInvite, offered only while
            // isPendingGroupInvite. The repository defers (and says so) while a member's key is missing.
            "group_accept" -> {
                val chat = heldGroup(app, op, intent) ?: return
                if (!repo.isPendingGroupInvite(chat.id).first()) {
                    Log.w(TAG, "group_accept -> REFUSED group=${chat.groupIdHex} reason=not a pending invite (the screen offers Accept for one only)")
                    return
                }
                repo.acceptGroupInvite(chat.id)
                val me = myStatus(app, chat)
                Log.i(TAG, "group_accept -> ${if (me == MemberStatus.ACCEPTED) "Accepted" else "Deferred"} group=${chat.groupIdHex} me=$me")
            }

            // The invite's Decline button: ConversationViewModel.declineInvite -> declineGroupInvite (the user's leave).
            "group_decline" -> {
                val chat = heldGroup(app, op, intent) ?: return
                if (!repo.isPendingGroupInvite(chat.id).first()) {
                    Log.w(TAG, "group_decline -> REFUSED group=${chat.groupIdHex} reason=not a pending invite (the screen offers Decline for one only)")
                    return
                }
                repo.declineGroupInvite(chat.id)
                Log.i(TAG, "group_decline -> ${if (app.database.chatDao().findById(chat.id) == null) "Declined" else "STILL_HELD"} group=${chat.groupIdHex}")
            }

            // The chat menu's Leave: ConversationScreen's onLeave -> leaveGroupChat (its sends neither wait for nor undo
            // the close; the chat is gone from this device afterwards).
            "group_leave" -> {
                val chat = heldGroup(app, op, intent) ?: return
                repo.leaveGroupChat(chat.id)
                Log.i(TAG, "group_leave -> ${if (app.database.chatDao().findById(chat.id) == null) "Left" else "STILL_HELD"} group=${chat.groupIdHex}")
            }

            // The New Group screen's Create: NavGraph's createGroup -> ChatRepository.createGroupChat(name, members), the
            // members picked from the contacts the screen lists. Created as the distro when one is loaded (§8.5).
            "group_create" -> {
                val name = (intent.getStringExtra("name") ?: "").trim()
                val members = (intent.getStringExtra("members") ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (name.isEmpty() || members.isEmpty() || members.any { !Regex("[0-9a-f]{32}").matches(it) }) {
                    Log.w(TAG, "group_create -> REFUSED reason=a name and one or more 32-hex members are needed")
                    return
                }
                when (val made = repo.createGroupChat(name, members.map { DistroCodec.hexToBytes(it)!! })) {
                    is GroupCreation.Created -> {
                        val chat = app.database.chatDao().findById(made.chatId)
                        Log.i(TAG, "group_create -> Created group=${chat?.groupIdHex ?: "-"} chat=${made.chatId} own=${chat?.ownMember ?: repo.selfDestHash.hex()}")
                    }
                    is GroupCreation.Refused -> Log.w(TAG, "group_create -> REFUSED why=${made.why}")
                }
            }

            // The composer's Send in a group: ConversationViewModel.send -> OutgoingText.of(draft), then
            // ChatRepository.sendMessage, which answers why when the post was refused (§8.7). A blank draft does not send.
            "group_post" -> {
                val text = OutgoingText.of(intent.getStringExtra("text") ?: "")
                val chat = heldGroup(app, op, intent) ?: return
                if (text.isEmpty()) { Log.w(TAG, "group_post -> REFUSED group=${chat.groupIdHex} reason=blank text"); return }
                val refused = repo.sendMessage(chat.id, text)
                if (refused != null) Log.w(TAG, "group_post -> REFUSED group=${chat.groupIdHex} why=${refused.signer} own=${refused.own}")
                else Log.i(TAG, "group_post -> Sent group=${chat.groupIdHex} chat=${chat.id}")
            }

            else -> Log.w(TAG, "$op -> UNKNOWN_OP (whoami, filter_status, filter_set, contact_add, contact_status, contact_forget, group_list, group_accept, group_decline, group_leave, group_create, group_post)")
        }
    }

    /** The group named by `--es group`, or null (logged) when this device holds none by that id. */
    private suspend fun heldGroup(app: RetichatApp, op: String, intent: Intent): ChatEntity? {
        val id = (intent.getStringExtra("group") ?: "").trim().lowercase()
        val dao = app.database.chatDao()
        val chat = (dao.findByGroupId(id) ?: dao.findById(id)
            ?: id.takeIf { it.length >= 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }?.let { dao.findByGroupIdPrefix("$it%") })
            ?.takeIf { it.isGroup }
        if (chat == null) Log.w(TAG, "$op -> NOT_HELD group=$id")
        return chat
    }

    private suspend fun myStatus(app: RetichatApp, chat: ChatEntity): String? =
        GroupMemberStatuses.statusOf(
            app.database.messageDao().groupMembersList(chat.id),
            app.repository.selfDestHash.hex(),
        )

    private fun hashArg(intent: Intent): String? =
        (intent.getStringExtra("hash") ?: "").trim().lowercase().takeIf { Regex("[0-9a-f]{32}").matches(it) }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "GroupDebug" }
}
