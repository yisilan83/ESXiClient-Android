package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.model.VmInfo
import dev.esxiclient.app.repository.RemoteEsxiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val isLoading: Boolean = true,
    val hostInfo: HostInfo? = null,
    val vmList: List<VmInfo> = emptyList(),
    val error: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val host = sessionManager.hostFlow.firstOrNull()
                val sessionId = sessionManager.sessionIdFlow.firstOrNull()

                if (host.isNullOrBlank() || sessionId.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "尚未登录或会话已过期"
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    val repo = RemoteEsxiRepository(host, sessionId)
                    Log.d("HomeVM", "开始获取主机信息...")
                    val hostInfo = repo.getHostInfo()
                    Log.d("HomeVM", "主机信息: $hostInfo")
                    Log.d("HomeVM", "开始获取 VM 列表...")
                    val vms = repo.getVmList()
                    Log.d("HomeVM", "VM 列表: ${vms.size} 个")
                    Pair(hostInfo, vms)
                }

                val hostInfo = result.first
                val vms = result.second

                val runningVms = vms.count { it.powerState == PowerState.POWERED_ON }
                val updatedHostInfo = hostInfo.copy(
                    runningVmCount = runningVms,
                    totalVmCount = vms.size.coerceAtLeast(hostInfo.totalVmCount)
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hostInfo = updatedHostInfo,
                    vmList = vms
                )
            } catch (e: Exception) {
                Log.e("HomeVM", "加载失败: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "网络请求失败: ${e.message}"
                )
            }
        }
    }

    fun togglePower(vmId: String) {
        loadData()
    }
}