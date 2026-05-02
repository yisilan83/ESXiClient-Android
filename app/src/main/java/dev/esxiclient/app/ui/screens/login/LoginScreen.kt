package dev.esxiclient.app.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.esxiclient.app.ui.components.AppLogo
import dev.esxiclient.app.viewmodel.LoginViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, viewModel: LoginViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var checkHttp by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.savedHost, uiState.savedUser, uiState.savedPass,
                   uiState.savedRememberMe, uiState.savedCheckHttp) {
        host = uiState.savedHost
        user = uiState.savedUser
        pass = uiState.savedPass
        rememberMe = uiState.savedRememberMe
        checkHttp = uiState.savedCheckHttp
    }

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) onLoginSuccess()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            AppLogo(modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("ESXi 客户端", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Material You 设计风格", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("ESXi 主机地址") },
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("用户名") },
                placeholder = { Text("root") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                }
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = checkHttp, onCheckedChange = { checkHttp = it })
                Text("HTTP", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.login(host, user, pass, rememberMe, checkHttp) },
                enabled = !uiState.isLoading && host.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (uiState.isLoading) { CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                Text("连接", style = MaterialTheme.typography.titleMedium)
            }

            uiState.error?.let { err ->
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(err, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
