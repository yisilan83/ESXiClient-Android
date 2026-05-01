package dev.esxiclient.app.viewmodel

import android.app.Application
import android.util.Log
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

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    private val sessionManager = SessionManager(application)

    fun login(host: String, user: String, pass: String, rememberMe: Boolean) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)
            try {
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

                val response = RetrofitClient.service.executeSoap(host, soapBody)
                val httpCode = response.code
                val bodyString = response.body?.string()
                val responseText = bodyString ?: ""
                response.close()

                Log.d("ESXiClient", "HTTP $httpCode from $host/sdk")
                Log.d("ESXiClient", "Response body: ${responseText.take(500)}")

                if (responseText.isBlank() && !response.isSuccessful) {
                    _uiState.value = LoginUiState(error = "服务器无响应 (HTTP " + httpCode + ")，请检查地址")
                    return@launch
                }

                if (httpCode == 404) {
                    _uiState.value = LoginUiState(error = "SOAP 端点不存在 (404)，请检查 ESXi 版本")
                    return@launch
                }

                if (httpCode == 500 && ("InvalidLogin" in responseText || "NoPermission" in responseText)) {
                    _uiState.value = LoginUiState(error = "用户名或密码错误")
                    return@launch
                }

                if ("<key>" in responseText) {
                    val sessionId = responseText.substringAfter("<key>").substringBefore("</key>")
                    if (sessionId.isNotBlank()) {
                        sessionManager.saveSession(host, sessionId, user)
                        _uiState.value = LoginUiState(isSuccess = true)
                        return@launch
                    }
                }

                if ("soapenv:Fault" in responseText) {
                    val faultMsg = responseText.substringAfter("<faultstring>").substringBefore("</faultstring>")
                    _uiState.value = LoginUiState(error = faultMsg.ifBlank { "SOAP 错误" })
                    return@launch
                }

                _uiState.value = LoginUiState(error = "登录失败 (HTTP " + httpCode + ")，响应格式未知")
            } catch (e: java.net.UnknownHostException) {
                _uiState.value = LoginUiState(error = "找不到主机，请检查 IP/域名")
            } catch (e: java.net.ConnectException) {
                _uiState.value = LoginUiState(error = "连接被拒绝，请确保与 ESXi 同一网络")
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                _uiState.value = LoginUiState(error = "SSL 握手失败: " + (e.message?.take(60) ?: ""))
            } catch (e: Exception) {
                _uiState.value = LoginUiState(error = "网络异常: " + e.javaClass.simpleName + " - " + e.message)
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState()
    }
}