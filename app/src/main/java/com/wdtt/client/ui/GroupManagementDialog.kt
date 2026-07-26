package com.wdtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wdtt.client.ProfileGroup
import com.wdtt.client.ProfilesStore
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManagementDialog(
    groups: List<ProfileGroup>,
    subscriptionGroupIds: Set<String> = emptySet(),
    profilesStore: ProfilesStore,
    onDismissRequest: () -> Unit,
    onExportGroup: (ProfileGroup) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<ProfileGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<ProfileGroup?>(null) }

    HopletAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { HopletSectionTitle("Управление папками") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (groups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            )
                            HopletDialogBodyText(text = "У вас пока нет папок")
                        }
                    }
                } else {
                    groups.forEach { group ->
                        val isSubscription = subscriptionGroupIds.contains(group.id)
                        AppSectionCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            color = HopletModalDefaults.softContainerColor(),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSubscription) Icons.Filled.RssFeed else Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.size(12.dp))
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Row {
                                    IconButton(onClick = { onExportGroup(group) }, modifier = Modifier.size(36.dp)) {
                                        Icon(
                                            imageVector = Icons.Filled.Share,
                                            contentDescription = "Экспорт",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (!isSubscription) {
                                        IconButton(onClick = { editGroup = group }, modifier = Modifier.size(36.dp)) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "Переименовать",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = { deleteGroup = group }, modifier = Modifier.size(36.dp)) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Удалить",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            HopletPrimaryButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Новая папка")
            }
        },
        dismissButton = {
            HopletTextActionButton(onClick = onDismissRequest) {
                Text("Закрыть")
            }
        }
    )

    if (showAddDialog || editGroup != null) {
        var nameInput by remember(editGroup, showAddDialog) { mutableStateOf(editGroup?.name ?: "") }
        HopletAlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editGroup = null
            },
            title = {
                HopletSectionTitle(
                    if (editGroup == null) "Создать папку" else "Переименовать папку"
                )
            },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Название папки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HopletModalDefaults.fieldShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = hopletOutlinedTextFieldColors()
                )
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        val finalName = nameInput.trim()
                        val groupToSave = editGroup
                        val isEditing = groupToSave != null
                        val isAdding = showAddDialog
                        if (finalName.isNotBlank() && (isAdding || isEditing)) {
                            showAddDialog = false
                            editGroup = null
                            scope.launch {
                                if (groupToSave == null) {
                                    profilesStore.saveGroup(
                                        ProfileGroup(id = UUID.randomUUID().toString(), name = finalName)
                                    )
                                } else {
                                    profilesStore.saveGroup(groupToSave.copy(name = finalName))
                                }
                            }
                        }
                    },
                    enabled = nameInput.isNotBlank()
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                HopletTextActionButton(
                    onClick = {
                        showAddDialog = false
                        editGroup = null
                    }
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (deleteGroup != null) {
        val target = deleteGroup!!
        val isSubDelete = subscriptionGroupIds.contains(target.id)
        HopletAlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = {
                HopletSectionTitle(
                    if (isSubDelete) "Удалить подписку?" else "Удалить папку?"
                )
            },
            text = {
                HopletDialogBodyText(
                    text = if (isSubDelete) {
                        "Подписка «${target.name}» и все её профили будут удалены."
                    } else {
                        "Папка «${target.name}» и все профили в ней будут удалены без возможности восстановления."
                    }
                )
            },
            confirmButton = {
                HopletPrimaryButton(
                    onClick = {
                        scope.launch {
                            profilesStore.deleteGroup(target.id)
                            deleteGroup = null
                        }
                    },
                    destructive = true
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                HopletTextActionButton(onClick = { deleteGroup = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
