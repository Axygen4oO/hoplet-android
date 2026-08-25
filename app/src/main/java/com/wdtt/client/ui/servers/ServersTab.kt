package com.wdtt.client.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SecureApiClient
import com.wdtt.client.TunnelManager
import com.wdtt.client.servers.ServerProfile
import com.wdtt.client.servers.ServersStore
import kotlinx.coroutines.launch

@Composable
fun ServersTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val serversStore = remember { ServersStore(context) }

    val servers by serversStore.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeServerId by serversStore.activeServerId.collectAsStateWithLifecycle(initialValue = null)
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    var editingServer by remember { mutableStateOf<ServerProfile?>(null) }
    var deleteServer by remember { mutableStateOf<ServerProfile?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionCard(
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Серверы",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val activeServer = servers.firstOrNull { it.id == activeServerId }
                    Text(
                        text = activeServer?.let { "Активный: ${it.name} · ${it.host}" }
                            ?: "Активный сервер пока не выбран",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tunnelRunning) {
                        Text(
                            text = "Сервер выбран. Текущее подключение продолжает работать, а следующий VPN connection attempt пойдёт на новый сервер.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Button(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить")
                }
            }
        }

        if (servers.isEmpty()) {
            AppSectionCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Пока нет сохранённых серверов",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Добавьте первый ServerProfile для административных запросов и deploy-связки.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    val isActive = server.id == activeServerId
                    val activeBorder = if (isActive) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f))
                    } else {
                        BorderStroke(1.dp, appSectionCardBorderColor())
                    }
                    val activeColor = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
                    } else {
                        AppCardDefaults.containerColor()
                    }

                    AppSectionCard(
                        border = activeBorder,
                        color = activeColor,
                        contentPadding = PaddingValues(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Text(
                                            text = "Активен",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Text(
                                    text = server.host,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "HTTPS ${server.httpsPort} · DTLS ${server.dtlsPort} · WG ${server.wgPort}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            serversStore.setActiveServerId(server.id)
                                            val message = if (tunnelRunning) {
                                                "Сервер выбран. Текущее подключение продолжает работать."
                                            } else {
                                                "Активный сервер обновлён."
                                            }
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isActive,
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isActive) "Выбран" else "Выбрать")
                                }

                                Button(
                                    onClick = { editingServer = server },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Изменить")
                                }
                            }

                            Button(
                                onClick = {
                                    if (servers.size <= 1) {
                                        Toast.makeText(
                                            context,
                                            "Нельзя удалить последний сервер.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        deleteServer = server
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ServerEditorDialog(
            initialServer = null,
            onDismiss = { showCreateDialog = false },
            onSave = { profile ->
                scope.launch {
                    serversStore.addServer(profile)
                    showCreateDialog = false
                    Toast.makeText(context, "Сервер добавлен.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    editingServer?.let { target ->
        ServerEditorDialog(
            initialServer = target,
            onDismiss = { editingServer = null },
            onSave = { profile ->
                scope.launch {
                    serversStore.updateServer(profile)
                    editingServer = null
                    Toast.makeText(context, "Сервер обновлён.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    deleteServer?.let { target ->
        HopletAlertDialog(
            onDismissRequest = { deleteServer = null },
            title = { HopletSectionTitle("Удалить сервер?") },
            text = {
                HopletDialogBodyText(
                    "Сервер «${target.name}» будет удалён из списка. Если он активный, приложение выберет следующий сервер по списку, либо предыдущий."
                )
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch {
                            serversStore.deleteServer(target.id)
                            deleteServer = null
                            Toast.makeText(context, "Сервер удалён.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    destructive = true
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                HopletSecondaryButton(onClick = { deleteServer = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ServerEditorDialog(
    initialServer: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit,
) {
    val baseServer = initialServer ?: ServerProfile(
        id = "",
        name = "",
        host = "",
        sshPort = ServerProfile.DEFAULT_SSH_PORT,
        dns1 = ServerProfile.DEFAULT_DNS1,
        dns2 = ServerProfile.DEFAULT_DNS2,
        dtlsPort = ServerProfile.DEFAULT_DTLS_PORT,
        wgPort = ServerProfile.DEFAULT_WG_PORT,
        httpsPort = SecureApiClient.DEFAULT_HTTPS_PORT,
    )

    var nameInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.name) }
    var hostInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.host) }
    var sshUserInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.sshUser) }
    var sshPortInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.sshPort.toString()) }
    var dns1Input by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.dns1) }
    var dns2Input by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.dns2) }
    var dtlsPortInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.dtlsPort.toString()) }
    var wgPortInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.wgPort.toString()) }
    var httpsPortInput by rememberSaveable(initialServer?.id) { mutableStateOf(baseServer.httpsPort.toString()) }

    fun normalizePort(raw: String, fallback: Int): Int {
        return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: fallback
    }

    HopletDialog(onDismissRequest = onDismiss) {
        HopletModalSurface(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HopletSectionTitle(if (initialServer == null) "Добавить сервер" else "Редактировать сервер")
                    HopletDialogCloseButton(onClick = onDismiss)
                }

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Название") },
                    placeholder = { Text("Основной сервер") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it.trim() },
                    label = { Text("Host / IP") },
                    placeholder = { Text("1.2.3.4") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = sshUserInput,
                    onValueChange = { sshUserInput = it.trim() },
                    label = { Text("SSH user") },
                    placeholder = { Text("root") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = sshPortInput,
                    onValueChange = { sshPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("SSH port") },
                    placeholder = { Text(ServerProfile.DEFAULT_SSH_PORT.toString()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = dns1Input,
                    onValueChange = { dns1Input = it.trim() },
                    label = { Text("DNS 1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = dns2Input,
                    onValueChange = { dns2Input = it.trim() },
                    label = { Text("DNS 2") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = dtlsPortInput,
                    onValueChange = { dtlsPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("DTLS port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = wgPortInput,
                    onValueChange = { wgPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("WG port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = httpsPortInput,
                    onValueChange = { httpsPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("HTTPS port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = HopletModalDefaults.fieldShape,
                    colors = hopletOutlinedTextFieldColors()
                )

                HopletPrimaryButton(
                    onClick = {
                        val dtlsPort = normalizePort(dtlsPortInput, ServerProfile.DEFAULT_DTLS_PORT)
                        val wgPort = normalizePort(wgPortInput, ServerProfile.DEFAULT_WG_PORT)
                        val profile = baseServer.copy(
                            name = nameInput.trim(),
                            host = hostInput,
                            sshUser = sshUserInput,
                            sshPort = normalizePort(sshPortInput, ServerProfile.DEFAULT_SSH_PORT),
                            dns1 = dns1Input,
                            dns2 = dns2Input,
                            dtlsPort = dtlsPort,
                            wgPort = wgPort,
                            httpsPort = normalizePort(httpsPortInput, SecureApiClient.DEFAULT_HTTPS_PORT),
                            manualPortsEnabled = dtlsPort != ServerProfile.DEFAULT_DTLS_PORT ||
                                wgPort != ServerProfile.DEFAULT_WG_PORT,
                        )
                        onSave(profile)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hostInput.isNotBlank()
                ) {
                    Text(if (initialServer == null) "Добавить сервер" else "Сохранить")
                }
            }
        }
    }
}
