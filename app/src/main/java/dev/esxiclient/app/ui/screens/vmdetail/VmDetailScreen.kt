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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.ui.components.EmptyState
import dev.esxiclient.app.ui.components.ResourceBar
import dev.esxiclient.app.ui.components.StatusBadge
import dev.esxiclient.app.viewmodel.VmDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmDetailScreen(
    vmId: String, 
    onBack: () -> Unit,
    viewModel: VmDetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 页面启动时加载虚拟机详情
    LaunchedEffect(vmId) {
        viewModel.loadVm(vmId)
    }

    val vm = uiState.vmInfo

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm?.name ?: "虚拟机详情") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { viewModel.loadVm(vmId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        
        if (uiState.error != null || vm == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.ErrorOutline,
                    title = "加载失败",
                    description = uiState.error ?: "未找到该虚拟机信息"
                )
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (vm.powerState == PowerState.POWERED_ON) Icons.Default.PlayCircle else Icons.Default.StopCircle, 
                        null, 
                        modifier = Modifier.size(48.dp), 
                        tint = if (vm.powerState == PowerState.POWERED_ON) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusBadge(state = vm.powerState)
                }
            }
            
            Text("基本信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("VM ID: ${vm.id}\nGuest OS: ${vm.guestOs}\nIP 地址: ${vm.ipAddress ?: "未分配"}\nCPU: ${vm.cpuCount} vCPU\n内存: ${vm.memoryMiB} MiB")
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