package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.network.RetrofitClient
import dev.esxiclient.app.network.dto.LoginRequest
import dev.esxiclient.app.network.dto.MoRef
import dev.esxiclient.app.network.dto.SoapBody
import dev.esxiclient.app.network.dto.SoapEnvelope
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
                
                // 2. 构建 SOAP 登录请求
                val loginRequest = LoginRequest(
                    _this = MoRef("SessionManager", "ha-sessionmgr"),
                    userName = user,
                    password = pass
                )
                val envelope = SoapEnvelope(body = SoapBody(login = loginRequest))
                
                // 3. 发送真实网络请求到 /sdk
                val response = service.soapRequest(envelope)

                if (response.isSuccessful) {
                    // 4. 解析成功的响应
                    val sessionKey = response.body()?.body?.loginResponse?.returnVal?.key
                    
                    if (!sessionKey.isNullOrBlank()) {
                        // 5. 将 Session ID 和 主机信息持久化保存
                        sessionManager.saveSession(host, sessionKey, user)
                        
                        // 6. 更新状态，通知 UI 跳转
                        _uiState.value = LoginUiState(isSuccess = true)
                    } else {
                        _uiState.value = LoginUiState(error = "登录失败: 无法获取 Session ID")
                    }
                } else {
                    // 处理 HTTP 错误 (例如 500 Internal Server Error 密码错误)
                    val errorMsg = if (response.code() == 500) "用户名或密码错误" else "登录失败: ${response.code()}"
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