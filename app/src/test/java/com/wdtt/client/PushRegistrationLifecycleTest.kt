package com.wdtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class PushRegistrationLifecycleTest {
    private class Harness {
        private val lifecycle = PushRegistrationLifecycle()
        private var token: String? = null
        private var subscription: String? = null
        private var registeredToken: String? = null
        private var registeredSubscription: String? = null
        var validSubscription = true
        var registrations = 0
        var unregisters = 0
        var networkSucceeds = true
        fun tokenReady(value: String) { token = value; attempt() }
        fun subscriptionReady(value: String?) { subscription = value; attempt() }
        fun changeSubscription(value: String) { subscription = value; attempt() }
        fun removeSubscription() { unregisters++; subscription = null; registeredToken = null; registeredSubscription = null; lifecycle.clear() }
        fun retry() = attempt()
        private fun attempt() {
            if (!validSubscription) return
            when (val decision = lifecycle.begin(token, subscription, registeredToken, registeredSubscription)) {
                is PushRegistrationLifecycle.Decision.Start -> {
                    registrations++
                    if (networkSucceeds) { registeredToken = decision.key.tokenHash; registeredSubscription = decision.key.subscriptionHash }
                    lifecycle.finish(decision.key)
                }
                else -> Unit
            }
        }
    }
    @Test fun `A token and imported subscription register`() { val h = Harness(); h.tokenReady("token"); h.subscriptionReady("subscription"); assertEquals(1, h.registrations) }
    @Test fun `B token first waits for import`() { val h = Harness(); h.tokenReady("token"); assertEquals(0, h.registrations); h.subscriptionReady("subscription"); assertEquals(1, h.registrations) }
    @Test fun `C subscription first waits for token`() { val h = Harness(); h.subscriptionReady("subscription"); assertEquals(0, h.registrations); h.tokenReady("token"); assertEquals(1, h.registrations) }
    @Test fun `D token refresh updates registration`() { val h = Harness(); h.subscriptionReady("subscription"); h.tokenReady("token-a"); h.tokenReady("token-b"); assertEquals(2, h.registrations) }
    @Test fun `E changed subscription creates a new registration`() { val h = Harness(); h.subscriptionReady("subscription-a"); h.tokenReady("token"); h.changeSubscription("subscription-b"); assertEquals(2, h.registrations) }
    @Test fun `F removed subscription disables registration`() { val h = Harness(); h.subscriptionReady("subscription"); h.tokenReady("token"); h.removeSubscription(); assertEquals(1, h.unregisters) }
    @Test fun `G no subscription never registers`() { val h = Harness(); h.tokenReady("token"); assertEquals(0, h.registrations) }
    @Test fun `H invalid subscription is rejected`() { val h = Harness(); h.validSubscription = false; h.tokenReady("token"); h.subscriptionReady("invalid"); assertEquals(0, h.registrations) }
    @Test fun `I duplicate registration is suppressed`() { val h = Harness(); h.subscriptionReady("subscription"); h.tokenReady("token"); h.retry(); assertEquals(1, h.registrations) }
    @Test fun `network failure permits retry`() { val h = Harness(); h.networkSucceeds = false; h.subscriptionReady("subscription"); h.tokenReady("token"); h.networkSucceeds = true; h.retry(); assertEquals(2, h.registrations) }
}
