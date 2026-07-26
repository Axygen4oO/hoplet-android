package com.wdtt.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wdtt.client.ConnectionLifecycle
import com.wdtt.client.ConnectionStage
import com.wdtt.client.ConnectionState
import com.wdtt.client.StageStatus
import com.wdtt.client.WDTTColors

private data class ConnectionProgressPalette(
    val cardBackground: Color,
    val cardBorder: Color,
    val onSurfaceVariant: Color,
    val onSurface: Color,
    val outlineVariant: Color,
    val primary: Color,
    val error: Color,
    val success: Color,
    val separator: Color
)

@Composable
private fun rememberConnectionProgressPalette(): ConnectionProgressPalette {
    val colorScheme = MaterialTheme.colorScheme
    val cardBackground = AppCardDefaults.containerColor()
    val isDarkPalette = colorScheme.surface.red + colorScheme.surface.green + colorScheme.surface.blue < 1.5f
    return remember(
        cardBackground,
        colorScheme.surface,
        colorScheme.surfaceVariant,
        colorScheme.onSurfaceVariant,
        colorScheme.onSurface,
        colorScheme.outlineVariant,
        isDarkPalette
    ) {
        ConnectionProgressPalette(
            cardBackground = cardBackground,
            cardBorder = lerp(
                colorScheme.outlineVariant,
                WDTTColors.terminalBlue,
                if (isDarkPalette) 0.14f else 0.08f
            ).copy(alpha = if (isDarkPalette) 0.76f else 0.5f),
            onSurfaceVariant = colorScheme.onSurfaceVariant,
            onSurface = colorScheme.onSurface,
            outlineVariant = colorScheme.outlineVariant,
            primary = WDTTColors.terminalBlue,
            error = WDTTColors.terminalRed,
            success = if (isDarkPalette) WDTTColors.connectedDark else WDTTColors.connected,
            separator = colorScheme.outlineVariant.copy(alpha = if (isDarkPalette) 0.88f else 0.64f)
        )
    }
}

@Composable
fun ConnectionProgressCard(
    state: ConnectionState,
    activeConnections: Int = 0,
    trafficText: String = "0.00 МБ",
    uptimeText: String? = null,
    modifier: Modifier = Modifier
) {
    val palette = rememberConnectionProgressPalette()

    AnimatedVisibility(
        visible = state.lifecycle != ConnectionLifecycle.IDLE,
        enter = fadeIn(animationSpec = tween(240)) + expandVertically(animationSpec = tween(280)),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(240))
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = palette.cardBackground,
            border = BorderStroke(1.dp, palette.cardBorder),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactLayout = maxWidth < 360.dp
                val horizontalPadding = if (compactLayout) 12.dp else 14.dp

                Column(
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedContent(
                        targetState = state.lifecycle == ConnectionLifecycle.CONNECTED,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(160))
                        },
                        label = "connection_card_top_slot"
                    ) { isConnected ->
                        if (isConnected) {
                            ConnectionActiveSummary(
                                activeConnections = activeConnections,
                                trafficText = trafficText,
                                uptimeText = uptimeText,
                                palette = palette,
                                compactLayout = compactLayout
                            )
                        } else {
                            ConnectionProgressHeader(
                                state = state,
                                palette = palette,
                                compactLayout = compactLayout
                            )
                        }
                    }

                    ConnectionProgressScheme(
                        state = state,
                        palette = palette,
                        compactLayout = compactLayout
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionProgressHeader(
    state: ConnectionState,
    palette: ConnectionProgressPalette,
    compactLayout: Boolean
) {
    val statusColor = statusTextColor(state.lifecycle, palette)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Схема подключения",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Crossfade(
            targetState = state.statusText,
            animationSpec = tween(durationMillis = 220),
            label = "connection_status_text"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (compactLayout) 8.dp else 12.dp)
            )
        }
    }
}

