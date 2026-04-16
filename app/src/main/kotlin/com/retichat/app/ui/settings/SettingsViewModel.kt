package com.retichat.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.retichat.app.ServiceState
import com.retichat.app.data.db.dao.InterfaceConfigDao
import com.retichat.app.data.db.entity.InterfaceConfigEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dao: InterfaceConfigDao,
    val serviceState: StateFlow<ServiceState>,
    private val onRestart: () -> Unit,
) : ViewModel() {

    val interfaces: StateFlow<List<InterfaceConfigEntity>> = dao
        .allInterfaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { dao.setEnabled(id, enabled) }
    }

    fun saveInterface(entity: InterfaceConfigEntity) {
        viewModelScope.launch { dao.upsert(entity) }
    }

    fun deleteInterface(id: Long) {
        viewModelScope.launch { dao.deleteById(id) }
    }

    fun restartService() = onRestart()

    class Factory(
        private val dao: InterfaceConfigDao,
        private val serviceState: StateFlow<ServiceState>,
        private val onRestart: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(dao, serviceState, onRestart) as T
    }
}
