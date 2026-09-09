package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {
    @Test
    fun `resolves start and stop shortcut actions`() {
        assertEquals(
            AppShortcutAction.START_TUNNEL,
            AppShortcuts.resolveShortcutAction(AppShortcuts.ACTION_START_TUNNEL)
        )
        assertEquals(
            AppShortcutAction.STOP_TUNNEL,
            AppShortcuts.resolveShortcutAction(AppShortcuts.ACTION_STOP_TUNNEL)
        )
    }

    @Test
    fun `unknown action does not dispatch to vpn`() {
        var startCalls = 0
        var stopCalls = 0

        val handled = AppShortcuts.dispatchShortcutAction(
            action = "com.wdtt.client.shortcut.UNKNOWN",
            onAddProfile = { error("should not be called") },
            onStartTunnel = { startCalls++ },
            onStopTunnel = { stopCalls++ },
        )

        assertFalse(handled)
        assertEquals(0, startCalls)
        assertEquals(0, stopCalls)
    }

    @Test
    fun `start shortcut is ignored while tunnel is already running or connecting`() {
        assertTrue(AppShortcuts.shouldStartTunnel(running = false, connecting = false))
        assertFalse(AppShortcuts.shouldStartTunnel(running = true, connecting = false))
        assertFalse(AppShortcuts.shouldStartTunnel(running = false, connecting = true))
    }
}