@Composable
private fun ConnectionProgressScheme(
    state: ConnectionState,
    palette: ConnectionProgressPalette,
    compactLayout: Boolean
) {
    val stages = ConnectionStage.entries.toList()
    val nodeOuterSize = if (compactLayout) 34.dp else 36.dp
    val nodeInnerSize = if (compactLayout) 24.dp else 26.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        stages.forEachIndexed { index, stage ->
            val status = state.stageStatuses[stage] ?: StageStatus.WAITING
            val previousStatus = stages.getOrNull(index - 1)?.let { prevStage ->
                state.stageStatuses[prevStage] ?: StageStatus.WAITING
            }
            val nextStatus = stages.getOrNull(index + 1)?.let { nextStage ->
                state.stageStatuses[nextStage] ?: StageStatus.WAITING
            }

            StageItem(
                stage = stage,
                label = stage.displayName,
                status = status,
                previousStatus = previousStatus,
                nextStatus = nextStatus,
                isCurrent = state.currentStage == stage,
                isFirst = index == 0,
                isLast = index == stages.lastIndex,
                palette = palette,
                outerNodeSize = nodeOuterSize,
                innerNodeSize = nodeInnerSize,
                compactLayout = compactLayout,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StageItem(
    stage: ConnectionStage,
    label: String,
    status: StageStatus,
    previousStatus: StageStatus?,
    nextStatus: StageStatus?,
    isCurrent: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    palette: ConnectionProgressPalette,
    outerNodeSize: Dp,
    innerNodeSize: Dp,
    compactLayout: Boolean,
    modifier: Modifier = Modifier
) {
    val lineThickness = 2.dp
    val lineRadius = 1.dp
    val leftVisual = rememberConnectorVisual(
        status = previousStatus ?: StageStatus.WAITING,
        nextStatus = status,
        palette = palette
    )
    val rightVisual = rememberConnectorVisual(
        status = status,
        nextStatus = nextStatus ?: StageStatus.WAITING,
        palette = palette
    )
    val leftProgress by animateFloatAsState(
        targetValue = if (isFirst) 0f else leftVisual.progress,
        animationSpec = tween(320),
        label = "connector_left_${stage.name}"
    )
    val rightProgress by animateFloatAsState(
        targetValue = if (isLast) 0f else rightVisual.progress,
        animationSpec = tween(320),
        label = "connector_right_${stage.name}"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(outerNodeSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val centerY = size.height / 2f
                val centerX = size.width / 2f
                val lineHeight = lineThickness.toPx()
                val cornerRadius = lineRadius.toPx()
                val nodeRadius = outerNodeSize.toPx() / 2f
                val leftStart = 0f
                val leftEnd = (centerX - nodeRadius).coerceAtLeast(leftStart)
                val rightStart = (centerX + nodeRadius).coerceAtMost(size.width)
                val rightEnd = size.width
                val top = centerY - lineHeight / 2f
                val inactiveColor = palette.outlineVariant.copy(alpha = 0.68f)

                if (!isFirst && leftEnd > leftStart) {
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = androidx.compose.ui.geometry.Offset(leftStart, top),
                        size = androidx.compose.ui.geometry.Size(leftEnd - leftStart, lineHeight),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )
                    if (leftProgress > 0f) {
                        val activeWidth = (leftEnd - leftStart) * leftProgress
                        drawRoundRect(
                            color = leftVisual.color,
                            topLeft = androidx.compose.ui.geometry.Offset(leftEnd - activeWidth, top),
                            size = androidx.compose.ui.geometry.Size(activeWidth, lineHeight),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    }
                }

                if (!isLast && rightEnd > rightStart) {
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = androidx.compose.ui.geometry.Offset(rightStart, top),
                        size = androidx.compose.ui.geometry.Size(rightEnd - rightStart, lineHeight),
                        cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                    )
                    if (rightProgress > 0f) {
                        val activeWidth = (rightEnd - rightStart) * rightProgress
                        drawRoundRect(
                            color = rightVisual.color,
                            topLeft = androidx.compose.ui.geometry.Offset(rightStart, top),
                            size = androidx.compose.ui.geometry.Size(activeWidth, lineHeight),
                            cornerRadius = CornerRadius(cornerRadius, cornerRadius)
                        )
                    }
                }
            }

            StageNode(
                stage = stage,
                status = status,
                isCurrent = isCurrent,
                palette = palette,
                outerSize = outerNodeSize,
                innerSize = innerNodeSize
            )
        }

        Spacer(modifier = Modifier.height(if (compactLayout) 5.dp else 6.dp))

        StageLabel(
            label = label,
            status = status,
            palette = palette
        )
    }
}

@Composable
private fun StageNode(
    stage: ConnectionStage,
    status: StageStatus,
    isCurrent: Boolean,
    palette: ConnectionProgressPalette,
    outerSize: Dp,
    innerSize: Dp
) {
    val targetColor = stageColor(status, palette)
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(300),
        label = "stage_color_${stage.name}"
    )
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (status == StageStatus.WAITING) {
            palette.outlineVariant.copy(alpha = 0.8f)
        } else {
            color.copy(alpha = 0.96f)
        },
        animationSpec = tween(300),
        label = "stage_border_${stage.name}"
    )
    val baseScale by animateFloatAsState(
        targetValue = when (status) {
            StageStatus.RUNNING -> 1.08f
            StageStatus.ERROR -> 1.1f
            else -> 1f
        },
        animationSpec = tween(260),
        label = "stage_scale_${stage.name}"
    )

    val pulseScale = if (status == StageStatus.RUNNING) {
        val runningTransition = rememberInfiniteTransition(label = "stage_pulse_${stage.name}")
        val pulse by runningTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "stage_pulse_value_${stage.name}"
        )
        pulse
    } else {
        1f
    }

    val shakeTranslation = if (status == StageStatus.ERROR) {
        val errorTransition = rememberInfiniteTransition(label = "stage_error_${stage.name}")
        val shake by errorTransition.animateFloat(
            initialValue = -1.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 220
                    0f at 110
                },
                repeatMode = RepeatMode.Reverse
            ),
            label = "stage_shake_${stage.name}"
        )
        shake
    } else {
        0f
    }

    val visualScale = if (status == StageStatus.RUNNING) pulseScale else baseScale
    val glowAlpha = when {
        status == StageStatus.RUNNING || isCurrent -> 0.24f
        status == StageStatus.SUCCESS -> 0.12f
        status == StageStatus.ERROR -> 0.16f
        else -> 0f
    }

    Box(
        modifier = Modifier
            .graphicsLayer { translationX = shakeTranslation }
            .size(outerSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(outerSize)
                .scale(visualScale)
                .alpha(1f)
                .background(color.copy(alpha = glowAlpha), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(innerSize)
                .scale(visualScale)
                .border(width = 1.25.dp, color = borderColor, shape = CircleShape)
                .background(stageFillColor(status, color), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AnimatedStageIcon(
                status = status,
                waitingColor = palette.outlineVariant.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun AnimatedStageIcon(
    status: StageStatus,
    waitingColor: Color
) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
        },
        label = "stage_icon"
    ) { current ->
        when (current) {
            StageStatus.SUCCESS -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
            StageStatus.ERROR -> Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
            StageStatus.RUNNING -> Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
            StageStatus.WAITING -> Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = waitingColor,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun StageLabel(
    label: String,
    status: StageStatus,
    palette: ConnectionProgressPalette
) {
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = when (status) {
            StageStatus.SUCCESS -> stageColor(StageStatus.SUCCESS, palette)
            StageStatus.ERROR -> stageColor(StageStatus.ERROR, palette)
            StageStatus.RUNNING -> stageColor(StageStatus.RUNNING, palette)
            StageStatus.WAITING -> palette.onSurfaceVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(220),
        label = "stage_label_$label"
    )
    val alpha by animateFloatAsState(
        targetValue = if (status == StageStatus.WAITING) 0.8f else 1f,
        animationSpec = tween(220),
        label = "stage_label_alpha_$label"
    )

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .alpha(alpha)
    )
}

@Composable
private fun ConnectionActiveSummary(
    activeConnections: Int,
    trafficText: String,
    uptimeText: String?,
    palette: ConnectionProgressPalette,
    compactLayout: Boolean
) {
    val textStyle = if (compactLayout) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.labelMedium
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SummaryMetricLine(
            label = "Активных: ",
            value = activeConnections.toString(),
            valueColor = palette.success,
            palette = palette,
            textStyle = textStyle,
            modifier = Modifier.weight(1.05f)
        )
        SummarySeparator(palette = palette)
        SummaryMetricLine(
            label = "Трафик: ",
            value = trafficText,
            valueColor = palette.primary,
            palette = palette,
            textStyle = textStyle,
            modifier = Modifier.weight(1.35f)
        )
        SummarySeparator(palette = palette)
        SummaryMetricLine(
            label = "\u23f1 ",
            value = formatSummaryUptime(uptimeText),
            valueColor = palette.primary,
            palette = palette,
            textStyle = textStyle,
            modifier = Modifier.weight(0.8f)
        )
    }
}

@Composable
private fun SummaryMetricLine(
    label: String,
    value: String,
    valueColor: Color,
    palette: ConnectionProgressPalette,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    val lineText = remember(label, value, valueColor, palette.onSurfaceVariant) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = palette.onSurfaceVariant)) {
                append(label)
            }
            withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.SemiBold)) {
                append(value)
            }
        }
    }

    Text(
        text = lineText,
        style = textStyle,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun SummarySeparator(palette: ConnectionProgressPalette) {
    Text(
        text = "|",
        style = MaterialTheme.typography.labelMedium,
        color = palette.separator,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

private data class ConnectorVisual(
    val color: Color,
    val progress: Float
)

@Composable
private fun rememberConnectorVisual(
    status: StageStatus,
    nextStatus: StageStatus,
    palette: ConnectionProgressPalette
): ConnectorVisual {
    val targetColor = stageColor(
        when {
            status == StageStatus.ERROR || nextStatus == StageStatus.ERROR -> StageStatus.ERROR
            status == StageStatus.SUCCESS -> StageStatus.SUCCESS
            status == StageStatus.RUNNING -> StageStatus.RUNNING
            else -> StageStatus.WAITING
        },
        palette
    )
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(260),
        label = "connector_color_${status.name}_${nextStatus.name}"
    )
    val progress = when (status) {
        StageStatus.SUCCESS -> 1f
        StageStatus.RUNNING -> 0.72f
        StageStatus.ERROR -> 1f
        StageStatus.WAITING -> 0f
    }
    return remember(color, progress) {
        ConnectorVisual(
            color = color,
            progress = progress
        )
    }
}

private fun formatSummaryUptime(uptimeText: String?): String {
    if (uptimeText.isNullOrBlank()) return "00:00"
    val parts = uptimeText.split(':')
    return if (parts.size == 2) {
        "${parts[0].padStart(2, '0')}:${parts[1]}"
    } else {
        uptimeText
    }
}

private fun statusTextColor(
    lifecycle: ConnectionLifecycle,
    palette: ConnectionProgressPalette
): Color = when (lifecycle) {
    ConnectionLifecycle.CONNECTED -> stageColor(StageStatus.SUCCESS, palette)
    ConnectionLifecycle.ERROR -> stageColor(StageStatus.ERROR, palette)
    ConnectionLifecycle.DISCONNECTING -> palette.onSurfaceVariant
    ConnectionLifecycle.CONNECTING -> stageColor(StageStatus.RUNNING, palette)
    ConnectionLifecycle.IDLE -> palette.onSurfaceVariant
}

private fun stageColor(
    status: StageStatus,
    palette: ConnectionProgressPalette
): Color = when (status) {
    StageStatus.WAITING -> palette.outlineVariant
    StageStatus.RUNNING -> palette.primary
    StageStatus.SUCCESS -> palette.success
    StageStatus.ERROR -> palette.error
}

private fun stageFillColor(status: StageStatus, color: Color): Color = when (status) {
    StageStatus.WAITING -> Color.Transparent
    StageStatus.RUNNING -> color.copy(alpha = 0.94f)
    StageStatus.SUCCESS -> color
    StageStatus.ERROR -> color
}
