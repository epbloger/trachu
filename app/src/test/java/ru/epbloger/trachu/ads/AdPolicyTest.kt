package ru.epbloger.trachu.ads

import org.junit.Assert.*
import org.junit.Test

class AdPolicyTest {
    @Test fun decliningOrWithdrawingPersonalizationDoesNotDisableConfiguredBanner() {
        for (consent in listOf(null,false,true)) {
            assertTrue(adBannerEnabled("configured-banner"))
            assertEquals(consent==true,adPersonalization(consent,false))
            assertFalse(adPersonalization(consent,true))
            assertFalse(adBannerEnabled(""))
        }
    }
    @Test fun noChoiceIsNotConsent() {
        assertFalse(adPersonalization(null,false))
        assertFalse(adPersonalization(false,false))
        assertTrue(adPersonalization(true,false))
    }
}
