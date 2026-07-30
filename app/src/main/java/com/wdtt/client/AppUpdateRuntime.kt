package com.wdtt.client

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

internal const val APP_UPDATE_NOTIFICATION_CHANNEL_ID = "wdtt_app_update_v1"
internal const val APP_UPDATE_NOTIFICATION_ID = 4107

internal const val ACTION_START_APP_UPDATE = "com.wdtt.client.action.START_APP_UPDATE"
internal const val ACTION_RESUME_APP_UPDATE = "com.wdtt.client.action.RESUME_APP_UPDATE"
internal const val ACTION_PAUSE_APP_UPDATE = "com.wdtt.client.action.PAUSE_APP_UPDATE"
internal const val ACTION_CANCEL_APP_UPDATE = "com.wdtt.client.action.CANCEL_APP_UPDATE"
internal const val ACTION_RETRY_APP_UPDATE = "com.wdtt.client.action.RETRY_APP_UPDATE"
internal const val ACTION_INSTALL_APP_UPDATE = "com.wdtt.client.action.INSTALL_APP_UPDATE"
internal const val ACTION_CLEAR_APP_UPDATE = "com.wdtt.client.action.CLEAR_APP_UPDATE"
internal const val ACTION_RESTORE_APP_UPDATE = "com.wdtt.client.action.RESTORE_APP_UPDATE"

private const val UPDATE_SNAPSHOT_LOG_TAG = "qWDTT"
private const val EXTRA_VERSION_TAG = "extra_version_tag"
private const val EXTRA_RELEASE_URL = "extra_release_url"
private const val EXTRA_DOWNLOAD_URL = "extra_download_url"
private const val EXTRA_RELEASE_NOTES = "extra_release_notes"
private const val EXTRA_IS_PRERELEASE = "extra_is_prerelease"
private const val EXTRA_DOWNLOAD_FILE_NAME = "extra_download_file_name"
private const val EXTRA_DOWNLOAD_SIZE_BYTES = "extra_download_size_bytes"
private const val EXTRA_EXPECTED_SHA256 = "extra_expected_sha256"
private const val EXTRA_SHA256_ASSET_URL = "extra_sha256_asset_url"

enum class AppUpdatePhase {
    IDLE,
    DOWNLOADING,
    WAITING_FOR_NETWORK,
    PAUSED,
    VERIFYING,
    READY_TO_INSTALL,
    ERROR,
    CANCELLED,
}

