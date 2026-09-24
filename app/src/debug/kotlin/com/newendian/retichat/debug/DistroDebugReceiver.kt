package com.newendian.retichat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.newendian.retichat.service.DistroManager
import com.newendian.retichat.service.RfedDistroClient
import com.newendian.retichat.service.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook for the distro feature, so the staging harness can put
 * a known distro key on the phone without driving the UI:
 *
 *   adb shell am broadcast -a com.newendian.retichat.DEBUG_DISTRO \
 *     -n com.newendian.retichat/.debug.DistroDebugReceiver --es op import --es key <128 hex>
 *   ... --es op generate | forget | register | pull | status
 *
 * Results are logged under the "DistroDebug" tag.
 */
class DistroDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (val op = intent.getStringExtra("op") ?: "status") {
            "import" -> {
                val key = intent.getStringExtra("key") ?: ""
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
            "pull" -> CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "pull -> ${RfedDistroClient.pull(app)} blob(s)")
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
                Log.i(TAG, "staging -> rfed=$rfed backbone=$host:$port (restart the app)")
            }
            "production" -> CoroutineScope(Dispatchers.IO).launch {
                val dao = (app as com.newendian.retichat.RetichatApp).database.interfaceConfigDao()
                dao.enabledInterfaces().filter { it.name == "Staging RPi" }.forEach { dao.delete(it) }
                UserPreferences.setRfedNodeIdentityHash(app, "")
                UserPreferences.setDefaultTcpEnabled(app, true)
                Log.i(TAG, "production -> defaults restored (restart the app)")
            }
            // Send a DM from this phone (as the distro when one is loaded):
            //   --es op send --es to <32 hex> --es text "hello"
            "send" -> CoroutineScope(Dispatchers.IO).launch {
                val to = intent.getStringExtra("to") ?: ""
                val text = intent.getStringExtra("text") ?: "ping"
                val dest = com.newendian.retichat.service.DistroCodec.hexToBytes(to.lowercase())
                if (dest == null || dest.size != 16) { Log.w(TAG, "send: bad address"); return@launch }
                val repo = (app as com.newendian.retichat.RetichatApp).repository
                repo.addContact(dest, to.take(8))
                val chatId = repo.getOrCreateDirectChat(com.newendian.retichat.data.model.Contact(dest, to.take(8)))
                repo.sendMessage(chatId, text)
                Log.i(TAG, "send -> queued '$text' to ${to.take(8)} in $chatId")
            }
            // Send this phone's distro identity to another device (LXMF field 0x0D):
            //   --es op transfer --es to <32 hex>
            "transfer" -> CoroutineScope(Dispatchers.IO).launch {
                val to = intent.getStringExtra("to") ?: ""
                Log.i(TAG, "transfer -> ${RfedDistroClient.sendIdentityTo(app, to)} to ${to.take(8)}")
            }
            else -> Log.i(
                TAG,
                "status: has=${DistroManager.hasDistro} address=${DistroManager.deliveryHashHex} " +
                    "rfed=${RfedDistroClient.status.value} (op=$op)",
            )
        }
    }

    private companion object { const val TAG = "DistroDebug" }
}
