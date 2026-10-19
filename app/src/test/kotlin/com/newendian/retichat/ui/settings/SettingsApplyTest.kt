package com.newendian.retichat.ui.settings

import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import com.newendian.retichat.service.DisplayNameSettings
import com.newendian.retichat.service.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings page holds its changes until Apply, as on iOS (James,
 * 2026-10-19: "Settings are adjusted but not applied until the user presses
 * the Apply button. And exiting after adjusting settings brings up a
 * confirmation modal. That means removing the Restart button as well, even
 * if the result is still to restart the network client").
 */
class SettingsApplyTest {

    private val tcp = InterfaceConfigEntity(id = 4, name = "Home", type = "TCPClientInterface", createdAt = 1)
    private val saved = SettingsValues(
        announceName = "Ann",
        messageName = "Mes",
        channelName = "",
        filterStrangers = false,
        rfedNodeHash = UserPreferences.DEFAULT_RFED_NODE_IDENTITY_HASH,
        lxmfPropOverride = "",
        defaultTcp = true,
        localNetwork = false,
        rtnodeBluetooth = false,
        interfaces = listOf(tcp),
    )

    // ---- What Apply does ----

    @Test
    fun nothingChangedPlansNothing() {
        val plan = planApply(saved, saved)
        assertFalse(hasChanges(saved, saved))
        assertEquals(emptyList<DisplayNameSettings.Kind>(), plan.names)
        assertFalse(plan.restart)
        assertFalse(plan.rtnodeBluetooth || plan.filterStrangers)
    }

    @Test
    fun namesAndThePrivacyFilterApplyWithNoRestart() {
        val draft = saved.withName(DisplayNameSettings.Kind.CHANNEL, "Chan").copy(filterStrangers = true)
        val plan = planApply(saved, draft)
        assertTrue(hasChanges(saved, draft))
        assertEquals(listOf(DisplayNameSettings.Kind.CHANNEL), plan.names)
        assertTrue(plan.filterStrangers)
        assertFalse(plan.restart)
    }

    /** What the Restart button was for: each of these restarts the stack. */
    @Test
    fun whatTheStackReadsAtBootstrapRestartsIt() {
        val node = "0123456789abcdef0123456789abcdef"
        for (draft in listOf(
            saved.copy(rfedNodeHash = node),
            saved.copy(lxmfPropOverride = node),
            saved.copy(defaultTcp = false),
            saved.copy(localNetwork = true),
            saved.withInterfaceEnabled(tcp.id, false),
            saved.withoutInterface(tcp.id),
            saved.withInterfaceAdded(tcp.copy(id = 0, name = "Work")),
        )) {
            assertTrue(draft.toString(), planApply(saved, draft).restart)
        }
    }

    @Test
    fun nearbyRtnodeAloneStartsWithNoRestart() {
        val plan = planApply(saved, saved.copy(rtnodeBluetooth = true))
        assertTrue(plan.rtnodeBluetooth)
        assertFalse(plan.restart)
    }

    /** An empty node field is the default node, as UserPreferences stores it. */
    @Test
    fun anEmptyNodeFieldIsTheDefaultNode() {
        assertFalse(planApply(saved, saved.copy(rfedNodeHash = "")).rfedNode)
    }

    @Test
    fun applyIsOfferedOnlyForValidHashes() {
        assertTrue(saved.valid)
        assertTrue(saved.copy(lxmfPropOverride = "").valid)
        assertFalse(saved.copy(rfedNodeHash = "abc").valid)
        assertFalse(saved.copy(lxmfPropOverride = "zz".repeat(16)).valid)
    }

    // ---- Interfaces on the page ----

    @Test
    fun addedInterfacesWaitWithTheirOwnNegativeIds() {
        val draft = saved
            .withInterfaceAdded(tcp.copy(id = 0, name = "A"))
            .withInterfaceAdded(tcp.copy(id = 0, name = "B"))
        assertEquals(listOf(4L, -1L, -2L), draft.interfaces.map { it.id })
        val plan = planApply(saved, draft)
        assertEquals(listOf("A", "B"), plan.savedInterfaces.map { it.name })
        assertEquals(emptyList<Long>(), plan.deletedInterfaceIds)
        // Edited before Apply: still one new interface, now renamed.
        val edited = draft.withInterfaceSaved(draft.interfaces[1].copy(name = "A2"))
        assertEquals(listOf("A2", "B"), planApply(saved, edited).savedInterfaces.map { it.name })
    }

    @Test
    fun anEditedOrDeletedInterfaceIsPlannedOnce() {
        val edited = saved.withInterfaceSaved(tcp.copy(configJson = "{\"target_port\":\"4242\"}"))
        assertEquals(listOf(tcp.id), planApply(saved, edited).savedInterfaces.map { it.id })
        val deleted = saved.withoutInterface(tcp.id)
        assertEquals(listOf(tcp.id), planApply(saved, deleted).deletedInterfaceIds)
        assertEquals(emptyList<InterfaceConfigEntity>(), planApply(saved, deleted).savedInterfaces)
        // Added then deleted before Apply: nothing to do.
        val gone = saved.withInterfaceAdded(tcp.copy(id = 0)).withoutInterface(-1)
        assertFalse(hasChanges(saved, gone))
    }

    // ---- The page ----

    private fun source(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val screen = source("ui/settings/SettingsScreen.kt")
    private val store = source("ui/settings/SettingsStore.kt")
    private val viewModel = source("ui/settings/SettingsViewModel.kt")

    private fun assertInOrder(source: String, vararg parts: String) {
        var from = 0
        for (part in parts) {
            val at = source.indexOf(part, from)
            assertTrue("\"$part\" after the previous part", at >= 0)
            from = at + part.length
        }
    }

    @Test
    fun applyIsInTheTopBarAndThereIsNoRestartButton() {
        assertInOrder(
            screen,
            "actions = {", "TextButton(", "onClick = { viewModel.apply() },",
            "enabled = changed && draft?.valid == true && !applying,", "Text(\"Apply\")",
        )
        assertFalse(screen.contains("Text(\"Restart\")"))
        assertFalse(screen.contains("onRestart"))
        assertFalse(viewModel.contains("restartService"))
    }

    /** iOS's alert, word for word, from the back arrow and Android's back. */
    @Test
    fun leavingWithChangesAsksFirst() {
        assertInOrder(screen, "fun leave() {", "if (changed) showRevertDialog = true else onBack()")
        assertTrue(screen.contains("IconButton(onClick = { leave() })"))
        assertTrue(screen.contains("BackHandler(enabled = changed) { showRevertDialog = true }"))
        assertInOrder(
            screen,
            "if (showRevertDialog) {",
            "Text(\"Unsaved Changes\")",
            "Text(\"Revert to your original settings and go back?\")",
            "viewModel.revert()", "onBack()", "Text(\"Revert\")",
            "Text(\"Cancel\")",
        )
    }

    /** The cards change the page's values only: nothing on the page saves
     *  or restarts but Apply. (DropAnnouncesCard, which saves at once, is
     *  never shown.) */
    @Test
    fun onlyApplySaves() {
        val code = screen.lines()
            .filterNot { it.trim().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }
            .joinToString("\n")
        val writes = Regex("UserPreferences\\.set\\w+|DisplayNameSettings\\.save|setCoreFilterStrangers|" +
            "dao\\.|StackRuntime\\.(restart|apply)").findAll(code).map { it.value }.toList()
        assertEquals(listOf("UserPreferences.setDropAnnouncesEnabled"), writes)
        assertEquals("DropAnnouncesCard is defined, never called", 1, Regex("DropAnnouncesCard\\(").findAll(code).count())
        for (write in listOf(
            "DisplayNameSettings.save(app, kind, draft.name(kind))",
            "app.repository.setCoreFilterStrangers(draft.filterStrangers)",
            "UserPreferences.setRfedNodeIdentityHash(",
            "UserPreferences.setRfedLxmfPropOverride(app, draft.lxmfPropOverride)",
            "UserPreferences.setDefaultTcpEnabled(app, draft.defaultTcp)",
            "UserPreferences.setAutoInterfaceEnabled(app, draft.localNetwork)",
            "UserPreferences.setRtnodeBluetoothEnabled(app, draft.rtnodeBluetooth)",
            "dao.deleteById(id)",
            "dao.upsert(",
        )) {
            assertTrue(write, store.contains(write))
        }
    }

    /** Saved first, the page clean again, then the stack: one restart. */
    @Test
    fun applySavesThenBringsTheStackInLine() {
        val apply = viewModel.substringAfter("fun apply() {")
        assertInOrder(
            apply,
            "if (_applying.value || !hasChanges(saved, draft) || !draft.valid) return",
            "val plan = planApply(saved, draft)",
            "applyScope.launch {",
            "val stored = store.save(draft, plan)",
            "_saved.value = stored",
            "_applying.value = false",
            "store.applyToStack(plan)",
        )
    }
}
