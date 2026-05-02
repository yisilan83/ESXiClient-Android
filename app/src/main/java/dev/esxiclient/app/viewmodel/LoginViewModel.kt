package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.esxiclient.app.data.local.SessionManager
import dev.esxiclient.app.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false,
    val savedHost: String = "",
    val savedUser: String = "",
    val savedPass: String = "",
    val savedRememberMe: Boolean = false,      // ← current
    val savedCheckHttp: Boolean = false        // ← new
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    private val sessionManager = SessionManager(application)

    init {
        viewModelScope.launch {
            val host    = sessionManager.hostFlow.firstOrNull() ?: ""
            val user    = sessionManager.usernameFlow.firstOrNull() ?: "root"
            val pass    = sessionManager.passwordFlow.firstOrNull() ?: ""
            val remember= sessionManager.rememberMeFlow.firstOrNull() ?: false
            val http    = sessionManager.checkHttpFlow.firstOrNull() ?: false
            _uiState.value = _uiState.value.copy(
                savedHost = host,
                savedUser = user,
                savedPass = if (remember) pass else "",
                savedRememberMe = remember,
                savedCheckHttp = http
            )
        }
    }

    fun login(host: String, user: String, pass: String, rememberMe: Boolean, checkHttp: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = withContext(Dispatchers.IO) {
                    val escapedUser = user.replace("&", "&amp;").replace("<", "&lt;")
                    val escapedPass = pass.replace("&", "&amp;").replace("<", "&lt;")

                    val soapBody = """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:vim="urn:vim25">
  <soapenv:Body>
    <vim:Login>
      <vim:_this type="SessionManager">ha-sessionmgr</vim:_this>
      <vim:userName>${escapedUser}</vim:userName>
      <vim:password>${escapedPass}</vim:password>
    </vim:Login>
  </soapenv:Body>
</soapenv:Envelope>"""

                    val response = RetrofitClient.service.executeSoap(host, soapBody, null)
                    val httpCode = response.code
                    val bodyString = response.body?.string()
                    val responseText = bodyString ?: ""
                    response.close()

                    Log.d("ESXiClient", "HTTP $httpCode from $host/sdk")
                    Log.d("ESXiClient", "Response body: ${responseText.take(500)}")

                    Pair(httpCode, responseText)
                }

                val httpCode = result.first
                val responseText = result.second

                if (responseText.isBlank() && httpCode >= 400) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "服务器无响应 (HTTP $httpCode)，请检查地址")
                    return@launch
                }

                if (httpCode == 404) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "SOAP 端点不存在 (404)，请检查 ESXi 版本")
                    return@launch
                }

                if (httpCode == 500 && ("InvalidLogin" in responseText || "NoPermission" in responseText)) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "用户名或密码错误")
                    return@launch
                }

                if ("<key>" in responseText) {
                    val sessionId = responseText.substringAfter("<key>").substringBefore("</key>")
                    if (sessionId.isNotBlank()) {
                        sessionManager.saveSession(host, sessionId, user, pass, rememberMe, checkHttp)
                        _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                        return@launch
                    }
                }

                if ("soapenv:Fault" in responseText) {
                    val faultMsg = responseText.substringAfter("<faultstring>").substringBefore("</faultstring>")
                    _uiState.value = _uiState.value.copy(isLoading = false, error = faultMsg.ifBlank { "SOAP 错误" })
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoading = false, error = "登录失败 (HTTP $httpCode)，响应格式未知")
            } catch (e: java.net.UnknownHostException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "找不到主机，请检查 IP/域名")
            } catch (e: java.net.ConnectException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "连接被拒绝，请确保与 ESXi 同一网络")
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "SSL 握手失败: " + (e.message?.take(60) ?: ""))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "网络异常: " + e.javaClass.simpleName + " - " + e.message)
            }
        }
    }

    fun resetState() {
        _uiState.value = _uiState.value.copy(isSuccess = false)
    }
}
