package dev.esxiclient.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.VmInfo
import dev.esxiclient.app.repository.EsxiRepository
import dev.esxiclient.app.repository.MockEsxiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val hostInfo: HostInfo? = null,
    val vmList: List<VmInfo> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val repository: EsxiRepository = MockEsxiRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val host = repository.getHostInfo()
                val vms = repository.getVmList()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hostInfo = host,
                    vmList = vms
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load data"
                )
            }
        }
    }

    fun togglePower(vmId: String) {
        viewModelScope.launch {
            val success = repository.toggleVmPower(vmId)
            if (success) {
                // Refresh list to get updated states
                val newList = repository.getVmList()
                _uiState.value = _uiState.value.copy(vmList = newList)
            }
        }
    }
}