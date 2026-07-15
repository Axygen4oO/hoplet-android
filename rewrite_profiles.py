import re

with open('app/src/main/java/com/wdtt/client/ui/ProfilesTab.kt', 'r') as f:
    content = f.read()

# Add imports
imports = """import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
"""
content = content.replace("import androidx.compose.foundation.layout.Box", imports, 1)

# Add showDeleteDialogFor state
state_code = """    var showFormatsInfoDialog by remember { mutableStateOf(false) }
    var showDeleteDialogFor by remember { mutableStateOf<ConnectionProfile?>(null) }
    var profilesList by remember { mutableStateOf(emptyList<ConnectionProfile>()) }

    LaunchedEffect(sortedProfiles) {
        profilesList = sortedProfiles
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            profilesList = profilesList.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        },
        canDragOver = { _, _ -> !sortByPing },
        onDragEnd = { startIndex, endIndex ->
            if (startIndex != endIndex) {
                val orderedIds = profilesList.map { it.id }
                scope.launch { profilesStore.updateProfileOrder(orderedIds) }
            }
        }
    )
"""
content = content.replace("    var showFormatsInfoDialog by remember { mutableStateOf(false) }", state_code, 1)

# Add AlertDialog
dialog_code = """
    if (showDeleteDialogFor != null) {
        val profileToDelete = showDeleteDialogFor!!
        AlertDialog(
            onDismissRequest = { showDeleteDialogFor = null },
            title = { Text("Удалить профиль") },
            text = { Text("Вы действительно хотите удалить конфигурацию «${profileToDelete.name}»?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { profilesStore.deleteProfile(profileToDelete.id) }
                    showDeleteDialogFor = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogFor = null }) { Text("Отмена") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
"""
content = content.replace("    Box(modifier = Modifier.fillMaxSize()) {", dialog_code, 1)

# Replace Column with LazyColumn
# From `Column(modifier = Modifier.fillMaxSize().verticalScroll...` to the end of Row for header.
column_start = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {"""

lazy_column_start = """        LazyColumn(
            state = reorderState.listState,
            modifier = Modifier
                .fillMaxSize()
                .reorderable(reorderState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp)
        ) {
            item {
"""
content = content.replace(column_start, lazy_column_start, 1)

# Find the empty state
empty_state_start = """        if (sortedProfiles.isEmpty()) {"""
empty_state_lazy = """            } // end of item header
            if (profilesList.isEmpty()) {
                item {"""
content = content.replace(empty_state_start, empty_state_lazy, 1)

# Find the end of empty state Box
empty_state_end = """            }
        } else {"""
empty_state_end_lazy = """                }
            }
        } else {"""
content = content.replace(empty_state_end, empty_state_end_lazy, 1)

# Find forEach loop
foreach_start = """            val visibleProfiles = sortedProfiles.filterNot { pendingDeletes.contains(it.id) }
            visibleProfiles.forEach { profile ->"""

items_start = """            val visibleProfiles = profilesList.filterNot { pendingDeletes.contains(it.id) }
            items(visibleProfiles, key = { it.id }) { profile ->
                ReorderableItem(reorderState, key = profile.id) { isDragging ->
"""
content = content.replace(foreach_start, items_start, 1)

# Replace dismiss state confirmStateChange
dismiss_old = """                    confirmStateChange = { value ->
                        if (value == DismissValue.DismissedToStart) {
                            // mark pending delete and show Snackbar for Undo
                            pendingDeletes = pendingDeletes + profile.id
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Профиль \\"${profile.name}\\" удалён",
                                    actionLabel = "Отменить",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    pendingDeletes = pendingDeletes - profile.id
                                } else {
                                    profilesStore.deleteProfile(profile.id)
                                    pendingDeletes = pendingDeletes - profile.id
                                }
                            }
                        }
                        true
                    }"""

dismiss_new = """                    confirmStateChange = { value ->
                        if (value == DismissValue.DismissedToStart) {
                            showDeleteDialogFor = profile
                        }
                        false // Return false to bounce back, dialog will handle deletion
                    }"""
content = content.replace(dismiss_old, dismiss_new, 1)

# Add detectReorderAfterLongPress to AppSectionCard modifier
card_mod_old = """                        modifier = Modifier.clickable {"""
card_mod_new = """                        modifier = Modifier.detectReorderAfterLongPress(reorderState).clickable {"""
content = content.replace(card_mod_old, card_mod_new, 1)

# We need to add closing braces for ReorderableItem.
# Find the end of `SwipeToDismiss { AppSectionCard { ... } }`
# I'll just write it back to the file and manually fix any brace issues.
with open('app/src/main/java/com/wdtt/client/ui/ProfilesTab.kt', 'w') as f:
    f.write(content)
print("Rewrite script completed!")
