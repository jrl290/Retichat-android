package com.newendian.retichat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.data.db.entity.ChannelEntity
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.service.RfedChannelClient
import com.newendian.retichat.service.RfedDistroClient
import com.newendian.retichat.service.StackRuntime
import com.newendian.retichat.service.UserPreferences
import com.newendian.retichat.service.WakeWorker
import com.newendian.retichat.ui.channels.ChannelNameForm
import com.newendian.retichat.ui.conversation.OutgoingText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook for the distro feature, so the staging harness can put
 * a known distro key on the phone without driving the UI:
 *
 *   adb shell am broadcast -a com.newendian.retichat.DEBUG_DISTRO \
 *     -n com.newendian.retichat/.debug.DistroDebugReceiver --es op import --es key <128 hex>
 *   ... --es op generate | forget | register | pull | status | restart | wake
 *   ... --es op channel_join | channel_leave | channel_post | channel_open | channel_notify | channel_list
 *       (see the channel ops below)
 *
 * Results are logged under the "DistroDebug" tag.
 */
class DistroDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (val op = intent.getStringExtra("op") ?: "status") {
            // Refuses to replace a loaded distro unless `--ez replace true`: a
            // harness import overwrote a person's only copy of a key on 2026-09-24.
            // (DistroManager keeps a backup on replace regardless.)
            "import" -> {
                val key = intent.getStringExtra("key") ?: ""
                val incoming = DistroCodec.parsePrivateKey(key)
                val current = DistroManager.exportHex()
                if (DistroManager.hasDistro && incoming != null && !incoming.contentEquals(DistroCodec.hexToBytes(current ?: ""))
                    && !intent.getBooleanExtra("replace", false)
                ) {
                    Log.w(TAG, "import -> REFUSED: device holds distro ${DistroManager.deliveryHashHex}; pass --ez replace true to replace it (a backup is kept)")
                    return
                }
                val ok = DistroManager.importText(app, key)
                Log.i(TAG, "import -> $ok address=${DistroManager.deliveryHashHex}")
                if (ok) RfedDistroClient.registerIfNeeded(app)
            }
            "generate" -> {
                val ok = DistroManager.generate(app)
                Log.i(TAG, "generate -> $ok address=${DistroManager.deliveryHashHex}")
                if (ok) RfedDistroClient.registerIfNeeded(app)
            }
            "forget" -> CoroutineScope(Dispatchers.IO).launch {
                RfedDistroClient.unregister(app)
                DistroManager.forget(app)
                Log.i(TAG, "forget -> done")
            }
            "register" -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "register -> ${RfedDistroClient.register(app)}")
            }
            // Same as the Settings "Restart" button: stop and start the stack
            // in this process (the case that left dead links behind).
            "restart" -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "restart -> ${StackRuntime.restart(app)}")
            }
            "pull" -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "pull -> ${RfedDistroClient.pull(app)} blob(s)")
            }
            // What an FCM push does (RetichatFcmService): enqueue the wake
            // worker, so staging can drive it without a push relay.
            "wake" -> {
                WakeWorker.enqueue(app)
                Log.i(TAG, "wake -> enqueued")
            }
            // Put this phone on the private staging chain (test-harnesses/staging):
            //   --es op staging --es rfed <staging rfed identity hex> --es host 192.168.2.107 --ei port 4242
            // Adds a TCPClientInterface row, points the RFed node preference at the
            // staging rfed and turns the built-in backbones off. Restart the app afterwards.
            "staging" -> CoroutineScope(Dispatchers.IO).launch {
                val rfed = intent.getStringExtra("rfed") ?: ""
                val host = intent.getStringExtra("host") ?: "192.168.2.107"
                val port = intent.getIntExtra("port", 4242)
                if (rfed.length == 32) UserPreferences.setRfedNodeIdentityHash(app, rfed)
                val dao = (app as com.newendian.retichat.RetichatApp).database.interfaceConfigDao()
                val existing = dao.enabledInterfaces().firstOrNull { it.name == "Staging RPi" }
                if (existing == null) {
                    dao.upsert(
                        com.newendian.retichat.data.db.entity.InterfaceConfigEntity(
                            name = "Staging RPi", type = "TCPClientInterface",
                            configJson = """{"target_host":"$host","target_port":"$port"}""",
                        ),
                    )
                }
                UserPreferences.setDefaultTcpEnabled(app, false)
                // Park every other interface: a second uplink lets a stale path
                // from another network win the route to a staging peer (the
                // 2026-09-24 reverse-leg failures). `op production` re-enables them.
                val parked = dao.enabledInterfaces().filter { it.name != "Staging RPi" }
                parked.forEach { dao.setEnabled(it.id, false) }
                app.getSharedPreferences("distro_debug", Context.MODE_PRIVATE).edit()
                    .putString("parked_interfaces", parked.joinToString(",") { it.id.toString() }).apply()
                Log.i(TAG, "staging -> rfed=$rfed backbone=$host:$port (restart the app)")
            }
            // Back to production: drops the staging row and the RFed override.
            // `--ez defaults false` keeps the built-in backbones off (a phone
            // that runs on its own LAN interface only).
            "production" -> CoroutineScope(Dispatchers.IO).launch {
                val defaults = intent.getBooleanExtra("defaults", true)
                val dao = (app as com.newendian.retichat.RetichatApp).database.interfaceConfigDao()
                dao.enabledInterfaces().filter { it.name == "Staging RPi" }.forEach { dao.delete(it) }
                val prefs = app.getSharedPreferences("distro_debug", Context.MODE_PRIVATE)
                prefs.getString("parked_interfaces", "")!!.split(",").filter { it.isNotBlank() }
                    .forEach { id -> dao.setEnabled(id.toLong(), true) }
                prefs.edit().remove("parked_interfaces").apply()
                UserPreferences.setRfedNodeIdentityHash(app, "")
                UserPreferences.setDefaultTcpEnabled(app, defaults)
                Log.i(TAG, "production -> staging row removed, default backbones=$defaults (restart the app)")
            }
            // Send a DM from this phone (as the distro when one is loaded):
            //   --es op send --es to <32 hex> --es text "hello"
            "send" -> CoroutineScope(Dispatchers.IO).launch {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: "ping"
                val dest = com.newendian.retichat.service.DistroCodec.hexToBytes(to.lowercase())
                if (dest == null || dest.size != 16) { Log.w(TAG, "send: bad address"); return@launch }
                val repo = (app as com.newendian.retichat.RetichatApp).repository
                val chatId = repo.getOrCreateDirectChat(dest)
                repo.sendMessage(chatId, text)
                Log.i(TAG, "send -> queued '$text' to ${to.take(8)} in $chatId")
            }
            // Send this phone's distro identity to another device (LXMF field 0x0D):
            //   --es op transfer --es to <32 hex>
            "transfer" -> CoroutineScope(Dispatchers.IO).launch {
                val to = intent.getStringExtra("to") ?: ""
                Log.i(TAG, "transfer -> ${RfedDistroClient.sendIdentityTo(app, to)} to ${to.take(8)}")
            }

            // ---- Channels and the distro (RFed SPEC §17.12), for test-harnesses/staging/probe_phone_distro_channels.mjs ----
            // Each op calls what the screen calls and logs one line, "<op> -> <result> name=<name> ...", under DistroDebug.
            // <name> is the channel's full name (public.x, or <root>.x for a private one), as the web takes it.

            // JoinChannelScreen.start(): the RFed node from Settings (no node, no join), a name the New Channel form
            // builds, then rfedChannelClient.joinChannel(name, node). The screen then opens the chat: `channel_open`.
            //   --es op channel_join --es name <name>
            "channel_join" -> CoroutineScope(Dispatchers.IO).launch {
                val name = intent.getStringExtra("name") ?: ""
                val node = UserPreferences.getEffectiveRfedNodeIdentityHash(app).trim().lowercase()
                if (node.length < 32) {
                    Log.w(TAG, "channel_join -> REFUSED name=$name reason=no RFed node address is configured")
                    return@launch
                }
                if (!ChannelNameForm.joinNameAccepted(name)) {
                    Log.w(TAG, "channel_join -> REFUSED name=$name reason=not a name the New Channel form builds")
                    return@launch
                }
                when (val r = (app as RetichatApp).rfedChannelClient.joinChannel(name, node)) {
                    is RfedChannelClient.JoinResult.Joined ->
                        Log.i(TAG, "channel_join -> Joined name=$name id=${r.channel.id.take(8)}")
                    is RfedChannelClient.JoinResult.Failed ->
                        Log.w(TAG, "channel_join -> Failed name=$name reason=${r.reason}")
                }
            }
            // ConversationScreen's Leave (and ChatListViewModel.leaveChannel): rfedChannelClient.leaveChannel(channel.id).
            //   --es op channel_leave --es name <name>
            "channel_leave" -> CoroutineScope(Dispatchers.IO).launch {
                val name = intent.getStringExtra("name") ?: ""
                val channel = heldChannel(app, name)
                if (channel == null) { Log.w(TAG, "channel_leave -> NOT_HELD name=$name"); return@launch }
                val left = (app as RetichatApp).rfedChannelClient.leaveChannel(channel.id)
                Log.i(TAG, "channel_leave -> $left name=$name")
            }
            // The composer's Send: OutgoingText.of(draft), then rfedChannelClient.sendMessage(channel, text) (true once
            // RFed has the post). A blank draft does not send, as the screen's Send button is disabled for one.
            // No spaces in the text: adb passes it through the device's shell.
            //   --es op channel_post --es name <name> --es text <text>
            "channel_post" -> CoroutineScope(Dispatchers.IO).launch {
                val name = intent.getStringExtra("name") ?: ""
                val text = OutgoingText.of(intent.getStringExtra("text") ?: "")
                if (text.isEmpty()) { Log.w(TAG, "channel_post -> REFUSED name=$name reason=blank text"); return@launch }
                val channel = heldChannel(app, name)
                if (channel == null) { Log.w(TAG, "channel_post -> NOT_HELD name=$name"); return@launch }
                val sent = (app as RetichatApp).rfedChannelClient.sendMessage(channel, text)
                Log.i(TAG, "channel_post -> $sent name=$name")
            }
            // What opening the channel's chat does (ConversationScreen): the channel joins the live stream's filter,
            // then its pull. A channel a sibling's join put on this device is not opened until the user opens it
            // (§17.12 rule 6), so its posts wait at RFed until then.
            //   --es op channel_open --es name <name>
            "channel_open" -> CoroutineScope(Dispatchers.IO).launch {
                val name = intent.getStringExtra("name") ?: ""
                val channel = heldChannel(app, name)
                if (channel == null) { Log.w(TAG, "channel_open -> NOT_HELD name=$name"); return@launch }
                val client = (app as RetichatApp).rfedChannelClient
                client.markChannelOpenedForStreaming(channel.id)
                client.resetCanPullMore(channel.id)
                val (pulled, more) = client.pullDeferred(channel)
                Log.i(TAG, "channel_open -> Opened name=$name pulled=$pulled more=$more")
            }
            // The Notifications switch of the channel's info sheet (UserPreferences; default off for a channel).
            //   --es op channel_notify --es name <name> --ez on true|false
            "channel_notify" -> CoroutineScope(Dispatchers.IO).launch {
                val name = intent.getStringExtra("name") ?: ""
                val on = intent.getBooleanExtra("on", true)
                val channel = heldChannel(app, name)
                if (channel == null) { Log.w(TAG, "channel_notify -> NOT_HELD name=$name"); return@launch }
                UserPreferences.setChannelNotificationsEnabled(app, channel.id, on)
                Log.i(TAG, "channel_notify -> $on name=$name")
            }
            // The channel list the chat list draws, and the last membership action recorded for each (§17.12 rule 5):
            //   channel_list -> 2 channel(s): public.a(1a2b3c4d,join@1700000000000) public.b(5e6f7a8b)
            //   --es op channel_list
            "channel_list" -> CoroutineScope(Dispatchers.IO).launch {
                val dao = (app as RetichatApp).database.channelDao()
                val rows = dao.activeChannels().sortedBy { it.channelName }.map { channel ->
                    val record = dao.membership(channel.id)?.let { ",${it.op}@${it.atMs}" } ?: ""
                    "${channel.channelName}(${channel.id.take(8)}$record)"
                }
                Log.i(TAG, "channel_list -> ${rows.size} channel(s): ${rows.joinToString(" ")}")
            }
            else -> Log.i(
                TAG,
                "status: has=${DistroManager.hasDistro} address=${DistroManager.deliveryHashHex} " +
                    "rfed=${RfedDistroClient.status.value} (op=$op)",
            )
        }
    }

    /** The channel named [name] in the list the chat list draws, or null when this device does not hold it. */
    private suspend fun heldChannel(app: Context, name: String): ChannelEntity? =
        (app as RetichatApp).database.channelDao().activeChannels().firstOrNull { it.channelName == name }

    private companion object { const val TAG = "DistroDebug" }
}
