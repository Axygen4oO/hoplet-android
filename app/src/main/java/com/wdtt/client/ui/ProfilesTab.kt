package com.wdtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CheckCircle
import com.wdtt.client.PeerAddress
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.ProfilesStore
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.wdtt.client.ProfileGroup
import com.wdtt.client.ProfileSubscription
import java.util.UUID
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import com.wdtt.client.ui.components.HopletSubscriptionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.lazy.items
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.DismissValue
import androidx.compose.material.DismissDirection
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FractionalThreshold
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.rememberDismissState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.wdtt.client.DeviceInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ProfilesTab(
    onProfileApplied: () -> Unit = {},
    importFileUri: android.net.Uri? = null,
    onImportHandled: () -> Unit = {},
    requestCreateProfile: Boolean = false,
    onCreateProfileHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profilesStore = remember { ProfilesStore(context) }
    val settingsStore = remember { SettingsStore(context) }

    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by profilesStore.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by profilesStore.subscriptions.collectAsStateWithLifecycle(initialValue = emptyList())

    var showMoreMenu by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf<ConnectionProfile?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val snackbarHostState = remember { SnackbarHostState() }
    var groupToExport by remember { mutableStateOf<ProfileGroup?>(null) }
    val globalHashes by settingsStore.globalVkHashes.collectAsStateWithLifecycle(initialValue = "")
    var currentTime by remember {
        mutableStateOf(System.currentTimeMillis() / 1000L)
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis() / 1000L
            kotlinx.coroutines.delay(60_000)
        }
    }

    LaunchedEffect(requestCreateProfile) {
        if (requestCreateProfile) {
            showCreateSheet = true
            onCreateProfileHandled()
        }
    }

    val pingResults = com.wdtt.client.PingHelper.pingResults
    val pingingState = com.wdtt.client.PingHelper.pingingState

    fun pingProfile(profile: ConnectionProfile) {
        if (pingingState[profile.id] == true) return
        pingingState[profile.id] = true
        scope.launch {
            // Resolve effective hashes: use global if profile says so
            val effectiveHashes = if (profile.useGlobalHashes) globalHashes.ifEmpty { profile.vkHashes } else profile.vkHashes
            val profileWithHashes = profile.copy(vkHashes = effectiveHashes)
            val result = com.wdtt.client.PingHelper.measurePing(context, profileWithHashes)
            pingResults[profile.id] = result
            pingingState[profile.id] = false
        }
    }

    fun pingAllProfiles(list: List<ConnectionProfile>) {
        list.forEach { pingProfile(it) }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val jsonArray = JSONArray()
                        profiles.forEach { p ->
                            val obj = JSONObject()
                            obj.put("id", p.id)
                            obj.put("name", p.name)
                            obj.put("peer", p.peer)
                            obj.put("vkHashes", p.vkHashes)
                            obj.put("workersPerHash", p.workersPerHash)
                            obj.put("listenPort", p.listenPort)
                            obj.put("password", p.password)
                            val groupName = groups.find { it.id == p.groupId }?.name ?: ""
                            obj.put("groupName", groupName)
                            jsonArray.put(obj)
                        }
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            ZipOutputStream(os).use { zos ->
                                val entry = ZipEntry("profiles.json")
                                zos.putNextEntry(entry)
                                zos.write(jsonArray.toString().toByteArray(Charsets.UTF_8))
                                zos.closeEntry()
                            }
                        }
                    }
                    Toast.makeText(context, "Профили успешно экспортированы", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val jsonArray = JSONArray()
                        val profilesToExport = if (groupToExport != null) profiles.filter { it.groupId == groupToExport!!.id } else profiles
                        profilesToExport.forEach { p ->
                            val obj = JSONObject()
                            obj.put("id", p.id)
                            obj.put("name", p.name)
                            obj.put("peer", p.peer)
                            obj.put("vkHashes", p.vkHashes)
                            obj.put("workersPerHash", p.workersPerHash)
                            obj.put("listenPort", p.listenPort)
                            obj.put("password", p.password)
                            jsonArray.put(obj)
                        }
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            val output = if (groupToExport != null) {
                                val rootObj = JSONObject()
                                rootObj.put("subscriptionName", groupToExport!!.name)
                                rootObj.put("profiles", jsonArray)
                                rootObj.toString(4)
                            } else {
                                jsonArray.toString(4)
                            }
                            os.write(output.toByteArray(Charsets.UTF_8))
                        }
                    }
                    Toast.makeText(context, "Папка успешно экспортирована", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    groupToExport = null
                }
            }
        } else {
            groupToExport = null
        }
    }

    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    var importedCount = 0
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            ZipInputStream(inputStream).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    if (entry.name.endsWith(".json")) {
                                        val content = zis.bufferedReader(Charsets.UTF_8).readText()
                                        val jsonArray = JSONArray(content)
                                        val localGroupsCache = mutableMapOf<String, String>()

                                        for (i in 0 until jsonArray.length()) {
                                            val obj = jsonArray.getJSONObject(i)
                                            val name = obj.optString("name", "Imported")
                                            val peer = obj.optString("peer", "")
                                            val vkHashes = obj.optString("vkHashes", "")
                                            val workers = obj.optInt("workersPerHash", 16)
                                            val port = obj.optInt("listenPort", 9000)
                                            val pass = obj.optString("password", "")
                                            val groupName = obj.optString("groupName", "")
                                            
                                            val targetGroupId = if (groupName.isNotBlank()) {
                                                localGroupsCache.getOrPut(groupName.trim().lowercase()) {
                                                    profilesStore.resolveGroupIdForImport(groupName)
                                                }
                                            } else {
                                                ""
                                            }
                                            
                                            profilesStore.createProfile(name, peer, vkHashes, workers, port, pass, targetGroupId)
                                            importedCount++
                                        }
                                        break
                                    }
                                    entry = zis.nextEntry
                                }
                            }
                        }
                    }
                    Toast.makeText(context, "Импортировано профилей: $importedCount", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val sortByPing by settingsStore.sortProfilesByPing.collectAsStateWithLifecycle(initialValue = false)
    val displayProfiles = remember(profiles, pingResults.toMap(), sortByPing) {
        if (sortByPing) {
            profiles.sortedWith(compareBy<ConnectionProfile> {
                val ping = pingResults[it.id]
                if (ping != null && ping >= 0) ping else Long.MAX_VALUE
            }.thenBy { it.name })
        } else {
            profiles
        }
    }
    val currentPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val currentHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val currentWorkers by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 16)
    val currentPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)
    val currentPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")

    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var peerInput by rememberSaveable { mutableStateOf("") }
    var hashesInput by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableStateOf("16") }
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var useGlobalHashesInput by rememberSaveable { mutableStateOf(true) }
    var showGroupManagement by rememberSaveable { mutableStateOf(false) }
    var showAddSubscriptionDialog by rememberSaveable { mutableStateOf(false) }
    var savingSubscription by remember { mutableStateOf(false) }
    var refreshingSubId by remember { mutableStateOf<String?>(null) }
    var deleteSubTarget by remember { mutableStateOf<ProfileSubscription?>(null) }
    var selectedFilterGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var moveToGroupTarget by rememberSaveable { mutableStateOf<ConnectionProfile?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<ConnectionProfile?>(null) }
    var pendingDeletes by remember { mutableStateOf(setOf<String>()) }
    var scannedProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var scannedMultipleProfiles by remember { mutableStateOf<ParsedSubscription?>(null) }
    var showFormatsInfoDialog by rememberSaveable { mutableStateOf(false) }
    var deviceStatuses by remember { mutableStateOf<Map<String, ProfileDeviceStatus>>(emptyMap()) }
    var unbindTarget by remember { mutableStateOf<ConnectionProfile?>(null) }
    var unbindOnlyCurrent by remember { mutableStateOf(true) }
    val savedServerDtlsPort by settingsStore.serverDtlsPort.collectAsStateWithLifecycle(initialValue = 56000)
    val savedManualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)

    val subscriptionGroupIds = remember(subscriptions) {
        subscriptions.mapNotNull { it.groupId.takeIf { id -> id.isNotBlank() } }.toSet()
    }
    val isSubscriptionFilter = selectedFilterGroup != null && subscriptionGroupIds.contains(selectedFilterGroup)
    val visibleSubscriptions = remember(subscriptions, selectedFilterGroup) {
        when (selectedFilterGroup) {
            null -> subscriptions
            else -> subscriptions.filter { it.groupId == selectedFilterGroup }
        }
    }

    val visibleProfiles = remember(displayProfiles, pendingDeletes, selectedFilterGroup) {
        displayProfiles.filterNot { pendingDeletes.contains(it.id) }.filter {
            if (selectedFilterGroup == null) true
            else it.groupId == selectedFilterGroup
        }
    }

    val density = LocalDensity.current
    val spacingPx = with(density) { 12.dp.toPx() }

    var draggingList by remember { mutableStateOf(emptyList<ConnectionProfile>()) }
    var isDragging by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var itemHeights by remember { mutableStateOf(mapOf<String, Int>()) }

    LaunchedEffect(visibleProfiles, isDragging) {
        if (!isDragging) {
            draggingList = visibleProfiles
        }
    }

    // Лаунчер системного выборщика файлов
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val parsed = parseMultipleConfigs(text)
                if (parsed != null) {
                    if (parsed.profiles.size == 1) {
                        scannedProfile = parsed.profiles.first()
                    } else {
                        scannedMultipleProfiles = parsed
                    }
                } else {
                    Toast.makeText(context, "Неверный формат файла", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Автообработка URI если приложение открыли через файл
    LaunchedEffect(importFileUri) {
        val uri = importFileUri ?: return@LaunchedEffect
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val parsed = parseMultipleConfigs(text)
            if (parsed != null) {
                if (parsed.profiles.size == 1) {
                    scannedProfile = parsed.profiles.first()
                } else {
                    scannedMultipleProfiles = parsed
                }
            } else {
                Toast.makeText(context, "Неверный формат файла", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        onImportHandled()
    }

    fun refreshProfileDeviceStatus(profile: ConnectionProfile) {
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val dtlsPort = if (savedManualPortsEnabled) savedServerDtlsPort else 56000
        deviceStatuses = deviceStatuses + (profile.id to (deviceStatuses[profile.id] ?: ProfileDeviceStatus()).copy(isLoading = true))
        scope.launch {
            val status = fetchProfileStatus(profile.peer, dtlsPort, profile.password, androidId)
            if (status != null) {
                deviceStatuses = deviceStatuses + (profile.id to status)
            } else {
                deviceStatuses = deviceStatuses + (profile.id to ProfileDeviceStatus(isError = true))
            }
        }
    }

    LaunchedEffect(profiles) {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val dtlsPort = if (savedManualPortsEnabled) savedServerDtlsPort else 56000

        val sentNames = mutableSetOf<String>()

        while (true) {
            profiles.forEach { profile ->
                if (profile.password.isNotBlank() && profile.peer.isNotBlank()) {
                    launch {
                        val status = fetchProfileStatus(
                            profile.peer,
                            dtlsPort,
                            profile.password,
                            androidId
                        )

                        if (sentNames.add(profile.id)) {
                            android.util.Log.d("WDTT", "About to send device name for ${profile.name}")
                            sendDeviceName(
                                peer = profile.peer,
                                dtlsPort = dtlsPort,
                                password = profile.password,
                                deviceId = androidId,
                                deviceName = DeviceInfo.getDeviceName()
                            )
                        }

                        if (status != null) {
                            deviceStatuses = deviceStatuses + (profile.id to status)
                        } else {
                            deviceStatuses = deviceStatuses + (
                                    profile.id to ProfileDeviceStatus(isError = true)
                                    )
                        }
                    }
                }
            }

            kotlinx.coroutines.delay(8000)
        }
    }

    fun openEditor(profile: ConnectionProfile? = null) {
        editingProfileId = profile?.id
        nameInput = profile?.name ?: ""
        peerInput = profile?.peer ?: currentPeer
        hashesInput = profile?.vkHashes ?: currentHashes
        workersInput = (profile?.workersPerHash ?: currentWorkers).toString()
        portInput = (profile?.listenPort ?: currentPort).toString()
        portInput = (profile?.listenPort ?: currentPort).toString()
        passwordInput = profile?.password ?: currentPassword
        useGlobalHashesInput = profile?.useGlobalHashes ?: true
        editorVisible = true
    }

    fun saveEditor() {
        if (!editorVisible) return
        editorVisible = false
        
        val name = nameInput.trim()
        val peer = peerInput.trim()
        val hashes = hashesInput.trim()
        val workers = workersInput.toIntOrNull()?.coerceIn(1, 128) ?: 16
        val port = portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000
        val password = passwordInput
        
        if (name.isBlank() || peer.isBlank() || (!useGlobalHashesInput && hashes.isBlank()) || password.isBlank()) {
            editorVisible = true // Revert if validation fails
            return
        }

        val currentEditId = editingProfileId
        val useGlobal = useGlobalHashesInput
        scope.launch {
            if (currentEditId == null) {
                val p = profilesStore.createProfile(name, peer, hashes, workers, port, password, "")
                profilesStore.saveProfile(p.copy(useGlobalHashes = useGlobal))
            } else {
                val existingProfile = profiles.firstOrNull { it.id == currentEditId }
                val existingTraffic = existingProfile?.trafficMb ?: 0.0
                val existingGroupId = existingProfile?.groupId ?: ""
                profilesStore.saveProfile(
                    ConnectionProfile(
                        id = currentEditId,
                        name = name,
                        peer = peer,
                        vkHashes = hashes,
                        workersPerHash = workers,
                        listenPort = port,
                        password = password,
                        trafficMb = existingTraffic,
                        groupId = existingGroupId,
                        useGlobalHashes = useGlobal
                    )
                )
            }
        }
    }

    if (editorVisible) {
        HopletAlertDialog(
            onDismissRequest = { editorVisible = false },
            properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false),
            modifier = Modifier.imePadding(),
            title = {
                HopletSectionTitle(if (editingProfileId == null) "Новый профиль" else "Редактировать профиль")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = HopletModalDefaults.fieldShape,
                        colors = hopletOutlinedTextFieldColors()
                    )
                    OutlinedTextField(
                        value = peerInput,
                        onValueChange = { peerInput = it },
                        label = { Text("Peer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = HopletModalDefaults.fieldShape,
                        colors = hopletOutlinedTextFieldColors()
                    )

                    AppSectionCard(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        color = HopletModalDefaults.softContainerColor(),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                        border = HopletModalDefaults.border()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { useGlobalHashesInput = !useGlobalHashesInput },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Использовать общие хеши",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                HopletSectionCaption("Брать VK-хеши из главной вкладки Hoplet.")
                            }
                            HopletSwitch(
                                checked = useGlobalHashesInput,
                                onCheckedChange = { useGlobalHashesInput = it }
                            )
                        }
                    }

                    if (!useGlobalHashesInput) {
                        OutlinedTextField(
                            value = hashesInput,
                            onValueChange = { hashesInput = it },
                            label = { Text("VK-хеши") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                            shape = HopletModalDefaults.fieldShape,
                            colors = hopletOutlinedTextFieldColors()
                        )
                    } else {
                        OutlinedTextField(
                            value = globalHashes.ifEmpty { "Не заданы (настройте на главной)" },
                            onValueChange = {},
                            label = { Text("VK Хеши (из главной вкладки)") },
                            minLines = 2,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            shape = HopletModalDefaults.fieldShape,
                            colors = hopletOutlinedTextFieldColors()
                        )
                    }

                    OutlinedTextField(
                        value = workersInput,
                        onValueChange = { workersInput = it.filter(Char::isDigit) },
                        label = { Text("Потоки") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = HopletModalDefaults.fieldShape,
                        colors = hopletOutlinedTextFieldColors()
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = HopletModalDefaults.fieldShape,
                        colors = hopletOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = { saveEditor() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                HopletSecondaryButton(
                    onClick = { editorVisible = false },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showAddSubscriptionDialog) {
        AddSubscriptionDialog(
            saving = savingSubscription,
            onDismiss = { if (!savingSubscription) showAddSubscriptionDialog = false },
            onConfirm = { url ->
                savingSubscription = true
                scope.launch {
                    val result = profilesStore.addSubscription(url)
                    savingSubscription = false
                    result.fold(
                        onSuccess = {
                            Toast.makeText(context, "Подписка «${it.name}» добавлена", Toast.LENGTH_SHORT).show()
                            selectedFilterGroup = it.groupId.ifBlank { selectedFilterGroup }
                            showAddSubscriptionDialog = false
                        },
                        onFailure = { e ->
                            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        )
    }

    deleteSubTarget?.let { sub ->
        DeleteSubscriptionDialog(
            sub = sub,
            onDismiss = { deleteSubTarget = null },
            onConfirm = {
                scope.launch {
                    profilesStore.deleteSubscription(sub.id)
                    if (selectedFilterGroup == sub.groupId) selectedFilterGroup = null
                    deleteSubTarget = null
                    Toast.makeText(context, "Подписка удалена", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showGroupManagement) {
        GroupManagementDialog(
            groups = groups,
            subscriptionGroupIds = subscriptionGroupIds,
            profilesStore = profilesStore,
            onDismissRequest = { showGroupManagement = false },
            onExportGroup = { group ->
                showGroupManagement = false
                groupToExport = group
                exportJsonLauncher.launch("${group.name}.json")
            }
        )
    }

    if (moveToGroupTarget != null) {
        MoveToGroupDialog(
            profile = moveToGroupTarget!!,
            groups = groups,
            excludedGroupIds = subscriptionGroupIds,
            onDismissRequest = { moveToGroupTarget = null },
            onGroupSelected = { groupId ->
                val target = moveToGroupTarget!!
                scope.launch {
                    try {
                        profilesStore.saveProfile(target.copy(groupId = groupId))
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Ошибка", Toast.LENGTH_LONG).show()
                    }
                }
                moveToGroupTarget = null
            }
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        HopletAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                HopletSectionTitle("Удалить профиль?")
            },
            text = {
                HopletDialogBodyText("Вы действительно хотите удалить профиль «${target.name}»?\n\nЭто действие нельзя будет отменить.")
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch { profilesStore.deleteProfile(target.id) }
                        deleteTarget = null
                    },
                    modifier = Modifier.weight(1f),
                    destructive = true
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                HopletSecondaryButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (unbindTarget != null) {
        val target = unbindTarget!!
        HopletAlertDialog(
            onDismissRequest = { unbindTarget = null },
            title = {
                HopletSectionTitle("Отвязать устройство")
            },
            text = {
                HopletDialogBodyText(
                    if (unbindOnlyCurrent) "Вы действительно хотите отвязать текущее устройство от этого профиля?"
                    else "Вы действительно хотите сбросить ВСЕ привязанные устройства от этого профиля?\n\nЭто освободит все лимиты подключения."
                )
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                        val deviceIdToSend = if (unbindOnlyCurrent) androidId else ""
                        val dtlsPort = if (savedManualPortsEnabled) savedServerDtlsPort else 56000
                        deviceStatuses = deviceStatuses + (target.id to (deviceStatuses[target.id] ?: ProfileDeviceStatus()).copy(isLoading = true))
                        scope.launch {
                            val success = sendUnbindRequest(target.peer, dtlsPort, target.password, deviceIdToSend)
                            if (success) {
                                Toast.makeText(context, if (unbindOnlyCurrent) "Устройство успешно отвязано!" else "Все привязки успешно сброшены!", Toast.LENGTH_SHORT).show()
                                val status = fetchProfileStatus(target.peer, dtlsPort, target.password, androidId)
                                if (status != null) {
                                    deviceStatuses = deviceStatuses + (target.id to status)
                                } else {
                                    deviceStatuses = deviceStatuses + (target.id to ProfileDeviceStatus(isError = true))
                                }
                            } else {
                                Toast.makeText(context, "Не удалось отвязать устройства", Toast.LENGTH_SHORT).show()
                                deviceStatuses = deviceStatuses + (target.id to (deviceStatuses[target.id] ?: ProfileDeviceStatus()).copy(isLoading = false))
                            }
                        }
                        unbindTarget = null
                    },
                    modifier = Modifier.weight(1f),
                    destructive = true
                ) {
                    Text(if (unbindOnlyCurrent) "Отвязать" else "Сбросить")
                }
            },
            dismissButton = {
                HopletSecondaryButton(
                    onClick = { unbindTarget = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (scannedProfile != null) {
        val profile = scannedProfile!!
        HopletAlertDialog(
            onDismissRequest = { scannedProfile = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    HopletSectionTitle("Импорт профиля")
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Найден новый профиль для импорта!",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    AppSectionCard(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        color = HopletModalDefaults.softContainerColor(),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                        border = HopletModalDefaults.border()
                    ) {
                        Text("Название: ${profile.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Сервер: ${profile.peer}", style = MaterialTheme.typography.bodyMedium)
                        Text("Потоков: ${profile.workersPerHash}", style = MaterialTheme.typography.bodyMedium)
                        val hashCount = if (profile.vkHashes.isBlank()) 0 else profile.vkHashes.trim().split(",").count { it.isNotBlank() }
                        Text("Хешей: $hashCount", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (profile.vkHashes.isBlank()) {
                        AppSectionCard(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f))
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Хеш звонка ВКонтакте не указан. После сохранения добавьте его вручную в настройках профиля.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    HopletDialogBodyText("Вы хотите сохранить его в список профилей?")
                }
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch {
                            profilesStore.saveProfile(profile)
                            Toast.makeText(context, "Профиль «${profile.name}» сохранен!", Toast.LENGTH_SHORT).show()
                            scannedProfile = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                HopletSecondaryButton(
                    onClick = { scannedProfile = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (scannedMultipleProfiles != null) {
        val parsed = scannedMultipleProfiles!!
        val list = parsed.profiles
        var folderNameInput by remember { mutableStateOf(parsed.suggestedName ?: "Новая группа") }
        val importBlocked = groups.find { it.name.equals(folderNameInput.trim(), ignoreCase = true) }
            ?.let { subscriptionGroupIds.contains(it.id) } == true
        HopletAlertDialog(
            onDismissRequest = { scannedMultipleProfiles = null },
            icon = {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                HopletSectionTitle("Импорт группы")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Найдено профилей: ${list.size}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (parsed.description.isNotBlank()) {
                        Text(
                            text = parsed.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (parsed.trafficUsedMb > 0 || parsed.trafficLimitMb > 0) {
                        val trafficLine = buildString {
                            if (parsed.trafficUsedMb > 0) append("Трафик: ${parsed.trafficUsedMb} МБ")
                            if (parsed.trafficLimitMb > 0) {
                                if (isNotEmpty()) append(" / ")
                                append("лимит ${parsed.trafficLimitMb} МБ")
                            }
                        }
                        Text(
                            text = trafficLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val existingGroup = groups.find { it.name.equals(folderNameInput.trim(), ignoreCase = true) }
                    val isSubscriptionFolder = existingGroup != null && subscriptionGroupIds.contains(existingGroup.id)
                    if (isSubscriptionFolder) {
                        Text(
                            text = "«${existingGroup!!.name}» — папка подписки. Добавлять профили вручную нельзя.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (existingGroup != null) {
                        Text(
                            text = "Папка «${existingGroup.name}» уже есть — старые профили в ней будут заменены.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = folderNameInput,
                        onValueChange = { folderNameInput = it },
                        label = { Text("Название папки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = HopletModalDefaults.fieldShape,
                        colors = hopletOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch {
                            val finalName = folderNameInput.trim()
                            if (finalName.isEmpty()) {
                                Toast.makeText(context, "Укажите название папки", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val targetGroup = groups.find { it.name.equals(finalName, ignoreCase = true) }
                            if (targetGroup != null && subscriptionGroupIds.contains(targetGroup.id)) {
                                Toast.makeText(context, "«$finalName» — подписка, импорт запрещён", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            try {
                                profilesStore.importProfilesToGroup(finalName, list)
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Ошибка импорта", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val replaced = groups.any { it.name.equals(finalName, ignoreCase = true) }
                            Toast.makeText(
                                context,
                                if (replaced) "Папка «$finalName» заменена (${list.size} проф.)"
                                else "Импортировано профилей: ${list.size}",
                                Toast.LENGTH_SHORT
                            ).show()
                            scannedMultipleProfiles = null
                        }
                    },
                    enabled = !importBlocked,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Импортировать")
                }
            },
            dismissButton = {
                HopletSecondaryButton(
                    onClick = { scannedMultipleProfiles = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showFormatsInfoDialog) {
        HopletAlertDialog(
            onDismissRequest = { showFormatsInfoDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    HopletSectionTitle("Поддерживаемые форматы")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Hoplet автоматически считывает и импортирует настройки из ссылок, файлов и QR-кодов.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. URI ссылки
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("1. Ссылки / QR-коды", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        AppSectionCard(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            color = HopletModalDefaults.softContainerColor(),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = HopletModalDefaults.border()
                        ) {
                            Text(
                                "qwdtt://config?peer=IP:порт&pass=пароль&workers=потоки&port=локальный_порт&name=имя&hashes=vk_хеши",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    }

                    // 2. Файлы
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("2. Файлы конфигурации", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Могут иметь любое расширение. Внутри должна быть конфигурация в виде URI-ссылки или JSON-структуры.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 3. JSON структура
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("3. JSON структура", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Поддерживаются как стандартные ключи, так и альтернативные (pass, vkHashes, workersPerHash, listenPort):",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppSectionCard(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            color = HopletModalDefaults.softContainerColor(),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = HopletModalDefaults.border()
                        ) {
                            Text(
                                "{\n" +
                                "  \"name\": \"Имя профиля\",\n" +
                                "  \"peer\": \"IP_сервера\",\n" +
                                "  \"password\": \"пароль\",\n" +
                                "  \"hashes\": \"vk_хеши\",\n" +
                                "  \"workers\": 16,\n" +
                                "  \"port\": 9000\n" +
                                "}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }

                    // 4. Группы / подписки
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("4. Подписки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "JSON на HTTPS: subscriptionName, description, trafficUsedMb, trafficLimitMb, profiles[]. Добавление: + → Подписка.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppSectionCard(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            color = HopletModalDefaults.softContainerColor(),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = HopletModalDefaults.border()
                        ) {
                            Text(
                                "{\n" +
                                "  \"subscriptionName\": \"Моя группа\",\n" +
                                "  \"description\": \"Описание для пользователя\",\n" +
                                "  \"trafficUsedMb\": 512.5,\n" +
                                "  \"trafficLimitMb\": 10240,\n" +
                                "  \"updatedAt\": \"2026-06-24\",\n" +
                                "  \"profiles\": [\n" +
                                "    { \"name\": \"Сервер 1\", \"peer\": \"IP:56000\", \"password\": \"...\" }\n" +
                                "  ]\n" +
                                "}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = { showFormatsInfoDialog = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Понятно", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val currentFilterLabel = when (selectedFilterGroup) {
            null -> "Все профили"
            "" -> "Без папки"
            else -> groups.firstOrNull { it.id == selectedFilterGroup }?.name ?: "Фильтр"
        }
        val activeProfileCount = profiles.count { it.id == currentProfileId }
        val issueProfileCount = profiles.count { profile ->
            val effectiveHashes = if (profile.useGlobalHashes) {
                globalHashes.ifEmpty { profile.vkHashes }
            } else {
                profile.vkHashes
            }
            effectiveHashes.isBlank() || profile.password.isBlank()
        }

        val activeStatus = profiles
            .mapNotNull { deviceStatuses[it.id] }
            .firstOrNull { !it.isError && it.expiresAt > 0L }

        val subscriptionStatus: String
        val subscriptionSubtitle: String
        val subscriptionColor: Color
        val subscriptionIcon: androidx.compose.ui.graphics.vector.ImageVector

        if (activeStatus == null) {
            subscriptionStatus = "Подписка отсутствует"
            subscriptionSubtitle = "Нет активной подписки"
            subscriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
            subscriptionIcon = Icons.Filled.Info
        } else {
            val now = currentTime
            if (now >= activeStatus.expiresAt) {
                subscriptionStatus = "Подписка истекла"
                subscriptionSubtitle = "Требуется продление"
                subscriptionColor = MaterialTheme.colorScheme.error
                subscriptionIcon = Icons.Filled.Info
            } else {
                val daysLeft = kotlin.math.ceil(
                    (activeStatus.expiresAt - now) / 86400.0
                ).toInt()
                subscriptionStatus = "Подписка активна"
                subscriptionSubtitle = "Осталось $daysLeft дн."
                subscriptionColor = when {
                    daysLeft < 5 -> MaterialTheme.colorScheme.error
                    daysLeft < 10 -> Color(0xFFDC8A00)
                    else -> Color(0xFF1E9E64)
                }
                subscriptionIcon = Icons.Filled.RssFeed
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (isSubscriptionFilter) 96.dp else 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfilesHeaderCard(
                totalProfiles = profiles.size,
                visibleProfiles = visibleProfiles.size,
                activeProfiles = activeProfileCount,
                issueProfiles = issueProfileCount,
                currentFilterLabel = currentFilterLabel,
                sortByPing = sortByPing,
                showMoreMenu = showMoreMenu,
                onInfoClick = { showFormatsInfoDialog = true },
                onPingAllClick = { pingAllProfiles(profiles) },
                onSortClick = {
                    scope.launch { settingsStore.saveSortProfilesByPing(!sortByPing) }
                },
                onMoreClick = { showMoreMenu = true },
                onDismissMoreMenu = { showMoreMenu = false },
                onManageGroupsClick = { showGroupManagement = true },
                onExportZipClick = { exportZipLauncher.launch("hoplet_profiles_export.zip") },
                onImportZipClick = { importZipLauncher.launch("application/zip") }
            )

            if (groups.isNotEmpty()) {
                ProfilesFilterCard(
                    groups = groups,
                    selectedFilterGroup = selectedFilterGroup,
                    subscriptionGroupIds = subscriptionGroupIds,
                    onFilterSelected = { selectedFilterGroup = it }
                )
            }

            if (visibleSubscriptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visibleSubscriptions.forEach { sub ->
                        ProfilesSubscriptionCard(
                            sub = sub,
                            isRefreshing = refreshingSubId == sub.id,
                            onRefresh = {
                                refreshingSubId = sub.id
                                scope.launch {
                                    val result = profilesStore.refreshSubscription(sub.id)
                                    refreshingSubId = null
                                    result.fold(
                                        onSuccess = { count ->
                                            Toast.makeText(
                                                context,
                                                "Обновлено профилей: $count",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        onFailure = { e ->
                                            Toast.makeText(
                                                context,
                                                "Ошибка: ${e.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    )
                                }
                            },
                            onDelete = { deleteSubTarget = sub },
                            onOpenGroup = if (selectedFilterGroup == null && sub.groupId.isNotBlank()) {
                                { selectedFilterGroup = sub.groupId }
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            if (profiles.isNotEmpty() || visibleSubscriptions.isNotEmpty()) {
                ProfilesStatusSummaryCard(
                    title = subscriptionStatus,
                    subtitle = subscriptionSubtitle,
                    accentColor = subscriptionColor,
                    icon = subscriptionIcon
                )
            }

            when {
                profiles.isEmpty() -> {
                    ProfilesEmptyStateCard(
                        title = "Пока нет сохранённых профилей",
                        description = "Добавьте первый профиль вручную, из файла, по подписке или через QR-код.",
                        primaryActionLabel = "Добавить профиль",
                        onPrimaryAction = { showCreateSheet = true }
                    )
                }
                visibleProfiles.isEmpty() -> {
                    val canResetFilter = selectedFilterGroup != null
                    ProfilesEmptyStateCard(
                        title = if (isSubscriptionFilter) {
                            "В выбранной подписке пока нет профилей"
                        } else {
                            "Этот фильтр пока пуст"
                        },
                        description = if (isSubscriptionFilter) {
                            "Обновите подписку или вернитесь ко всем профилям."
                        } else {
                            "Сбросьте фильтр или добавьте новый профиль в нужную папку."
                        },
                        primaryActionLabel = if (canResetFilter) "Сбросить фильтр" else "Добавить профиль",
                        onPrimaryAction = {
                            if (canResetFilter) {
                                selectedFilterGroup = null
                            } else {
                                showCreateSheet = true
                            }
                        }
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        draggingList.forEach { profile ->
                            androidx.compose.runtime.key(profile.id) {
                                val isActive = profile.id == currentProfileId
                                val effectiveHashes = if (profile.useGlobalHashes) {
                                    globalHashes.ifEmpty { profile.vkHashes }
                                } else {
                                    profile.vkHashes
                                }
                                val hasIssue = effectiveHashes.isBlank() || profile.password.isBlank()
                                val groupName = groups.firstOrNull { it.id == profile.groupId }?.name

                                val dismissState = rememberDismissState(
                                    confirmStateChange = { value ->
                                        if (value == DismissValue.DismissedToStart) {
                                            deleteTarget = profile
                                        }
                                        false
                                    }
                                )

                                val isCurrentDragged = draggedIndex != null && draggingList.getOrNull(draggedIndex!!)?.id == profile.id
                                val translationY = if (isCurrentDragged) dragOffset else 0f
                                val scale = if (isCurrentDragged) 1.02f else 1f
                                val elevation = if (isCurrentDragged) 8.dp else 0.dp

                                SwipeToDismiss(
                                    state = dismissState,
                                    directions = setOf(DismissDirection.EndToStart),
                                    modifier = Modifier
                                        .onGloballyPositioned { coords ->
                                            itemHeights = itemHeights + (profile.id to coords.size.height)
                                        }
                                        .graphicsLayer {
                                            this.translationY = translationY
                                            this.scaleX = scale
                                            this.scaleY = scale
                                            this.shadowElevation = elevation.toPx()
                                            this.shape = RoundedCornerShape(28.dp)
                                            this.clip = isCurrentDragged
                                        }
                                        .pointerInput(profile.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    if (sortByPing) {
                                                        Toast.makeText(context, "Отключите сортировку по пингу для ручного перемещения", Toast.LENGTH_SHORT).show()
                                                        return@detectDragGesturesAfterLongPress
                                                    }
                                                    isDragging = true
                                                    draggedIndex = draggingList.indexOfFirst { it.id == profile.id }
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val currentIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                                    dragOffset += dragAmount.y

                                                    if (dragOffset > 0f && currentIdx < draggingList.size - 1) {
                                                        val nextProfile = draggingList[currentIdx + 1]
                                                        val nextHeight = itemHeights[nextProfile.id] ?: 0
                                                        val swapThreshold = (nextHeight + spacingPx) / 2f
                                                        if (dragOffset > swapThreshold) {
                                                            val newList = draggingList.toMutableList()
                                                            newList[currentIdx] = nextProfile
                                                            newList[currentIdx + 1] = profile
                                                            draggingList = newList
                                                            draggedIndex = currentIdx + 1
                                                            dragOffset -= (nextHeight + spacingPx)
                                                        }
                                                    } else if (dragOffset < 0f && currentIdx > 0) {
                                                        val prevProfile = draggingList[currentIdx - 1]
                                                        val prevHeight = itemHeights[prevProfile.id] ?: 0
                                                        val swapThreshold = (prevHeight + spacingPx) / 2f
                                                        if (dragOffset < -swapThreshold) {
                                                            val newList = draggingList.toMutableList()
                                                            newList[currentIdx] = prevProfile
                                                            newList[currentIdx - 1] = profile
                                                            draggingList = newList
                                                            draggedIndex = currentIdx - 1
                                                            dragOffset += (prevHeight + spacingPx)
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    isDragging = false
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                    scope.launch {
                                                        profilesStore.reorderProfiles(draggingList.map { it.id })
                                                    }
                                                },
                                                onDragCancel = {
                                                    isDragging = false
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                }
                                            )
                                        },
                                    background = {
                                        if (dismissState.dismissDirection != null) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(28.dp))
                                                    .background(MaterialTheme.colorScheme.errorContainer),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Удалить",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier
                                                        .padding(end = 28.dp)
                                                        .size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    AppSectionCard(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        border = if (isActive) {
                                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                                        } else if (hasIssue) {
                                            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                                        } else {
                                            null
                                        },
                                        color = AppCardDefaults.containerColor(),
                                        shadowElevation = if (isCurrentDragged) 0.dp else 0.dp,
                                        tonalElevation = 0.dp,
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                profilesStore.applyProfile(context = context, id = profile.id)
                                                Toast.makeText(context, "Применено", Toast.LENGTH_SHORT).show()
                                                onProfileApplied()
                                            }
                                        }
                                    ) {
                                        ProfilesProfileCardContent(
                                            profile = profile,
                                            groupName = groupName,
                                            isActive = isActive,
                                            hasIssue = hasIssue,
                                            missingHashes = effectiveHashes.isBlank(),
                                            pingMs = pingResults[profile.id],
                                            isPinging = pingingState[profile.id] == true,
                                            status = deviceStatuses[profile.id],
                                            onPing = { pingProfile(profile) },
                                            onMoveToGroup = { moveToGroupTarget = profile },
                                            onShare = { showExportSheet = profile },
                                            onEdit = { openEditor(profile) },
                                            onUnbindCurrentDevice = {
                                                unbindOnlyCurrent = true
                                                unbindTarget = profile
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Смахните профиль влево для удаления. Удерживайте карточку для перестановки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
        )

        if (!isSubscriptionFilter) {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить")
            }
        }
    }

    val isPingingAny = pingingState.values.any { it }
    if (isPingingAny) {
        HopletDialog(
            onDismissRequest = { },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            HopletModalSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SignalCellularAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    Text(
                        text = "Замер задержки",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = "Проверяем доступность и измеряем скорость подключения. Это может занять до 20 секунд.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
        HopletModalBottomSheet(
            onDismissRequest = { showCreateSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Добавить профиль",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Выберите источник импорта или создайте профиль вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ProfilesCreateOptionRow(
                    icon = Icons.Filled.RssFeed,
                    title = "Подписка",
                    subtitle = "Профили по адресу JSON на сервере",
                    onClick = {
                        showCreateSheet = false
                        showAddSubscriptionDialog = true
                    },
                    tint = MaterialTheme.colorScheme.primary
                )

                ProfilesCreateOptionRow(
                    icon = Icons.Filled.Add,
                    title = "Вручную",
                    subtitle = "Создать новый профиль с нуля",
                    onClick = {
                        showCreateSheet = false
                        openEditor()
                    }
                )

                ProfilesCreateOptionRow(
                    icon = Icons.Filled.FileOpen,
                    title = "Из файла",
                    subtitle = "Выбрать файл конфигурации на устройстве",
                    onClick = {
                        showCreateSheet = false
                        filePickerLauncher.launch("*/*")
                    }
                )

                ProfilesCreateOptionRow(
                    icon = Icons.Filled.ContentCopy,
                    title = "Из буфера",
                    subtitle = "Вставить скопированную конфигурацию",
                    onClick = {
                        showCreateSheet = false
                        try {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString() ?: ""
                                val parsed = parseMultipleConfigs(text)
                                if (parsed != null) {
                                    if (parsed.profiles.size == 1) {
                                        scannedProfile = parsed.profiles.first()
                                    } else {
                                        scannedMultipleProfiles = parsed
                                    }
                                    Toast.makeText(context, "Конфигурация успешно прочитана!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "В буфере обмена нет подходящей конфигурации", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Не удалось прочитать буфер: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                ProfilesCreateOptionRow(
                    icon = Icons.Filled.QrCodeScanner,
                    title = "Сканировать QR-код",
                    subtitle = "Считать профиль с другого устройства",
                    onClick = {
                        showCreateSheet = false
                        try {
                            val activity = run {
                                val currentAct = com.wdtt.client.MainActivity.currentActivity
                                if (currentAct != null) return@run currentAct
                                var c = context
                                while (c is android.content.ContextWrapper) {
                                    if (c is android.app.Activity) return@run c
                                    c = c.baseContext
                                }
                                context
                            }
                            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(activity)
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    val rawText = barcode.rawValue ?: ""
                                    val parsed = parseMultipleConfigs(rawText)
                                    if (parsed != null) {
                                        if (parsed.profiles.size == 1) {
                                            scannedProfile = parsed.profiles.first()
                                        } else {
                                            scannedMultipleProfiles = parsed
                                        }
                                    } else {
                                        Toast.makeText(context, "Неверный формат QR-кода", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(context, "Ошибка сканирования: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        } catch (e: Exception) {
                            val msg = e.message ?: "неизвестная ошибка"
                            if (msg.contains("null", ignoreCase = true) || msg.contains("NullPointerException", ignoreCase = true)) {
                                Toast.makeText(context, "Не удалось запустить сканер: отсутствует или отключен Google Play Services", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Не удалось запустить сканер: $msg", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    showExportSheet?.let { profile ->
        val exportDtlsPort = if (savedManualPortsEnabled) savedServerDtlsPort else 56000
        ExportProfileSheet(
            profile = profile,
            serverDtlsPort = exportDtlsPort,
            onDismissRequest = { showExportSheet = null }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            colors.primary.copy(alpha = 0.14f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.48f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                colors.primary.copy(alpha = 0.32f)
            } else {
                colors.outlineVariant.copy(alpha = 0.18f)
            }
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) colors.primary else (tint ?: colors.onSurfaceVariant),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ProfilesCompactChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.12f,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = backgroundAlpha),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leading?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                maxLines = 1
            )
        }
    }
}

private fun formatProfilesTrafficMb(value: Double): String {
    return when {
        value >= 1024 -> String.format(java.util.Locale.US, "%.2f ГБ", value / 1024.0)
        value >= 1 -> String.format(java.util.Locale.US, "%.1f МБ", value)
        value > 0 -> String.format(java.util.Locale.US, "%.2f МБ", value)
        else -> "0 МБ"
    }
}

private fun formatProfilesSyncTime(ts: Long): String {
    if (ts <= 0L) return "ещё не обновлялась"
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
}

@Composable
private fun ProfilesHeaderCard(
    totalProfiles: Int,
    visibleProfiles: Int,
    activeProfiles: Int,
    issueProfiles: Int,
    currentFilterLabel: String,
    sortByPing: Boolean,
    showMoreMenu: Boolean,
    onInfoClick: () -> Unit,
    onPingAllClick: () -> Unit,
    onSortClick: () -> Unit,
    onMoreClick: () -> Unit,
    onDismissMoreMenu: () -> Unit,
    onManageGroupsClick: () -> Unit,
    onExportZipClick: () -> Unit,
    onImportZipClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = AppCardDefaults.containerColor(),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.24f)),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Профили",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.onSurface
                    )
                    Text(
                        text = "Быстрый доступ к профилям, фильтрам и импорту без лишнего шума.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                ProfilesActionButton(
                    icon = Icons.Filled.Info,
                    contentDescription = "Справка",
                    onClick = onInfoClick,
                    tint = colors.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfilesCompactChip(
                    text = "Всего $totalProfiles",
                    color = colors.primary
                )
                ProfilesCompactChip(
                    text = "Видимых $visibleProfiles",
                    color = colors.secondary
                )
                if (activeProfiles > 0) {
                    ProfilesCompactChip(
                        text = "Активен $activeProfiles",
                        color = Color(0xFF1E9E64)
                    )
                }
                if (issueProfiles > 0) {
                    ProfilesCompactChip(
                        text = "Проблемы $issueProfiles",
                        color = colors.error
                    )
                }
                ProfilesCompactChip(
                    text = currentFilterLabel,
                    color = colors.tertiary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilesActionButton(
                    icon = Icons.Filled.SignalCellularAlt,
                    contentDescription = "Проверить пинг",
                    onClick = onPingAllClick,
                    tint = colors.primary
                )
                ProfilesActionButton(
                    icon = Icons.Filled.Sort,
                    contentDescription = "Сортировать по пингу",
                    onClick = onSortClick,
                    selected = sortByPing,
                    tint = colors.onSurfaceVariant
                )
                Box {
                    ProfilesActionButton(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = "Дополнительно",
                        onClick = onMoreClick,
                        tint = colors.onSurfaceVariant
                    )
                    HopletDropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = onDismissMoreMenu
                    ) {
                        HopletDropdownMenuItem(
                            text = { Text("Управление папками") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = colors.primary
                                )
                            },
                            onClick = {
                                onManageGroupsClick()
                                onDismissMoreMenu()
                            }
                        )
                        HopletDropdownMenuItem(
                            text = { Text("Экспорт всех профилей (ZIP)") },
                            onClick = {
                                onExportZipClick()
                                onDismissMoreMenu()
                            }
                        )
                        HopletDropdownMenuItem(
                            text = { Text("Импорт профилей (ZIP)") },
                            onClick = {
                                onImportZipClick()
                                onDismissMoreMenu()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilesFilterCard(
    groups: List<ProfileGroup>,
    selectedFilterGroup: String?,
    subscriptionGroupIds: Set<String>,
    onFilterSelected: (String?) -> Unit
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        color = AppCardDefaults.containerColor(),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Фильтры",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (selectedFilterGroup != null) {
                TextButton(onClick = { onFilterSelected(null) }) {
                    Text("Сбросить")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilterGroup == null,
                onClick = { onFilterSelected(null) },
                label = { Text("Все") }
            )
            FilterChip(
                selected = selectedFilterGroup == "",
                onClick = { onFilterSelected("") },
                label = { Text("Без папки") }
            )
            groups.forEach { group ->
                val isSubscriptionGroup = subscriptionGroupIds.contains(group.id)
                FilterChip(
                    selected = selectedFilterGroup == group.id,
                    onClick = { onFilterSelected(group.id) },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isSubscriptionGroup) {
                                Icon(
                                    Icons.Filled.RssFeed,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(group.name)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProfilesSubscriptionCard(
    sub: ProfileSubscription,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onOpenGroup: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val openGroupAction = onOpenGroup
    val hasLimit = sub.trafficLimitMb > 0
    val hasTraffic = sub.remoteTrafficUsedMb > 0 || hasLimit
    val progress = if (hasLimit && sub.trafficLimitMb > 0) {
        (sub.remoteTrafficUsedMb / sub.trafficLimitMb).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        color = AppCardDefaults.containerColor(),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.14f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.then(
            if (openGroupAction != null) {
                Modifier.clickable { openGroupAction() }
            } else {
                Modifier
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = colors.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RssFeed,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = sub.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (sub.description.isNotBlank()) {
                            Text(
                                text = sub.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfilesActionButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "Обновить подписку",
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    tint = colors.primary
                )
                ProfilesActionButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Удалить подписку",
                    onClick = onDelete,
                    tint = colors.error
                )
            }
        }

        if (hasTraffic) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (hasLimit) {
                        "Трафик: ${formatProfilesTrafficMb(sub.remoteTrafficUsedMb)} из ${formatProfilesTrafficMb(sub.trafficLimitMb)}"
                    } else {
                        "Трафик: ${formatProfilesTrafficMb(sub.remoteTrafficUsedMb)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (hasLimit) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = colors.primary,
                        trackColor = colors.primary.copy(alpha = 0.14f)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Обновлено: ${formatProfilesSyncTime(sub.lastSyncAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            if (onOpenGroup != null) {
                Text(
                    text = "Открыть папку",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.primary
                )
            }
        }

        if (sub.lastSyncError.isNotBlank()) {
            ProfilesCompactChip(
                text = "Ошибка: ${sub.lastSyncError}",
                color = colors.error,
                backgroundAlpha = 0.08f
            )
        }
    }
}

@Composable
private fun ProfilesStatusSummaryCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        color = AppCardDefaults.containerColor(),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.18f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = accentColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Подписка",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            ProfilesCompactChip(
                text = subtitle,
                color = accentColor
            )
        }
    }
}

@Composable
private fun ProfilesEmptyStateCard(
    title: String,
    description: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        color = AppCardDefaults.containerColor(),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPrimaryAction,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(primaryActionLabel)
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesCreateOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color? = null
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = colors.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.2f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = (tint ?: colors.primary).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint ?: colors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfilesProfileCardContent(
    profile: ConnectionProfile,
    groupName: String?,
    isActive: Boolean,
    hasIssue: Boolean,
    missingHashes: Boolean,
    pingMs: Long?,
    isPinging: Boolean,
    status: ProfileDeviceStatus?,
    onPing: () -> Unit,
    onMoveToGroup: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onUnbindCurrentDevice: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val displayFlag = getCountryFlag(profile.name)
    val displayName = if (displayFlag.isNotEmpty()) "$displayFlag ${profile.name}" else profile.name
    val pingColor = when {
        pingMs == null -> colors.onSurfaceVariant
        pingMs < 0 -> colors.error
        pingMs < 700 -> Color(0xFF1E9E64)
        pingMs < 1000 -> Color(0xFFDC8A00)
        else -> colors.error
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        ProfilesCompactChip(
                            text = "Активен",
                            color = colors.primary
                        )
                    }
                    if (hasIssue) {
                        ProfilesCompactChip(
                            text = "Проверить",
                            color = colors.error
                        )
                    }
                }

                Text(
                    text = obfuscatePeer(profile.peer),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProfilesCompactChip(
                        text = "W${profile.workersPerHash}",
                        color = colors.secondary
                    )
                    ProfilesCompactChip(
                        text = "Порт ${profile.listenPort}",
                        color = colors.tertiary
                    )
                    if (!groupName.isNullOrBlank()) {
                        ProfilesCompactChip(
                            text = groupName,
                            color = colors.primary
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfilesActionButton(
                        icon = Icons.Filled.SignalCellularAlt,
                        contentDescription = "Проверить пинг",
                        onClick = onPing,
                        selected = isPinging,
                        enabled = !isPinging,
                        tint = colors.primary
                    )
                    ProfilesActionButton(
                        icon = Icons.Filled.Folder,
                        contentDescription = "В папку",
                        onClick = onMoveToGroup,
                        tint = colors.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfilesActionButton(
                        icon = Icons.Filled.Share,
                        contentDescription = "Поделиться",
                        onClick = onShare,
                        tint = colors.onSurfaceVariant
                    )
                    ProfilesActionButton(
                        icon = Icons.Filled.Edit,
                        contentDescription = "Изменить",
                        onClick = onEdit,
                        tint = colors.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status != null && !status.isError && profile.password.isNotBlank() && profile.peer.isNotBlank()) {
                if (status.isLoading) {
                    ProfilesCompactChip(
                        text = "Загрузка статуса",
                        color = colors.primary,
                        leading = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.4.dp,
                                color = colors.primary
                            )
                        }
                    )
                } else {
                    val isExpired = status.expiresAt > 0L && System.currentTimeMillis() > status.expiresAt * 1000L
                    if (isExpired) {
                        ProfilesCompactChip(
                            text = "Пароль истёк",
                            color = colors.error
                        )
                    } else {
                        val isFull = status.boundDevices >= status.maxDevices && !status.isCurrentBound
                        ProfilesCompactChip(
                            text = "Устройства ${status.boundDevices}/${status.maxDevices}",
                            color = if (isFull) colors.error else colors.secondary
                        )
                        if (status.boundDevices > 0 && status.isCurrentBound && onUnbindCurrentDevice != null) {
                            ProfilesActionButton(
                                icon = Icons.Filled.Delete,
                                contentDescription = "Отвязать текущее устройство",
                                onClick = onUnbindCurrentDevice,
                                tint = colors.error
                            )
                        }
                    }
                }
            }

            if (pingMs != null) {
                ProfilesCompactChip(
                    text = if (pingMs < 0) "Ping fail" else "${pingMs} ms",
                    color = pingColor
                )
            }
        }

        if (hasIssue) {
            val warnings = mutableListOf<String>()
            if (missingHashes) warnings.add("хеш")
            if (profile.password.isBlank()) warnings.add("пароль")
            val message = if (warnings.isNotEmpty()) {
                "Проверьте: ${warnings.joinToString(", ")}"
            } else {
                "Проверьте параметры профиля"
            }
            ProfilesCompactChip(
                text = message,
                color = colors.error,
                backgroundAlpha = 0.08f
            )
        }
    }
}

private fun parseQrConfig(rawText: String): ConnectionProfile? {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) return null

    // 0. Try legacy original WDTT scheme
    if (trimmed.startsWith("wdtt://")) {
        try {
            // wdtt://<server_ip>:<dtls_port>:<wg_port>:<local_port>:<password>:<vk_hash>
            val parts = trimmed.removePrefix("wdtt://").split(":")
            if (parts.size >= 6) {
                val ip = parts[0]
                val dtlsPort = parts[1]
                val localPort = parts[3].toIntOrNull() ?: 9000
                val pass = parts[4]
                val hash = parts.drop(5).joinToString(":")
                return ConnectionProfile(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "Hoplet $ip",
                    peer = "$ip:$dtlsPort",
                    vkHashes = hash,
                    workersPerHash = 16,
                    listenPort = localPort,
                    password = pass
                )
            }
        } catch (e: Exception) {
            // continue parsing if failed
        }
    }

    // 1. Try URL scheme
    if (trimmed.startsWith("qwdtt://config") || trimmed.startsWith("qwdtt:config")) {
        try {
            val uri = android.net.Uri.parse(trimmed.replace("qwdtt:config", "qwdtt://config"))
            val name = uri.getQueryParameter("name") ?: "QR Профиль"
            val peerRaw = uri.getQueryParameter("peer") ?: return null
            val dtlsPortParam = uri.getQueryParameter("dtls_port") ?: uri.getQueryParameter("server_port")
            val peer = if (dtlsPortParam != null) {
                PeerAddress.ensurePort(peerRaw, dtlsPortParam.toIntOrNull()?.coerceIn(1, 65535) ?: 56000)
            } else {
                peerRaw
            }
            val hashes = uri.getQueryParameter("hashes") ?: ""
            val workers = uri.getQueryParameter("workers")?.toIntOrNull() ?: 18
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 9000
            val pass = uri.getQueryParameter("pass") ?: ""
            return ConnectionProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                peer = peer,
                vkHashes = hashes,
                workersPerHash = workers,
                listenPort = port,
                password = pass
            )
        } catch (e: Exception) {
            // fallback
        }
    }

    // 2. Try JSON (raw or base64)
    var jsonStr = trimmed
    if (!trimmed.startsWith("{")) {
        try {
            val decodedBytes = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            jsonStr = String(decodedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            // not base64
        }
    }

    if (jsonStr.startsWith("{")) {
        try {
            val jsonObj = org.json.JSONObject(jsonStr)
            val name = jsonObj.optString("name", "QR Профиль")
            val peer = jsonObj.getString("peer")
            val hashes = jsonObj.optString("hashes", jsonObj.optString("vkHashes", ""))
            val workers = jsonObj.optInt("workers", jsonObj.optInt("workersPerHash", 18))
            val port = jsonObj.optInt("port", jsonObj.optInt("listenPort", 9000))
            val pass = jsonObj.optString("password", jsonObj.optString("pass", ""))
            return ConnectionProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                peer = peer,
                vkHashes = hashes,
                workersPerHash = workers,
                listenPort = port,
                password = pass
            )
        } catch (e: Exception) {
            // invalid json
        }
    }

    return null
}

private data class ParsedSubscription(
    val profiles: List<ConnectionProfile>,
    val suggestedName: String? = null,
    val description: String = "",
    val trafficUsedMb: Double = 0.0,
    val trafficLimitMb: Double = 0.0,
    val updatedAt: String = ""
)

private fun parseMultipleConfigs(rawText: String): ParsedSubscription? {
    val parsed = com.wdtt.client.SubscriptionImport.parsePayload(rawText) ?: run {
        val single = parseQrConfig(rawText) ?: return null
        return ParsedSubscription(profiles = listOf(single), suggestedName = single.name)
    }
    return ParsedSubscription(
        profiles = parsed.profiles,
        suggestedName = parsed.subscriptionName,
        description = parsed.description,
        trafficUsedMb = parsed.trafficUsedMb,
        trafficLimitMb = parsed.trafficLimitMb,
        updatedAt = parsed.updatedAt
    )
}

// ==================== Profile Device Management API Client ====================

data class ProfileDeviceStatus(
    val maxDevices: Int = 1,
    val boundDevices: Int = 0,
    val activeDevices: Int = 0,
    val isCurrentBound: Boolean = false,
    val expiresAt: Long = 0L,
    val isError: Boolean = false,
    val isLoading: Boolean = false
)

private suspend fun fetchProfileStatus(
    peer: String,
    dtlsPort: Int,
    password: String,
    deviceId: String
): ProfileDeviceStatus? = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val encodedPass = URLEncoder.encode(password, "UTF-8")
        val encodedDevice = URLEncoder.encode(deviceId, "UTF-8")
        val url = URL("http://${PeerAddress.httpEndpoint(peer, dtlsPort)}/api/profile/status?password=$encodedPass&device_id=$encodedDevice")
        conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            ProfileDeviceStatus(
                maxDevices = json.optInt("max_devices", 1),
                boundDevices = json.optInt("bound_devices", 0),
                activeDevices = json.optInt("active_devices", 0),
                isCurrentBound = json.optBoolean("is_current_bound", false),
                expiresAt = json.optLong("expires_at", 0L)
            )
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        conn?.disconnect()
    }
}


private suspend fun sendDeviceName(
    peer: String,
    dtlsPort: Int,
    password: String,
    deviceId: String,
    deviceName: String
) = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null

    try {
        val endpoint = PeerAddress.httpEndpoint(peer, dtlsPort)
        val url = URL("http://$endpoint/api/device/name")

        android.util.Log.d("WDTT", "sendDeviceName()")
        android.util.Log.d("WDTT", "endpoint = $endpoint")
        android.util.Log.d("WDTT", "url = $url")
        android.util.Log.d("WDTT", "deviceId = $deviceId")
        android.util.Log.d("WDTT", "deviceName = $deviceName")

        conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")

        val json = JSONObject().apply {
            put("password", password)
            put("device_id", deviceId)
            put("device_name", deviceName)
        }

        conn.outputStream.use {
            it.write(json.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode

        android.util.Log.d("WDTT", "responseCode = $code")

        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText()
        }

        android.util.Log.d("WDTT", "responseBody = $body")

    } catch (e: Exception) {
        android.util.Log.e("WDTT", "sendDeviceName failed", e)
    } finally {
        conn?.disconnect()
    }
}

private suspend fun sendUnbindRequest(
    peer: String,
    dtlsPort: Int,
    password: String,
    deviceId: String
): Boolean = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL("http://${PeerAddress.httpEndpoint(peer, dtlsPort)}/api/profile/unbind")
        conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val postData = "password=${URLEncoder.encode(password, "UTF-8")}&device_id=${URLEncoder.encode(deviceId, "UTF-8")}"
        conn.outputStream.use { os ->
            os.write(postData.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        responseCode == 200
    } catch (e: Exception) {
        e.printStackTrace()
        false
    } finally {
        conn?.disconnect()
    }
}

fun getCountryFlag(profileName: String): String {
    val name = profileName.lowercase()
    return when {
        name.contains("герман") || name.contains("germany") -> "🇩🇪"
        name.contains("финлянд") || name.contains("finland") -> "🇫🇮"
        name.contains("нидерланд") || name.contains("netherlands") -> "🇳🇱"
        name.contains("сша") || name.contains("usa") || name.contains("америк") -> "🇺🇸"
        name.contains("росси") || name.contains("russia") || name.contains("рф") -> "🇷🇺"
        name.contains("франци") || name.contains("france") -> "🇫🇷"
        name.contains("швеци") || name.contains("sweden") -> "🇸🇪"
        name.contains("англи") || name.contains("великобритани") || name.contains("uk") -> "🇬🇧"
        name.contains("польш") || name.contains("poland") -> "🇵🇱"
        name.contains("турци") || name.contains("turkey") -> "🇹🇷"
        name.contains("канад") || name.contains("canada") -> "🇨🇦"
        name.contains("япони") || name.contains("japan") -> "🇯🇵"
        name.contains("украин") || name.contains("ukraine") -> "🇺🇦"
        name.contains("казахстан") || name.contains("kazakhstan") -> "🇰🇿"
        name.contains("испани") || name.contains("spain") -> "🇪🇸"
        name.contains("итали") || name.contains("italy") -> "🇮🇹"
        else -> ""
    }
}

private fun obfuscatePeer(peer: String): String {
    val ipv4Regex = Regex("^(\\d{1,3}\\.\\d{1,3}\\.)\\d{1,3}\\.\\d{1,3}(:\\d+)?$")
    if (ipv4Regex.matches(peer)) {
        return ipv4Regex.replace(peer, "$1***.***$2")
    }
    return peer
}
