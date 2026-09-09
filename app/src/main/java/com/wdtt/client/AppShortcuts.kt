package com.wdtt.client

import android.content.Context
import android.content.pm.ShortcutManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AppShortcutAction {
    ADD_PROFILE,
    START_TUNNEL,
    STOP_TUNNEL,
}

object AppShortcuts {
    const val ACTION_ADD_PROFILE = "com.wdtt.client.shortcut.ADD_PROFILE"
    const val ACTION_START_TUNNEL = "com.wdtt.client.shortcut.START_TUNNEL"
    const val ACTION_STOP_TUNNEL = "com.wdtt.client.shortcut.STOP_TUNNEL"

    const val ID_ADD_PROFILE = "shortcut_add_profile"
    const val ID_START_TUNNEL = "shortcut_start_tunnel"
    const val ID_STOP_TUNNEL = "shortcut_stop_tunnel"

    private val removedShortcutIds = listOf(
        "shortcut_toggle_tunnel",
        "shortcut_toggle_vk_mode",
    )

    fun resolveShortcutAction(action: String?): AppShortcutAction? = when (action) {
        ACTION_ADD_PROFILE -> AppShortcutAction.ADD_PROFILE
        ACTION_START_TUNNEL -> AppShortcutAction.START_TUNNEL
        ACTION_STOP_TUNNEL -> AppShortcutAction.STOP_TUNNEL
        else -> null
    }

    fun dispatchShortcutAction(
        action: String?,
        onAddProfile: () -> Unit,
        onStartTunnel: () -> Unit,
        onStopTunnel: () -> Unit,
    ): Boolean {
        when (resolveShortcutAction(action)) {
            AppShortcutAction.ADD_PROFILE -> onAddProfile()
            AppShortcutAction.START_TUNNEL -> onStartTunnel()
            AppShortcutAction.STOP_TUNNEL -> onStopTunnel()
            null -> return false
        }
        return true
    }

    fun shouldStartTunnel(running: Boolean, connecting: Boolean): Boolean = !running && !connecting

    fun refreshAsync(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        CoroutineScope(Dispatchers.IO).launch {
            cleanupRemovedShortcuts(context.applicationContext)
        }
    }

    private fun cleanupRemovedShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            shortcutManager.disableShortcuts(removedShortcutIds)
            shortcutManager.removeDynamicShortcuts(removedShortcutIds)
        }
    }
}
