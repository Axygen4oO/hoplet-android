package com.wdtt.client.ui

import com.wdtt.client.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.PeerAddress
import com.wdtt.client.ConnectionLifecycle
import com.wdtt.client.ConnectionProgressManager
import com.wdtt.client.HopletTheme
import com.wdtt.client.ResolvedVkHashes
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import com.wdtt.client.VkHashSourceResolver
import com.wdtt.client.WDTTColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wdtt.client.VkAuthWebViewManager
import com.wdtt.client.ManlCaptchaWebViewManager
import kotlin.math.roundToInt
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.scale
import com.wdtt.client.NotificationHelper
import com.wdtt.client.isNewerVersion
import com.wdtt.client.stripVkUrlStatic

import androidx.compose.ui.res.stringResource

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

import com.wdtt.client.ServerVkHashes
import com.wdtt.client.AdminSession
import org.json.JSONObject
import java.io.OutputStreamWriter


import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.withStyle


private const val WORKERS_PER_GROUP = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    onConnectRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        SettingsTabContent(
            context = context,
            scope = scope,
            settingsStore = settingsStore,
            onConnectRequested = onConnectRequested
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore,
    onConnectRequested: () -> Unit = {}
) {
    val savedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    val savedManualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedServerDtlsPort by settingsStore.serverDtlsPort.collectAsStateWithLifecycle(initialValue = 56000)
    val savedServerWgPort by settingsStore.serverWgPort.collectAsStateWithLifecycle(initialValue = 56001)
    val savedListenPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val isConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val connectedSinceMs by TunnelManager.connectedSinceMs.collectAsStateWithLifecycle()
    val tunnelStats by TunnelManager.stats.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()
    val connectionProgressState by ConnectionProgressManager.state.collectAsStateWithLifecycle()
    val autoSwitchToLogs by settingsStore.autoSwitchToLogs.collectAsStateWithLifecycle(initialValue = true)
    val stopOnWifi by settingsStore.stopOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val showSpeedGraph by settingsStore.showSpeedGraph.collectAsStateWithLifecycle(initialValue = true)
    val detailedLogs by settingsStore.detailedLogs.collectAsStateWithLifecycle(initialValue = false)
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = com.wdtt.client.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )

    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")
    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val savedPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val savedWorkers by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 18)

    val profilesStore = remember { com.wdtt.client.ProfilesStore(context) }
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())

    val tunnelBusy by TunnelManager.enabled.collectAsStateWithLifecycle()

    val cooldownSeconds by TunnelManager.cooldownSeconds.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) {
            TunnelManager.startCooldown(5)
        }
        wasRunning = tunnelRunning
    }

    var peerInput by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(18f) }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var showLocalHashesDialog by rememberSaveable { mutableStateOf(false) }
    var autoCaptchaEnabled by rememberSaveable { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }
    var isManualMode by rememberSaveable { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable { mutableStateOf(true) }
    var vkAccountAuth by rememberSaveable { mutableStateOf(false) }
    var vkAuthBusy by remember { mutableStateOf(false) }
    var vkLoggedIn by remember { mutableStateOf(false) }
    var manualPortsEnabled by rememberSaveable { mutableStateOf(false) }
    var serverDtlsPortInput by rememberSaveable { mutableStateOf("56000") }
    var serverWgPortInput by rememberSaveable { mutableStateOf("56001") }
    var showAppSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showGeneralSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var isAdminMode by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var versionClickCount by rememberSaveable { mutableIntStateOf(0) }
    var aboutClickCount by remember { mutableIntStateOf(0) }
    var showRolePickerDialog by remember { mutableStateOf(false) }
    val openAppSettingsRequest by TunnelManager.openAppSettingsRequest.collectAsStateWithLifecycle()
    var lastHandledOpenSettings by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(openAppSettingsRequest) {
        if (openAppSettingsRequest > 0L && openAppSettingsRequest != lastHandledOpenSettings) {
            lastHandledOpenSettings = openAppSettingsRequest
            showAppSettingsDialog = true
        }
    }

    val activeHashesRaw by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val localHashesRaw by settingsStore.localVkHashes.collectAsStateWithLifecycle(initialValue = "")
    val serverVkHashesCache by settingsStore.serverVkHashesCache.collectAsStateWithLifecycle(initialValue = "")
    val vkHashSource by settingsStore.vkHashSource.collectAsStateWithLifecycle(
        initialValue = SettingsStore.VK_HASH_SOURCE_SERVER
    )
    val localUniqueHashes = remember(localHashesRaw) {
        localHashesRaw.split(Regex("[,\\s\\n]+"))
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
    }
    val localFilledHashCount = localUniqueHashes.size
    val combinedLocalHashes = localUniqueHashes.joinToString(",")
    val sourceHashesForWorkerLimit = remember(vkHashSource, localHashesRaw, serverVkHashesCache, activeHashesRaw) {
        if (vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
            localHashesRaw
        } else {
            serverVkHashesCache.ifBlank { activeHashesRaw }
        }
    }
    val sourceUniqueHashes = remember(sourceHashesForWorkerLimit) {
        sourceHashesForWorkerLimit.split(Regex("[,\\s\\n]+"))
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
    }
    val sourceFilledHashCount = sourceUniqueHashes.size
    val dynamicMaxWorkers = remember(sourceFilledHashCount, vkAccountAuth) {
        if (vkAccountAuth) SettingsStore.VK_ACCOUNT_MAX_WORKERS.toFloat()
        else SettingsStore.maxAnonymousWorkers(sourceFilledHashCount.coerceAtLeast(1)).toFloat()
    }

    val vkAnonPath by settingsStore.vkAnonPath.collectAsStateWithLifecycle(initialValue = "vkcalls")
    val goDnsPreset by settingsStore.goDnsPreset.collectAsStateWithLifecycle(initialValue = "yandex")
    val goDnsCustomStored by settingsStore.goDnsCustom.collectAsStateWithLifecycle(initialValue = "")
    val goDnsDohCustomStored by settingsStore.goDnsDohCustom.collectAsStateWithLifecycle(initialValue = "")
    val obfsMode by settingsStore.obfsMode.collectAsStateWithLifecycle(initialValue = "audio")
    val interfaceRole by settingsStore.interfaceRole.collectAsStateWithLifecycle(initialValue = "admin")
    var goDnsCustomInput by rememberSaveable { mutableStateOf("") }
    var goDnsDohCustomInput by rememberSaveable { mutableStateOf("") }
    val useVKCallsAuth = !vkAnonPath.equals("legacy", ignoreCase = true)
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var sniInput by rememberSaveable { mutableStateOf("") }
    var showWelcomeDialog by rememberSaveable {
        mutableStateOf(false)
    }


    val currentWorkers = if (vkAccountAuth) {
        workersInput.coerceIn(1f, dynamicMaxWorkers)
    } else {
        workersInput.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers)
    }

    val localHashErrors = remember(localHashesRaw) {
        buildList {
            val parts = localHashesRaw.split(Regex("[,\\s\\n]+")).filter { it.isNotEmpty() }
            parts.forEachIndexed { i, h ->
                if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
            }
            val filled = parts.filter { it.isNotBlank() && it.length >= 16 }
            if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
        }
    }
    val hasLocalHashErrors = localHashErrors.isNotEmpty()

    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    fun normalizeHashes(vararg hashes: String): String {
        return hashes
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    LaunchedEffect(Unit) {
        val peer = settingsStore.peer.first()
        val hashes = settingsStore.vkHashes.first()
        val hashSource = settingsStore.vkHashSource.first()
        val localHashes = settingsStore.localVkHashes.first()
        val serverHashes = settingsStore.serverVkHashesCache.first()
        val workers = settingsStore.workersPerHash.first()
        val port = settingsStore.listenPort.first()
        val manualPorts = settingsStore.manualPortsEnabled.first()
        val serverDtlsPort = settingsStore.serverDtlsPort.first()
        val serverWgPort = settingsStore.serverWgPort.first()
        val sni = settingsStore.sni.first()
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvCaptchaMethod = settingsStore.captchaWbvSolveMethod.first()
        val vkAuthMode = settingsStore.vkAuthMode.first()

        val embeddedPort = PeerAddress.port(peer)
        peerInput = PeerAddress.host(peer)
        val workerLimitHashes = if (hashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
            localHashes
        } else {
            serverHashes.ifBlank { hashes }
        }
        val initialHashesList = workerLimitHashes.split(Regex("[,\\s\\n]+"))
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
        val initialHashesCount = initialHashesList.size.coerceAtLeast(1)
        workersInput = roundToGroup(
            workers.toFloat(),
            SettingsStore.maxAnonymousWorkers(initialHashesCount).toFloat()
        )
        portInput = port.toString()
        manualPortsEnabled = manualPorts
        serverDtlsPortInput = (embeddedPort ?: serverDtlsPort).toString()
        serverWgPortInput = serverWgPort.toString()
        if (embeddedPort != null && PeerAddress.hasExplicitPort(peer)) {
            if (embeddedPort != 56000) {
                settingsStore.saveManualPortsEnabled(true)
                manualPortsEnabled = true
            }
            settingsStore.savePorts(embeddedPort, serverWgPort, port)
            settingsStore.save(
                PeerAddress.host(peer), hashes, "",
                workers, "udp", port, sni, false
            )
        }
        sniInput = sni
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvCaptchaMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"
        vkAccountAuth = !vkAuthMode.equals("anonymous", ignoreCase = true)
        goDnsCustomInput = settingsStore.goDnsCustom.first()
        goDnsDohCustomInput = settingsStore.goDnsDohCustom.first().ifBlank {
            val legacy = settingsStore.goDnsCustom.first()
            if (legacy.startsWith("https://", ignoreCase = true)) legacy else ""
        }

        initialized = true
        vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
    }

    LaunchedEffect(goDnsCustomStored) {
        if (goDnsCustomInput != goDnsCustomStored) {
            goDnsCustomInput = goDnsCustomStored
        }
    }

    LaunchedEffect(goDnsDohCustomStored) {
        if (goDnsDohCustomInput != goDnsDohCustomStored) {
            goDnsDohCustomInput = goDnsDohCustomStored
        }
    }

    LaunchedEffect(currentProfileId, savedPeer, savedWorkers, savedListenPort, vkAccountAuth, sourceHashesForWorkerLimit) {
        if (currentProfileId.isBlank()) return@LaunchedEffect
        if (savedPeer.isNotBlank()) {
            peerInput = PeerAddress.host(savedPeer)
            PeerAddress.port(savedPeer)?.let { embedded ->
                serverDtlsPortInput = embedded.toString()
            }
        }
        portInput = savedListenPort.toString()
        val hashesCount = sourceHashesForWorkerLimit.split(",").filter { it.isNotBlank() }.size.coerceAtLeast(1)
        val maxW = if (vkAccountAuth) {
            SettingsStore.VK_ACCOUNT_MAX_WORKERS.toFloat()
        } else {
            SettingsStore.maxAnonymousWorkers(hashesCount).toFloat()
        }
        workersInput = roundToGroup(savedWorkers.toFloat(), maxW, vkAccountAuth)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vkAuthBusy) {
        if (!vkAuthBusy) {
            vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
        }
    }

    LaunchedEffect(vkAccountAuth) {
        if (vkAccountAuth) {
            vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
        }
    }

    LaunchedEffect(savedManualPortsEnabled) {
        manualPortsEnabled = savedManualPortsEnabled
    }

    LaunchedEffect(savedServerDtlsPort) {
        serverDtlsPortInput = savedServerDtlsPort.toString()
    }

    LaunchedEffect(savedServerWgPort) {
        serverWgPortInput = savedServerWgPort.toString()
    }

    LaunchedEffect(savedListenPort) {
        portInput = savedListenPort.toString()
    }

    LaunchedEffect(aboutClickCount) {
        if (aboutClickCount > 0) {
            delay(3000)
            aboutClickCount = 0
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            aboutClickCount = 0
            showRolePickerDialog = false
        }
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun activeHashesForPersistence(localOverride: String? = null): String {
        return if (vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
            SettingsStore.normalizeVkHashes(localOverride ?: combinedLocalHashes)
        } else {
            SettingsStore.normalizeVkHashes(activeHashesRaw)
        }
    }

    fun saveTunnelSettingsNow(activeHashesOverride: String? = null, onSaved: (() -> Unit)? = null) {
        saveJob?.cancel()
        scope.launch {
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            val hashes = activeHashesForPersistence(activeHashesOverride)
            val hashesList = hashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
            val hashesCount = hashesList.size.coerceAtLeast(1)
            val maxW = if (vkAccountAuth) {
                SettingsStore.VK_ACCOUNT_MAX_WORKERS
            } else {
                SettingsStore.maxAnonymousWorkers(hashesCount)
            }
            val finalWorkers = if (vkAccountAuth) {
                workersInput.toInt().coerceIn(1, maxW)
            } else {
                workersInput.toInt().coerceIn(9, maxW)
            }
            val host = PeerAddress.host(peerInput.trim())
            settingsStore.save(
                host, hashes, "",
                finalWorkers, "udp", savedLocalPort, sniInput, false
            )
            onSaved?.invoke()
        }
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            val hashes = activeHashesForPersistence()
            val hashesList = hashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
            val hashesCount = hashesList.size.coerceAtLeast(1)
            val maxW = if (vkAccountAuth) {
                SettingsStore.VK_ACCOUNT_MAX_WORKERS
            } else {
                SettingsStore.maxAnonymousWorkers(hashesCount)
            }
            val finalWorkers = if (vkAccountAuth) {
                workersInput.toInt().coerceIn(1, maxW)
            } else {
                workersInput.toInt().coerceIn(9, maxW)
            }
            val host = PeerAddress.host(peerInput.trim())
            settingsStore.save(
                host, hashes, "",
                finalWorkers, "udp", savedLocalPort, sniInput, false
            )
        }
    }

    val scrollState = rememberScrollState()

    val speedHistory = remember { mutableStateListOf<Float>() }
    var currentSpeedKbps by remember { mutableFloatStateOf(0f) }
    var lastTraffic by remember { mutableDoubleStateOf(-1.0) }
    var lastTime by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(tunnelRunning, connectedSinceMs) {
        if (!tunnelRunning || connectedSinceMs <= 0L) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val uptimeText = if (tunnelRunning && connectedSinceMs > 0L) {
        TunnelManager.formatUptime(nowMs - connectedSinceMs)
    } else {
        null
    }
    val trafficSummaryText = remember(tunnelStats) {
        parseTrafficMb(tunnelStats)?.let { String.format("%.2f МБ", it) } ?: "0.00 МБ"
    }

    LaunchedEffect(tunnelRunning) {
        if (tunnelRunning) {
            speedHistory.clear()
            repeat(30) { speedHistory.add(0f) }
            lastTraffic = -1.0
            lastTime = System.currentTimeMillis()
            currentSpeedKbps = 0f

            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val statsText = TunnelManager.stats.value
                val currentTraffic = parseTrafficMb(statsText)

                if (currentTraffic != null) {
                    if (lastTraffic >= 0.0) {
                        val deltaTrafficMb = currentTraffic - lastTraffic
                        if (deltaTrafficMb > 0.0) {
                            val deltaTimeSec = (now - lastTime) / 1000.0
                            if (deltaTimeSec > 0) {
                                val rawSpeed = ((deltaTrafficMb * 1024.0) / deltaTimeSec).toFloat()
                                currentSpeedKbps = rawSpeed
                                lastTraffic = currentTraffic
                                lastTime = now
                            }
                        } else {
                            if (now - lastTime > 3800) {
                                currentSpeedKbps = 0f
                            }
                        }
                    } else {
                        lastTraffic = currentTraffic
                        lastTime = now
                    }
                }

                var speedPoint = currentSpeedKbps
                if (speedPoint > 2f) {
                    val oscillation = (Math.random() * 0.12 - 0.06).toFloat()
                    speedPoint = (speedPoint + speedPoint * oscillation).coerceAtLeast(0f)
                }
                if (speedHistory.size >= 30) speedHistory.removeAt(0)
                speedHistory.add(speedPoint)
            }
        } else {
            currentSpeedKbps = 0f
            speedHistory.clear()
        }
    }

    val isPeerValid = peerInput.isNotBlank()
    val isHashesValid = if (vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
        combinedLocalHashes.isNotBlank()
    } else {
        true
    }
    val isValid = isPeerValid &&
        isHashesValid &&
        savedConnectionPassword.isNotBlank() &&
        !(vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL && hasLocalHashErrors)
    val effectiveServerDtlsPort = if (manualPortsEnabled) serverDtlsPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 56000 else 56000
    val effectiveLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }

    fun startTunnelService() {
        val effectiveCaptchaMode = if (autoCaptchaEnabled) "auto" else if (useWVCaptcha) "wv" else "rjs"
        val effectiveCaptchaSolveMethod = if (!autoCaptchaEnabled && effectiveCaptchaMode == "wv" && isManualMode) "manual" else "auto"
        val host = PeerAddress.host(peerInput.trim())
        val peerForTunnel = PeerAddress.ensurePort(host, effectiveServerDtlsPort)
        saveJob?.cancel()
        scope.launch {
            val effectiveVkAnonPath = SettingsStore.resolveVkAnonPath(context)
            val resolvedHashes = when (vkHashSource) {
                SettingsStore.VK_HASH_SOURCE_LOCAL -> ResolvedVkHashes(
                    source = vkHashSource,
                    hashes = SettingsStore.normalizeVkHashes(combinedLocalHashes),
                )
                else -> VkHashSourceResolver.resolveForConnection(
                    context = context,
                    settingsStore = settingsStore,
                    peer = host,
                )
            }
            val finalHashes = resolvedHashes.hashes
            if (finalHashes.isBlank()) {
                val message = if (resolvedHashes.source == SettingsStore.VK_HASH_SOURCE_SERVER) {
                    "Не удалось получить серверные VK hash"
                } else {
                    "Локальные VK hash не заданы"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val hashesList = finalHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
            val hashesCount = hashesList.size.coerceAtLeast(1)
            val maxW = if (vkAccountAuth) {
                SettingsStore.VK_ACCOUNT_MAX_WORKERS
            } else {
                SettingsStore.maxAnonymousWorkers(hashesCount)
            }
            val finalWorkers = if (vkAccountAuth) {
                workersInput.toInt().coerceIn(1, maxW)
            } else {
                workersInput.toInt().coerceIn(9, maxW)
            }

            settingsStore.save(
                host,
                finalHashes,
                "",
                finalWorkers,
                "udp",
                effectiveLocalPort,
                sniInput,
                false
            )
            android.util.Log.d("VKHASH", "settingsStore.save OK")

            settingsStore.saveCaptchaMode(effectiveCaptchaMode)
            settingsStore.saveCaptchaSolveMethod(effectiveCaptchaSolveMethod)
            settingsStore.saveVkAnonPath(effectiveVkAnonPath)

            val effectiveGoDns = try {
                settingsStore.resolveGoDnsArg()
            } catch (e: Exception) {
                android.util.Log.e("VKHASH", "resolveGoDnsArg failed", e)
                ""
            }

            android.util.Log.d("VKHASH", "resolveGoDnsArg OK")


            val intent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer", peerForTunnel)
                putExtra("vk_hashes", finalHashes)
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", finalWorkers)
                putExtra("port", effectiveLocalPort)
                putExtra("sni", sniInput)
                putExtra("connection_password", savedConnectionPassword)
                putExtra("captcha_mode", effectiveCaptchaMode)
                putExtra("captcha_solve_method", effectiveCaptchaSolveMethod)
                putExtra("vk_auth_mode", if (vkAccountAuth) "account" else "anonymous")
                putExtra("vk_anon_path", effectiveVkAnonPath)
                putExtra("go_dns_arg", effectiveGoDns)
                putExtra("obfs_mode", obfsMode)
            }
            android.util.Log.d("VKHASH", "Intent created")

            try {
                if (Build.VERSION.SDK_INT >= 26)
                    context.startForegroundService(intent)
                else
                    context.startService(intent)

                android.util.Log.d("VKHASH", "TunnelService started")

            } catch (e: Exception) {
                android.util.Log.e("VKHASH", e.stackTraceToString())
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) {
                startTunnelService()
            } else {
                Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestVpnAndStart() {
        (context as? com.wdtt.client.MainActivity)?.requestNotificationPermissionIfNeeded()
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    @Composable
    fun GeneralBehaviorSettingsSection() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Поведение",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "Логи при подключении",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Переключаться на вкладку «Логи» при запуске туннеля",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = autoSwitchToLogs,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.saveAutoSwitchToLogs(enabled) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "Отключать на Wi-Fi",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Автоматически отключать туннель при подключении к Wi-Fi (удобно для обхода БС только в мобильной сети)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = stopOnWifi,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.saveStopOnWifi(enabled) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "График скорости",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Отображать график скорости на вкладке туннеля при активном соединении",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = showSpeedGraph,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.saveShowSpeedGraph(enabled) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "Подробные логи",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Записывать больше диагностической информации (замедляет работу)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = detailedLogs,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.saveDetailedLogs(enabled) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "Проверять обновления",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Автоматически проверять наличие обновлений при открытии приложения",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = updateCheckIntervalHours != com.wdtt.client.UPDATE_CHECK_NEVER,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            val newInterval = if (enabled) {
                                com.wdtt.client.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
                            } else {
                                com.wdtt.client.UPDATE_CHECK_NEVER
                            }
                            settingsStore.saveUpdateCheckIntervalHours(newInterval)
                        }
                    }
                )
            }

            val notificationsEnabled = NotificationHelper.areNotificationsEnabled(context)
            if (!notificationsEnabled) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Уведомления отключены",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Без них не видно статус туннеля, капчу и вход VK. На Xiaomi/Samsung включите уведомления для Hoplet вручную.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(
                            onClick = {
                                (context as? com.wdtt.client.MainActivity)?.let { activity ->
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        !NotificationHelper.hasPostNotificationsPermission(context)
                                    ) {
                                        activity.requestNotificationPermissionIfNeeded()
                                    } else {
                                        activity.openNotificationSettings()
                                    }
                                } ?: NotificationHelper.openAppNotificationSettings(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Включить уведомления")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun VkHashSourceSettingsSection() {
        val localCount = localUniqueHashes.size
        val serverCount = serverVkHashesCache.split(Regex("[,\\s\\n]+")).count { it.isNotBlank() }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "VK Hash Source",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Выберите источник VK hash для следующего подключения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    SettingsStore.VK_HASH_SOURCE_SERVER to "SERVER",
                    SettingsStore.VK_HASH_SOURCE_LOCAL to "LOCAL",
                ).forEachIndexed { index, (value, title) ->
                    SegmentedButton(
                        selected = vkHashSource == value,
                        onClick = {
                            scope.launch {
                                settingsStore.saveVkHashSource(value)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 2
                        )
                    ) {
                        Text(title)
                    }
                }
            }
            Text(
                text = if (vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
                    if (localCount > 0) {
                        "Сейчас будут использованы локальные hash. Сохранено: $localCount."
                    } else {
                        "Сейчас выбран LOCAL, но локальные hash ещё не заданы."
                    }
                } else {
                    if (serverCount > 0) {
                        "Сейчас будут использованы серверные hash. Последний полученный набор: $serverCount."
                    } else {
                        "Сейчас будут использованы серверные hash. Локальные хранятся отдельно."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showLocalHashesDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Локальные VK Hash")
            }
            Text(
                "Локальные hash сохраняются отдельно и не изменяют серверные.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    fun VkConnectionSettingsSection() {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Вход через аккаунт VK",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Если анонимный режим не работает — включите и войдите в свой аккаунт VK. Подключение стабильнее.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HopletSwitch(
                    checked = vkAccountAuth,
                    enabled = !tunnelBusy && !vkAuthBusy,
                    onCheckedChange = { enabled ->
                        vkAccountAuth = enabled
                        scope.launch {
                            settingsStore.saveVkAuthMode(if (enabled) "account" else "anonymous")
                        }
                    }
                )
            }

            if (vkAccountAuth) {
                Button(
                    onClick = {
                        scope.launch {
                            vkAuthBusy = true
                            try {
                                val result = VkAuthWebViewManager.loginOnly(context)
                                result.onSuccess {
                                    vkLoggedIn = true
                                    Toast.makeText(
                                        context,
                                        "Вход в VK выполнен",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }.onFailure {
                                    vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
                                    Toast.makeText(
                                        context,
                                        "VK: ${it.message ?: "ошибка"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } finally {
                                vkAuthBusy = false
                            }
                        }
                    },
                    enabled = !tunnelBusy && !vkAuthBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (vkAuthBusy) "Ожидание входа VK..." else "Войти в VK")
                }
                if (vkLoggedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF43A047),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Вход в VK выполнен",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF43A047),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "Вход в VK не выполнен",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            AnimatedVisibility(visible = !vkAccountAuth) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Режим VK",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProtocolChip("Звонок", useVKCallsAuth, enabled = !tunnelBusy) {
                                scope.launch { settingsStore.saveVkAnonPath("vkcalls") }
                            }
                            ProtocolChip("Капча", !useVKCallsAuth, enabled = !tunnelBusy) {
                                scope.launch { settingsStore.saveVkAnonPath("legacy") }
                            }
                        }
                    }
                    if (useVKCallsAuth) {
                        Text(
                            "TURN через «Звонок», обычно без капчи. При ошибке — запасной режим «Капча».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = !useVKCallsAuth,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                HopletSwitch(
                                    checked = autoCaptchaEnabled,
                                    onCheckedChange = { enabled ->
                                        autoCaptchaEnabled = enabled
                                        scope.launch {
                                            if (enabled) {
                                                settingsStore.saveCaptchaMode("auto")
                                                settingsStore.saveCaptchaSolveMethod("auto")
                                            } else {
                                                val mode = if (useWVCaptcha) "wv" else "rjs"
                                                settingsStore.saveCaptchaMode(mode)
                                                settingsStore.saveCaptchaSolveMethod(if (mode == "wv" && isManualMode) "manual" else "auto")
                                            }
                                        }
                                    }
                                )
                            }

                            AnimatedVisibility(
                                visible = !autoCaptchaEnabled,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Метод обхода капчи",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ProtocolChip("WBV", useWVCaptcha, enabled = true) {
                                                useWVCaptcha = true
                                                isManualMode = wbvManualMode
                                                scope.launch {
                                                    settingsStore.saveCaptchaMode("wv")
                                                    settingsStore.saveCaptchaSolveMethod(if (wbvManualMode) "manual" else "auto")
                                                }
                                            }
                                            ProtocolChip("RJS", !useWVCaptcha, enabled = true, isError = false) {
                                                useWVCaptcha = false
                                                isManualMode = false
                                                scope.launch {
                                                    settingsStore.saveCaptchaMode("rjs")
                                                    settingsStore.saveCaptchaSolveMethod("auto")
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Режим обхода",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (useWVCaptcha) {
                                                ProtocolChip(
                                                    "РУЧ",
                                                    isManualMode,
                                                    enabled = true,
                                                    isError = false
                                                ) {
                                                    isManualMode = true
                                                    wbvManualMode = true
                                                    scope.launch { settingsStore.saveWbvCaptchaSolveMethod("manual") }
                                                }
                                                ProtocolChip(
                                                    "АВТ",
                                                    !isManualMode,
                                                    enabled = true,
                                                    isError = false
                                                ) {
                                                    isManualMode = false
                                                    wbvManualMode = false
                                                    scope.launch { settingsStore.saveWbvCaptchaSolveMethod("auto") }
                                                }
                                            } else {
                                                ProtocolChip(
                                                    "АВТ",
                                                    selected = true,
                                                    enabled = true,
                                                    isError = false
                                                ) {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun GeneralNetworkSettingsSection() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Сеть",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            VkHashSourceSettingsSection()
            VkConnectionSettingsSection()
            GoDnsSettingsSection(
                goDnsPreset = goDnsPreset,
                goDnsCustomInput = goDnsCustomInput,
                goDnsDohCustomInput = goDnsDohCustomInput,
                tunnelBusy = tunnelBusy,
                onPresetChange = { preset ->
                    scope.launch {
                        settingsStore.saveGoDns(
                            preset = preset,
                            custom = goDnsCustomInput,
                            dohCustom = goDnsDohCustomInput,
                        )
                    }
                },
                onCustomChange = { value ->
                    goDnsCustomInput = value
                    scope.launch {
                        settingsStore.saveGoDns(
                            preset = goDnsPreset,
                            custom = goDnsCustomInput,
                            dohCustom = goDnsDohCustomInput,
                        )
                    }
                },
                onDohCustomChange = { value ->
                    goDnsDohCustomInput = value
                    scope.launch {
                        settingsStore.saveGoDns(
                            preset = goDnsPreset,
                            custom = goDnsCustomInput,
                            dohCustom = goDnsDohCustomInput,
                        )
                    }
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Маскировка трафика",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "RTP-пакеты под аудио (OPUS) или видео (H.264) звонок VK. Сервер подстраивается под выбранный режим.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("audio" to "Аудио", "video" to "Видео").forEach { (mode, label) ->
                        FilterChip(
                            selected = obfsMode == mode,
                            onClick = {
                                if (!tunnelBusy) {
                                    scope.launch { settingsStore.saveObfsMode(mode) }
                                }
                            },
                            label = { Text(label) },
                            enabled = !tunnelBusy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (tunnelBusy) {
                    Text(
                        "Смена режима — после отключения туннеля",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    fun GeneralSettingsEntryCard() {
        AppSectionCard(
            modifier = Modifier.clickable { showGeneralSettingsDialog = true },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            color = AppCardDefaults.containerColor(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Общие настройки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Сеть • Поведение",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ═══ Dialogs ═══

    if (showPinDialog) {
        var pin by rememberSaveable { mutableStateOf("") }

        HopletAlertDialog(
            onDismissRequest = {
                showPinDialog = false
                pin = ""
            },
            title = {
                HopletSectionTitle("Вход администратора")
            },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 12) pin = it
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text("PIN")
                    },
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val url = URL("http://${PeerAddress.httpEndpoint(peerInput, effectiveServerDtlsPort)}/api/admin/login")

                                val conn = url.openConnection() as HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.doOutput = true
                                conn.connectTimeout = 5000
                                conn.readTimeout = 5000
                                conn.setRequestProperty("Content-Type", "application/json")

                                val body = JSONObject().apply {
                                    put("password", pin)
                                }

                                OutputStreamWriter(conn.outputStream).use {
                                    it.write(body.toString())
                                }

                                val response = conn.inputStream.bufferedReader().readText()
                                val json = JSONObject(response)

                                withContext(Dispatchers.Main) {
                                    if (json.optBoolean("success")) {
                                        val token = json.getString("token")

                                        AdminSession.saveToken(context, token)

                                        isAdminMode = true
                                        showPinDialog = false

                                        Toast.makeText(
                                            context,
                                            "Авторизация выполнена",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Неверный пароль",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }

                                conn.disconnect()

                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        e.message ?: "Ошибка подключения",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Войти")
                }
            }
        )
    }

    if (showRolePickerDialog) {
        val interfaceOptions = listOf(
            "user" to "Пользователь",
            "admin" to "Админ"
        )

        HopletAlertDialog(
            onDismissRequest = {
                showRolePickerDialog = false
            },
            title = {
                HopletSectionTitle("Режим приложения")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HopletDialogBodyText("Выберите режим приложения.")

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        interfaceOptions.forEachIndexed { index, (value, title) ->
                            SegmentedButton(
                                selected = interfaceRole == value,
                                onClick = {
                                    scope.launch {
                                        settingsStore.saveInterfaceRole(value)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = interfaceOptions.size
                                )
                            ) {
                                Text(title)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                HopletSecondaryButton(
                    onClick = {
                        showRolePickerDialog = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Закрыть")
                }
            }
        )
    }



    if (showSecretsDialog) {
        SecretsDialog(
            settingsStore = settingsStore,
            initialPassword = savedConnectionPassword,
            initialServerDtlsPort = serverDtlsPortInput,
            initialServerWgPort = serverWgPortInput,
            initialLocalPort = portInput,
            onSaved = { dtls, wg, local ->
                serverDtlsPortInput = dtls
                serverWgPortInput = wg
                portInput = local
            },
            onDismiss = { showSecretsDialog = false }
        )
    }

    if (showWelcomeDialog) {
        WelcomeDialog(
            onDismiss = {
                showWelcomeDialog = false
            }
        )
    }


    if (showLocalHashesDialog) {
        val localParts = localHashesRaw.split(Regex("[,\\s\\n]+")).filter { it.isNotEmpty() }
        val captchaModeForCheck by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
        val goDnsArgForCheck = remember(goDnsPreset, goDnsCustomInput, goDnsDohCustomInput) {
            when (SettingsStore.normalizeGoDnsPreset(goDnsPreset)) {
                "custom" -> {
                    val servers = SettingsStore.normalizeGoDnsServers(goDnsCustomInput)
                    if (servers.isNotEmpty()) "custom:$servers" else "yandex"
                }
                "doh-custom" -> {
                    val urls = SettingsStore.normalizeGoDnsDohUrls(goDnsDohCustomInput)
                    if (urls.isNotEmpty()) "doh:$urls" else "doh-yandex"
                }
                else -> goDnsPreset
            }
        }
        HashesDialog(
            title = "Локальные VK Хеши",
            hash1 = localParts.getOrElse(0) { "" },
            hash2 = localParts.getOrElse(1) { "" },
            hash3 = localParts.getOrElse(2) { "" },
            hash4 = localParts.getOrElse(3) { "" },
            captchaMode = captchaModeForCheck,
            vkAnonPath = vkAnonPath,
            goDnsArg = goDnsArgForCheck,
            preferServerHashesForCheck = false,
            onSave = { h1, h2, h3, h4 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val combined = normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4)

                scope.launch {
                    settingsStore.saveLocalVkHashes(combined)

                    val newHashCount = combined.split(",").filter { it.isNotBlank() && it.length >= 16 }.size.coerceAtLeast(1)
                    val newMax = SettingsStore.maxAnonymousWorkers(newHashCount)
                    if (!vkAccountAuth && workersInput > newMax) {
                        workersInput = newMax.toFloat()
                    }

                    if (vkHashSource == SettingsStore.VK_HASH_SOURCE_LOCAL) {
                        saveTunnelSettingsNow(activeHashesOverride = combined) {
                            showLocalHashesDialog = false
                        }
                    } else {
                        showLocalHashesDialog = false
                    }
                }
            },
            onDismiss = { showLocalHashesDialog = false }
        )
    }

    if (showHashesDialog) {
        val activeParts = activeHashesRaw.split(Regex("[,\\s\\n]+")).filter { it.isNotEmpty() }
        val captchaModeForCheck by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
        val goDnsArgForCheck = remember(goDnsPreset, goDnsCustomInput, goDnsDohCustomInput) {
            when (SettingsStore.normalizeGoDnsPreset(goDnsPreset)) {
                "custom" -> {
                    val servers = SettingsStore.normalizeGoDnsServers(goDnsCustomInput)
                    if (servers.isNotEmpty()) "custom:$servers" else "yandex"
                }
                "doh-custom" -> {
                    val urls = SettingsStore.normalizeGoDnsDohUrls(goDnsDohCustomInput)
                    if (urls.isNotEmpty()) "doh:$urls" else "doh-yandex"
                }
                else -> goDnsPreset
            }
        }
        HashesDialog(
            hash1 = activeParts.getOrElse(0) { "" },
            hash2 = activeParts.getOrElse(1) { "" },
            hash3 = activeParts.getOrElse(2) { "" },
            hash4 = activeParts.getOrElse(3) { "" },
            captchaMode = captchaModeForCheck,
            vkAnonPath = vkAnonPath,
            goDnsArg = goDnsArgForCheck,
            onSave = { h1, h2, h3, h4 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val combined = normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4)


                scope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("http://${PeerAddress.httpEndpoint(peerInput, effectiveServerDtlsPort)}/api/vkhashes")

                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000

                        conn.setRequestProperty("Content-Type", "application/json")
                        AdminSession.getToken(context)?.let { token ->
                            conn.setRequestProperty(
                                "Authorization",
                                "Bearer $token"
                            )
                        }

                        val body = JSONObject().apply {
                            put(
                                "hashes",
                                JSONArray(
                                    combined.split(",").filter { it.isNotBlank() }
                                )
                            )
                        }

                        conn.outputStream.use {
                            it.write(body.toString().toByteArray(Charsets.UTF_8))
                        }

                        conn.responseCode
                        conn.disconnect()

                    } catch (_: Exception) {
                    }
                }

                scope.launch {
                    val currentProfileIdStr = settingsStore.currentProfileId.first()
                    val currentProfile = profiles.firstOrNull { it.id == currentProfileIdStr }

                    if (currentProfileIdStr.isEmpty() || (currentProfile != null && currentProfile.useGlobalHashes)) {
                        settingsStore.saveGlobalVkHashes(combined)
                    }

                    // Coerce workers count to new max immediately!
                    val newHashCount = combined.split(",").filter { it.isNotBlank() && it.length >= 16 }.size.coerceAtLeast(1)
                    val newMax = SettingsStore.maxAnonymousWorkers(newHashCount)
                    if (!vkAccountAuth && workersInput > newMax) {
                        workersInput = newMax.toFloat()
                    }

                    settingsStore.saveServerVkHashesCache(combined)
                    saveTunnelSettingsNow(activeHashesOverride = combined) { showHashesDialog = false }
                }
            },
            onDismiss = { showHashesDialog = false }
        )
    }

    if (showAppSettingsDialog) {
        HopletDialog(
            onDismissRequest = {
                showGeneralSettingsDialog = false
                showAppSettingsDialog = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            HopletModalSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HopletSectionTitle("Настройки")
                        IconButton(onClick = {
                            showGeneralSettingsDialog = false
                            showAppSettingsDialog = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        GeneralSettingsEntryCard()

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // ═══ Раздел: О приложении ═══
                        Text(
                            "О приложении",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    aboutClickCount++
                                    if (aboutClickCount >= 5) {
                                        aboutClickCount = 0
                                        showRolePickerDialog = true
                                    }
                                },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                    val currentVersion = remember { "v${com.wdtt.client.BuildConfig.VERSION_NAME.removePrefix("v")}" }
                    var isCheckingUpdates by remember { mutableStateOf(false) }
                        var latestRelease by remember {
                            mutableStateOf<com.wdtt.client.AppReleaseInfo?>(null)
                        }
                        var downloadState by remember {
                            mutableStateOf<com.wdtt.client.DownloadState>(com.wdtt.client.DownloadState.Idle)
                        }

                        var isDownloading by remember {
                            mutableStateOf(false)
                        }
                        var lastDownloadRelease by remember {
                            mutableStateOf<com.wdtt.client.AppReleaseInfo?>(null)
                        }
                    val updateLatestVersion by settingsStore.updateLatestVersion.collectAsStateWithLifecycle(initialValue = "")
                    val updateLastError by settingsStore.updateLastError.collectAsStateWithLifecycle(initialValue = "")
                        LaunchedEffect(updateLatestVersion) {
                            if (
                                latestRelease == null &&
                                updateLatestVersion.isNotBlank() &&
                                isNewerVersion(currentVersion, updateLatestVersion, false)
                            ) {
                                scope.launch {
                                    try {
                                        latestRelease =
                                            com.wdtt.client.fetchLatestReleaseInfo(
                                                currentVersion,
                                                false
                                            )
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                showWelcomeDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Как начать")
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Hoplet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Версия $currentVersion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable {
                                        versionClickCount++

                                        if (versionClickCount >= 7) {
                                            versionClickCount = 0
                                            showPinDialog = true
                                        }
                                    }
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+uUh28784ZctiNTNi"))
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Telegram", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hoplet.ru"))
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Личный кабинет", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        Text(
                            text = "Проект Hoplet основан на оригинальном proxy-turn-vk-android, но развивается как самостоятельный форк.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )


                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Проверка обновлений
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val updateStatusText = remember(
                                isCheckingUpdates,
                                latestRelease,
                                updateLatestVersion,
                                updateLastError
                            ) {
                                when {
                                    isCheckingUpdates ->
                                        "Проверка обновлений..."

                                    latestRelease != null ->
                                        "⬇ Доступна ${latestRelease!!.versionTag}"

                                    updateLatestVersion.isNotBlank() ->
                                        "✓ Установлена последняя версия"

                                    updateLastError.isNotBlank() ->
                                        "Ошибка проверки"

                                    else ->
                                        "Не проверено"
                                }
                            }

                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    "Обновления",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = updateStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (latestRelease != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isCheckingUpdates = true

                                            try {
                                                val release = com.wdtt.client.fetchLatestReleaseInfo(
                                                    currentVersion,
                                                    false
                                                )

                                                if (release != null) {

                                                    val hasUpdate =
                                                        isNewerVersion(currentVersion, release.versionTag, false)

                                                    latestRelease =
                                                        if (hasUpdate) release else null

                                                    settingsStore.saveUpdateState(
                                                        lastCheckAt = System.currentTimeMillis(),
                                                        latestVersion = if (hasUpdate) release.versionTag else currentVersion,
                                                        error = ""
                                                    )

                                                    Toast.makeText(
                                                        context,
                                                        if (latestRelease != null)
                                                            "Доступна новая версия ${release.versionTag}"
                                                        else
                                                            "✓ Установлена последняя версия",
                                                        Toast.LENGTH_SHORT
                                                    ).show()

                                                } else {

                                                    latestRelease = null
                                                    lastDownloadRelease = null

                                                    settingsStore.saveUpdateState(
                                                        lastCheckAt = System.currentTimeMillis(),
                                                        latestVersion = "",
                                                        error = "Ошибка"
                                                    )

                                                    Toast.makeText(
                                                        context,
                                                        "Не удалось проверить обновления",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }

                                            } catch (e: Exception) {

                                                latestRelease = null

                                                Toast.makeText(
                                                    context,
                                                    "Ошибка: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()

                                            } finally {
                                                isCheckingUpdates = false
                                            }
                                        }
                                    },
                                    enabled = !isCheckingUpdates,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {

                                    if (isCheckingUpdates) {

                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )

                                    } else {

                                        Text(
                                            "Проверить",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = latestRelease != null,
                                    enter = fadeIn() + expandHorizontally(),
                                    exit = fadeOut() + shrinkHorizontally()
                                ) {

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {

                                        AnimatedVisibility(
                                            visible = downloadState is com.wdtt.client.DownloadState.Downloading,
                                            enter = fadeIn(),
                                            exit = fadeOut()
                                        ) {

                                            val progress =
                                                (downloadState as com.wdtt.client.DownloadState.Downloading)
                                                    .progress
                                                    .coerceIn(0f, 1f)

                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {

                                                LinearProgressIndicator(
                                                    progress = { progress },
                                                    modifier = Modifier.width(120.dp)
                                                )

                                                Spacer(Modifier.height(4.dp))

                                                Text(
                                                    "${(progress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {

                                                val release = latestRelease
                                                    ?: lastDownloadRelease
                                                    ?: return@OutlinedButton

                                                val url = release.downloadUrl ?: release.releaseUrl

                                                scope.launch {

                                                    if (release.downloadUrl == null) {
                                                        context.startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse(release.releaseUrl)
                                                            )
                                                        )
                                                        return@launch
                                                    }

                                                    isDownloading = true
                                                    lastDownloadRelease = release

                                                    com.wdtt.client.downloadUpdate(
                                                        context,
                                                        url,
                                                        release.versionTag
                                                    ).collect { state ->

                                                        downloadState = state

                                                        when (state) {

                                                            is com.wdtt.client.DownloadState.Finished -> {

                                                                isDownloading = false
                                                                downloadState = com.wdtt.client.DownloadState.Idle

                                                                latestRelease?.let { release ->
                                                                    scope.launch {
                                                                        settingsStore.saveUpdateState(
                                                                            lastCheckAt = System.currentTimeMillis(),
                                                                            latestVersion = release.versionTag,
                                                                            error = ""
                                                                        )
                                                                    }
                                                                }

                                                                latestRelease = null
                                                                lastDownloadRelease = null

                                                                com.wdtt.client.installApk(
                                                                    context,
                                                                    state.file
                                                                )
                                                            }

                                                            is com.wdtt.client.DownloadState.Error -> {

                                                                isDownloading = false
                                                                downloadState =
                                                                    com.wdtt.client.DownloadState.Idle

                                                                Toast.makeText(
                                                                    context,
                                                                    state.message,
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }

                                                            else -> {}
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isDownloading,
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(
                                                horizontal = 8.dp,
                                                vertical = 4.dp
                                            )
                                        ) {

                                            Text(
                                                when {
                                                    isDownloading -> "Загрузка..."
                                                    lastDownloadRelease != null &&
                                                            downloadState is com.wdtt.client.DownloadState.Idle -> "Повторить"
                                                    else -> "Скачать"
                                                },
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }


                                    }
                                }



                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Копия отчета
                        OutlinedButton(
                            onClick = {
                                val reportText = """
                                    Приложение: Hoplet
                                    Версия: $currentVersion
                                    Android API: ${Build.VERSION.SDK_INT}
                                    Архитектура (ABI): ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}
                                    Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
                                """.trimIndent()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Hoplet Report", reportText))
                                Toast.makeText(context, "Отчёт о системе скопирован!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Скопировать системный отчёт")
                        }

                        Spacer(Modifier.height(12.dp))

                            if (isAdminMode) {

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Text(
                                "Администрирование",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(16.dp))


                            OutlinedTextField(
                                value = peerInput,
                                onValueChange = {
                                    var cleaned = it.filter { c -> c != ' ' }
                                    if (PeerAddress.hasExplicitPort(cleaned)) {
                                        cleaned = PeerAddress.host(cleaned)
                                    }
                                    peerInput = cleaned
                                    scheduleSave()
                                },
                                label = { Text("IP сервера или домен") },
                                placeholder = { Text("31.76.102.29") },
                                singleLine = true,
                                isError = !isPeerValid && peerInput.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                )
                            )

                            Button(
                                onClick = {
                                    showHashesDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("VK Hash")
                            }
                            }

                        }

                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            showGeneralSettingsDialog = false
                            showAppSettingsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }

    if (showGeneralSettingsDialog) {
        HopletDialog(
            onDismissRequest = { showGeneralSettingsDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            HopletModalSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HopletSectionTitle("Общие настройки")
                        IconButton(onClick = { showGeneralSettingsDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        GeneralBehaviorSettingsSection()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        GeneralNetworkSettingsSection()
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showGeneralSettingsDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }

    val tunnelSecretsMissing = savedConnectionPassword.isBlank()
    val connectionLifecycle = connectionProgressState.lifecycle
    val heroStatusLabel = when {
        tunnelRunning -> "Подключено"
        isConnecting -> "Подключение"
        connectionLifecycle == ConnectionLifecycle.ERROR -> "Ошибка"
        cooldownSeconds > 0 -> "Пауза"
        else -> "Готово"
    }
    val heroStatusColor = when {
        tunnelRunning -> MaterialTheme.colorScheme.primary
        isConnecting -> MaterialTheme.colorScheme.secondary
        connectionLifecycle == ConnectionLifecycle.ERROR -> MaterialTheme.colorScheme.error
        cooldownSeconds > 0 -> WDTTColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val heroStatusIcon = when {
        tunnelRunning -> Icons.Default.Verified
        isConnecting -> Icons.Default.PowerSettingsNew
        connectionLifecycle == ConnectionLifecycle.ERROR -> Icons.Default.Error
        cooldownSeconds > 0 -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
    val heroTitle = when {
        tunnelRunning -> "Туннель активен"
        isConnecting -> "Идет подключение"
        connectionLifecycle == ConnectionLifecycle.ERROR -> "Подключение не удалось"
        tunnelSecretsMissing -> "Нужны секреты"
        !isValid -> "Проверьте параметры"
        cooldownSeconds > 0 -> "Небольшая пауза"
        else -> "Готово к запуску"
    }
    val heroSubtitle = when {
        tunnelRunning -> "TURN и TUN работают в текущем профиле."
        isConnecting -> connectionProgressState.statusText
        connectionLifecycle == ConnectionLifecycle.ERROR -> connectionProgressState.errorReason ?: "Повторите подключение еще раз."
        tunnelSecretsMissing -> "Добавьте секрет подключения перед запуском Tunnel."
        !isValid -> "Заполните обязательные параметры перед подключением."
        cooldownSeconds > 0 -> "Повторное подключение будет доступно через $cooldownSeconds c."
        currentProfileName.isNotEmpty() -> "Текущий профиль: $currentProfileName"
        else -> "Выберите профиль и запустите подключение."
    }
    val heroButtonLabel = when {
        tunnelBusy -> "Остановить"
        cooldownSeconds > 0 -> "Подождите"
        else -> "Подключить"
    }
    val heroButtonCaption = when {
        tunnelRunning -> uptimeText ?: "Сеанс активен"
        isConnecting -> "Идет запуск"
        connectionLifecycle == ConnectionLifecycle.ERROR -> "Повторить"
        cooldownSeconds > 0 -> "$cooldownSeconds c"
        else -> " "
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Tunnel · Private Network",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalIconButton(onClick = { showAppSettingsDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки Tunnel"
                )
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            color = AppCardDefaults.containerColor(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TunnelStatusBadge(
                    label = heroStatusLabel,
                    icon = heroStatusIcon,
                    containerColor = heroStatusColor.copy(alpha = 0.14f),
                    contentColor = heroStatusColor,
                    modifier = Modifier.weight(1f)
                )
                TunnelMetricTile(
                    label = "Потоки",
                    value = activeWorkers.toString(),
                    modifier = Modifier.widthIn(min = 84.dp)
                )
            }

            Text(
                text = heroTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = heroSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (profiles.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TunnelProfileButton(
                            title = if (currentProfileName.isNotEmpty()) currentProfileName else "Быстрый выбор профиля",
                            onClick = { expanded = true },
                            modifier = Modifier.weight(1f)
                        )
                        TunnelCompactActionButton(
                            text = "Секреты",
                            icon = Icons.Default.Key,
                            onClick = { showSecretsDialog = true },
                            isAlert = tunnelSecretsMissing
                        )
                    }

                    HopletDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        profiles.forEach { p ->
                            HopletDropdownMenuItem(
                                text = {
                                    Text(
                                        text = p.name,
                                        fontWeight = if (p.id == currentProfileId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (p.id == currentProfileId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        profilesStore.applyProfile(context, p.id)

                                        peerInput = PeerAddress.host(settingsStore.peer.first())
                                        portInput = settingsStore.listenPort.first().toString()
                                        workersInput = roundToGroup(
                                            settingsStore.workersPerHash.first().toFloat(),
                                            dynamicMaxWorkers,
                                            vkAccountAuth
                                        )

                                        if (tunnelBusy) {
                                            context.startService(
                                                Intent(
                                                    context,
                                                    TunnelService::class.java
                                                ).apply { action = "STOP" }
                                            )
                                            kotlinx.coroutines.delay(800)
                                            requestVpnAndStart()
                                        }

                                        Toast.makeText(
                                            context,
                                            "Профиль «${p.name}» применен!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (p.id == currentProfileId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TunnelCompactActionButton(
                        text = "Секреты",
                        icon = Icons.Default.Key,
                        onClick = { showSecretsDialog = true },
                        isAlert = tunnelSecretsMissing
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TunnelConnectionButton(
                    title = heroButtonLabel,
                    subtitle = heroButtonCaption,
                    icon = if (tunnelBusy) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                    enabled = (isValid && cooldownSeconds == 0) || tunnelBusy,
                    active = tunnelRunning,
                    connecting = isConnecting,
                    error = connectionLifecycle == ConnectionLifecycle.ERROR,
                    onClick = {
                        if (tunnelBusy) {
                            context.startService(
                                Intent(context, TunnelService::class.java).apply { action = "STOP" }
                            )
                        } else {
                            if (autoSwitchToLogs) {
                                onConnectRequested()
                            }
                            requestVpnAndStart()
                        }
                    }
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = tunnelRunning && showSpeedGraph,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
            ) {
                SpeedGraphCard(speedHistory = speedHistory, currentSpeed = currentSpeedKbps)
            }
        }

        ConnectionProgressCard(
            state = connectionProgressState,
            activeConnections = activeWorkers,
            trafficText = trafficSummaryText,
            uptimeText = uptimeText
        )

        AnimatedVisibility(
            visible = !tunnelRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            AppSectionCard(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                color = AppCardDefaults.containerColor(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Мощность",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (vkAccountAuth) {
                                "Точная настройка количества потоков."
                            } else {
                                "Шаг по $WORKERS_PER_GROUP потоков для подключения."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TunnelStatusBadge(
                        label = currentWorkers.toInt().toString(),
                        icon = Icons.Default.Tag,
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }

                val maxWorkers = dynamicMaxWorkers
                val minWorkers = if (vkAccountAuth) 1f else WORKERS_PER_GROUP.toFloat()
                val workerStep = if (vkAccountAuth) 1f else WORKERS_PER_GROUP.toFloat()
                val currentWorkersVal = if (vkAccountAuth) {
                    currentWorkers.coerceIn(1f, maxWorkers).roundToInt().toFloat()
                } else {
                    roundToGroup(currentWorkers.coerceIn(minWorkers, maxWorkers), maxWorkers)
                }

                CompactSteppedSlider(
                    value = currentWorkersVal,
                    onValueChange = { raw ->
                        workersInput = if (vkAccountAuth) {
                            raw.coerceIn(1f, maxWorkers).roundToInt().toFloat()
                        } else {
                            roundToGroup(raw, maxWorkers)
                        }
                        scheduleSave()
                    },
                    valueRange = minWorkers..maxWorkers,
                    stepSize = workerStep,
                    enabled = !tunnelBusy,
                    modifier = Modifier.fillMaxWidth()
                )

                PowerRecommendationInfoBlock(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TunnelStatusBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TunnelMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = AppCardDefaults.containerColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TunnelProfileButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(16.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "Профиль",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TunnelCompactActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isAlert: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isAlert) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val contentColor = if (isAlert) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isAlert) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    }

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TunnelConnectionButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    active: Boolean,
    connecting: Boolean,
    error: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val disconnectedGlow = Color(0xFF6EDDD5)
    val disconnectedSurface = Color(0xFF1F6F69)
    val disconnectedEdge = Color(0xFF0F4D4A)
    val connectedGlow = Color(0xFFAF5A64)
    val connectedSurface = Color(0xFF7A3D48)
    val connectedEdge = Color(0xFF4B1F28)
    val iconTint = Color(0xFF6EDDD5)
    val subtitleTint = Color(0xFF9FE8E0)
    val connectedTitleTint = Color(0xFFF1E1CF)
    val baseColor = when {
        error -> MaterialTheme.colorScheme.error
        active -> connectedGlow
        connecting -> MaterialTheme.colorScheme.secondary
        enabled -> disconnectedGlow
        else -> MaterialTheme.colorScheme.outline
    }
    val haloColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(280),
        label = "tunnel_button_halo"
    )
    val haloAlpha by animateFloatAsState(
        targetValue = when {
            error -> 0.16f
            active -> 0.18f
            connecting -> 0.14f
            enabled -> 0.14f
            else -> 0.08f
        },
        animationSpec = tween(280),
        label = "tunnel_button_halo_alpha"
    )
    val haloScale by animateFloatAsState(
        targetValue = when {
            active -> 1.16f
            connecting -> 1.1f
            error -> 1.08f
            else -> 1f
        },
        animationSpec = tween(280),
        label = "tunnel_button_scale"
    )
    val surfaceColor by animateColorAsState(
        targetValue = when {
            error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.84f)
            active -> connectedSurface.copy(alpha = 0.80f)
            connecting -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
            enabled -> disconnectedSurface.copy(alpha = 0.80f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_surface"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            error -> MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
            active -> connectedGlow.copy(alpha = 0.38f)
            connecting -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
            enabled -> disconnectedGlow.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_border"
    )
    val innerBorderColor by animateColorAsState(
        targetValue = when {
            error -> Color(0xFFFFF2EC).copy(alpha = 0.075f)
            active -> Color(0xFFFFF0E3).copy(alpha = 0.085f)
            connecting -> Color.White.copy(alpha = 0.085f)
            enabled -> Color.White.copy(alpha = 0.10f)
            else -> Color.White.copy(alpha = 0.065f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_inner_border"
    )
    val centerTint by animateColorAsState(
        targetValue = when {
            error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f)
            active -> connectedSurface.copy(alpha = 0.24f)
            connecting -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
            enabled -> disconnectedSurface.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_center_tint"
    )
    val edgeTint by animateColorAsState(
        targetValue = when {
            error -> MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
            active -> connectedEdge.copy(alpha = 0.26f)
            connecting -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)
            enabled -> disconnectedEdge.copy(alpha = 0.24f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_edge_tint"
    )
    val titleColor by animateColorAsState(
        targetValue = when {
            error -> Color.White
            active -> connectedTitleTint
            connecting -> Color.White
            enabled -> Color.White
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_title"
    )
    val subtitleColor by animateColorAsState(
        targetValue = when {
            error -> subtitleTint
            active -> subtitleTint
            connecting -> subtitleTint
            enabled -> subtitleTint
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_subtitle"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            error -> iconTint
            active -> iconTint
            connecting -> iconTint
            enabled -> iconTint
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        animationSpec = tween(280),
        label = "tunnel_button_icon"
    )

    Box(
        modifier = modifier
            .size(176.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .scale(haloScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            haloColor.copy(alpha = haloAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = if (active || connecting) 10.dp else 4.dp,
            modifier = Modifier.size(160.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(7.dp)
                    .clip(CircleShape)
                    .drawWithCache {
                        val baseBrush = Brush.radialGradient(
                            colors = listOf(
                                centerTint.copy(alpha = 0.96f),
                                centerTint.copy(alpha = 0.82f),
                                edgeTint.copy(alpha = 0.92f),
                                edgeTint.copy(alpha = 1f)
                            ),
                            center = Offset(size.width * 0.42f, size.height * 0.34f),
                            radius = size.minDimension * 0.98f
                        )
                        val glossBrush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color.White.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.34f, size.height * 0.22f),
                            radius = size.minDimension * 0.62f
                        )
                        val sheenBrush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.11f)
                            ),
                            start = Offset.Zero,
                            end = Offset(0f, size.height)
                        )
                        val ringWidth = 1.dp.toPx()

                        onDrawWithContent {
                            drawCircle(brush = baseBrush)
                            drawCircle(brush = glossBrush)
                            drawRect(brush = sheenBrush)
                            drawCircle(color = innerBorderColor, style = Stroke(width = ringWidth))
                            drawContent()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = subtitleColor
                    )
                }
            }
        }
    }
}

// ═══ Reusable mode chip ═══
@Composable
private fun ProtocolChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            selectedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun CompactSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    fun snap(raw: Float): Float {
        val min = valueRange.start
        val max = valueRange.endInclusive
        val snapped = (((raw - min) / stepSize).roundToInt() * stepSize) + min
        return snapped.coerceIn(min, max)
    }

    val steps = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt() - 1).coerceAtLeast(0)
    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val valueLabel = clampedValue.toInt().toString()

    Slider(
        value = clampedValue,
        onValueChange = { onValueChange(snap(it)) },
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = "Количество потоков"
            stateDescription = "$valueLabel, от ${valueRange.start.toInt()} до ${valueRange.endInclusive.toInt()}"
        }
    )
}

@Composable
private fun PowerRecommendationInfoBlock(
    modifier: Modifier = Modifier
) {
    val accentColor = HopletTheme.colors.accent
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f))
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.padding(7.dp).size(14.dp)
                )
            }

            Text(
                modifier = Modifier.weight(1f),
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append("Рекомендуется не более ")
                    withStyle(
                        style = androidx.compose.ui.text.SpanStyle(
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append("36")
                    }
                    append(" потоков.\nПовышение этого значения увеличивает расход заряда батареи.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

// ═══ Important Info Dialog ═══
@Composable
fun ImportantInfoDialog(onDismiss: () -> Unit) {
    HopletDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        HopletModalSurface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(8.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HopletSectionTitle("Важная информация")
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(16.dp))

                InfoSection("Капча ВК",
                    "По умолчанию в приложении установлен ручной режим (WBV + РУЧ), но его можно заменить на RJS-АВТ. Это продвинутый автоматический метод решения капчи без всплывающих окон и участия человека, основанный на реверс-инжиниринге JS-кода капчи. Он имитирует действия пользователя в фоновом режиме, обеспечивая бесперебойную работу.\n\nВАЖНО: Если в вашем случае RJS не проходит капчу или выдает ошибки (проблемы со связью или изменения на стороне ВК) — переключитесь обратно в ручной режим."
                )
                InfoSection("Как решать капчу",
                    "Она не сложная: нужно просто потянуть слайдер вправо так, чтобы все элементы (обычно это 3 слова) идеально сошлись в пазле."
                )
                InfoSection("Сетевое окружение",
                    "Отключите другие VPN/Прокси и «Приватный DNS» перед использованием."
                )
                InfoSection("Связь потоков и капч",
                    "Рекомендую выбирать 12-36 потока для меньшего количества капч. Если вам всё равно на частоту ввода капчи в фоне — ставьте 48 и более ради скорости."
                )

                Spacer(Modifier.height(20.dp))
                HopletPrimaryButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Понятно")
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
}

// Округление до ближайшего кратного WORKERS_PER_GROUP (анонимный режим) или 1..max (аккаунт VK)
private fun roundToGroup(value: Float, maxW: Float = 96f, accountMode: Boolean = false): Float {
    if (accountMode || maxW < WORKERS_PER_GROUP) {
        return value.coerceIn(1f, maxW.coerceAtLeast(1f))
    }
    val rounded = (Math.round(value / WORKERS_PER_GROUP) * WORKERS_PER_GROUP).toFloat()
    return rounded.coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)
}

// ═══ Модальное окно хешей ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashesDialog(
    title: String = "VK Хеши",
    hash1: String,
    hash2: String,
    hash3: String,
    hash4: String,
    captchaMode: String = "auto",
    vkAnonPath: String = "vkcalls",
    goDnsArg: String = "yandex",
    preferServerHashesForCheck: Boolean = true,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var h1 by remember { mutableStateOf(hash1) }
    var h2 by remember { mutableStateOf(hash2) }
    var h3 by remember { mutableStateOf(hash3) }
    var h4 by remember { mutableStateOf(hash4) }
    var isChecking by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkResults by remember { mutableStateOf<Map<Int, com.wdtt.client.HashCheckResult>>(emptyMap()) }
    var menuExpanded by remember { mutableStateOf(false) }

    val currentHashes = remember(h1, h2, h3, h4) {
        listOf(h1, h2, h3, h4).map { stripVkUrlStatic(it) }
    }

    var serverHashes by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(preferServerHashesForCheck) {
        if (!preferServerHashesForCheck) {
            serverHashes = emptyList()
            return@LaunchedEffect
        }
        val server = PeerAddress.httpEndpoint(
            SettingsStore(context).peer.first(),
            56000
        )

        serverHashes = ServerVkHashes.load(
            server = server,
            token = AdminSession.getToken(context) ?: ""
        )
    }

    val filledHashes = remember(currentHashes) {
        currentHashes.filter { it.isNotBlank() }
    }
    val checkableHashes = remember(currentHashes, serverHashes, preferServerHashesForCheck) {

        val hashes =
            if (preferServerHashesForCheck && serverHashes.isNotEmpty())
                serverHashes
            else
                currentHashes

        hashes.mapIndexedNotNull { index, hash ->
            if (hash.length >= 16)
                index + 1 to hash
            else
                null
        }
    }
    val completedChecks = checkResults.values.count {
        it.status !in setOf("pending", "checking", "solving_captcha")
    }
    val okCount = checkResults.values.count { it.status == "ok" }
    val badCount = checkResults.values.count {
        it.status in setOf("dead", "error", "network", "limited", "captcha")
    }
    val tunnelBusy by TunnelManager.enabled.collectAsStateWithLifecycle()
    val vkLoggedIn = remember { mutableStateOf(VkAuthWebViewManager.hasVkSessionCookie()) }
    LaunchedEffect(Unit) {
        vkLoggedIn.value = VkAuthWebViewManager.hasVkSessionCookie()
    }
    val progress = if (checkableHashes.isEmpty()) {
        0f
    } else {
        completedChecks.toFloat() / checkableHashes.size.toFloat()
    }

    fun startHashGeneration() {
        if (isGenerating || isChecking || tunnelBusy || filledHashes.size >= SettingsStore.MAX_VK_HASHES) return
        if (!VkAuthWebViewManager.hasVkSessionCookie()) {
            Toast.makeText(context, "Сначала войдите в аккаунт VK", Toast.LENGTH_SHORT).show()
            return
        }
        checkJob = scope.launch {
            isGenerating = true
            val emptyCount = (SettingsStore.MAX_VK_HASHES - filledHashes.size).coerceAtLeast(1)
            try {
                val result = withContext(Dispatchers.IO) {
                    com.wdtt.client.VkCallHashGenerator.generateHashes(context, emptyCount)
                }
                result.fold(
                    onSuccess = { newHashes ->
                        val slots = mutableListOf(h1, h2, h3, h4)
                        newHashes.forEach { hash ->
                            val idx = slots.indexOfFirst { it.isBlank() }
                            if (idx >= 0) slots[idx] = hash
                        }
                        h1 = slots[0]
                        h2 = slots[1]
                        h3 = slots[2]
                        h4 = slots[3]
                        Toast.makeText(
                            context,
                            "Создано хешей: ${newHashes.size}",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            context,
                            e.message ?: "Не удалось создать звонок VK",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } finally {
                isGenerating = false
                checkJob = null
            }
        }
    }

    fun cancelHashCheck(updateUi: Boolean = true) {
        checkJob?.cancel()
        checkJob = null
        ManlCaptchaWebViewManager.cancelCaptcha()
        if (updateUi) {
            isChecking = false
            val active = checkResults.filterValues {
                it.status in setOf("pending", "checking", "solving_captcha")
            }
            if (active.isNotEmpty()) {
                checkResults = checkResults + active.mapValues { (_, r) ->
                    r.copy(status = "cancelled", message = "Остановлено")
                }
            }
        }
    }

    fun closeDialog() {
        cancelHashCheck()
        onDismiss()
    }

    fun startHashCheck() {
        if (isChecking || tunnelBusy || checkableHashes.isEmpty()) return
        checkJob = scope.launch {
            isChecking = true
            checkResults = checkableHashes.associate { (slot, hash) ->
                slot to com.wdtt.client.HashCheckResult(
                    hash = hash,
                    status = "pending",
                    message = "В очереди"
                )
            }
            try {
                val results = withContext(Dispatchers.IO) {
                    com.wdtt.client.HashCheckHelper.checkHashes(
                        context = context,
                        hashes = checkableHashes,
                        captchaMode = captchaMode,
                        vkAnonPath = vkAnonPath,
                        goDnsArg = goDnsArg,
                        onUpdate = { slot, result ->
                            scope.launch(Dispatchers.Main) {
                                checkResults = checkResults + (slot to result)
                            }
                        }
                    )
                }
                checkResults = results
            } catch (e: Exception) {
                val message = e.message ?: "Сбой диагностики"
                checkResults = checkableHashes.associate { (slot, hash) ->
                    slot to (checkResults[slot] ?: com.wdtt.client.HashCheckResult(
                        hash = hash,
                        status = "error",
                        message = message
                    ))
                }
            } finally {
                isChecking = false
                checkJob = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { cancelHashCheck(updateUi = false) }
    }

    HopletDialog(
        onDismissRequest = { closeDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        HopletModalSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxHeight(0.92f),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── Header (fixed) ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tag,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = filledHashes.isNotEmpty(),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                        }
                        HopletDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            HopletDropdownMenuItem(
                                text = { Text("Копировать через запятую") },
                                onClick = {
                                    menuExpanded = false
                                    copyText(context, "VK Хеши", filledHashes.joinToString(","))
                                },
                                enabled = filledHashes.isNotEmpty()
                            )
                            HopletDropdownMenuItem(
                                text = { Text("Копировать по строкам") },
                                onClick = {
                                    menuExpanded = false
                                    copyText(context, "VK Хеши", filledHashes.joinToString("\n"))
                                },
                                enabled = filledHashes.isNotEmpty()
                            )
                            HopletDropdownMenuItem(
                                text = { Text("Сбросить статусы") },
                                onClick = {
                                    menuExpanded = false
                                    if (!isChecking) checkResults = emptyMap()
                                },
                                enabled = checkResults.isNotEmpty() && !isChecking
                            )
                        }
                    }
                    IconButton(onClick = { closeDialog() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                AnimatedVisibility(visible = isChecking || checkResults.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { if (isChecking) progress.coerceIn(0.05f, 1f) else 1f },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = when {
                                badCount > 0 && !isChecking -> MaterialTheme.colorScheme.error
                                okCount > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        )
                        Text(
                            if (isChecking) {
                                "$completedChecks/${checkableHashes.size}"
                            } else {
                                "✓$okCount ✕$badCount"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ─── Slots (scroll) ───
                Column(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vkLoggedIn.value) {
                        HopletSecondaryButton(
                            onClick = { startHashGeneration() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating && !isChecking && !tunnelBusy && filledHashes.size < SettingsStore.MAX_VK_HASHES,
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isGenerating) "Создаём звонок VK…" else "Сгенерировать хеш VK",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "Создаёт новый групповой звонок через ваш аккаунт VK и подставляет хеш автоматически",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Для автогенерации хешей включите «Вход через аккаунт VK» на вкладке «Туннель» и войдите в VK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    listOf(
                        Triple("1", h1) { v: String -> h1 = v },
                        Triple("2", h2) { v: String -> h2 = v },
                        Triple("3", h3) { v: String -> h3 = v },
                        Triple("4", h4) { v: String -> h4 = v }
                    ).forEachIndexed { idx, (label, value, onChange) ->
                        HashSlotCard(
                            slot = idx + 1,
                            label = label,
                            required = idx == 0,
                            value = value,
                            result = checkResults[idx + 1],
                            enabled = !isChecking && !isGenerating,
                            onValueChange = { raw ->
                                onChange(stripVkUrlStatic(raw.filter { c -> c != ' ' && c != '\n' }))
                            },
                            onCopy = {
                                val cleaned = stripVkUrlStatic(value)
                                if (cleaned.isNotBlank()) copyText(context, "VK Хеш ${idx + 1}", cleaned)
                            }
                        )
                    }
                    if (tunnelBusy) {
                        Text(
                            "Проверка недоступна при активном туннеле.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // ─── Footer (fixed) ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isChecking) {
                        HopletSecondaryButton(
                            onClick = { cancelHashCheck() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Стоп", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    } else {
                        HopletSecondaryButton(
                            onClick = { startHashCheck() },
                            modifier = Modifier.weight(1f),
                            enabled = checkableHashes.isNotEmpty() && !tunnelBusy,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.Verified, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (tunnelBusy) "Занято" else "Проверить",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                    HopletPrimaryButton(
                        onClick = {
                            cancelHashCheck(updateUi = false)
                            onSave(h1, h2, h3, h4)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = h1.isNotBlank() && h1.length >= 16 && !isChecking,
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HashSlotCard(
    slot: Int,
    label: String,
    required: Boolean,
    value: String,
    result: com.wdtt.client.HashCheckResult?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCopy: () -> Unit,
) {
    val cleaned = stripVkUrlStatic(value)
    val isShort = cleaned.isNotBlank() && cleaned.length < 16
    val statusText = when {
        isShort -> "короткий"
        result == null -> null
        else -> when (result.status) {
            "ok" -> "живой"
            "dead" -> "закрыт"
            "captcha" -> "капча"
            "limited" -> "лимит"
            "network" -> "сеть"
            "checking", "solving_captcha" -> "…"
            "pending" -> "…"
            "cancelled" -> "стоп"
            else -> "ошибка"
        }
    }
    val statusColor = when {
        isShort -> MaterialTheme.colorScheme.error
        result?.status == "ok" -> MaterialTheme.colorScheme.primary
        result?.status in setOf("checking", "pending", "solving_captcha", "captcha", "limited") ->
            MaterialTheme.colorScheme.tertiary
        result != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        isError = isShort || result?.status in setOf("dead", "error", "network"),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (required) "Слот $label *" else "Слот $label")
                if (statusText != null) {
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(statusText, color = statusColor, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        placeholder = { Text("ссылка или хеш") },
        supportingText = when {
            isShort -> {{ Text("мин. 16 символов", color = MaterialTheme.colorScheme.error) }}
            result != null && result.status !in setOf("pending", "checking", "solving_captcha") -> {
                { Text(result.message, maxLines = 1) }
            }
            else -> null
        },
        trailingIcon = {
            if (cleaned.isNotBlank()) {
                IconButton(onClick = onCopy, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = HopletModalDefaults.fieldShape,
        colors = hopletOutlinedTextFieldColors()
    )
}

private fun copyText(context: android.content.Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

// ═══ Модальное окно секретов ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    initialServerDtlsPort: String,
    initialServerWgPort: String,
    initialLocalPort: String,
    onSaved: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passwordInput by rememberSaveable { mutableStateOf(initialPassword) }
    var serverDtlsPort by rememberSaveable { mutableStateOf(initialServerDtlsPort.ifBlank { "56000" }) }
    var serverWgPort by rememberSaveable { mutableStateOf(initialServerWgPort.ifBlank { "56001" }) }
    var localPort by rememberSaveable { mutableStateOf(initialLocalPort.ifBlank { "9000" }) }

    fun normalizePort(value: String, fallback: String): String {
        return value.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: fallback
    }

    HopletDialog(onDismissRequest = onDismiss) {
        HopletModalSurface(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HopletSectionTitle("Секреты")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Заданный пароль туннеля") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Порты", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Стандартные: DTLS 56000, WireGuard 56001, локальный 9000",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverDtlsPort,
                    onValueChange = { serverDtlsPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт сервера DTLS") },
                    placeholder = { Text("56000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverWgPort,
                    onValueChange = { serverWgPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт сервера WireGuard") },
                    placeholder = { Text("56001") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Локальный порт VPN") },
                    placeholder = { Text("9000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(20.dp))

                HopletPrimaryButton(
                    onClick = {
                        val finalDtls = normalizePort(serverDtlsPort, "56000")
                        val finalWg = normalizePort(serverWgPort, "56001")
                        val finalLocal = normalizePort(localPort, "9000")
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                            settingsStore.savePorts(finalDtls.toInt(), finalWg.toInt(), finalLocal.toInt())
                            val customPorts = finalDtls != "56000" || finalWg != "56001" || finalLocal != "9000"
                            if (customPorts) {
                                settingsStore.saveManualPortsEnabled(true)
                            }
                            onSaved(finalDtls, finalWg, finalLocal)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// extension
private fun androidx.compose.ui.graphics.Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

@Composable
private fun SpeedGraphCard(speedHistory: List<Float>, currentSpeed: Float) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val cardBg = if (isDark) colors.surface.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.5f)
    val cardBorder = colors.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.2f)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Скорость:",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        text = formatSpeed(currentSpeed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                      ) {
                          Box(
                              modifier = Modifier
                                  .size(6.dp)
                                  .clip(androidx.compose.foundation.shape.CircleShape)
                                  .background(colors.primary)
                          )
                          Text(
                              text = "LIVE",
                              style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                              fontWeight = FontWeight.Bold,
                              color = colors.primary
                          )
                      }
                  }
              }

              Box(
                  modifier = Modifier
                      .fillMaxWidth()
                      .height(44.dp)
              ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    
                    if (speedHistory.size > 1) {
                        val maxVal = speedHistory.maxOrNull()?.coerceAtLeast(10f) ?: 10f
                        val stepX = width / (speedHistory.size - 1)
                        
                        val path = Path()
                        path.moveTo(0f, height - (speedHistory[0] / maxVal) * height)
                        
                        for (i in 1 until speedHistory.size) {
                            val x = i * stepX
                            val y = height - (speedHistory[i] / maxVal) * height
                            val prevX = (i - 1) * stepX
                            val prevY = height - (speedHistory[i - 1] / maxVal) * height
                            
                            val cx1 = prevX + stepX / 2f
                            val cy1 = prevY
                            val cx2 = prevX + stepX / 2f
                            val cy2 = y
                            
                            path.cubicTo(cx1, cy1, cx2, cy2, x, y)
                        }
                        
                        val fillPath = Path().apply {
                            addPath(path)
                            lineTo(width, height)
                            lineTo(0f, height)
                            close()
                        }
                        
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    colors.primary.copy(alpha = 0.24f),
                                    Color.Transparent
                                )
                            )
                        )
                        
                        drawPath(
                            path = path,
                            color = colors.primary,
                            style = Stroke(
                                width = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        )
                        
                        val lastY = height - (speedHistory.last() / maxVal) * height
                        drawCircle(
                            color = colors.primary,
                            radius = 4.5.dp.toPx(),
                            center = Offset(width, lastY)
                        )
                        drawCircle(
                            color = colors.primary.copy(alpha = 0.35f),
                            radius = 9.dp.toPx(),
                            center = Offset(width, lastY)
                        )
                    }
                }
            }
        }
    }
}

private fun formatSpeed(kbps: Float): String {
    return when {
        kbps >= 1024f -> String.format("%.2f МБ/с", kbps / 1024f)
        else -> String.format("%.1f КБ/с", kbps)
    }
}

private fun parseTrafficMb(stats: String): Double? {
    val match = Regex("Трафик:\\s*([\\d.,]+)").find(stats)
    return match?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoDnsSettingsSection(
    goDnsPreset: String,
    goDnsCustomInput: String,
    goDnsDohCustomInput: String,
    tunnelBusy: Boolean,
    onPresetChange: (String) -> Unit,
    onCustomChange: (String) -> Unit,
    onDohCustomChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isCheckingDns by remember { mutableStateOf(false) }
    var checkResultText by remember { mutableStateOf<String?>(null) }
    var checkResultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val udpPresets = remember {
        listOf(
            "yandex" to "Яндекс DNS",
            "cloudflare" to "Cloudflare",
            "google" to "Google DNS",
            "custom" to "Свой DNS",
        )
    }
    val dohPresets = remember {
        listOf(
            "doh-yandex" to "Яндекс DoH",
            "doh-cloudflare" to "Cloudflare DoH",
            "doh-google" to "Google DoH",
            "doh-custom" to "Свой DoH",
        )
    }

    fun presetSubtitle(preset: String): String {
        return when (preset) {
            "custom" -> SettingsStore.goDnsDisplay("custom", goDnsCustomInput).servers
                .joinToString(" · ")
                .ifBlank { "укажите IP ниже" }
            "doh-custom" -> SettingsStore.goDnsDisplay("doh-custom", goDnsDohCustomInput).servers
                .joinToString(" · ")
                .ifBlank { "укажите URL ниже" }
            else -> SettingsStore.goDnsDisplay(preset).servers.joinToString(" · ")
        }
    }

    val display = SettingsStore.goDnsDisplay(
        goDnsPreset,
        if (goDnsPreset == "doh-custom") goDnsDohCustomInput else goDnsCustomInput,
    )
    val isDohSelected = SettingsStore.isDohGoDnsPreset(goDnsPreset)
    val canCheck = !isCheckingDns && when (goDnsPreset) {
        "custom" -> goDnsCustomInput.isNotBlank()
        "doh-custom" -> goDnsDohCustomInput.isNotBlank()
        else -> true
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DNS для VK",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Резолв login.vk.ru и api.vk.me при подключении.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!tunnelBusy) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = display.title,
                onValueChange = {},
                readOnly = true,
                enabled = !tunnelBusy,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                label = { Text("Провайдер DNS") },
                supportingText = {
                    Text(
                        "${presetSubtitle(goDnsPreset)} · ${if (isDohSelected) "DoH" else "UDP :53"}"
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = HopletModalDefaults.fieldShape,
                colors = hopletOutlinedTextFieldColors()
            )
            HopletDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 280.dp)
            ) {
                Text(
                    "UDP · порт 53",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                udpPresets.forEach { (preset, title) ->
                    GoDnsDropdownItem(
                        title = title,
                        subtitle = presetSubtitle(preset),
                        selected = goDnsPreset == preset,
                        onClick = {
                            expanded = false
                            checkResultText = null
                            onPresetChange(preset)
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    "DoH · HTTPS",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                dohPresets.forEach { (preset, title) ->
                    GoDnsDropdownItem(
                        title = title,
                        subtitle = presetSubtitle(preset),
                        selected = goDnsPreset == preset,
                        onClick = {
                            expanded = false
                            checkResultText = null
                            onPresetChange(preset)
                        }
                    )
                }
            }
        }

        if (goDnsPreset == "custom" || goDnsPreset == "doh-custom") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (goDnsPreset == "custom") {
                    OutlinedTextField(
                        value = goDnsCustomInput,
                        onValueChange = { value ->
                            checkResultText = null
                            onCustomChange(value.filter { c -> c.isDigit() || c in ".,; \t" })
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !tunnelBusy,
                        label = { Text("IP-адреса DNS") },
                        placeholder = { Text("1.1.1.1, 8.8.8.8") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                if (goDnsPreset == "doh-custom") {
                    OutlinedTextField(
                        value = goDnsDohCustomInput,
                        onValueChange = { value ->
                            checkResultText = null
                            onDohCustomChange(value)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !tunnelBusy,
                        label = { Text("URL DoH") },
                        placeholder = { Text("https://…/dns-query") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (!canCheck) return@OutlinedButton
                scope.launch {
                    isCheckingDns = true
                    checkResultText = null
                    try {
                        val result = com.wdtt.client.GoDnsProbe.checkPreset(
                            preset = goDnsPreset,
                            customRaw = goDnsCustomInput,
                            dohCustomRaw = goDnsDohCustomInput,
                        )
                        checkResultOk = result.reachable
                        checkResultText = if (result.reachable) {
                            "Доступен: ${result.statusText}"
                        } else {
                            "Недоступен: ${result.statusText}"
                        }
                    } catch (e: Exception) {
                        checkResultOk = false
                        checkResultText = "Ошибка проверки: ${e.message ?: e::class.java.simpleName}"
                    } finally {
                        isCheckingDns = false
                    }
                }
            },
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isCheckingDns) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Проверка…")
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Проверить DNS")
            }
        }

        checkResultText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (checkResultOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        if (tunnelBusy) {
            Text(
                "Перезапустите туннель, чтобы применить DNS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun GoDnsDropdownItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        title,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    )
}
