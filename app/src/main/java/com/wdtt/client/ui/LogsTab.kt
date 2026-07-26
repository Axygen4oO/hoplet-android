package com.wdtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.wdtt.client.ConnectionLifecycle
import com.wdtt.client.ConnectionProgressManager
import com.wdtt.client.ConnectionState
import com.wdtt.client.ConnectionStage
import com.wdtt.client.LogEntry
import com.wdtt.client.StageStatus
import com.wdtt.client.TunnelManager
import com.wdtt.client.WDTTColors
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun LogsTab() {
    val context = LocalContext.current
    val currentLogs by TunnelManager.logs.collectAsStateWithLifecycle()
    val statsText by TunnelManager.stats.collectAsStateWithLifecycle()
    val isRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val isConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val connectedSinceMs by TunnelManager.connectedSinceMs.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val connectionState by ConnectionProgressManager.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, connectedSinceMs) {
        if (!isRunning || connectedSinceMs <= 0L) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val uptimeText = if (isRunning && connectedSinceMs > 0L) {
        TunnelManager.formatUptime(nowMs - connectedSinceMs)
    } else {
        null
    }

    val visibleLogs = remember(currentLogs) {
        currentLogs.filterNot { it.key == "stats" }
    }
    val pinnedStatsMessage = remember(statsText, isRunning, isConnecting, currentLogs) {
        val fromLog = currentLogs.firstOrNull { it.key == "stats" }?.message
        when {
            !fromLog.isNullOrBlank() -> fromLog
            (isRunning || isConnecting) && statsText.isNotBlank() -> "[СТАТИСТИКА] $statsText"
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LogsHeader(
            pinnedStatsMessage = pinnedStatsMessage,
            uptimeText = uptimeText,
            isRunning = isRunning,
            isConnecting = isConnecting,
            activeWorkers = activeWorkers,
            connectionState = connectionState,
            logCount = visibleLogs.size,
            onClear = { TunnelManager.clearLogs() },
            onCopy = { copyLogsToClipboard(context, pinnedStatsMessage, visibleLogs) }
        )

        LogsOverviewCard(
            pinnedStatsMessage = pinnedStatsMessage,
            uptimeText = uptimeText,
            isRunning = isRunning,
            isConnecting = isConnecting,
            activeWorkers = activeWorkers,
            connectionState = connectionState,
            logCount = visibleLogs.size
        )

        LogsListCard(
            modifier = Modifier.weight(1f),
            logs = visibleLogs,
            listState = listState
        )
    }
}

@Composable
private fun LogsHeader(
    pinnedStatsMessage: String?,
    uptimeText: String?,
    isRunning: Boolean,
    isConnecting: Boolean,
    activeWorkers: Int,
    connectionState: ConnectionState,
    logCount: Int,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Логи",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = connectionSummaryText(
                    pinnedStatsMessage = pinnedStatsMessage,
                    uptimeText = uptimeText,
                    isRunning = isRunning,
                    isConnecting = isConnecting,
                    activeWorkers = activeWorkers,
                    connectionState = connectionState,
                    logCount = logCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        CompactActionButton(onClick = onCopy, icon = Icons.Default.ContentCopy, contentDescription = "Копировать")
        CompactActionButton(onClick = onClear, icon = Icons.Default.Delete, contentDescription = "Очистить")
    }
}

