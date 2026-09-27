package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DISPLAY_NAMES.md §10 wiring that needs the native library to run, checked
 * in the source (as GroupFallbackContractTest does): every group entry is
 * written through GroupFields and read through LxmfFields.group, nothing
 * reads 0xD1 as a name but the Rust decoder, and the Kotlin declarations of
 * the Retichat field setters match the JNI exports.
 */
class GroupFieldsWiringTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private val sources: Map<String, String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).path to it.readText() }
    private val jni = File("../rust/retichat-jni/src/lib.rs").readText()
    private val bridge = sources.getValue("bridge/RetichatBridge.kt")

    private fun sitesOf(pattern: Regex) = sources.filter { (_, text) -> pattern.containsMatchIn(text) }.keys

    @Test
    fun groupEntriesAreWrittenOnlyThroughGroupFields() {
        // The setters are called only by GroupFields (and declared in the bridge).
        assertEquals(setOf("bridge/RetichatBridge.kt", "service/GroupFields.kt"),
            sitesOf(Regex("messageSetRetichat(String|Bool)\\(")))
        // Every other top-level field write is one of LXMF's custom fields.
        for ((path, text) in sources) {
            if (path == "service/GroupFields.kt" || path == "bridge/RetichatBridge.kt") continue
            Regex("messageAddField(String|Bool)\\([^,]+,\\s*([^,]+),").findAll(text).forEach {
                assertTrue("$path writes field ${it.groupValues[2]}", it.groupValues[2].startsWith("LxmfFields.FIELD_CUSTOM_"))
            }
        }
        // The old field numbers live only in the GroupEntry table.
        assertEquals(setOf("bridge/LxmfFields.kt", "service/GroupFields.kt"), sitesOf(Regex("\\.legacyField\\b|legacyField:")))
        val gcm = sources.getValue("service/GroupChatManager.kt")
        assertFalse(gcm.contains("messageAddField"))
        assertTrue(gcm.contains("GroupFields.set(handle, GroupEntry.RELAY_DONE, true)"))
    }

    @Test
    fun groupEntriesAreReadOnlyThroughTheTransitionReader() {
        // No group entry is read from a field number directly.
        assertEquals(emptySet<String>(), sitesOf(Regex("LxmfFields\\.GROUP_(?!ENTRIES_IN_RETICHAT_FIELD)")))
        val repo = sources.getValue("data/repository/ChatRepository.kt")
        assertEquals(10, Regex("fields\\.group\\(GroupEntry\\.").findAll(repo).count())
    }

    @Test
    fun nothingReadsTheRetichatFieldAsAName() {
        assertEquals(setOf("bridge/LxmfFields.kt"), sitesOf(Regex("\\bFIELD_RETICHAT\\b")))
        assertEquals(emptySet<String>(), sitesOf(Regex("\\bFIELD_DISPLAY_NAME\\b")))
        // The name comes from the one Rust decoder.
        assertTrue(sources.getValue("data/repository/ChatRepository.kt")
            .contains("NameField.fromTrailer(RetichatBridge.displayNameDecode(fieldsRaw))"))
    }

    @Test
    fun theRetichatSettersMatchTheirJniExports() {
        val rustToKotlin = mapOf("jlong" to "Long", "jint" to "Int", "JString" to "String", "jni::sys::jboolean" to "Boolean")
        for (name in listOf("nativeMessageSetRetichatString", "nativeMessageSetRetichatBool",
                            "nativeMessageAddFieldString", "nativeMessageAddFieldBool")) {
            val export = Regex("fn Java_com_newendian_retichat_bridge_RetichatBridge_$name\\(([^)]*)\\)\\s*->\\s*jint")
                .find(jni) ?: error("no JNI export for $name")
            // (env, class, then the Kotlin parameters)
            val rustTypes = export.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .drop(2).map { rustToKotlin[it.substringAfter(":").trim()] ?: error("$name: type $it") }
            val decl = Regex("private external fun $name\\(([^)]*)\\):\\s*Int").find(bridge)
                ?: error("no Kotlin declaration of $name returning Int")
            val kotlinTypes = decl.groupValues[1].split(",").map { it.substringAfter(":").trim() }
            assertEquals(name, rustTypes, kotlinTypes)
        }
    }

    @Test
    fun everyNativeDeclarationHasAJniExport() {
        // A missing export is an UnsatisfiedLinkError at the first call.
        val declared = Regex("external fun (native\\w+)\\(").findAll(bridge).map { it.groupValues[1] }.toList()
        assertTrue(declared.size > 50)
        for (name in declared) {
            assertTrue("no JNI export for $name", jni.contains("Java_com_newendian_retichat_bridge_RetichatBridge_$name("))
        }
    }
}
