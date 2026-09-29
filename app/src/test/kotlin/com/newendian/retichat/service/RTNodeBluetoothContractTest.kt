package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bluetooth to any RTNode in range (2026-09-28). The radio code is
 * RTNodeBluetooth; the protocol and every dial decision are Reticulum-rust's
 * (interfaces::prns_ble). These pin what the source must keep.
 *
 * Off by default (James, 2026-09-29): a user who never turns it on sees no
 * Bluetooth (Nearby devices) permission prompt and nothing scans. The
 * permission is asked for only when the user turns the Settings switch on.
 */
class RTNodeBluetoothContractTest {

    private val stackRuntime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
    private val radio = File("src/main/kotlin/com/newendian/retichat/service/RTNodeBluetooth.kt").readText()
    private val prefs = File("src/main/kotlin/com/newendian/retichat/service/UserPreferences.kt").readText()
    private val settings = File("src/main/kotlin/com/newendian/retichat/ui/settings/SettingsScreen.kt").readText()
    private val activity = File("src/main/kotlin/com/newendian/retichat/MainActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private val mainSources: Map<String, String> = listOf(File("src/main"), File("src/debug"))
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .associate { it.name to it.readText() }

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val next = Regex("\n    (private |internal |fun |suspend fun )").find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }

    /** The Settings card, a top-level composable. */
    private val card: String by lazy {
        val start = settings.indexOf("private fun RTNodeBluetoothCard()")
        assertTrue("RTNodeBluetoothCard not found", start >= 0)
        settings.substring(start, settings.indexOf("\n}\n", start))
    }

