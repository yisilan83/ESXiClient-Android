package dev.esxiclient.app.ui.screens.vmdetail
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
import androidx.compose.ui.unit.dp
import dev.esxiclient.app.data.MockData
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.ui.components.ResourceBar
import dev.esxiclient.app.ui.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmDetailScreen(vmId: String, onBack: () -> Unit) {
    val vm = remember(vmId) { MockData.getVmById(vmId) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm?.name ?: "详情") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { paddingValues ->
        if (vm == null) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (vm.powerState == PowerState.POWERED_ON) Icons.Default.PlayCircle else Icons.Default.StopCircle, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusBadge(state = vm.powerState)
                }
            }
            Text("基本信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Guest OS: ${vm.guestOs}\nIP 地址: ${vm.ipAddress ?: "未分配"}\nCPU: ${vm.cpuCount} vCPU\n内存: ${vm.memoryMiB / 1024} GB")
                }
            }
            if (vm.powerState == PowerState.POWERED_ON) {
                Text("实时监控", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ResourceBar(label = "CPU", usagePercent = vm.cpuUsagePercent)
                        ResourceBar(label = "内存", usagePercent = vm.memoryUsagePercent)
                    }
                }
            }
        }
    }
}