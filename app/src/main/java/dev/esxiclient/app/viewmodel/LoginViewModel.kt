package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

/**
 * 处理真实登录逻辑的 ViewModel。
 * 使用 AndroidViewModel 是为了能够直接拿到 Application Context 进而初始化 SessionManager。
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    private val sessionManager = SessionManager(application)

    fun login(host: String, user: String, pass: String, rememberMe: Boolean) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)
            
            try {
                // 1. 初始化对应主机的 HTTP 客户端
                val service = RetrofitClient.createService(host)
                
                // 2. 将用户名和密码拼接并进行 Base64 编码，生成 Basic Auth 字符串
                val credentials = "$user:$pass"
                val encodedCredentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
                val basicAuthStr = "Basic $encodedCredentials"
                
                // 3. 发送真实网络请求到 /api/session
                val response = service.createSession(basicAuthStr)

                if (response.isSuccessful) {
                    // 4. 解析成功的响应 (ESXi 返回的 Session ID 通常带有双引号，如 "xxxxxxxx-xxxx-xxxx")
                    val rawSession = response.body() ?: ""
                    val sessionId = rawSession.replace("\"", "")
                    
                    // 5. 将 Session ID 和 主机信息持久化保存
                    sessionManager.saveSession(host, sessionId, user)
                    
                    // 6. 更新状态，通知 UI 跳转
                    _uiState.value = LoginUiState(isSuccess = true)
                } else {
                    // 处理 HTTP 错误 (例如 401 Unauthorized 密码错误)
                    val errorMsg = if (response.code() == 401) "用户名或密码错误" else "登录失败: ${response.code()}"
                    _uiState.value = LoginUiState(error = errorMsg)
                }
            } catch (e: java.net.UnknownHostException) {
                _uiState.value = LoginUiState(error = "找不到主机，请检查 IP/域名 是否正确")
            } catch (e: java.net.ConnectException) {
                _uiState.value = LoginUiState(error = "连接被拒绝，请确保设备与 ESXi 在同一局域网内")
            } catch (e: Exception) {
                _uiState.value = LoginUiState(error = "网络异常: ${e.message}")
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState()
    }
}