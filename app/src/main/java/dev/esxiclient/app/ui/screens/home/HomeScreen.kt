package dev.esxiclient.app.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.esxiclient.app.model.PowerState
import dev.esxiclient.app.model.VmInfo
import dev.esxiclient.app.ui.components.EmptyState
import dev.esxiclient.app.ui.components.HostOverviewCard
import dev.esxiclient.app.ui.components.VmCard
import dev.esxiclient.app.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVmClick: (String) -> Unit, 
    onSettingsClick: () -> Unit, 
    onLogout: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var confirmDialogVm by remember { mutableStateOf<VmInfo?>(null) }

    confirmDialogVm?.let { vm ->
        AlertDialog(
            onDismissRequest = { confirmDialogVm = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认操作") },
            text = { Text("确定要操作虚拟机 \"${vm.name}\" 吗？${if (vm.powerState == PowerState.POWERED_ON) "\n未保存数据将会丢失。" else ""}") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.togglePower(vm.id)
                        confirmDialogVm = null
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (vm.powerState == PowerState.POWERED_ON) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (vm.powerState == PowerState.POWERED_ON) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmDialogVm = null }) { Text("取消") } }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { 
                    Column {
                        Text("ESXi 客户端")
                        uiState.hostInfo?.let {
                            Text(it.hostAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, null) } },
                actions = { 
                    IconButton(onClick = { viewModel.loadData() }) { Icon(Icons.Default.Refresh, null) }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, null) } 
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(paddingValues), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uiState.hostInfo?.let { host ->
                    item { HostOverviewCard(hostInfo = host) }
                }
                
                item { 
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "虚拟机 (${uiState.vmList.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { /* 模拟排序 */ }) {
                            Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("排序")
                        }
                    }
                }
                
                if (uiState.vmList.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.CloudOff,
                            title = "暂无虚拟机",
                            description = "当前主机上没有发现任何虚拟机",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
                        )
                    }
                } else {
                    items(items = uiState.vmList, key = { it.id }) { vm ->
                        VmCard(vm = vm, onPowerToggle = { confirmDialogVm = vm }, onClick = { onVmClick(vm.id) })
                    }
                }
            }
        }
    }
}