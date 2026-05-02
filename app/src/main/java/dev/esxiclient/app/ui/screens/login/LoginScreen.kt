package dev.esxiclient.app.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.esxiclient.app.ui.components.AppLogo
import dev.esxiclient.app.viewmodel.LoginViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var host by remember { mutableStateOf(uiState.savedHost) }
    var username by remember { mutableStateOf(uiState.savedUser.ifBlank { "root" }) }
    var password by remember { mutableStateOf(uiState.savedPass) }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(uiState.savedRememberMe) }

    // 当 ViewModel 加载完保存的凭据后，刷新本地状态
    LaunchedEffect(uiState.savedHost, uiState.savedUser, uiState.savedPass, uiState.savedRememberMe) {
        if (host.isBlank() && uiState.savedHost.isNotBlank()) host = uiState.savedHost
        if (username == "root" && uiState.savedUser.isNotBlank()) username = uiState.savedUser
        if (password.isBlank() && uiState.savedPass.isNotBlank()) password = uiState.savedPass
        if (!rememberMe && uiState.savedRememberMe) rememberMe = true
    }

    var localValidationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            viewModel.resetState()
            onLoginSuccess()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            AppLogo(
                modifier = Modifier.size(80.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                holeColor = MaterialTheme.colorScheme.background
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("ESXi Client", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("连接到您的 VMware ESXi 主机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it; localValidationError = null },
                label = { Text("服务器地址") },
                placeholder = { Text("IP或域名") },
                leadingIcon = { Icon(Icons.Default.Language, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; localValidationError = null },
                label = { Text("用户名") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localValidationError = null },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it },
                    enabled = !uiState.isLoading
                )
                Text(text = "记住密码", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    localValidationError = null
                    if (host.isBlank() || username.isBlank() || password.isBlank()) {
                        localValidationError = "请填写完整的登录信息"
                    } else {
                        viewModel.login(host.trim(), username.trim(), password.trim(), rememberMe)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text("登录", style = MaterialTheme.typography.labelLarge)
                }
            }

            val displayError = uiState.error ?: localValidationError
            displayError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("首次连接时将自动接受服务器证书指纹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}