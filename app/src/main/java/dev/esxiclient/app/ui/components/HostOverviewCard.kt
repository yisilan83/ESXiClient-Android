package dev.esxiclient.app.ui.components
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.esxiclient.app.model.HostInfo

@Composable
fun HostOverviewCard(hostInfo: HostInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                Toast.makeText(context, "Secure Shell (SSH) 已启用", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(text = "概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(icon = Icons.Default.Memory, value = "${hostInfo.cpuUsagePercent}%", label = "CPU", modifier = Modifier.weight(1f))
                StatItem(icon = Icons.Default.Storage, value = "${hostInfo.memoryUsagePercent}%", label = "内存", modifier = Modifier.weight(1f))
                StatItem(icon = Icons.Default.Timer, value = hostInfo.uptimeText, label = "运行时间", modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(icon = Icons.Default.PlayCircle, value = "${hostInfo.runningVmCount}/${hostInfo.totalVmCount}", label = "运行VM", modifier = Modifier.weight(1f))
                StatItem(icon = Icons.Default.SdCard, value = "${hostInfo.storageUsedGB}/${hostInfo.storageTotalGB} GB", label = "存储", modifier = Modifier.weight(1f))
                StatItem(icon = Icons.Default.Info, value = hostInfo.version, label = "ESXi 版本", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}