package dev.esxiclient.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.model.VmInfo
import dev.esxiclient.app.repository.RemoteEsxiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class VmDetailUiState(
    val isLoading: Boolean = true,
    val vmInfo: VmInfo? = null,
    val error: String? = null
)

class VmDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val _uiState = MutableStateFlow(VmDetailUiState())
    val uiState = _uiState.asStateFlow()

    fun loadVm(vmId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val host = sessionManager.hostFlow.firstOrNull()
                val sessionId = sessionManager.sessionIdFlow.firstOrNull()
                
                if (host != null && sessionId != null) {
                    val repo = RemoteEsxiRepository(host, sessionId)
                    val vm = repo.getVmById(vmId)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        vmInfo = vm,
                        error = if (vm == null) "找不到虚拟机信息" else null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "未登录"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "请求失败: ${e.message}"
                )
            }
        }
    }
}