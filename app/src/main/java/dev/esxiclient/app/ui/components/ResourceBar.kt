package dev.esxiclient.app.ui.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ResourceBar(label: String, usagePercent: Int, detailText: String? = null, modifier: Modifier = Modifier) {
    val animatedProgress by animateFloatAsState(targetValue = usagePercent / 100f, animationSpec = tween(durationMillis = 600), label = "progress")
    val barColor = when {
        usagePercent < 50 -> MaterialTheme.colorScheme.primary
        usagePercent < 80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$usagePercent%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = barColor)
                detailText?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = barColor, trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}