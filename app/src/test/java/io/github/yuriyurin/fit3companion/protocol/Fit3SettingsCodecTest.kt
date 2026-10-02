package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fit3SettingsCodecTest {
    @Test
    fun parsesCapturedAza3FullSettingsResponse() {
        val packet = byteArrayOf(
            0x60, 0x16,
            0x00, 0x00,
            0x01, 0x00,
            0x02, 0x00,
            0x03, 0x01,
            0x04, 0x01,
            0x05, 0x01, 0x00,
            0x06, 0x01, 0x00,
            0x07, 0x0A,
            0x08, 0x01,
            0x09, 0x00,
            0x0A, 0x01,
            0x0B, 0x00,
            0x0C, 0x00,
            0x0D, 0x01,
            0x0E, 0x3C, 0x00,
            0x0F, 0x14, 0x00,
            0x10, 0x00, 0x00,
            0x11, 0x01, 0x00,
            0x12, 0x00, 0x00,
            0x13, 0x01,
            0x14, 0x00,
            0x15, 0x01,
        )
        val state = Fit3SettingsCodec.merge(Fit3SettingsCodec.State(), packet)
        assertEquals(10, state.brightness)
        assertTrue(state.autoBrightness == true)
        assertFalse(state.alwaysOnDisplay == true)
        assertTrue(state.raiseToWake == true)
        assertFalse(state.touchToWake == true)
        assertFalse(state.autoMediaControl == true)
        assertEquals(60, state.timeoutSeconds)
        assertEquals(1, state.doublePressAction)
        assertEquals(0, state.statusIndicator)
        assertEquals(0, state.vibrationIntensity)
        assertFalse(state.rightWrist == true)
        assertTrue(state.buttonOnRight == true)
    }

    @Test
    fun standaloneTimeoutReplyIsByteButSetterIsShort() {
        val reply = byteArrayOf(0x47, 0x0E, 0x3C)
        val state = Fit3SettingsCodec.merge(Fit3SettingsCodec.State(), reply)
        assertEquals(60, state.timeoutSeconds)
        // The band can report 60 s, but the confirmed selectable values stop at 30 s.
        assertTrue(Fit3SettingsCodec.setScreenTimeout(30).contentEquals(
            byteArrayOf(0x07, 0x0E, 0x1E, 0x00)))
    }

    @Test
    fun advancedReplyIsByteButSettersRemainSamsungShortForm() {
        val reply = byteArrayOf(0x48, 0x11, 0x02, 0x12, 0x01)
        val state = Fit3SettingsCodec.merge(Fit3SettingsCodec.State(), reply)
        assertEquals(2, state.doublePressAction)
        assertEquals(1, state.statusIndicator)
        assertTrue(Fit3SettingsCodec.setDoublePressAction(2).contentEquals(
            byteArrayOf(0x08, 0x11, 0x02, 0x00)))
        assertTrue(Fit3SettingsCodec.setStatusIndicator(1).contentEquals(
            byteArrayOf(0x08, 0x12, 0x01, 0x00)))
    }

    @Test fun orientationUsesSamsungMessageTenAndParametersTwentyAndTwentyOne() {
        assertTrue(Fit3SettingsCodec.setRightWrist(true).contentEquals(
            byteArrayOf(0x0A, 0x14, 0x01)))
        assertTrue(Fit3SettingsCodec.setButtonOnRight(false).contentEquals(
            byteArrayOf(0x0A, 0x15, 0x00)))
    }
}