@Composable
private fun LogsOverviewCard(
    pinnedStatsMessage: String?,
    uptimeText: String?,
    isRunning: Boolean,
    isConnecting: Boolean,
    activeWorkers: Int,
    connectionState: ConnectionState,
    logCount: Int
) {
    AppSectionCard(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Журнал подключения",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = connectionSummaryText(
                        pinnedStatsMessage = pinnedStatsMessage,
                        uptimeText = uptimeText,
                        isRunning = isRunning,
                        isConnecting = isConnecting,
                        activeWorkers = activeWorkers,
                        connectionState = connectionState,
                        logCount = logCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!pinnedStatsMessage.isNullOrBlank()) {
            Surface(
                color = AppCardDefaults.containerColor(),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = pinnedStatsMessage
                        .removePrefix("[СТАТИСТИКА] ")
                        .removePrefix("[СТАТИСТИКА]"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                label = lifecycleLabel(connectionState.lifecycle),
                accent = lifecycleAccent(connectionState.lifecycle),
                modifier = Modifier.weight(1f)
            )
            MetricChip(
                label = if (uptimeText != null) "СЕССИЯ ${uptimeText}" else "СЕССИЯ --:--",
                accent = WDTTColors.terminalBlue,
                modifier = Modifier.weight(1f)
            )
            MetricChip(
                label = "ЛОГИ $logCount",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }

        StageStrip(connectionState)
    }
}

@Composable
private fun LogsListCard(
    modifier: Modifier = Modifier,
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    AppSectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "События",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${logs.size}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        if (logs.isEmpty()) {
            LogsEmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(logs, key = { _, entry -> entry.key }) { index, entry ->
                    LogRow(entry = entry)
                    if (index < logs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.wrapContentHeight()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "Логи пусты",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "События туннеля появятся здесь",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colors = MaterialTheme.colorScheme
    val tone = remember(entry.key, entry.message, entry.priority, entry.isError) {
        logTone(entry)
    }

    var pulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.count) {
        pulse = 1
        delay(140)
        pulse = 0
    }

    val countScale by animateFloatAsState(
        targetValue = if (pulse > 0) 1.14f else 1f,
        animationSpec = spring(),
        label = "logCountScale",
    )

    val sourceLabel = remember(entry.key, entry.message) { logSourceLabel(entry) }
    val showDnsSettingsAction = entry.key == "go_dns_tip" ||
        entry.key == "err_vk_dns" ||
        entry.key == "go_dns_precheck_fail"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = tone.accent.copy(alpha = 0.16f),
                contentColor = tone.accent,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, tone.accent.copy(alpha = 0.28f)),
                modifier = Modifier
                    .defaultMinSize(minWidth = 30.dp, minHeight = 30.dp)
                    .graphicsLayer(scaleX = countScale, scaleY = countScale)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${entry.count}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompactTag(
                        label = tone.label,
                        accent = tone.accent
                    )
                    CompactTag(
                        label = sourceLabel,
                        accent = colors.onSurfaceVariant,
                        containerAlpha = 0.08f,
                        borderAlpha = 0.16f
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = entry.message,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (entry.isError) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = 16.sp
                    )
                )

                if (showDnsSettingsAction) {
                    TextButton(
                        onClick = { TunnelManager.requestOpenAppSettings() },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "Открыть ⚙️ → Сеть",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = Modifier
            .size(42.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        contentColor = accent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    MetricChip(
        label = label,
        accent = accent,
        modifier = modifier
    )
}

@Composable
private fun CompactTag(
    label: String,
    accent: Color,
    containerAlpha: Float = 0.10f,
    borderAlpha: Float = 0.22f
) {
    Surface(
        color = accent.copy(alpha = containerAlpha),
        contentColor = accent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = borderAlpha))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StageStrip(connectionState: ConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConnectionStage.values().forEach { stage ->
            val status = connectionState.stageStatuses[stage] ?: StageStatus.WAITING
            val accent = when (status) {
                StageStatus.RUNNING -> WDTTColors.terminalBlue
                StageStatus.SUCCESS -> WDTTColors.terminalGreen
                StageStatus.ERROR -> WDTTColors.terminalRed
                StageStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                color = accent.copy(alpha = if (status == StageStatus.WAITING) 0.08f else 0.12f),
                contentColor = accent,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(
                    1.dp,
                    accent.copy(alpha = if (status == StageStatus.WAITING) 0.14f else 0.22f)
                )
            ) {
                Text(
                    text = stage.displayName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

private data class LogTone(
    val label: String,
    val accent: Color
)

private fun logTone(entry: LogEntry): LogTone {
    val message = entry.message.lowercase(Locale.getDefault())
    return when {
        entry.isError || message.contains("ошибка") || message.contains("error") || message.contains("fail") ->
            LogTone("ОШИБКА", WDTTColors.terminalRed)
        entry.priority <= 1 -> LogTone("ДИАГНОСТИКА", WDTTColors.terminalBlue)
        entry.priority == 2 -> LogTone("ИНФО", WDTTColors.terminalGreen)
        entry.priority == 3 -> LogTone("СТАТИСТИКА", WDTTColors.terminalBlue)
        entry.priority in 4..20 -> LogTone("ПРЕДУПРЕЖДЕНИЕ", WDTTColors.terminalYellow)
        else -> LogTone("ИНФО", WDTTColors.terminalBlue)
    }
}

private fun logSourceLabel(entry: LogEntry): String {
    Regex("^\\[(.+?)]").find(entry.message)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
        return it
    }

    val key = entry.key.lowercase(Locale.getDefault())
    return when {
        key.startsWith("recovery_") -> "RECOVERY"
        "deploy" in key -> "DEPLOY"
        "dns" in key -> "DNS"
        "vk" in key -> "VK"
        "stats" == key -> "STATS"
        "wrap" in key -> "WRAP"
        "turn" in key -> "TURN"
        "dtls" in key -> "DTLS"
        else -> "SYSTEM"
    }
}

private fun lifecycleLabel(lifecycle: ConnectionLifecycle): String = when (lifecycle) {
    ConnectionLifecycle.IDLE -> "ОЖИДАНИЕ"
    ConnectionLifecycle.CONNECTING -> "ПОДКЛЮЧЕНИЕ"
    ConnectionLifecycle.CONNECTED -> "ПОДКЛЮЧЕНО"
    ConnectionLifecycle.ERROR -> "ОШИБКА"
    ConnectionLifecycle.DISCONNECTING -> "ОТКЛЮЧЕНО"
}

private fun lifecycleAccent(lifecycle: ConnectionLifecycle): Color = when (lifecycle) {
    ConnectionLifecycle.IDLE -> WDTTColors.terminalText
    ConnectionLifecycle.CONNECTING -> WDTTColors.terminalBlue
    ConnectionLifecycle.CONNECTED -> WDTTColors.terminalGreen
    ConnectionLifecycle.ERROR -> WDTTColors.terminalRed
    ConnectionLifecycle.DISCONNECTING -> WDTTColors.terminalYellow
}

private fun connectionSummaryText(
    pinnedStatsMessage: String?,
    uptimeText: String?,
    isRunning: Boolean,
    isConnecting: Boolean,
    activeWorkers: Int,
    connectionState: ConnectionState,
    logCount: Int
): String {
    val stage = connectionState.currentStage?.displayName ?: "ожидание"
    val base = when {
        isRunning -> "Подключено"
        isConnecting -> "Подключение"
        else -> "Готово"
    }
    val statsPart = pinnedStatsMessage?.let {
        it.removePrefix("[СТАТИСТИКА] ").removePrefix("[СТАТИСТИКА]").trim()
    }?.takeIf { it.isNotBlank() }
    return buildString {
        append(base)
        append(" · ")
        append(stage)
        if (!uptimeText.isNullOrBlank()) {
            append(" · ")
            append("UP ")
            append(uptimeText)
        }
        append(" · ")
        append("workers ")
        append(activeWorkers)
        append(" · ")
        append("Логи ")
        append(logCount)
        if (!statsPart.isNullOrBlank()) {
            append(" · ")
            append(statsPart)
        }
    }
}

private fun copyLogsToClipboard(
    context: Context,
    pinnedStatsMessage: String?,
    visibleLogs: List<LogEntry>
) {
    val text = buildString {
        if (pinnedStatsMessage != null) {
            appendLine(pinnedStatsMessage)
        }
        visibleLogs.forEach { appendLine("${it.message} (x${it.count})") }
    }.trim()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("WDTT Logs", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}
