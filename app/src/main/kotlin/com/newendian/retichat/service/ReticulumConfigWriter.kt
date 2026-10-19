package com.newendian.retichat.service

import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import org.json.JSONObject
import java.io.File

/**
 * Generate a Reticulum config file from the enabled Room interface entities,
 * plus the built-in AutoInterface when [autoInterface] is on (Settings' Local
 * network switch, UserPreferences.isAutoInterfaceEnabled).
 *
 * Lifted out of the now-deleted `ReticulumService` so [StackRuntime] (and
 * any future debug tooling) can render the config without owning a Service
 * instance.
 */
/** The config section name of the built-in AutoInterface. */
internal const val AUTO_INTERFACE_NAME = "Local network"

internal fun writeReticulumConfig(
    configDir: File,
    interfaces: List<InterfaceConfigEntity>,
    autoInterface: Boolean = false,
) {
    val sb = StringBuilder()

    sb.appendLine("[reticulum]")
    sb.appendLine("  enable_transport = false")
    sb.appendLine("  share_instance = false")
    sb.appendLine("  shared_instance_port = 37428")
    sb.appendLine("  instance_control_port = 37429")
    sb.appendLine("  panic_on_interface_errors = false")
    sb.appendLine()
    sb.appendLine("[logging]")
    sb.appendLine("  loglevel = 4")
    sb.appendLine()
    sb.appendLine("[interfaces]")

    for (iface in interfaces) {
        sb.appendLine("  [[${iface.name}]]")
        sb.appendLine("    type = ${iface.type}")
        sb.appendLine("    enabled = true")
        try {
            val json = JSONObject(iface.configJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (value.isNotEmpty()) {
                    sb.appendLine("    $key = $value")
                }
            }
        } catch (_: Exception) {
            // skip malformed configJson
        }
        sb.appendLine()
    }

    if (autoInterface) {
        // Reticulum's defaults (group "reticulum", link-local scope, ports
        // 29716/42671), so it finds Sideband, MeshChat, rnsd and the other
        // apps on the network. Transport names it AutoInterface[$AUTO_INTERFACE_NAME]
        // and each device found AutoInterfacePeer[if/addr].
        sb.appendLine("  [[$AUTO_INTERFACE_NAME]]")
        sb.appendLine("    type = AutoInterface")
        sb.appendLine("    enabled = true")
        sb.appendLine()
    }

    File(configDir, "config").writeText(sb.toString())
}
