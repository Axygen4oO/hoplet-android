package com.wdtt.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeDialogTest {
    @Test
    fun initialPageIsZero() {
        assertEquals(0, INITIAL_WELCOME_PAGE)
    }

    @Test
    fun nextFromPageZeroGoesToPageOne() {
        assertEquals(1, nextWelcomePage(0))
    }

    @Test
    fun nextFromPageOneGoesToPageTwo() {
        assertEquals(2, nextWelcomePage(1))
    }

    @Test
    fun backFromPageTwoGoesToPageOne() {
        assertEquals(1, previousWelcomePage(2))
    }

    @Test
    fun firstRunFinishOpensSubscription() {
        assertEquals(true, shouldOpenSubscriptionAfterWelcome(WelcomeDialogContext.FIRST_RUN))
    }

    @Test
    fun settingsFinishClosesDialog() {
        assertEquals(false, shouldOpenSubscriptionAfterWelcome(WelcomeDialogContext.SETTINGS))
    }

    @Test
    fun pageBoundsAreStable() {
        assertEquals(0, previousWelcomePage(0))
        assertEquals(2, nextWelcomePage(2))
    }
}
