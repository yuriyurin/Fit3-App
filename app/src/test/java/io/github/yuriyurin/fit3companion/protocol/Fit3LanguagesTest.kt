package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class Fit3LanguagesTest {
    @Test fun followsAppWithoutRussianFallback() {
        assertEquals(13, Fit3Languages.localeId("app", "en"))
        assertEquals(57, Fit3Languages.localeId("app", "ru"))
        assertEquals(32, Fit3Languages.localeId("ja", "ru"))
        assertEquals(32, Fit3Languages.localeId("ja", "en"))
    }

    @Test fun languageChangeMatchesOfficialChannel11Packet() {
        assertArrayEquals(byteArrayOf(1, 4, 13, 0), Fit3Languages.languagePacket(13))
        assertArrayEquals(byteArrayOf(1, 4, 57, 0), Fit3Languages.languagePacket(57))
    }

    @Test fun everyFirmwareLanguageCanBeEncodedInOobe() {
        assertEquals(69, Fit3Languages.ids.size)
        assertFalse(Fit3Languages.isSelection("unsupported"))
        Fit3Languages.ids.forEach { (tag, id) ->
            assertEquals(id, Fit3Languages.localeId(tag, "en"))
            val packet = Fit3OobeCodec.initSettingsRequest(Instant.EPOCH, ZoneId.of("UTC"), id)
            assertEquals(id, (packet[2].toInt() and 255) or ((packet[3].toInt() and 255) shl 8))
        }
    }
}
