package dev.esxiclient.app.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.esxiclient.app.model.PowerState

@Composable
fun StatusBadge(state: PowerState, modifier: Modifier = Modifier) {
    val (containerColor, contentColor, icon) = when (state) {
        PowerState.POWERED_ON -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Default.PlayCircle)
        PowerState.POWERED_OFF -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Default.StopCircle)
        PowerState.SUSPENDED -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Default.PauseCircle)
    }
    Surface(modifier = modifier.clip(RoundedCornerShape(8.dp)), color = containerColor, contentColor = contentColor) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = state.displayText(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}