    private fun assertInOrder(source: String, vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = source.indexOf(part, from)
            assertTrue("\"$part\" after the previous part", at >= 0)
            from = at + part.length
        }
    }

    // ---- Off by default ----

    /** The behaviour is RTNodeBluetoothDefaultOffTest; this pins that there
     *  is no second read with another default. */
    @Test
    fun bluetoothIsOffUntilTheUserTurnsItOn() {
        val reads = Regex("getBoolean\\(PREF_KEY_RTNODE_BLUETOOTH, (\\w+)\\)")
            .findAll(prefs).map { it.groupValues[1] }.toList()
        assertEquals("one read of the preference, defaulting to off", listOf("false"), reads)
        val readers = mainSources.filterValues { it.contains("PREF_KEY_RTNODE_BLUETOOTH") }.keys
        assertEquals(setOf("UserPreferences.kt"), readers)
    }

    /** Launching the app asks for notifications only, exactly as before
     *  Bluetooth (origin/main b916092): no Bluetooth request from onCreate or
     *  from the notification request's answer. */
    @Test
    fun launchingTheAppAsksForNoBluetoothPermission() {
        for (bluetooth in listOf("RTNodeBluetooth", "BLUETOOTH", "RequestMultiplePermissions", "isRtnodeBluetoothEnabled")) {
            assertFalse("MainActivity: no $bluetooth", activity.contains(bluetooth))
        }
        assertEquals("one permission launcher", 1, Regex("registerForActivityResult\\(").findAll(activity).count())
        assertEquals("one request", 1, Regex("\\.launch\\(").findAll(activity).count())
        assertInOrder(
            activity,
            "ActivityResultContracts.RequestPermission()",
            ") { granted ->",
            "Log.i(TAG, \"POST_NOTIFICATIONS permission granted=\$granted\")\n    }",
        )
        val onCreate = body(activity, "onCreate")
        assertInOrder(
            onCreate,
            "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {",
            "ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)",
            "notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)",
        )
    }

    /** The one place the Bluetooth permissions are asked for is the card,
     *  from the switch (and its Allow button once it is on). */
    @Test
    fun onlyTheSettingsSwitchAsksForThePermission() {
        val askers = mainSources.filterValues {
            it.contains("RTNodeBluetooth.PERMISSIONS") || it.contains("BLUETOOTH_SCAN") || it.contains("BLUETOOTH_CONNECT")
        }.keys - "RTNodeBluetooth.kt"
        assertEquals(setOf("SettingsScreen.kt"), askers)
        val launch = Regex("\\.launch\\(RTNodeBluetooth\\.PERMISSIONS\\)")
        assertEquals("one request, the card's", 1, launch.findAll(settings).count())
        assertInOrder(card, "fun askForPermission() {", "permissionLauncher.launch(RTNodeBluetooth.PERMISSIONS)")
        assertTrue(card.contains("rememberLauncherForActivityResult("))
        assertTrue(card.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        // Asked from the switch turning on, and from Allow Bluetooth (on, but
        // the permission withdrawn since), nowhere else.
        assertEquals(2, Regex("askForPermission\\(\\)").findAll(card).count() - 1)
        assertTrue(switch.contains("else -> askForPermission()"))
        assertInOrder(card, "enabled && !permitted", "onClick = { askForPermission() }", "Text(\"Allow Bluetooth\")")
    }

    /** Only StackRuntime starts and stops it: bootstrap and shutdown, and the
     *  Settings switch through applyRtnodeBluetoothSetting. */
    @Test
    fun onlyStackRuntimeStartsAndStopsBluetooth() {
        val starters = mainSources.filterValues { it.contains("RTNodeBluetooth.start(") }.keys
        assertEquals(setOf("StackRuntime.kt"), starters)
        val stoppers = mainSources.filterValues { it.contains("RTNodeBluetooth.stop(") }.keys
        assertEquals(setOf("StackRuntime.kt"), stoppers)
        val bootstrap = body(stackRuntime, "bootstrap")
        assertInOrder(bootstrap, "if (UserPreferences.isRtnodeBluetoothEnabled(app)) {", "RTNodeBluetooth.start(")
        // A grant starts it directly: nothing waits for a start() that, with
        // the switch off at stack start, never ran.
        for ((name, source) in mainSources) {
            assertFalse("$name: no onPermissionsGranted", source.contains("onPermissionsGranted"))
        }
    }

    /** Nothing scans until start(): the scan wants `running`, set in start() only. */
    @Test
    fun nothingScansUntilStarted() {
        assertEquals("one scan start", 1, Regex("startScan\\(").findAll(radio).count())
        assertTrue(body(radio, "applyScan").contains("val want = running && scanWanted"))
        assertEquals(1, Regex("running = true").findAll(radio).count())
        assertTrue(body(radio, "start").contains("running = true"))
    }

    // ---- The Settings switch ----

    private val switch: String by lazy {
        card.substringAfter("onCheckedChange = { newValue ->").substringBefore("\n            )")
    }

    /** The permission request's answer. */
    private val answer: String by lazy {
        card.substringAfter("RequestMultiplePermissions()").substringBefore("fun askForPermission() {")
    }

    /** On asks first and turns on only with the permissions granted; off stops it. */
    @Test
    fun theSwitchAsksBeforeItTurnsOn() {
        assertInOrder(
            switch,
            "!newValue ->", "turnOff()",
            "RTNodeBluetooth.hasPermissions(context) ->", "turnOn()",
            "else -> askForPermission()",
        )
        // The request's answer: on only if granted.
        assertInOrder(answer, "permitted = RTNodeBluetooth.hasPermissions(context)", "if (permitted) {", "turnOn()")
        // The preference is written true in one place, which then applies it.
        assertEquals(1, Regex("setRtnodeBluetoothEnabled\\(context, true\\)").findAll(settings).count())
        assertInOrder(card, "fun turnOn() {", "setRtnodeBluetoothEnabled(context, true)", "applySetting()")
        assertInOrder(card, "fun turnOff() {", "setRtnodeBluetoothEnabled(context, false)", "applySetting()")
    }

    /** A grant, or turning it off, takes effect at once on the running
     *  stack, not at the next Restart, and never on the main thread: the
     *  card hands it to the app's scope and StackRuntime moves it to IO. */
    @Test
    fun theSwitchTakesEffectAtOnceOffTheMainThread() {
        assertInOrder(
            card,
            "fun applySetting() {",
            "app.applicationScope.launch { StackRuntime.applyRtnodeBluetoothSetting(app) }",
        )
        val apply = body(stackRuntime, "applyRtnodeBluetoothSetting")
        assertInOrder(apply, "withContext(Dispatchers.IO) {", "initLock.withLock {", "RTNodeBluetooth.stop()")
        assertInOrder(apply, "withContext(Dispatchers.IO) {", "initLock.withLock {", "RTNodeBluetooth.start(")
        assertFalse("no Restart needed", card.contains("Restart") || card.contains("restart"))
    }

    /** Denied: it stays off and says why; if Android may no longer show the
     *  request, the card offers the app's system settings. */
    @Test
    fun aDenialSaysWhyAndOffersTheSystemSettings() {
        assertInOrder(card, "fun askForPermission() {", "rationaleBefore = context.bluetoothPermissionRationale()", "permissionLauncher.launch(")
        assertInOrder(answer, "val rationaleAfter = context.bluetoothPermissionRationale()", "blocked = !rationaleAfter", "refusal = when {")
        assertTrue("left off", answer.contains("if (enabled) turnOff()"))
        assertTrue(
            settings.substringAfter("private fun Context.bluetoothPermissionRationale()").substringBefore("\n}\n")
                .contains("shouldShowRequestPermissionRationale("),
        )
        assertInOrder(card, "if (blocked) {", "openAppSystemSettings()", "Text(\"Open settings\")")
        assertTrue(settings.contains("ACTION_APPLICATION_DETAILS_SETTINGS"))
        // Granted there: on when the user comes back.
        val resume = card.substringAfter("LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {")
        assertInOrder(resume, "val grantedSince = now && !permitted", "if (grantedSince && (enabled || awaitingSystemSettings)) turnOn()")
    }

    // ---- StackRuntime ----

    /** A Throwable from Bluetooth must not fail the stack's bootstrap or the
     *  switch's apply. */
    @Test
    fun everyStartAndStopFromSettingsIsCaught() {
        val starts = Regex("RTNodeBluetooth\\.start\\(").findAll(stackRuntime).count()
        val caught = Regex(
            "runCatching \\{ RTNodeBluetooth\\.start\\(app, configDir\\.absolutePath\\) \\}\\s*" +
                "\\.onFailure \\{ Log\\.e\\(TAG, \"RTNodeBluetooth\\.start failed\", it\\) \\}"
        ).findAll(stackRuntime).count()
        assertEquals("bootstrap and the Settings switch", 2, starts)
        assertEquals("each in runCatching, logged", starts, caught)
        assertTrue(
            Regex(
                "runCatching \\{ RTNodeBluetooth\\.stop\\(\\) \\}\\s*" +
                    "\\.onFailure \\{ Log\\.e\\(TAG, \"RTNodeBluetooth\\.stop failed\", it\\) \\}"
            ).containsMatchIn(body(stackRuntime, "applyRtnodeBluetoothSetting"))
        )
    }

    /** The switch applies the preference under initLock, read there, so it
     *  cannot interleave with a bootstrap, shutdown or restart or with
     *  another tap. On starts it only on a ready stack, from the same
     *  directory bootstrap uses. */
    @Test
    fun theSwitchIsAppliedUnderTheStackLock() {
        val apply = body(stackRuntime, "applyRtnodeBluetoothSetting")
        assertInOrder(
            apply,
            "initLock.withLock {",
            "if (!UserPreferences.isRtnodeBluetoothEnabled(app)) {", "RTNodeBluetooth.stop()",
            "} else if (isReady) {",
            "val configDir = File(app.filesDir, \"reticulum\")",
            "RTNodeBluetooth.start(app, configDir.absolutePath)",
        )
        assertTrue(body(stackRuntime, "bootstrap").contains("val configDir = File(app.filesDir, \"reticulum\")"))
    }

    /** isReady, which the switch's start waits for, is set only once
     *  bootstrap has returned: after the publish and the link registrations
     *  (next test), under the same lock. */
    @Test
    fun isReadyComesAfterTheWholeBootstrap() {
        assertEquals("one writer of isReady = <result>", 1, Regex("isReady = ok").findAll(stackRuntime).count())
        assertInOrder(body(stackRuntime, "startIfNeeded"), "initLock.withLock {", "bootstrap(app)", "isReady = ok")
        assertFalse(Regex("isReady = true").containsMatchIn(stackRuntime))
    }

    /** DESIGN_PRINCIPLES §5: the RTNode interface's up-edge is when Transport
     *  announces the published destination on it and app-links re-attempts
     *  its held links, so both must be in place before Bluetooth starts. */
    @Test
    fun bluetoothStartsAfterThePublishAndTheLinkRegistrations() {
        val bootstrap = body(stackRuntime, "bootstrap")
        val publish = bootstrap.indexOf("RetichatBridge.transportPublishDestination(selfDestHash")
        val links = bootstrap.indexOf("ConnectionStateManager.register(app, routerHandle)")
        val bluetooth = bootstrap.indexOf("RTNodeBluetooth.start(app, configDir.absolutePath)")
        assertTrue(publish >= 0 && links >= 0 && bluetooth >= 0)
        assertTrue("after the publish", publish < bluetooth)
        assertTrue("after the link registrations", links < bluetooth)
    }

    @Test
    fun bluetoothStopsBeforeTheStackShutsDown() {
        val shutdown = body(stackRuntime, "shutdownNow")
        val stop = shutdown.indexOf("RTNodeBluetooth.stop()")
        val unpublish = shutdown.indexOf("RetichatBridge.transportUnpublishDestination")
        val stack = shutdown.indexOf("RetichatBridge.shutdown()")
        assertTrue(stop >= 0 && unpublish >= 0 && stack >= 0)
        assertTrue("before the unpublish", stop < unpublish)
        assertTrue("before the stack", stop < stack)
    }

    // ---- The radio ----

    /** James: "Retichat should not make itself available to another Retichat
     *  instance." The app only dials: no advertising, no GATT server. */
    @Test
    fun theAppIsACentralOnly() {
        for (peripheral in listOf("BluetoothLeAdvertiser", "startAdvertising", "openGattServer", "BluetoothGattServer")) {
            assertFalse("no $peripheral", radio.contains(peripheral))
        }
        assertFalse("no advertise permission", manifest.contains("BLUETOOTH_ADVERTISE"))
    }

    /** DESIGN_PRINCIPLES §3/§4: the radio keeps no timers and never re-dials
     *  on its own; it connects only on a link the engine returned for an
     *  advertisement it reported. */
    @Test
    fun theRadioConnectsOnlyWhereTheEngineSays() {
        for (timer in listOf("postDelayed", "Timer(", "delay(", "schedule", "sleep(")) {
            assertFalse("no $timer", radio.contains(timer))
        }
        assertEquals("one connect", 1, Regex("connectGatt\\(").findAll(radio).count())
        val sighted = body(radio, "sighted")
        val decision = sighted.indexOf("RetichatBridge.prnsBleSighted(")
        val zero = sighted.indexOf("if (link == 0L) return")
        val connect = sighted.indexOf("connectGatt(")
        assertTrue(decision >= 0 && zero >= 0 && connect >= 0)
        assertTrue("the engine decides, then the radio connects", decision < zero && zero < connect)
    }
}