data class AppUpdateDownloadSnapshot(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val versionTag: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val isPrerelease: Boolean = false,
    val downloadFileName: String = "",
    val downloadSizeBytes: Long = 0L,
    val expectedSha256: String = "",
    val sha256AssetUrl: String = "",
    val filePath: String = "",
    val tempFilePath: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val estimatedRemainingMs: Long = -1L,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastError: String = "",
    val lastVerifiedSha256: String = "",
    val statusMessage: String = "",
    val rangeSupported: Boolean = false,
    val autoResumeOnNetwork: Boolean = false,
) {
    val progressFraction: Float
        get() = when {
            totalBytes <= 0L -> 0f
            downloadedBytes <= 0L -> 0f
            else -> (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).roundToInt().coerceIn(0, 100)

    val isDownloading: Boolean
        get() = phase == AppUpdatePhase.DOWNLOADING

    val isActive: Boolean
        get() = phase == AppUpdatePhase.DOWNLOADING ||
            phase == AppUpdatePhase.WAITING_FOR_NETWORK ||
            phase == AppUpdatePhase.VERIFYING

    val canPause: Boolean
        get() = phase == AppUpdatePhase.DOWNLOADING || phase == AppUpdatePhase.WAITING_FOR_NETWORK

    val canResume: Boolean
        get() = phase == AppUpdatePhase.PAUSED || phase == AppUpdatePhase.ERROR || phase == AppUpdatePhase.CANCELLED

    val canInstall: Boolean
        get() = phase == AppUpdatePhase.READY_TO_INSTALL

    val canClear: Boolean
        get() = phase == AppUpdatePhase.READY_TO_INSTALL ||
            phase == AppUpdatePhase.ERROR ||
            phase == AppUpdatePhase.CANCELLED ||
            phase == AppUpdatePhase.PAUSED

    fun matchesVersion(otherVersionTag: String): Boolean {
        if (versionTag.isBlank() || otherVersionTag.isBlank()) return false
        return normalizeVersionTag(versionTag) == normalizeVersionTag(otherVersionTag)
    }

    fun toReleaseInfo(): AppReleaseInfo? {
        if (versionTag.isBlank() || releaseUrl.isBlank()) return null
        return AppReleaseInfo(
            versionTag = versionTag,
            releaseUrl = releaseUrl,
            source = RemoteVersionSource.Release,
            downloadUrl = downloadUrl.ifBlank { null },
            releaseNotes = releaseNotes,
            isPrerelease = isPrerelease,
            downloadFileName = downloadFileName.ifBlank { null },
            downloadSizeBytes = downloadSizeBytes,
            expectedSha256 = expectedSha256.ifBlank { null },
            sha256AssetUrl = sha256AssetUrl.ifBlank { null },
        )
    }
}

fun startAppUpdateDownload(context: Context, release: AppReleaseInfo) {
    startAppUpdateService(context, ACTION_START_APP_UPDATE, release)
}

fun resumeAppUpdateDownload(context: Context) {
    startAppUpdateService(context, ACTION_RESUME_APP_UPDATE)
}

fun pauseAppUpdateDownload(context: Context) {
    context.startService(appUpdateServiceIntent(context, ACTION_PAUSE_APP_UPDATE))
}

fun cancelAppUpdateDownload(context: Context) {
    context.startService(appUpdateServiceIntent(context, ACTION_CANCEL_APP_UPDATE))
}

fun retryAppUpdateDownload(context: Context) {
    startAppUpdateService(context, ACTION_RETRY_APP_UPDATE)
}

fun requestInstallDownloadedUpdate(context: Context) {
    startAppUpdateService(context, ACTION_INSTALL_APP_UPDATE)
}

fun clearAppUpdateDownload(context: Context) {
    context.startService(appUpdateServiceIntent(context, ACTION_CLEAR_APP_UPDATE))
}

internal fun appUpdateServiceIntent(
    context: Context,
    action: String,
    release: AppReleaseInfo? = null,
): Intent = Intent(context, AppUpdateService::class.java).apply {
    this.action = action
    release?.let(::putAppReleaseInfo)
}

private fun startAppUpdateService(context: Context, action: String, release: AppReleaseInfo? = null) {
    val intent = appUpdateServiceIntent(context, action, release)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

internal fun Intent.putAppReleaseInfo(release: AppReleaseInfo): Intent = apply {
    putExtra(EXTRA_VERSION_TAG, release.versionTag)
    putExtra(EXTRA_RELEASE_URL, release.releaseUrl)
    putExtra(EXTRA_DOWNLOAD_URL, release.downloadUrl)
    putExtra(EXTRA_RELEASE_NOTES, release.releaseNotes)
    putExtra(EXTRA_IS_PRERELEASE, release.isPrerelease)
    putExtra(EXTRA_DOWNLOAD_FILE_NAME, release.downloadFileName)
    putExtra(EXTRA_DOWNLOAD_SIZE_BYTES, release.downloadSizeBytes)
    putExtra(EXTRA_EXPECTED_SHA256, release.expectedSha256)
    putExtra(EXTRA_SHA256_ASSET_URL, release.sha256AssetUrl)
}

internal fun Intent.readAppReleaseInfo(): AppReleaseInfo? {
    val versionTag = getStringExtra(EXTRA_VERSION_TAG).orEmpty().trim()
    val releaseUrl = getStringExtra(EXTRA_RELEASE_URL).orEmpty().trim()
    if (versionTag.isBlank() || releaseUrl.isBlank()) return null
    return AppReleaseInfo(
        versionTag = normalizeVersionTag(versionTag),
        releaseUrl = releaseUrl,
        source = RemoteVersionSource.Release,
        downloadUrl = getStringExtra(EXTRA_DOWNLOAD_URL)?.trim()?.ifBlank { null },
        releaseNotes = getStringExtra(EXTRA_RELEASE_NOTES).orEmpty(),
        isPrerelease = getBooleanExtra(EXTRA_IS_PRERELEASE, false),
        downloadFileName = getStringExtra(EXTRA_DOWNLOAD_FILE_NAME)?.trim()?.ifBlank { null },
        downloadSizeBytes = getLongExtra(EXTRA_DOWNLOAD_SIZE_BYTES, 0L),
        expectedSha256 = getStringExtra(EXTRA_EXPECTED_SHA256)?.trim()?.ifBlank { null },
        sha256AssetUrl = getStringExtra(EXTRA_SHA256_ASSET_URL)?.trim()?.ifBlank { null },
    )
}

internal fun encodeAppUpdateSnapshot(snapshot: AppUpdateDownloadSnapshot): String = JSONObject().apply {
    put("phase", snapshot.phase.name)
    put("versionTag", snapshot.versionTag)
    put("releaseUrl", snapshot.releaseUrl)
    put("downloadUrl", snapshot.downloadUrl)
    put("releaseNotes", snapshot.releaseNotes)
    put("isPrerelease", snapshot.isPrerelease)
    put("downloadFileName", snapshot.downloadFileName)
    put("downloadSizeBytes", snapshot.downloadSizeBytes)
    put("expectedSha256", snapshot.expectedSha256)
    put("sha256AssetUrl", snapshot.sha256AssetUrl)
    put("filePath", snapshot.filePath)
    put("tempFilePath", snapshot.tempFilePath)
    put("downloadedBytes", snapshot.downloadedBytes)
    put("totalBytes", snapshot.totalBytes)
    put("speedBytesPerSecond", snapshot.speedBytesPerSecond)
    put("estimatedRemainingMs", snapshot.estimatedRemainingMs)
    put("startedAt", snapshot.startedAt)
    put("updatedAt", snapshot.updatedAt)
    put("lastError", snapshot.lastError)
    put("lastVerifiedSha256", snapshot.lastVerifiedSha256)
    put("statusMessage", snapshot.statusMessage)
    put("rangeSupported", snapshot.rangeSupported)
    put("autoResumeOnNetwork", snapshot.autoResumeOnNetwork)
}.toString()

internal fun decodeAppUpdateSnapshot(raw: String?): AppUpdateDownloadSnapshot {
    if (raw.isNullOrBlank()) return AppUpdateDownloadSnapshot()
    return try {
        val json = JSONObject(raw)
        AppUpdateDownloadSnapshot(
            phase = json.optString("phase")
                .takeIf { it.isNotBlank() }
                ?.let { name -> AppUpdatePhase.entries.firstOrNull { it.name == name } }
                ?: AppUpdatePhase.IDLE,
            versionTag = json.optString("versionTag"),
            releaseUrl = json.optString("releaseUrl"),
            downloadUrl = json.optString("downloadUrl"),
            releaseNotes = json.optString("releaseNotes"),
            isPrerelease = json.optBoolean("isPrerelease"),
            downloadFileName = json.optString("downloadFileName"),
            downloadSizeBytes = json.optLong("downloadSizeBytes"),
            expectedSha256 = json.optString("expectedSha256"),
            sha256AssetUrl = json.optString("sha256AssetUrl"),
            filePath = json.optString("filePath"),
            tempFilePath = json.optString("tempFilePath"),
            downloadedBytes = json.optLong("downloadedBytes"),
            totalBytes = json.optLong("totalBytes"),
            speedBytesPerSecond = json.optLong("speedBytesPerSecond"),
            estimatedRemainingMs = json.optLong("estimatedRemainingMs", -1L),
            startedAt = json.optLong("startedAt"),
            updatedAt = json.optLong("updatedAt"),
            lastError = json.optString("lastError"),
            lastVerifiedSha256 = json.optString("lastVerifiedSha256"),
            statusMessage = json.optString("statusMessage"),
            rangeSupported = json.optBoolean("rangeSupported"),
            autoResumeOnNetwork = json.optBoolean("autoResumeOnNetwork"),
        )
    } catch (error: Exception) {
        runCatching {
            Log.w(UPDATE_SNAPSHOT_LOG_TAG, "Failed to decode app update snapshot", error)
        }
        AppUpdateDownloadSnapshot()
    }
}

fun formatAppUpdateStatus(snapshot: AppUpdateDownloadSnapshot): String = when (snapshot.phase) {
    AppUpdatePhase.IDLE -> "Загрузка обновления не запущена"
    AppUpdatePhase.DOWNLOADING -> buildString {
        append("Скачивание ${snapshot.versionTag.ifBlank { "обновления" }}")
        if (snapshot.totalBytes > 0L) append(" • ${snapshot.progressPercent}%")
    }
    AppUpdatePhase.WAITING_FOR_NETWORK -> snapshot.statusMessage.ifBlank { "Ожидание сети для продолжения загрузки" }
    AppUpdatePhase.PAUSED -> snapshot.statusMessage.ifBlank { "Загрузка приостановлена" }
    AppUpdatePhase.VERIFYING -> "Проверяем целостность APK"
    AppUpdatePhase.READY_TO_INSTALL -> "APK готов к установке"
    AppUpdatePhase.ERROR -> snapshot.lastError.ifBlank { "Ошибка обновления" }
    AppUpdatePhase.CANCELLED -> snapshot.statusMessage.ifBlank { "Загрузка обновления отменена" }
}

fun formatAppUpdateDetails(snapshot: AppUpdateDownloadSnapshot): String {
    val parts = mutableListOf<String>()
    if (snapshot.downloadedBytes > 0L || snapshot.totalBytes > 0L) {
        val total = if (snapshot.totalBytes > 0L) formatBytes(snapshot.totalBytes) else "?"
        parts += "${formatBytes(snapshot.downloadedBytes)} / $total"
    }
    if (snapshot.speedBytesPerSecond > 0L) {
        parts += "${formatBytes(snapshot.speedBytesPerSecond)}/с"
    }
    if (snapshot.estimatedRemainingMs > 0L) {
        parts += "осталось ${formatDuration(snapshot.estimatedRemainingMs)}"
    }
    if (snapshot.statusMessage.isNotBlank() && snapshot.phase != AppUpdatePhase.WAITING_FOR_NETWORK) {
        parts += snapshot.statusMessage
    }
    return parts.joinToString(" • ")
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val precision = if (value >= 100 || unitIndex == 0) 0 else 1
    return "%.${precision}f %s".format(value, units[unitIndex])
}

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "меньше минуты"
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes >= 60L -> {
            val hours = minutes / 60L
            val remMinutes = minutes % 60L
            if (remMinutes == 0L) "${hours} ч" else "${hours} ч ${remMinutes} мин"
        }
        minutes > 0L -> if (seconds == 0L) "${minutes} мин" else "${minutes} мин ${seconds} с"
        else -> "${seconds.coerceAtLeast(1L)} с"
    }
}

