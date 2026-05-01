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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(48.dp))
            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("ESXi Client", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("服务器地址") }, leadingIcon = { Icon(Icons.Default.Language, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, leadingIcon = { Icon(Icons.Default.Person, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }
            }, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                if (host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) onLoginSuccess()
                else errorMessage = "请填写完整信息"
            }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Text("登录", style = MaterialTheme.typography.labelLarge)
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}