package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.model.HostInfo
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.model.VmInfo
import dev.esxiclient.app.repository.EsxiConnector
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
    val error: String? = null,
    /** Display the protocol used to fetch data (REST or SOAP). */
    val protocol: String = ""
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        Log.d("HomeVM", "HomeViewModel init, 开始加载数据")
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val host = sessionManager.hostFlow.firstOrNull()
                val sessionId = sessionManager.sessionIdFlow.firstOrNull()
                val username = sessionManager.usernameFlow.firstOrNull() ?: "root"
                val password = sessionManager.passwordFlow.firstOrNull() ?: ""

                Log.d("HomeVM", "读取 session: host=$host, user=$username, sessionId=${sessionId?.take(10)}...")

                if (host.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "尚未登录或会话已过期"
                    )
                    return@launch
                }

                Log.d("HomeVM", "开始协议协商...")
                val result = withContext(Dispatchers.IO) {
                    val connector = EsxiConnector(host)
                    val conn = connector.connect(username, password, sessionId ?: "")
                    Log.d("HomeVM", "已选择协议: ${conn.usedProtocol}")
                    val repo = conn.repository
                    val hostInfo = repo.getHostInfo()
                    Log.d("HomeVM", "主机信息: cpu=${hostInfo.cpuUsagePercent}% mem=${hostInfo.memoryUsagePercent}%")
                    val vms = repo.getVmList()
                    Log.d("HomeVM", "VM 列表: ${vms.size} 个")
                    Triple(hostInfo, vms, conn.usedProtocol)
                }

                val hostInfo = result.first
                val vms = result.second
                val protocol = result.third

                val runningVms = vms.count { it.powerState == PowerState.POWERED_ON }
                val updatedHostInfo = hostInfo.copy(
                    runningVmCount = runningVms,
                    totalVmCount = vms.size.coerceAtLeast(hostInfo.totalVmCount)
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    hostInfo = updatedHostInfo,
                    vmList = vms,
                    protocol = protocol
                )
                Log.d("HomeVM", "UI 状态已更新 (协议: $protocol)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeVM", "加载失败: ${e.javaClass.simpleName} - ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "网络请求失败: ${e.javaClass.simpleName} - ${e.message}"
                )
            }
        }
    }

    fun togglePower(vmId: String) {
        loadData()
    }
}
