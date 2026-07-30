package com.wdtt.client

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class AppUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private lateinit var settingsStore: SettingsStore
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var controlRequest: ControlRequest = ControlRequest.NONE

    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundActive = false
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                maybeResumeWaitingDownload()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        NotificationHelper.ensureAuxChannel(
            this,
            APP_UPDATE_NOTIFICATION_CHANNEL_ID,
            "Обновления Hoplet",
            "Загрузка и установка обновлений приложения"
        )
        registerNetworkCallbackIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTORE_APP_UPDATE
        scope.launch {
            commandMutex.withLock {
                when (action) {
                    ACTION_START_APP_UPDATE -> {
                        val release = intent?.readAppReleaseInfo()
                        if (release == null || release.downloadUrl.isNullOrBlank()) {
                            Log.w(LOG_TAG, "Ignored start request without downloadable release")
                            stopSelfResult(startId)
                            return@withLock
                        }
                        startOrResumeDownload(release, "start")
                    }

                    ACTION_RESUME_APP_UPDATE -> {
                        val release = intent?.readAppReleaseInfo()
                            ?: settingsStore.updateDownloadState.first().toReleaseInfo()
                        if (release == null || release.downloadUrl.isNullOrBlank()) {
                            Log.w(LOG_TAG, "Ignored resume request without stored release")
                            stopSelfResult(startId)
                            return@withLock
                        }
                        startOrResumeDownload(release, "resume")
                    }

                    ACTION_RETRY_APP_UPDATE -> {
                        val release = intent?.readAppReleaseInfo()
                            ?: settingsStore.updateDownloadState.first().toReleaseInfo()
                        if (release == null || release.downloadUrl.isNullOrBlank()) {
                            Log.w(LOG_TAG, "Ignored retry request without stored release")
                            stopSelfResult(startId)
                            return@withLock
                        }
                        startOrResumeDownload(release, "retry")
                    }

                    ACTION_PAUSE_APP_UPDATE -> pauseDownload()
                    ACTION_CANCEL_APP_UPDATE -> cancelDownload()
                    ACTION_INSTALL_APP_UPDATE -> installDownloadedApk()
                    ACTION_CLEAR_APP_UPDATE -> clearStoredUpdate()
                    ACTION_RESTORE_APP_UPDATE -> restoreIfNeeded()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activeCall?.cancel()
        downloadJob?.cancel()
        releaseWakeLock()
        unregisterNetworkCallbackIfNeeded()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startOrResumeDownload(release: AppReleaseInfo, reason: String) {
        val currentJob = downloadJob
        if (currentJob?.isActive == true) {
            val currentSnapshot = settingsStore.updateDownloadState.first()
            if (currentSnapshot.matchesVersion(release.versionTag)) {
                Log.i(LOG_TAG, "Update download already in progress for ${release.versionTag}")
                return
            }
            Log.w(LOG_TAG, "Ignoring request for ${release.versionTag} while ${currentSnapshot.versionTag} is active")
            return
        }

        controlRequest = ControlRequest.NONE
        cleanupForeignArtifacts(release.versionTag)
        val snapshot = buildSnapshot(release)

        if (snapshot.filePath.isNotBlank()) {
            val readyFile = File(snapshot.filePath)
            if (readyFile.exists()) {
                when (val verification = verifyDownloadedApk(readyFile, snapshot)) {
                    is VerificationResult.Failure -> {
                        readyFile.delete()
                        Log.w(LOG_TAG, "Deleting stale ready APK for ${snapshot.versionTag}: ${verification.message}")
                    }

                    is VerificationResult.Success -> {
                        markReady(snapshot, readyFile, verification.sha256, "Файл уже скачан, можно устанавливать")
                        return
                    }
                }
            }
        }

        Log.i(LOG_TAG, "Starting app update download for ${release.versionTag}, reason=$reason")
        downloadJob = scope.launch {
            runDownload(snapshot)
        }
    }

    private suspend fun pauseDownload() {
        val snapshot = settingsStore.updateDownloadState.first()
        if (snapshot.phase == AppUpdatePhase.WAITING_FOR_NETWORK && downloadJob?.isActive != true) {
            markPaused(snapshot, "Загрузка приостановлена")
            return
        }
        controlRequest = ControlRequest.PAUSE
        activeCall?.cancel()
        downloadJob?.cancel()
    }

    private suspend fun cancelDownload() {
        if (downloadJob?.isActive != true) {
            val snapshot = settingsStore.updateDownloadState.first()
            markCancelled(snapshot)
            return
        }
        controlRequest = ControlRequest.CANCEL
        activeCall?.cancel()
        downloadJob?.cancel()
    }

    private suspend fun installDownloadedApk() {
        val snapshot = settingsStore.updateDownloadState.first()
        val apkFile = snapshot.filePath.takeIf { it.isNotBlank() }?.let(::File)
        if (snapshot.phase != AppUpdatePhase.READY_TO_INSTALL || apkFile == null || !apkFile.exists()) {
            markError(snapshot, "Файл обновления не найден, скачайте APK заново")
            return
        }

        when (val verification = verifyDownloadedApk(apkFile, snapshot)) {
            is VerificationResult.Failure -> {
                apkFile.delete()
                markError(snapshot, verification.message)
                return
            }

            is VerificationResult.Success -> {
                when (val result = installApk(this, apkFile)) {
                    InstallApkResult.Started -> {
                        val updated = snapshot.copy(
                            lastVerifiedSha256 = verification.sha256,
                            lastError = "",
                            statusMessage = "Открыт системный установщик APK",
                            updatedAt = System.currentTimeMillis(),
                        )
                        settingsStore.saveUpdateDownloadState(updated)
                        showDetachedNotification(updated)
                        stopSelf()
                    }

                    InstallApkResult.PermissionRequired -> {
                        val updated = snapshot.copy(
                            lastVerifiedSha256 = verification.sha256,
                            lastError = "",
                            statusMessage = "Разрешите установку APK и повторите действие",
                            updatedAt = System.currentTimeMillis(),
                        )
                        settingsStore.saveUpdateDownloadState(updated)
                        showDetachedNotification(updated)
                        stopSelf()
                    }

                    is InstallApkResult.Failed -> {
                        markError(snapshot, result.message)
                    }
                }
            }
        }
    }

    private suspend fun clearStoredUpdate() {
        val snapshot = settingsStore.updateDownloadState.first()
        deleteFileIfExists(snapshot.filePath)
        deleteFileIfExists(snapshot.tempFilePath)
        settingsStore.clearUpdateDownloadState()
        removeNotification()
        stopSelf()
    }

    private suspend fun restoreIfNeeded() {
        val snapshot = settingsStore.updateDownloadState.first()
        when (snapshot.phase) {
            AppUpdatePhase.DOWNLOADING,
            AppUpdatePhase.WAITING_FOR_NETWORK,
            AppUpdatePhase.VERIFYING -> {
                val release = snapshot.toReleaseInfo()
                if (release == null || release.downloadUrl.isNullOrBlank()) {
                    clearStoredUpdate()
                } else if (snapshot.phase == AppUpdatePhase.WAITING_FOR_NETWORK && !hasUsableNetwork()) {
                    ensureForeground(snapshot)
                } else {
                    startOrResumeDownload(release, "restore")
                }
            }

            AppUpdatePhase.READY_TO_INSTALL,
            AppUpdatePhase.PAUSED,
            AppUpdatePhase.ERROR,
            AppUpdatePhase.CANCELLED -> {
                showDetachedNotification(snapshot)
                stopSelf()
            }

            AppUpdatePhase.IDLE -> {
                removeNotification()
                stopSelf()
            }
        }
    }

    private suspend fun maybeResumeWaitingDownload() {
        if (downloadJob?.isActive == true) return
        val snapshot = settingsStore.updateDownloadState.first()
        if (snapshot.phase != AppUpdatePhase.WAITING_FOR_NETWORK || !snapshot.autoResumeOnNetwork) return
        if (!hasUsableNetwork()) return
        val release = snapshot.toReleaseInfo() ?: return
        Log.i(LOG_TAG, "Network restored, resuming app update ${snapshot.versionTag}")
        startOrResumeDownload(release, "network-restored")
    }

    private suspend fun runDownload(initialSnapshot: AppUpdateDownloadSnapshot) {
        acquireWakeLock()
        var snapshot = initialSnapshot.copy(
            phase = AppUpdatePhase.DOWNLOADING,
            lastError = "",
            autoResumeOnNetwork = false,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            statusMessage = if (initialSnapshot.downloadedBytes > 0L) {
                "Возобновляем загрузку"
            } else {
                "Подготавливаем обновление"
            },
            startedAt = initialSnapshot.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        try {
            settingsStore.saveUpdateDownloadState(snapshot)
            ensureForeground(snapshot)

            val expectedSha256 = resolveExpectedSha256(snapshot)
            if (expectedSha256.isNotBlank()) {
                snapshot = snapshot.copy(expectedSha256 = expectedSha256)
                settingsStore.saveUpdateDownloadState(snapshot)
            }

            val updatesDir = appUpdateDirectory(this)
            if (!updatesDir.exists() && !updatesDir.mkdirs()) {
                markError(snapshot, "Не удалось подготовить каталог для обновлений")
                return
            }

            val targetFile = File(snapshot.filePath)
            val partFile = File(snapshot.tempFilePath)

            if (targetFile.exists()) {
                when (val verification = verifyDownloadedApk(targetFile, snapshot)) {
                    is VerificationResult.Success -> {
                        markReady(snapshot, targetFile, verification.sha256, "Файл уже скачан, можно устанавливать")
                        return
                    }

                    is VerificationResult.Failure -> {
                        targetFile.delete()
                    }
                }
            }

            downloadToFile(snapshot, partFile, targetFile)
        } finally {
            activeCall = null
            releaseWakeLock()
        }
    }

    private suspend fun downloadToFile(
        initialSnapshot: AppUpdateDownloadSnapshot,
        partFile: File,
        targetFile: File,
    ) {
        var snapshot = initialSnapshot
        var restartWithoutResume = false

        while (true) {
            if (controlRequest == ControlRequest.CANCEL) {
                markCancelled(snapshot)
                return
            }

            if (controlRequest == ControlRequest.PAUSE) {
                markPaused(snapshot, "Загрузка приостановлена")
                return
            }

            val existingBytes = if (partFile.exists() && !restartWithoutResume) partFile.length() else 0L
            if (restartWithoutResume && partFile.exists()) {
                partFile.delete()
            }

            val request = Request.Builder()
                .url(snapshot.downloadUrl)
                .get()
                .header("User-Agent", "qWDTTAndroid/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
                .apply {
                    if (existingBytes > 0L) {
                        header("Range", "bytes=$existingBytes-")
                    }
                }
                .build()

            try {
                activeCall = httpClient.newCall(request)
                activeCall!!.execute().use { response ->
                    when {
                        response.code == 416 && existingBytes > 0L -> {
                            restartWithoutResume = true
                            continue
                        }

                        response.code !in 200..299 && response.code != 206 -> {
                            throw DownloadHttpException(response.code, response.peekBody(512 * 1024).string())
                        }

                        response.code == 200 && existingBytes > 0L -> {
                            restartWithoutResume = true
                            snapshot = snapshot.copy(
                                downloadedBytes = 0L,
                                speedBytesPerSecond = 0L,
                                estimatedRemainingMs = -1L,
                                statusMessage = "Сервер не поддержал докачку, начинаем заново",
                                updatedAt = System.currentTimeMillis(),
                            )
                            settingsStore.saveUpdateDownloadState(snapshot)
                            ensureForeground(snapshot)
                            continue
                        }
                    }

                    val append = response.code == 206 && existingBytes > 0L
                    val body = response.body ?: throw IOException("Пустой ответ сервера обновлений")
                    val totalBytes = resolveTotalBytes(response, existingBytes, snapshot.downloadSizeBytes)
                    if (!hasEnoughDiskSpace(totalBytes, existingBytes)) {
                        throw IOException("Недостаточно свободного места")
                    }

                    snapshot = snapshot.copy(
                        phase = AppUpdatePhase.DOWNLOADING,
                        downloadedBytes = if (append) existingBytes else 0L,
                        totalBytes = totalBytes,
                        rangeSupported = append || supportsByteRange(response),
                        statusMessage = if (append) "Продолжаем загрузку" else "",
                        updatedAt = System.currentTimeMillis(),
                    )
                    settingsStore.saveUpdateDownloadState(snapshot)
                    ensureForeground(snapshot)

                    writeResponseBodyToFile(body.byteStream(), partFile, append, snapshot) { updated ->
                        snapshot = updated
                    }
                }

                break
            } catch (cancelled: CancellationException) {
                val currentSnapshot = snapshot.copy(
                    downloadedBytes = partFile.length().coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis(),
                )
                when (controlRequest) {
                    ControlRequest.PAUSE -> markPaused(currentSnapshot, "Загрузка приостановлена")
                    ControlRequest.CANCEL -> markCancelled(currentSnapshot)
                    ControlRequest.NONE -> {
                        if (!hasUsableNetwork()) {
                            waitForNetwork(currentSnapshot, "Нет сети. Продолжим автоматически после восстановления подключения")
                        } else {
                            markError(currentSnapshot, "Загрузка обновления была прервана")
                        }
                    }
                }
                return
            } catch (error: Exception) {
                val currentSnapshot = snapshot.copy(
                    downloadedBytes = partFile.length().coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis(),
                    speedBytesPerSecond = 0L,
                    estimatedRemainingMs = -1L,
                )

                when (controlRequest) {
                    ControlRequest.PAUSE -> {
                        markPaused(currentSnapshot, "Загрузка приостановлена")
                        return
                    }

                    ControlRequest.CANCEL -> {
                        markCancelled(currentSnapshot)
                        return
                    }

                    ControlRequest.NONE -> {
                        if (shouldWaitForNetwork(error)) {
                            waitForNetwork(
                                currentSnapshot,
                                "Нет сети. Продолжим автоматически после восстановления подключения"
                            )
                        } else {
                            markError(currentSnapshot, describeDownloadError(error))
                        }
                        return
                    }
                }
            } finally {
                activeCall = null
            }
        }

        snapshot = snapshot.copy(
            phase = AppUpdatePhase.VERIFYING,
            downloadedBytes = partFile.length().coerceAtLeast(0L),
            totalBytes = snapshot.totalBytes.coerceAtLeast(partFile.length().coerceAtLeast(0L)),
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            statusMessage = "Проверяем целостность APK",
            updatedAt = System.currentTimeMillis(),
        )
        settingsStore.saveUpdateDownloadState(snapshot)
        ensureForeground(snapshot)

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!moveDownloadedPart(partFile, targetFile)) {
            markError(snapshot, "Не удалось сохранить APK после загрузки")
            return
        }

        when (val verification = verifyDownloadedApk(targetFile, snapshot)) {
            is VerificationResult.Success -> {
                markReady(snapshot, targetFile, verification.sha256, "APK готов к установке")
            }

            is VerificationResult.Failure -> {
                targetFile.delete()
                markError(snapshot, verification.message)
            }
        }
    }

    private suspend fun writeResponseBodyToFile(
        inputStream: java.io.InputStream,
        outputFile: File,
        append: Boolean,
        baseSnapshot: AppUpdateDownloadSnapshot,
        onProgress: suspend (AppUpdateDownloadSnapshot) -> Unit,
    ) {
        var downloadedBytes = if (append) outputFile.length() else 0L
        var snapshot = baseSnapshot.copy(downloadedBytes = downloadedBytes)
        var lastReportAt = 0L
        var speedWindowBytes = 0L
        var speedWindowStartedAt = System.currentTimeMillis()

        FileOutputStream(outputFile, append).use { output ->
            inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break

                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    speedWindowBytes += bytesRead.toLong()

                    val now = System.currentTimeMillis()
                    if (now - lastReportAt >= 500L) {
                        val elapsed = (now - speedWindowStartedAt).coerceAtLeast(1L)
                        val speed = (speedWindowBytes * 1000L) / elapsed
                        val etaMs = if (speed > 0L && snapshot.totalBytes > 0L) {
                            ((snapshot.totalBytes - downloadedBytes).coerceAtLeast(0L) * 1000L) / speed
                        } else {
                            -1L
                        }
                        snapshot = snapshot.copy(
                            phase = AppUpdatePhase.DOWNLOADING,
                            downloadedBytes = downloadedBytes,
                            speedBytesPerSecond = speed,
                            estimatedRemainingMs = etaMs,
                            lastError = "",
                            updatedAt = now,
                        )
                        settingsStore.saveUpdateDownloadState(snapshot)
                        ensureForeground(snapshot)
                        onProgress(snapshot)
                        lastReportAt = now
                        if (elapsed >= 1000L) {
                            speedWindowBytes = 0L
                            speedWindowStartedAt = now
                        }
                    }
                }
            }
            output.fd.sync()
        }

        val finishedAt = System.currentTimeMillis()
        val finalSnapshot = snapshot.copy(
            downloadedBytes = downloadedBytes,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            updatedAt = finishedAt,
        )
        settingsStore.saveUpdateDownloadState(finalSnapshot)
        ensureForeground(finalSnapshot)
        onProgress(finalSnapshot)
    }

    private suspend fun buildSnapshot(release: AppReleaseInfo): AppUpdateDownloadSnapshot {
        val existing = settingsStore.updateDownloadState.first()
        val apkFile = appUpdateApkFile(this, release.versionTag)
        val partFile = appUpdatePartFile(this, release.versionTag)
        val downloadedBytes = when {
            apkFile.exists() -> apkFile.length()
            partFile.exists() -> partFile.length()
            existing.matchesVersion(release.versionTag) -> existing.downloadedBytes
            else -> 0L
        }

        return AppUpdateDownloadSnapshot(
            phase = when {
                apkFile.exists() -> AppUpdatePhase.READY_TO_INSTALL
                downloadedBytes > 0L -> existing.phase.takeIf { it != AppUpdatePhase.IDLE } ?: AppUpdatePhase.PAUSED
                else -> AppUpdatePhase.IDLE
            },
            versionTag = release.versionTag,
            releaseUrl = release.releaseUrl,
            downloadUrl = release.downloadUrl.orEmpty(),
            releaseNotes = release.releaseNotes,
            isPrerelease = release.isPrerelease,
            downloadFileName = release.downloadFileName.orEmpty(),
            downloadSizeBytes = release.downloadSizeBytes.coerceAtLeast(0L),
            expectedSha256 = release.expectedSha256.orEmpty(),
            sha256AssetUrl = release.sha256AssetUrl.orEmpty(),
            filePath = apkFile.absolutePath,
            tempFilePath = partFile.absolutePath,
            downloadedBytes = downloadedBytes,
            totalBytes = maxOf(existing.totalBytes, release.downloadSizeBytes, downloadedBytes),
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            startedAt = existing.startedAt.takeIf { existing.matchesVersion(release.versionTag) } ?: 0L,
            updatedAt = System.currentTimeMillis(),
            lastError = "",
            lastVerifiedSha256 = existing.lastVerifiedSha256.takeIf { existing.matchesVersion(release.versionTag) }.orEmpty(),
            statusMessage = "",
            rangeSupported = existing.rangeSupported && existing.matchesVersion(release.versionTag),
            autoResumeOnNetwork = false,
        )
    }

    private suspend fun resolveExpectedSha256(snapshot: AppUpdateDownloadSnapshot): String {
        snapshot.expectedSha256.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        val sidecarUrl = snapshot.sha256AssetUrl.takeIf { it.isNotBlank() } ?: return ""
        return try {
            val request = Request.Builder()
                .url(sidecarUrl)
                .get()
                .header("User-Agent", "qWDTTAndroid/${BuildConfig.VERSION_NAME}")
                .header("Accept", "text/plain,*/*")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LOG_TAG, "SHA-256 sidecar request failed with HTTP ${response.code}")
                    return ""
                }
                extractSha256FromText(response.body?.string().orEmpty(), snapshot.downloadFileName).orEmpty()
            }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Failed to resolve SHA-256 sidecar", error)
            ""
        }
    }

    private suspend fun markReady(
        snapshot: AppUpdateDownloadSnapshot,
        apkFile: File,
        actualSha256: String,
        message: String,
    ) {
        deleteFileIfExists(snapshot.tempFilePath)
        val readySnapshot = snapshot.copy(
            phase = AppUpdatePhase.READY_TO_INSTALL,
            filePath = apkFile.absolutePath,
            downloadedBytes = apkFile.length().coerceAtLeast(snapshot.downloadedBytes),
            totalBytes = snapshot.totalBytes.coerceAtLeast(apkFile.length()),
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            autoResumeOnNetwork = false,
            lastError = "",
            lastVerifiedSha256 = actualSha256,
            statusMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        Log.i(LOG_TAG, "App update ready for install: version=${readySnapshot.versionTag}, file=${apkFile.name}")
        settingsStore.saveUpdateDownloadState(readySnapshot)
        showDetachedNotification(readySnapshot)
        stopSelf()
    }

    private suspend fun markPaused(snapshot: AppUpdateDownloadSnapshot, message: String) {
        val pausedSnapshot = snapshot.copy(
            phase = AppUpdatePhase.PAUSED,
            downloadedBytes = File(snapshot.tempFilePath).takeIf(File::exists)?.length() ?: snapshot.downloadedBytes,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            autoResumeOnNetwork = false,
            statusMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        Log.i(LOG_TAG, "App update paused: version=${pausedSnapshot.versionTag}, downloaded=${pausedSnapshot.downloadedBytes}")
        settingsStore.saveUpdateDownloadState(pausedSnapshot)
        showDetachedNotification(pausedSnapshot)
        stopSelf()
    }

    private suspend fun waitForNetwork(snapshot: AppUpdateDownloadSnapshot, message: String) {
        val waitingSnapshot = snapshot.copy(
            phase = AppUpdatePhase.WAITING_FOR_NETWORK,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            autoResumeOnNetwork = true,
            lastError = "",
            statusMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        Log.i(LOG_TAG, "App update waiting for network: version=${waitingSnapshot.versionTag}")
        settingsStore.saveUpdateDownloadState(waitingSnapshot)
        ensureForeground(waitingSnapshot)
    }

    private suspend fun markError(snapshot: AppUpdateDownloadSnapshot, message: String) {
        val errorSnapshot = snapshot.copy(
            phase = AppUpdatePhase.ERROR,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            autoResumeOnNetwork = false,
            lastError = message,
            statusMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
        Log.w(LOG_TAG, "App update failed: version=${errorSnapshot.versionTag}, error=$message")
        settingsStore.saveUpdateDownloadState(errorSnapshot)
        showDetachedNotification(errorSnapshot)
        stopSelf()
    }

    private suspend fun markCancelled(snapshot: AppUpdateDownloadSnapshot) {
        deleteFileIfExists(snapshot.filePath)
        deleteFileIfExists(snapshot.tempFilePath)
        val cancelledSnapshot = snapshot.copy(
            phase = AppUpdatePhase.CANCELLED,
            downloadedBytes = 0L,
            totalBytes = 0L,
            speedBytesPerSecond = 0L,
            estimatedRemainingMs = -1L,
            autoResumeOnNetwork = false,
            lastError = "",
            statusMessage = "Загрузка обновления отменена",
            updatedAt = System.currentTimeMillis(),
        )
        Log.i(LOG_TAG, "App update cancelled: version=${cancelledSnapshot.versionTag}")
        settingsStore.saveUpdateDownloadState(cancelledSnapshot)
        removeNotification()
        stopSelf()
    }

    private fun ensureForeground(snapshot: AppUpdateDownloadSnapshot) {
        val notification = buildNotification(snapshot, isForeground = true)
        if (foregroundActive) {
            notificationManager.notify(APP_UPDATE_NOTIFICATION_ID, notification)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                APP_UPDATE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(APP_UPDATE_NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun showDetachedNotification(snapshot: AppUpdateDownloadSnapshot) {
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foregroundActive = false
        }
        notificationManager.notify(
            APP_UPDATE_NOTIFICATION_ID,
            buildNotification(snapshot, isForeground = false)
        )
    }

    private fun removeNotification() {
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        notificationManager.cancel(APP_UPDATE_NOTIFICATION_ID)
    }

    private fun buildNotification(snapshot: AppUpdateDownloadSnapshot, isForeground: Boolean): Notification {
        val details = formatAppUpdateDetails(snapshot)
        val contentText = listOf(formatAppUpdateStatus(snapshot), details.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(" • ")

        val builder = NotificationCompat.Builder(this, APP_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(
                when (snapshot.phase) {
                    AppUpdatePhase.READY_TO_INSTALL -> android.R.drawable.stat_sys_download_done
                    AppUpdatePhase.ERROR -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                }
            )
            .setContentTitle("Обновление Hoplet ${snapshot.versionTag.ifBlank { "" }}".trim())
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(isForeground || snapshot.phase == AppUpdatePhase.WAITING_FOR_NETWORK)
            .setAutoCancel(!isForeground)
            .setContentIntent(contentPendingIntent())
            .setPriority(
                if (snapshot.phase == AppUpdatePhase.ERROR) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                }
            )

        if (snapshot.totalBytes > 0L && snapshot.phase == AppUpdatePhase.DOWNLOADING) {
            builder.setProgress(100, snapshot.progressPercent, false)
        } else if (snapshot.phase == AppUpdatePhase.VERIFYING || snapshot.phase == AppUpdatePhase.WAITING_FOR_NETWORK) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(0, 0, false)
        }

        when {
            snapshot.canPause -> {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Пауза",
                    servicePendingIntent(ACTION_PAUSE_APP_UPDATE)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Отмена",
                    servicePendingIntent(ACTION_CANCEL_APP_UPDATE)
                )
            }

            snapshot.phase == AppUpdatePhase.PAUSED || snapshot.phase == AppUpdatePhase.CANCELLED -> {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Продолжить",
                    servicePendingIntent(ACTION_RESUME_APP_UPDATE)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_delete,
                    "Очистить",
                    servicePendingIntent(ACTION_CLEAR_APP_UPDATE)
                )
            }

            snapshot.phase == AppUpdatePhase.ERROR -> {
                builder.addAction(
                    android.R.drawable.ic_popup_sync,
                    "Повторить",
                    servicePendingIntent(ACTION_RETRY_APP_UPDATE)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_delete,
                    "Очистить",
                    servicePendingIntent(ACTION_CLEAR_APP_UPDATE)
                )
            }

            snapshot.phase == AppUpdatePhase.READY_TO_INSTALL -> {
                builder.addAction(
                    android.R.drawable.stat_sys_download_done,
                    "Установить",
                    servicePendingIntent(ACTION_INSTALL_APP_UPDATE)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_delete,
                    "Очистить",
                    servicePendingIntent(ACTION_CLEAR_APP_UPDATE)
                )
            }
        }

        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(
            this,
            action.hashCode(),
            appUpdateServiceIntent(this, action),
            flags
        )
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AppUpdate").apply {
            setReferenceCounted(false)
            acquire(20L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            Log.w(LOG_TAG, "Failed to register network callback", it)
        }
    }

    private fun unregisterNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }.onFailure {
            Log.w(LOG_TAG, "Failed to unregister network callback", it)
        }
    }

    private fun cleanupForeignArtifacts(versionTag: String) {
        val keepFiles = setOf(
            appUpdateApkFile(this, versionTag).absolutePath,
            appUpdatePartFile(this, versionTag).absolutePath,
        )
        appUpdateDirectory(this).listFiles().orEmpty().forEach { file ->
            if (file.absolutePath !in keepFiles) {
                runCatching { file.delete() }
            }
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun shouldWaitForNetwork(error: Exception): Boolean {
        return !hasUsableNetwork() ||
            error is ConnectException ||
            error is java.net.UnknownHostException
    }

    private fun resolveTotalBytes(response: Response, existingBytes: Long, hintedTotalBytes: Long): Long {
        val contentRange = response.header("Content-Range")
        val totalFromRange = contentRange
            ?.substringAfterLast('/')
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        val responseLength = response.body?.contentLength()?.takeIf { it > 0L } ?: 0L
        return when {
            totalFromRange != null -> totalFromRange
            response.code == 206 && responseLength > 0L -> existingBytes + responseLength
            responseLength > 0L -> responseLength
            hintedTotalBytes > 0L -> hintedTotalBytes
            else -> existingBytes
        }
    }

    private fun supportsByteRange(response: Response): Boolean {
        return response.code == 206 ||
            response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
    }

    private fun hasEnoughDiskSpace(totalBytes: Long, existingBytes: Long): Boolean {
        if (totalBytes <= 0L) return true
        val remainingBytes = (totalBytes - existingBytes).coerceAtLeast(0L)
        val usableSpace = appUpdateDirectory(this).usableSpace
        return usableSpace > remainingBytes + (8L * 1024L * 1024L)
    }

    private fun moveDownloadedPart(partFile: File, targetFile: File): Boolean {
        return try {
            if (!partFile.exists()) return false
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
            true
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Failed to move APK from part file", error)
            false
        }
    }

    private fun verifyDownloadedApk(
        apkFile: File,
        snapshot: AppUpdateDownloadSnapshot,
    ): VerificationResult {
        val actualSha256 = try {
            sha256(apkFile)
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Failed to calculate SHA-256", error)
            return VerificationResult.Failure("Не удалось проверить SHA-256 загруженного APK")
        }

        if (snapshot.expectedSha256.isNotBlank() &&
            !actualSha256.equals(snapshot.expectedSha256, ignoreCase = true)
        ) {
            return VerificationResult.Failure("Контрольная сумма APK не совпала, файл удален")
        }

        val archiveInfo = readArchivePackageInfo(apkFile)
            ?: return VerificationResult.Failure("Загруженный файл не распознан как корректный APK")
        if (archiveInfo.packageName != packageName) {
            return VerificationResult.Failure("Загруженный APK принадлежит другому приложению")
        }

        val installedInfo = readInstalledPackageInfo()
            ?: return VerificationResult.Success(actualSha256)
        val downloadedVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
        if (downloadedVersionCode <= installedVersionCode) {
            return VerificationResult.Failure("Скачанная версия не новее установленной")
        }

        return VerificationResult.Success(actualSha256)
    }

    private fun readArchivePackageInfo(apkFile: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
    }

    private fun readInstalledPackageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun describeDownloadError(error: Exception): String = when (error) {
        is DownloadHttpException -> when (error.code) {
            404 -> "APK обновления не найден на сервере"
            408 -> "Сервер обновлений слишком долго отвечает"
            429 -> "Сервер временно ограничил загрузки, попробуйте позже"
            in 500..599 -> "Сервер обновлений временно недоступен (HTTP ${error.code})"
            else -> "Ошибка загрузки APK (HTTP ${error.code})"
        }

        is java.net.UnknownHostException -> "Нет подключения к интернету"
        is SocketTimeoutException -> "Истекло время ожидания при скачивании APK"
        is ConnectException -> "Не удалось подключиться к серверу обновлений"
        is SSLException -> "Ошибка безопасного соединения при скачивании APK"
        is IOException -> {
            val message = error.message.orEmpty()
            when {
                message.contains("space", ignoreCase = true) ||
                    message.contains("enospc", ignoreCase = true) -> {
                    "Недостаточно свободного места для загрузки обновления"
                }

                else -> message.ifBlank { "Ошибка ввода-вывода при загрузке APK" }
            }
        }

        else -> error.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка при загрузке обновления"
    }

    private enum class ControlRequest {
        NONE,
        PAUSE,
        CANCEL,
    }

    private sealed interface VerificationResult {
        data class Success(val sha256: String) : VerificationResult
        data class Failure(val message: String) : VerificationResult
    }

    private class DownloadHttpException(val code: Int, responseBody: String) :
        IOException("HTTP $code ${responseBody.take(160)}")

    private companion object {
        private const val LOG_TAG = "qWDTT"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
