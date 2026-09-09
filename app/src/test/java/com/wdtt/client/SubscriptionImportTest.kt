package com.wdtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImportTest {
    @Test
    fun `connection profile defaults to auto`() {
        val profile = ConnectionProfile(
            id = "1",
            name = "p",
            peer = "1.2.3.4:56000",
            vkHashes = "hash",
            workersPerHash = 9,
            listenPort = 9000,
            password = "pass"
        )

        assertTrue(profile.transportMode == TransportMode.AUTO)
    }

    @Test
    fun `json import reads transport mode`() {
        val parsed = SubscriptionImport.parsePayload(
            """{"profiles":[{"name":"p","peer":"1.2.3.4:56000","vkHashes":"hash","workersPerHash":9,"listenPort":9000,"password":"pass","transportMode":"direct"}]}"""
        )

        assertNotNull(parsed)
        assertFalse(parsed!!.profiles.first().transportMode.isTurnTcp())
    }
}
