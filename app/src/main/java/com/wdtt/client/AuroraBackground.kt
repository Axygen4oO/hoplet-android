package com.wdtt.client

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val AuroraBase = Color(0xFF050608)
private val AuroraCycle = (PI * 2.0).toFloat()

private data class AuroraCloudSpec(
    val color: Color,
    val anchorX: Float,
    val anchorY: Float,
    val radiusFactor: Float,
    val alpha: Float,
    val amplitudeXFactor: Float,
    val amplitudeYFactor: Float,
    val durationMillis: Int,
    val phaseX: Float,
    val phaseY: Float
)

private val AuroraClouds = listOf(
    AuroraCloudSpec(
        color = Color(0xFF1AA878),
        anchorX = 0.14f,
        anchorY = 0.16f,
        radiusFactor = 0.38f,
        alpha = 0.33f,
        amplitudeXFactor = 0.022f,
        amplitudeYFactor = 0.018f,
        durationMillis = 34000,
        phaseX = 0.5f,
        phaseY = 1.2f
    ),
    AuroraCloudSpec(
        color = Color(0xFF19C3AA),
        anchorX = 0.86f,
        anchorY = 0.24f,
        radiusFactor = 0.32f,
        alpha = 0.29f,
        amplitudeXFactor = 0.018f,
        amplitudeYFactor = 0.024f,
        durationMillis = 28000,
        phaseX = 1.9f,
        phaseY = 0.7f
    ),
    AuroraCloudSpec(
        color = Color(0xFF29C8FF),
        anchorX = 0.30f,
        anchorY = 0.82f,
        radiusFactor = 0.35f,
        alpha = 0.27f,
        amplitudeXFactor = 0.020f,
        amplitudeYFactor = 0.016f,
        durationMillis = 36000,
        phaseX = 2.6f,
        phaseY = 1.0f
    ),
    AuroraCloudSpec(
        color = Color(0xFF7C5CFF),
        anchorX = 0.84f,
        anchorY = 0.78f,
        radiusFactor = 0.30f,
        alpha = 0.24f,
        amplitudeXFactor = 0.017f,
        amplitudeYFactor = 0.021f,
        durationMillis = 31000,
        phaseX = 3.0f,
        phaseY = 0.4f
    )
)

@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora_background")
    val progressStates = AuroraClouds.mapIndexed { index, cloud ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = cloud.durationMillis,
                    easing = LinearEasing
                )
            ),
            label = "aurora_cloud_${index + 1}"
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(AuroraBase)

        val width = size.width
        val height = size.height
        val diagonal = hypot(width, height)

        AuroraClouds.forEachIndexed { index, cloud ->
            val cycle = progressStates[index].value * AuroraCycle
            val center = Offset(
                x = width * cloud.anchorX +
                    sin(cycle + cloud.phaseX) * width * cloud.amplitudeXFactor +
                    sin(cycle * 0.51f + cloud.phaseY) * width * cloud.amplitudeXFactor * 0.24f,
                y = height * cloud.anchorY +
                    cos(cycle * 0.82f + cloud.phaseY) * height * cloud.amplitudeYFactor +
                    cos(cycle * 0.44f + cloud.phaseX) * height * cloud.amplitudeYFactor * 0.22f
            )

            drawAuroraCloud(
                center = center,
                radius = diagonal * cloud.radiusFactor,
                tint = cloud.color,
                alpha = cloud.alpha
            )
        }
    }
}

private fun DrawScope.drawAuroraCloud(
    center: Offset,
    radius: Float,
    tint: Color,
    alpha: Float
) {
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to tint.copy(alpha = alpha),
                0.14f to tint.copy(alpha = alpha * 0.88f),
                0.28f to tint.copy(alpha = alpha * 0.52f),
                0.46f to tint.copy(alpha = alpha * 0.18f),
                0.62f to tint.copy(alpha = alpha * 0.05f),
                0.72f to Color.Transparent,
                1.0f to Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Screen
    )
}
