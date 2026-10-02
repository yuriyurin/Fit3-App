package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3BatteryCodecTest {
    @Test fun parsesFullSettingsBattery() {
        assertEquals(Fit3BatteryCodec.Reading(58, false),
            Fit3BatteryCodec.parse(byteArrayOf(0x42, 5, 58, 6, 0)))
        assertEquals(Fit3BatteryCodec.Reading(100, true),
            Fit3BatteryCodec.parse(byteArrayOf(0x42, 5, 100, 6, 1)))
    }

    @Test fun toleratesOrderAndExtraProviderFields() {
        assertEquals(Fit3BatteryCodec.Reading(58, false),
            Fit3BatteryCodec.parse(byteArrayOf(0x42, 6, 0, 9, 77, 5, 58)))
    }

    @Test fun rejectsOtherOrInvalidPackets() {
        assertNull(Fit3BatteryCodec.parse(byteArrayOf(0x42, 5, 101, 6, 0)))
        assertNull(Fit3BatteryCodec.parse(byteArrayOf(0x42, 5, 58, 6)))
        assertNull(Fit3BatteryCodec.parse(byteArrayOf(0x41, 5, 58, 6, 0)))
    }
}
