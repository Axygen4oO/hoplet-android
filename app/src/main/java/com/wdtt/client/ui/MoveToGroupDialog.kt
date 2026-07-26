package com.wdtt.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wdtt.client.ConnectionProfile
import com.wdtt.client.ProfileGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToGroupDialog(
    profile: ConnectionProfile,
    groups: List<ProfileGroup>,
    excludedGroupIds: Set<String> = emptySet(),
    onDismissRequest: () -> Unit,
    onGroupSelected: (String) -> Unit
) {
    HopletAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { HopletSectionTitle("Переместить в папку") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (profile.groupId.isNotEmpty()) {
                    item {
                        GroupChoiceCard(
                            onClick = { onGroupSelected("") }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Убрать из папки",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    HopletDialogBodyText(
                                        text = "Профиль вернётся в общий список без группы.",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                if (groups.isEmpty() && profile.groupId.isEmpty()) {
                    item {
                        HopletDialogBodyText(
                            text = "Сначала создайте папку в меню 'Управление папками'",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                items(groups.filterNot { excludedGroupIds.contains(it.id) }) { group ->
                    val isSelected = group.id == profile.groupId
                    GroupChoiceCard(
                        selected = isSelected,
                        onClick = { onGroupSelected(group.id) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    HopletDialogBodyText(
                                        text = if (isSelected) {
                                            "Текущая папка профиля"
                                        } else {
                                            "Переместить в эту папку"
                                        }
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Текущая папка",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            HopletTextActionButton(onClick = onDismissRequest) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun GroupChoiceCard(
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    AppSectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            HopletModalDefaults.softContainerColor()
        },
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        content()
    }
}
