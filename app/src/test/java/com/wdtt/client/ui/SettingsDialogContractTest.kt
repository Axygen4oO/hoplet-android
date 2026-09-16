package com.wdtt.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsDialogContractTest {
    private val settingsSource: String by lazy {
        val candidates = listOf(
            File("app/src/main/java/com/wdtt/client/ui/SettingsTab.kt"),
            File("src/main/java/com/wdtt/client/ui/SettingsTab.kt"),
        )
        candidates.firstOrNull(File::isFile)?.readText()
            ?: error("SettingsTab.kt was not found from ${File(".").absolutePath}")
    }

    private val appSettingsDialog: String by lazy {
        settingsSource.substringAfter("if (showAppSettingsDialog) {")
            .substringBefore("if (showGeneralSettingsDialog) {")
    }

    @Test
    fun removedActionsAreAbsentFromSettingsDialog() {
        assertFalse(appSettingsDialog.contains("Text(\"Telegram\""))
        assertFalse(appSettingsDialog.contains("Text(\"Личный кабинет\""))
        assertFalse(appSettingsDialog.contains("Бета-обновления"))
        assertFalse(appSettingsDialog.contains("Включать предварительные релизы GitHub"))
        assertFalse(appSettingsDialog.contains("saveIncludeBetaUpdates"))
    }

    @Test
    fun gettingStartedActionStillOpensWelcomeDialog() {
        assertTrue(appSettingsDialog.contains("Text(\"Как начать\")"))
        assertTrue(appSettingsDialog.contains("showWelcomeDialog = true"))
    }

    @Test
    fun notificationPreferencesRemainConnected() {
        assertTrue(settingsSource.contains("Text(\"Уведомления\""))
        assertTrue(settingsSource.contains("settingsStore.savePushPreferences"))
        assertTrue(settingsSource.contains("PushPreferenceRow(\"Push-уведомления\""))
    }

    @Test
    fun settingsDialogUsesCompactLayoutWithoutRemovedPlaceholders() {
        assertTrue(appSettingsDialog.contains(".heightIn(max = 420.dp)"))
        assertTrue(appSettingsDialog.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        assertFalse(appSettingsDialog.contains(".heightIn(max = 480.dp)"))
        assertFalse(appSettingsDialog.contains("Spacer(Modifier.height(16.dp))"))
    }
}