internal fun appUpdateDirectory(context: Context): File = File(context.filesDir, "updates")

internal fun appUpdateApkFile(context: Context, versionTag: String): File {
    val safeTag = safeUpdateVersionTag(versionTag)
    return File(appUpdateDirectory(context), "Hoplet_$safeTag.apk")
}

internal fun appUpdatePartFile(context: Context, versionTag: String): File {
    val safeTag = safeUpdateVersionTag(versionTag)
    return File(appUpdateDirectory(context), "Hoplet_$safeTag.apk.part")
}

internal fun safeUpdateVersionTag(versionTag: String): String = normalizeVersionTag(versionTag)
    .removePrefix("v")
    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    .ifBlank { "update" }

internal fun deleteFileIfExists(path: String) {
    if (path.isBlank()) return
    runCatching { File(path).takeIf(File::exists)?.delete() }
}

internal suspend fun reconcileAppUpdateState(context: Context) {
    val settingsStore = SettingsStore(context)
    val snapshot = settingsStore.updateDownloadState.first()
    if (snapshot.phase == AppUpdatePhase.IDLE) return

    val currentVersion = "v${BuildConfig.VERSION_NAME.removePrefix("v")}"
    val updateStillNeeded = isNewerVersion(currentVersion, snapshot.versionTag)
    if (updateStillNeeded) return

    deleteFileIfExists(snapshot.filePath)
    deleteFileIfExists(snapshot.tempFilePath)
    settingsStore.clearUpdateDownloadState()
    Log.i(
        UPDATE_SNAPSHOT_LOG_TAG,
        "Cleared stale app update state for ${snapshot.versionTag}; current=$currentVersion"
    )
}
