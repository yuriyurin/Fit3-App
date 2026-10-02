package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fit3NotificationCodecTest {
    @Test fun capabilityRequestMatchesStockFixedPacketWithoutParameterCount() {
        assertTrue(byteArrayOf(0x0d).contentEquals(Fit3NotificationCodec.iconCapabilityRequest()))
    }
    @Test fun newNotificationUsesVariableFormatAndKnownFields() {
        val bytes = Fit3NotificationCodec.newNotification(42, "Тест", "Привет",
            "Fit3", "io.test", 1234L)
        assertEquals(0x80, bytes[0].toInt() and 0xff)
        assertEquals(10, bytes[1].toInt() and 0xff)
        assertTrue(bytes.size < 300)
        assertEquals(7, Fit3SapCodec.decodeFrame(Fit3SapCodec.encodeSingle(7, bytes, 500)).serviceId)
    }

    @Test fun ackMustMatchExpectedShape() {
        assertEquals(42 to true, Fit3NotificationCodec.parseAck(
            byteArrayOf(0x40, 1, 42, 0, 0, 0, 11, 1)))
        assertNull(Fit3NotificationCodec.parseAck(byteArrayOf(0x40, 1, 42)))
    }

    @Test fun appIdsDistinguishTelegramClientsAndAvoidGenericZero() {
        assertEquals(226, Fit3NotificationCodec.appIdForPackage("org.telegram.messenger"))
        val nekogram = Fit3NotificationCodec.appIdForPackage("tw.nekomimi.nekogram")
        assertTrue(nekogram > 0)
        assertTrue(nekogram != 226)
        val message = Fit3NotificationCodec.newNotification(7, "Привет", "Сообщение",
            "Nekogram", "tw.nekomimi.nekogram", 1234L)
        val appIdText = nekogram.toString().toByteArray(Charsets.UTF_8)
        assertTrue(message.toList().windowed(appIdText.size).any { window ->
            window.toByteArray().contentEquals(appIdText)
        })
    }

    @Test fun iconUrlIsPartOfNotificationInsteadOfSeparateRegistration() {
        val packet = Fit3NotificationCodec.newNotification(1, "Title", "Body", "Nekogram",
            "tw.nekomimi.nekogram", 1234L, appId = 28,
            iconUrl = "APP_ICON_28", iconNewlyAdded = true)
        assertEquals(12, packet[1].toInt() and 0xff)
        assertTrue(packet.toList().windowed(11).any { it.toByteArray()
            .contentEquals("APP_ICON_28".toByteArray()) })
        assertEquals(18, packet[packet.indexOfLast { it.toInt() == 18 }].toInt())
    }

    @Test fun parsesWatchIconRequestAndCapability() {
        val request = byteArrayOf(3, 9, 11) + "APP_ICON_28".toByteArray() + byteArrayOf(35, 35)
        assertEquals("APP_ICON_28" to 35, Fit3NotificationCodec.parseIconRequest(request))
        val capability = byteArrayOf(13, 2, 5, 5, 6, 1, 35)
        assertEquals(5, Fit3NotificationCodec.parseIconCapability(capability)?.format)
        assertEquals(listOf(35), Fit3NotificationCodec.parseIconCapability(capability)?.sizes)
        val response = Fit3NotificationCodec.iconResponse("APP_ICON_28", byteArrayOf(1, 2, 3), 35)
        assertEquals(0x43, response[0].toInt() and 0xff)
        val imageParam = response.indexOf(7)
        assertEquals(3, response[imageParam + 1].toInt() and 0xff)
        assertEquals(0, response[imageParam + 2].toInt() and 0xff)
        assertEquals(0, response[imageParam + 3].toInt() and 0xff)
        assertEquals(0, response[imageParam + 4].toInt() and 0xff)
        assertEquals(listOf<Byte>(1, 2, 3), response.copyOfRange(imageParam + 5, imageParam + 8).toList())
        assertTrue(response.takeLast(5).toByteArray().contentEquals(byteArrayOf(35, 35, 0, 0, 0)))
    }

    @Test fun fit3LargeIconRequestKeepsTheRequested112Pixels() {
        val request = byteArrayOf(3, 9, 11) + "APP_ICON_28".toByteArray() + byteArrayOf(35, 112)
        assertEquals("APP_ICON_28" to 112, Fit3NotificationCodec.parseIconRequest(request))
        val capability = byteArrayOf(0x4d, 8, 0, 1, 1, 1, 2, 1, 3, 1, 4, 1, 5, 3, 7, 1, 6, 2, 52, 112)
        assertEquals(Fit3NotificationCodec.IconCapability(3, listOf(52, 112)),
            Fit3NotificationCodec.parseIconCapability(capability))
        assertNull(Fit3NotificationCodec.parseIconRequest(request.dropLast(1).toByteArray() + byteArrayOf(127)))
    }
}
