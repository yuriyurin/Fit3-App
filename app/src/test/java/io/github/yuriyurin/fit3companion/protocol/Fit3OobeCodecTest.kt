package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class Fit3OobeCodecTest {
    @Test fun deviceStatusMatchesPcClient() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00), Fit3OobeCodec.deviceStatusRequest)
    }

    @Test fun agreementMatchesPcClient() {
        assertArrayEquals(byteArrayOf(0x84.toByte(), 0x01, 0x01),
            Fit3OobeCodec.userAgreementRequest)
    }

    @Test fun initSettingsMatchesPcClient() {
        val request = Fit3OobeCodec.initSettingsRequest(
            Instant.ofEpochSecond(1_700_000_000), ZoneId.of("Europe/Moscow"))
        assertEquals("830139000200030a313730303030303030300400302a0501",
            request.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    @Test fun onlyOobeResponseHeadersAreAccepted() {
        assertEquals(3, Fit3OobeCodec.responseId(byteArrayOf(0x43)))
        assertEquals(null, Fit3OobeCodec.responseId(byteArrayOf(0x03)))
        assertEquals(null, Fit3OobeCodec.responseId(byteArrayOf()))
    }
}
