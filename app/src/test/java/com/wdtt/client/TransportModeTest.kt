package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportModeTest {
    @Test
    fun `legacy booleans map safely`() {
        assertEquals(TransportMode.NORMAL, transportModeFromLegacy(false, false))
        assertEquals(TransportMode.DIRECT, transportModeFromLegacy(true, false))
        assertEquals(TransportMode.TURN_TCP, transportModeFromLegacy(false, true))
        assertEquals(TransportMode.DIRECT, transportModeFromLegacy(true, true))
    }

    @Test
    fun `auto resolves to turn tcp runtime`() {
        assertEquals(TransportMode.TURN_TCP, TransportMode.AUTO.resolveForRuntime())
    }

    @Test
    fun `unknown mode falls back to normal`() {
        assertEquals(TransportMode.NORMAL, normalizeTransportMode("mystery"))
    }

    @Test
    fun `raw tun mode parses and persists`() {
        assertEquals(TransportMode.RAW_TUN, normalizeTransportMode("raw_tun"))
        assertEquals("raw_tun", TransportMode.RAW_TUN.toPersistedValue())
        assertTrue(TransportMode.RAW_TUN.isRawTun())
    }

    @Test
    fun `connection diagnostics label the actual transport`() {
        assertEquals("DTLS", diagnosticLabel(TransportMode.NORMAL))
        assertEquals("Direct", diagnosticLabel(TransportMode.DIRECT))
        assertEquals("TURN TCP / DTLS", diagnosticLabel(TransportMode.TURN_TCP))
        assertEquals("RAW", diagnosticLabel(TransportMode.RAW_TUN))
    }

    @Test
    fun `direct errors never keep a DTLS label`() {
        assertEquals("Direct timeout", transportErrorLabel("DTLS timeout", "Direct"))
        assertEquals("RAW error", transportErrorLabel("DTLS error", "RAW"))
        assertEquals("TURN TCP / DTLS timeout", transportErrorLabel("DTLS timeout", "TURN TCP / DTLS"))
    }

    private fun diagnosticLabel(mode: TransportMode): String {
        val state = ConnectionState(
            currentStage = ConnectionStage.DTLS,
            stageStatuses = emptyMap(),
            statusText = "",
            timeoutSeconds = null,
            errorReason = null,
            lifecycle = ConnectionLifecycle.CONNECTING,
            transportLabel = when (mode) {
                TransportMode.DIRECT -> "Direct"
                TransportMode.TURN_TCP -> "TURN TCP / DTLS"
                TransportMode.RAW_TUN -> "RAW"
                TransportMode.AUTO, TransportMode.NORMAL -> "DTLS"
            },
        )
        return state.displayName(ConnectionStage.DTLS)
    }
}
