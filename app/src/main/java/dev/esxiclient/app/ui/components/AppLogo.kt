package dev.esxiclient.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    holeColor: Color = MaterialTheme.colorScheme.surface
) {
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val width = size.width
        val height = size.height

        // Define proportions based on the reference image
        val rectHeight = height * 0.42f
        val gap = height * 0.16f
        val cornerRadius = CornerRadius(width * 0.12f, width * 0.12f)

        val circleRadius = rectHeight * 0.35f
        val circleOffsetX = width * 0.25f

        // Top server rack
        drawRoundRect(
            color = containerColor,
            topLeft = Offset(0f, 0f),
            size = Size(width, rectHeight),
            cornerRadius = cornerRadius
        )
        // Top circle (drive bay)
        drawCircle(
            color = holeColor,
            radius = circleRadius,
            center = Offset(circleOffsetX, rectHeight / 2)
        )

        // Bottom server rack
        drawRoundRect(
            color = containerColor,
            topLeft = Offset(0f, rectHeight + gap),
            size = Size(width, rectHeight),
            cornerRadius = cornerRadius
        )
        // Bottom circle (drive bay)
        drawCircle(
            color = holeColor,
            radius = circleRadius,
            center = Offset(circleOffsetX, rectHeight + gap + rectHeight / 2)
        )
    }
}