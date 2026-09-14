package com.wdtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.AppReleaseInfo
import com.wdtt.client.AppUpdatePhase
import com.wdtt.client.BuildConfig
import com.wdtt.client.isInstallableOtaRelease
import com.wdtt.client.SettingsStore
import com.wdtt.client.cancelAppUpdateDownload
import com.wdtt.client.formatAppUpdateDetails
import com.wdtt.client.formatAppUpdateStatus
import com.wdtt.client.pauseAppUpdateDownload
import com.wdtt.client.requestInstallDownloadedUpdate
import com.wdtt.client.resumeAppUpdateDownload
import com.wdtt.client.retryAppUpdateDownload
import com.wdtt.client.startAppUpdateDownload
import com.wdtt.client.sanitizedReleaseNotes

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    isLatestReleaseInfo: Boolean = false,
    onDismiss: () -> Unit,
    onPostpone: () -> Unit,
    onDownloadStarted: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val isTagOnly = !isInstallableOtaRelease(release)
    val title = when {
        isLatestReleaseInfo -> "Последнее обновление"
        isTagOnly -> "Доступна новая версия"
        else -> "Доступно обновление Hoplet"
    }
    val context = LocalContext.current
    val settingsStore = remember(context) { SettingsStore(context) }
    val updateSnapshot by settingsStore.updateDownloadState.collectAsStateWithLifecycle(
        initialValue = com.wdtt.client.AppUpdateDownloadSnapshot()
    )
    val activeSnapshot = updateSnapshot.takeIf { it.matchesVersion(release.versionTag) }
    val displayVersion = release.versionName?.ifBlank { null } ?: release.versionTag
    val installedVersion = BuildConfig.VERSION_NAME.removePrefix("v")
    var autoInstallRequested by rememberSaveable(release.versionTag) { mutableStateOf(false) }

    LaunchedEffect(activeSnapshot?.phase, activeSnapshot?.filePath, autoInstallRequested) {
        if (autoInstallRequested && activeSnapshot?.phase == AppUpdatePhase.READY_TO_INSTALL) {
            autoInstallRequested = false
            requestInstallDownloadedUpdate(context)
        }
    }

    val description = if (isLatestReleaseInfo) {
        buildString {
            append("У вас последняя версия")
            release.publishedAt?.takeIf { it.isNotBlank() }?.let { append("\nДата выпуска: $it") }
        }
    } else if (isTagOnly) {
        "Обнаружена новая версия Hoplet $displayVersion. Обновление станет доступно сразу после публикации релиза."
    } else {
        "Сейчас установлено $installedVersion.\nДоступна новая версия Hoplet $displayVersion."
    }

    val secondaryLabel = when (activeSnapshot?.phase) {
        AppUpdatePhase.DOWNLOADING,
        AppUpdatePhase.WAITING_FOR_NETWORK -> "Отмена"
        AppUpdatePhase.PAUSED,
        AppUpdatePhase.ERROR,
        AppUpdatePhase.READY_TO_INSTALL,
        AppUpdatePhase.CANCELLED,
        AppUpdatePhase.VERIFYING -> "Скрыть"
        else -> if (isLatestReleaseInfo) "Подробнее" else if (isTagOnly) "Закрыть" else if (release.mandatory) "Закрыть" else "Позже"
    }

    val primaryLabel = when {
        activeSnapshot?.phase == AppUpdatePhase.READY_TO_INSTALL -> "Установить"
        activeSnapshot?.phase == AppUpdatePhase.DOWNLOADING ||
            activeSnapshot?.phase == AppUpdatePhase.WAITING_FOR_NETWORK -> "Пауза"
        activeSnapshot?.phase == AppUpdatePhase.PAUSED -> "Продолжить"
        activeSnapshot?.phase == AppUpdatePhase.CANCELLED -> "Скачать заново"
        activeSnapshot?.phase == AppUpdatePhase.ERROR -> "Повторить"
        activeSnapshot?.phase == AppUpdatePhase.VERIFYING -> "Проверяем"
        isLatestReleaseInfo -> "Закрыть"
        isTagOnly -> "Закрыть"
        else -> "Скачать и установить"
    }

    val primaryEnabled = activeSnapshot?.phase != AppUpdatePhase.VERIFYING

    val statusColor = when (activeSnapshot?.phase) {
        AppUpdatePhase.ERROR -> MaterialTheme.colorScheme.error
        AppUpdatePhase.READY_TO_INSTALL -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    HopletDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        HopletModalSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = displayVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                val visibleReleaseNotes = sanitizedReleaseNotes(release.releaseNotes)
                if (visibleReleaseNotes.isNotBlank() || isLatestReleaseInfo) {
                    Text(
                        text = "Что нового",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    AppSectionCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        color = HopletModalDefaults.softContainerColor(),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = visibleReleaseNotes.ifBlank { "Описание обновления отсутствует." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                if (!isLatestReleaseInfo && release.downloadSizeBytes > 0L) {
                    Text(
                        text = "Размер обновления: ${com.wdtt.client.formatBytes(release.downloadSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (release.isPrerelease) {
                    Text(
                        text = "Предрелиз (beta)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                activeSnapshot?.takeIf { it.phase != AppUpdatePhase.IDLE }?.let { snapshot ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (snapshot.phase == AppUpdatePhase.DOWNLOADING && snapshot.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { snapshot.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else if (snapshot.phase == AppUpdatePhase.WAITING_FOR_NETWORK ||
                            snapshot.phase == AppUpdatePhase.VERIFYING
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }

                        Text(
                            text = formatAppUpdateStatus(snapshot),
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Medium
                        )

                        val details = formatAppUpdateDetails(snapshot)
                        if (details.isNotBlank()) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (snapshot.lastVerifiedSha256.isNotBlank() &&
                            snapshot.phase == AppUpdatePhase.READY_TO_INSTALL
                        ) {
                            Text(
                                text = "SHA-256: ${snapshot.lastVerifiedSha256}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!release.mandatory || isLatestReleaseInfo) HopletSecondaryButton(
                        onClick = {
                            when (activeSnapshot?.phase) {
                                AppUpdatePhase.DOWNLOADING,
                                AppUpdatePhase.WAITING_FOR_NETWORK -> {
                                    cancelAppUpdateDownload(context)
                                    onDismiss()
                                }

                                AppUpdatePhase.PAUSED,
                                AppUpdatePhase.ERROR,
                                AppUpdatePhase.READY_TO_INSTALL,
                                AppUpdatePhase.CANCELLED,
                                AppUpdatePhase.VERIFYING -> onDismiss()
                                else -> if (isLatestReleaseInfo) onOpenReleasePage() else onPostpone()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(secondaryLabel, fontWeight = FontWeight.SemiBold)
                    }

                    HopletPrimaryButton(
                        onClick = {
                            when (activeSnapshot?.phase) {
                                AppUpdatePhase.DOWNLOADING,
                                AppUpdatePhase.WAITING_FOR_NETWORK -> pauseAppUpdateDownload(context)
                                AppUpdatePhase.PAUSED -> {
                                    autoInstallRequested = true
                                    resumeAppUpdateDownload(context)
                                }

                                AppUpdatePhase.CANCELLED -> {
                                    autoInstallRequested = true
                                    onDownloadStarted()
                                    startAppUpdateDownload(context, release)
                                }

                                AppUpdatePhase.ERROR -> {
                                    autoInstallRequested = true
                                    retryAppUpdateDownload(context)
                                }

                                AppUpdatePhase.READY_TO_INSTALL -> requestInstallDownloadedUpdate(context)
                                AppUpdatePhase.VERIFYING -> Unit
                                else -> {
                                    if (isLatestReleaseInfo) {
                                        onDismiss()
                                    } else if (isTagOnly) {
                                        onDismiss()
                                    } else {
                                        autoInstallRequested = true
                                        onDownloadStarted()
                                        startAppUpdateDownload(context, release)
                                    }
                                }
                            }
                        },
                        enabled = primaryEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(primaryLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
