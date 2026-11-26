package ru.epbloger.trachu.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {
    @Test fun legacyAndModernNotificationAccessReflectTheOsPermission() {
        assertTrue(notificationAccessAllowed(26,true,false))
        assertTrue(notificationAccessAllowed(32,true,false))
        assertTrue(notificationAccessAllowed(33,true,true))
        assertTrue(notificationAccessAllowed(36,true,true))
        assertFalse(notificationAccessAllowed(33,true,false))
        assertFalse(notificationAccessAllowed(36,true,false))
        assertFalse(notificationAccessAllowed(36,false,true))
    }
}
