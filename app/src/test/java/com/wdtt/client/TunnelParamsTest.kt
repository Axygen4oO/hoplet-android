package com.wdtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelParamsTest {
    @Test
    fun `copy keeps original transport snapshot intact`() {
        val original = TunnelParams(
            peer = "1.2.3.4:56000",
            vkHashes = "hash",
            workersPerHash = 9,
            port = 9000,
            transportMode = TransportMode.TURN_TCP
        )
        val updated = original.copy(transportMode = TransportMode.NORMAL)

        assertTrue(original.transportMode.isTurnTcp())
        assertFalse(updated.transportMode.isTurnTcp())
    }
}
