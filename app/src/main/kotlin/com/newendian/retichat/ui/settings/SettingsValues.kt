package com.newendian.retichat.ui.settings

import com.newendian.retichat.data.db.entity.InterfaceConfigEntity
import com.newendian.retichat.service.DisplayNameSettings
import com.newendian.retichat.service.UserPreferences

/**
 * Everything the Settings page edits, as iOS's SettingsViewModel holds it
 * (James, 2026-10-19: "Settings are adjusted but not applied until the user
 * presses the Apply button"). The page edits a copy; Apply saves it and does
 * what the changes need, a restart of the stack included, and leaving with
 * the copy changed asks first.
 *
 * [rfedNodeHash] is the node in use as the field shows it (the default when
 * none is set). [interfaces] are the custom interfaces in page order; one
 * added since the last Apply has a negative id until Apply inserts it.
 */
data class SettingsValues(
    val announceName: String,
    val messageName: String,
    val channelName: String,
    val filterStrangers: Boolean,
    val rfedNodeHash: String,
    val lxmfPropOverride: String,
    val defaultTcp: Boolean,
    val localNetwork: Boolean,
    val rtnodeBluetooth: Boolean,
    val interfaces: List<InterfaceConfigEntity>,
) {
    /** Both hashes empty or 32 hex characters: Apply is offered only then. */
    val valid: Boolean
        get() = isHex32OrEmpty(rfedNodeHash) && isHex32OrEmpty(lxmfPropOverride)

    fun name(kind: DisplayNameSettings.Kind): String = when (kind) {
        DisplayNameSettings.Kind.ANNOUNCE -> announceName
        DisplayNameSettings.Kind.MESSAGE -> messageName
        DisplayNameSettings.Kind.CHANNEL -> channelName
    }

    fun withName(kind: DisplayNameSettings.Kind, name: String): SettingsValues = when (kind) {
        DisplayNameSettings.Kind.ANNOUNCE -> copy(announceName = name)
        DisplayNameSettings.Kind.MESSAGE -> copy(messageName = name)
        DisplayNameSettings.Kind.CHANNEL -> copy(channelName = name)
    }

    /** [entity] added at the end, with the next free negative id. */
    fun withInterfaceAdded(entity: InterfaceConfigEntity): SettingsValues {
        val id = minOf(0L, interfaces.minOfOrNull { it.id } ?: 0L) - 1
        return copy(interfaces = interfaces + entity.copy(id = id))
    }

    /** The interface with [entity]'s id replaced by it, in place. */
    fun withInterfaceSaved(entity: InterfaceConfigEntity): SettingsValues =
        copy(interfaces = interfaces.map { if (it.id == entity.id) entity else it })

    fun withInterfaceEnabled(id: Long, enabled: Boolean): SettingsValues =
        copy(interfaces = interfaces.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun withoutInterface(id: Long): SettingsValues =
        copy(interfaces = interfaces.filterNot { it.id == id })
}

/**
 * What Apply does to go from the saved values to the page's (planApply).
 *
 * [restart]: the stack reads these only at bootstrap, so a change restarts it
 * (the RFed node and propagation override, Default TCP, Local network, the
 * custom interfaces). [rtnodeBluetooth]: started or stopped on the running
 * stack with no restart, unless the restart does it. Names apply at once
 * through the router (DisplayNameSettings), the privacy filter at the next
 * delivery.
 */
data class ApplyPlan(
    val names: List<DisplayNameSettings.Kind>,
    val filterStrangers: Boolean,
    val rfedNode: Boolean,
    val lxmfPropOverride: Boolean,
    val defaultTcp: Boolean,
    val localNetwork: Boolean,
    val rtnodeBluetooth: Boolean,
    val deletedInterfaceIds: List<Long>,
    val savedInterfaces: List<InterfaceConfigEntity>,
) {
    val restart: Boolean
        get() = rfedNode || lxmfPropOverride || defaultTcp || localNetwork ||
            deletedInterfaceIds.isNotEmpty() || savedInterfaces.isNotEmpty()
}

fun planApply(saved: SettingsValues, draft: SettingsValues): ApplyPlan {
    val savedById = saved.interfaces.associateBy { it.id }
    val draftIds = draft.interfaces.map { it.id }.toSet()
    return ApplyPlan(
        names = DisplayNameSettings.Kind.entries.filter { draft.name(it) != saved.name(it) },
        filterStrangers = draft.filterStrangers != saved.filterStrangers,
        rfedNode = rfedNodeOrDefault(draft.rfedNodeHash) != rfedNodeOrDefault(saved.rfedNodeHash),
        lxmfPropOverride = draft.lxmfPropOverride != saved.lxmfPropOverride,
        defaultTcp = draft.defaultTcp != saved.defaultTcp,
        localNetwork = draft.localNetwork != saved.localNetwork,
        rtnodeBluetooth = draft.rtnodeBluetooth != saved.rtnodeBluetooth,
        deletedInterfaceIds = saved.interfaces.map { it.id }.filter { it !in draftIds },
        // New (negative id) or changed since the last Apply.
        savedInterfaces = draft.interfaces.filter { savedById[it.id] != it },
    )
}

/** An empty node field means the default node, as UserPreferences stores it. */
fun rfedNodeOrDefault(hash: String): String =
    hash.ifEmpty { UserPreferences.DEFAULT_RFED_NODE_IDENTITY_HASH }

fun isHex32(s: String): Boolean =
    s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

fun isHex32OrEmpty(s: String): Boolean = s.isEmpty() || isHex32(s)
