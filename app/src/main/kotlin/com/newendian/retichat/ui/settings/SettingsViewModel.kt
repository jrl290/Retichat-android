package com.newendian.retichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.newendian.retichat.ServiceState
import com.newendian.retichat.data.db.dao.InterfaceConfigDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Settings page edits [draft], a copy of [saved]; nothing changes until
 * [apply] (James, 2026-10-19, as on iOS). Both are null until the saved
 * values are read. [applyScope] outlives the page, so a restart Apply starts
 * runs to the end when the user leaves.
 */
class SettingsViewModel(
    dao: InterfaceConfigDao,
    val serviceState: StateFlow<ServiceState>,
    private val store: SettingsStore,
    private val applyScope: CoroutineScope,
) : ViewModel() {

    private val _saved = MutableStateFlow<SettingsValues?>(null)
    val saved: StateFlow<SettingsValues?> = _saved.asStateFlow()

    private val _draft = MutableStateFlow<SettingsValues?>(null)
    val draft: StateFlow<SettingsValues?> = _draft.asStateFlow()

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    init {
        viewModelScope.launch {
            val values = store.read().copy(interfaces = dao.allInterfaces().first())
            _saved.value = values
            _draft.value = values
        }
    }

    fun edit(change: (SettingsValues) -> SettingsValues) {
        _draft.update { it?.let(change) }
    }

    /** Back to the saved values, as iOS's Revert. */
    fun revert() {
        _draft.value = _saved.value
    }

    /**
     * Saves the changes, then brings the stack in line: one restart if any
     * change needs it (what the Restart button did), else Nearby RTNode
     * started or stopped on the running stack. The page is clean again once
     * the changes are saved, before the restart has finished.
     */
    fun apply() {
        val saved = _saved.value ?: return
        val draft = _draft.value ?: return
        if (_applying.value || !hasChanges(saved, draft) || !draft.valid) return
        val plan = planApply(saved, draft)
        _applying.value = true
        applyScope.launch {
            try {
                val stored = store.save(draft, plan)
                _saved.value = stored
                // An edit made while saving stays pending.
                _draft.update { if (it == draft) stored else it }
            } finally {
                _applying.value = false
            }
            store.applyToStack(plan)
        }
    }

    class Factory(
        private val dao: InterfaceConfigDao,
        private val serviceState: StateFlow<ServiceState>,
        private val store: SettingsStore,
        private val applyScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(dao, serviceState, store, applyScope) as T
    }
}

/** The page differs from what is saved: Apply is offered, and leaving asks first. */
fun hasChanges(saved: SettingsValues?, draft: SettingsValues?): Boolean =
    saved != null && draft != null && draft != saved
