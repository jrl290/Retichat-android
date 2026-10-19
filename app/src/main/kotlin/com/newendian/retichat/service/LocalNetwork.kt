package com.newendian.retichat.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * What the built-in AutoInterface needs from Android (James, 2026-10-19):
 * the Wi-Fi multicast lock, and the local network permission where Android
 * asks for one.
 *
 * Wi-Fi drops multicast packets addressed to the phone unless an app holds a
 * multicast lock, and AutoInterface finds its peers by IPv6 multicast, so the
 * lock is held for as long as the stack runs with the AutoInterface on
 * ([acquire] in StackRuntime's bootstrap, [release] at its stop). It costs
 * some battery, which is one reason the interface is off by default.
 *
 * Local network access is a runtime permission (ACCESS_LOCAL_NETWORK) for an
 * app targeting Android 17 (SDK 37) on Android 17 or later. Below that target
 * Android grants it with INTERNET, and asks apps not to request it, so
 * [needsPermission] is false until targetSdk is raised to 37; from then on
 * the Settings switch asks for it the first time it's turned on.
 */
object LocalNetwork {

    private const val TAG = "LocalNetwork"

    /** android.Manifest.permission.ACCESS_LOCAL_NETWORK (SDK 37; compileSdk is 36). */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ANDROID_17 = 37

    private var lock: WifiManager.MulticastLock? = null

    /** Whether Android will ask for [PERMISSION] for this app on this device. */
    fun permissionApplies(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17

    /** True when the switch must ask for [PERMISSION] before turning on. */
    fun needsPermission(context: Context): Boolean =
        permissionApplies(context) &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED

    /** Hold the Wi-Fi multicast lock (once; a second call keeps the one held). */
    @Synchronized
    fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Log.w(TAG, "no Wi-Fi service: AutoInterface runs without the multicast lock")
            return
        }
        lock = wifi.createMulticastLock("retichat:autointerface").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { Log.e(TAG, "multicast lock not acquired: AutoInterface may find no peers on Wi-Fi", it) }
        }
    }

    /** Let go of the multicast lock, if held. */
    @Synchronized
    fun release() {
        lock?.let { held -> if (held.isHeld) runCatching { held.release() } }
        lock = null
    }
}
