package com.newendian.retichat.ui.settings

import com.newendian.retichat.RetichatApp
import com.newendian.retichat.service.ConnectionStateManager
import com.newendian.retichat.service.DisplayNameSettings
import com.newendian.retichat.service.StackRuntime
import com.newendian.retichat.service.UserPreferences
import kotlinx.coroutines.flow.first

/** Where the Settings page reads its values and Apply writes them. */
interface SettingsStore {
    /** The saved values; [SettingsValues.interfaces] empty (the page reads them from Room). */
    fun read(): SettingsValues

    /**
     * Saves what [plan] says changed in [draft], and returns the values as
     * now stored: names cleaned, the node field filled with the default when
     * empty, new interfaces with their Room ids.
     */
    suspend fun save(draft: SettingsValues, plan: ApplyPlan): SettingsValues

    /** Brings the running stack in line with what [save] stored. */
    suspend fun applyToStack(plan: ApplyPlan)
}

class AppSettingsStore(private val app: RetichatApp) : SettingsStore {

    private val dao get() = app.database.interfaceConfigDao()

    override fun read(): SettingsValues = SettingsValues(
        announceName = DisplayNameSettings.stored(app, DisplayNameSettings.Kind.ANNOUNCE),
        messageName = DisplayNameSettings.stored(app, DisplayNameSettings.Kind.MESSAGE),
        channelName = DisplayNameSettings.stored(app, DisplayNameSettings.Kind.CHANNEL),
        filterStrangers = UserPreferences.isFilterStrangersEnabled(app),
        // The node in use is always shown: the configured one, or the default.
        rfedNodeHash = UserPreferences.getEffectiveRfedNodeIdentityHash(app),
        lxmfPropOverride = UserPreferences.getRfedLxmfPropOverride(app),
        defaultTcp = UserPreferences.isDefaultTcpEnabled(app),
        localNetwork = UserPreferences.isAutoInterfaceEnabled(app),
        rtnodeBluetooth = UserPreferences.isRtnodeBluetoothEnabled(app),
        interfaces = emptyList(),
    )

    override suspend fun save(draft: SettingsValues, plan: ApplyPlan): SettingsValues {
        var stored = draft.copy(rfedNodeHash = rfedNodeOrDefault(draft.rfedNodeHash))
        // Cleaned by the Rust cleaner and applied to the running router at
        // once (DisplayNameSettings): no restart.
        for (kind in plan.names) {
            stored = stored.withName(kind, DisplayNameSettings.save(app, kind, draft.name(kind)))
        }
        if (plan.filterStrangers) app.repository.setCoreFilterStrangers(draft.filterStrangers)
        if (plan.rfedNode) {
            // Storing the default explicitly or as blank is the same node;
            // keep blank so a future default change still applies.
            val node = stored.rfedNodeHash
            UserPreferences.setRfedNodeIdentityHash(
                app,
                if (node == UserPreferences.DEFAULT_RFED_NODE_IDENTITY_HASH) "" else node,
            )
        }
        if (plan.lxmfPropOverride) UserPreferences.setRfedLxmfPropOverride(app, draft.lxmfPropOverride)
        if (plan.defaultTcp) UserPreferences.setDefaultTcpEnabled(app, draft.defaultTcp)
        if (plan.localNetwork) UserPreferences.setAutoInterfaceEnabled(app, draft.localNetwork)
        if (plan.rtnodeBluetooth) UserPreferences.setRtnodeBluetoothEnabled(app, draft.rtnodeBluetooth)
        if (plan.deletedInterfaceIds.isNotEmpty() || plan.savedInterfaces.isNotEmpty()) {
            for (id in plan.deletedInterfaceIds) dao.deleteById(id)
            // A new interface's negative id is the page's; Room assigns its own.
            for (entity in plan.savedInterfaces) dao.upsert(if (entity.id < 0) entity.copy(id = 0) else entity)
            stored = stored.copy(interfaces = dao.allInterfaces().first())
        }
        return stored
    }

    override suspend fun applyToStack(plan: ApplyPlan) {
        if (plan.restart) {
            // Bootstrap reads every setting, Nearby RTNode included.
            StackRuntime.restart(app)
        } else if (plan.rtnodeBluetooth) {
            StackRuntime.applyRtnodeBluetoothSetting(app)
        }
        // The card's dot follows the node now in use.
        if (plan.rfedNode) ConnectionStateManager.openRfedNodeLink()
    }
}
