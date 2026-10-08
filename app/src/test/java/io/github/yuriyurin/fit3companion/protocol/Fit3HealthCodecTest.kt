package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class Fit3HealthCodecTest {
    @Test
    fun repliesToRealFit3GmCapabilityRequest() {
        val request = byteArrayOf(
            0xA2.toByte(), 0x02,
            0xE0.toByte(), 0x17, 0x27,
            0xE1.toByte(), 0x2D, 0x01,
        )
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), request, 100L)
        assertTrue(result.state.sessionReady)
        assertEquals(301, result.state.healthProtocolVersion)
        assertEquals(Fit3HealthCodec.Event.GM_CAPABILITY_REQUEST, result.event)
        val reply = requireNotNull(result.reply)
        assertEquals(0xE2, reply[0].toInt() and 0xff)
        assertEquals(6, reply[1].toInt() and 0xff)
    }


    @Test
    fun repliesToRealWearableCapabilityRequestAndEchoesSequence() {
        // Prefix copied from the real AZA3 frame shown in Protocol Monitor. The remaining
        // 23-field capability body is not needed to identify the request or its E0 sequence.
        val request = byteArrayOf(
            0xA0.toByte(), 0x17,
            0xE0.toByte(), 0x3A, 0x27,
            0xA0.toByte(), 0x01,
            0xA1.toByte(), 0x04, 0x33,
            0xA3.toByte(), 0x11,
        )
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), request, 100L)
        assertTrue(result.state.sessionReady)
        assertEquals(Fit3HealthCodec.Event.WEARABLE_CAPABILITY_REQUEST, result.event)

        val reply = requireNotNull(result.reply)
        assertEquals(0xE0, reply[0].toInt() and 0xff)
        assertEquals(0xE0, reply[2].toInt() and 0xff)
        assertEquals(0x3A, reply[3].toInt() and 0xff)
        assertEquals(0x27, reply[4].toInt() and 0xff)
        assertTrue(reply.size > 500) // must exercise SAP fragmentation path
    }

    @Test
    fun acknowledgesSyncDataRequest() {
        val request = byteArrayOf(0x82.toByte(), 0x03, 0xE0.toByte(), 0x6D, 0x28, 0x07)
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), request, 100L)
        assertEquals(Fit3HealthCodec.Event.SYNC_DATA, result.event)
        assertArrayEquals(byteArrayOf(
            0x42, 0x03, 0xE0.toByte(), 0x6D, 0x28,
            0x04, 0x02, 0x01, 0x01,
        ) + Fit3HealthCodec.deviceInfo(0), result.reply)
    }

    @Test
    fun doesNotAcknowledgeSyncDataWithoutSequence() {
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), byteArrayOf(0x82.toByte()), 100L)
        assertNull(result.reply)
    }

    @Test
    fun requestDataResponseEchoesRealWatchSequenceWithoutFieldCount() {
        val request = byteArrayOf(
            0x03, 0xE0.toByte(), 0x4C, 0x27, 0x04, 0x02, 0x01, 0x01,
            0x05, 0x19, 0x01, 0x19, 0x02, 0x4C, 0x27,
        )
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), request, 100L)
        assertEquals(Fit3HealthCodec.Event.REQUEST_DATA, result.event)
        assertArrayEquals(byteArrayOf(
            0x43, 0xE0.toByte(), 0x4C, 0x27,
            0x04, 0x02, 0x01, 0x01,
        ) + Fit3HealthCodec.deviceInfo(0), result.reply)
    }

    @Test
    fun doesNotAcknowledgeRequestDataWithoutSequence() {
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), byteArrayOf(0x03), 100L)
        assertNull(result.reply)
    }

    @Test
    fun acknowledgementResultMatchesStockMessageInfoAndPreservesSequenceBytes() {
        // ModenStringToByteParser.createMessageInfoByte replaces its length placeholder:
        // the final SUCCESS block is 04 02 01 01, with no remaining 00 byte.
        for (sequence in listOf(0, 255, 256, 65535)) {
            val low = sequence.toByte()
            val high = (sequence ushr 8).toByte()
            val sync = byteArrayOf(0x82.toByte(), 1, 0xE0.toByte(), low, high)
            val request = byteArrayOf(3, 0xE0.toByte(), low, high)
            assertArrayEquals(byteArrayOf(0x42, 3, 0xE0.toByte(), low, high, 4, 2, 1, 1) +
                Fit3HealthCodec.deviceInfo(0),
                Fit3HealthCodec.handle(Fit3HealthCodec.State(), sync, 100L).reply)
            assertArrayEquals(byteArrayOf(0x43, 0xE0.toByte(), low, high, 4, 2, 1, 1) +
                Fit3HealthCodec.deviceInfo(0),
                Fit3HealthCodec.handle(Fit3HealthCodec.State(), request, 100L).reply)
        }
    }

    @Test
    fun checkStatusIncludesOfficialFieldCountAndSequence() {
        val request = Fit3HealthCodec.checkStatusRequest(10001)
        assertEquals(listOf(0x01, 0x01, 0xE0, 0x11, 0x27),
            request.map { it.toInt() and 0xff })
    }

    @Test
    fun hostCapabilityProbeMatchesOfficialPlugin() {
        assertEquals(0x34, Fit3HealthCodec.mobileCapabilityRequest.single().toInt() and 0xff)
    }

    @Test fun requestDataMatchesStock31ByteDeviceInfoRequest() {
        val request = Fit3HealthCodec.requestData(10001, 1_234_567L)
        assertEquals(31, request.size)
        assertEquals(listOf(3, 0xE0, 0x11, 0x27, 5, 25, 1, 0, 2, 0, 0, 3, 4, 51, 4),
            request.take(15).map { it.toInt() and 0xff })
        // last_sync_time is the watermark; start and end stay zero as in vendor requests.
        assertEquals(1_234_567, SaMessageCodec.readLeInt(request, 15))
        assertEquals(0, SaMessageCodec.readLeInt(request, 20))
        assertEquals(0, SaMessageCodec.readLeInt(request, 25))
        assertEquals(10001, Fit3HealthCodec.sequence(request))
    }

    @Test fun syncAckClaimsTheBandClockOfTheHandledPush() {
        // A push ending in the band's DEVICE_INFO (tag 6 = its clock, 0x44add387).
        val bandInfo = "0519011902" + "4c27" + "030503" + "0400000000" + "0587d3ad44" + "0687d3ad44" + "0700"
        val push = byteArrayOf(0x82.toByte(), 2, 0xE0.toByte(), 0x20, 0x27) +
            bandInfo.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(0x44add387L, Fit3HealthCodec.trailingBandTime(push))
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), push, 100L)
        assertEquals(0x44add387L, result.state.syncWatermark)
        val reply = requireNotNull(result.reply)
        assertEquals(36, reply.size)
        assertEquals(0x44add387, SaMessageCodec.readLeInt(reply, 9 + 11))
        // The next REQUEST_DATA reply carries the same watermark.
        val next = Fit3HealthCodec.handle(result.state, byteArrayOf(3, 0xE0.toByte(), 0x21, 0x27), 200L)
        assertEquals(0x44add387, SaMessageCodec.readLeInt(requireNotNull(next.reply), 8 + 11))
    }

    @Test fun retainsConfirmedMinimalAcknowledgmentsWithoutReflectingWearableDeviceIdentity() {
        val device = Fit3HealthCodec.requestData(10001, 1790736668000L).copyOfRange(4, 31)
        val sync = byteArrayOf(0x82.toByte(), 2, 0xE0.toByte(), 0x28, 0x27) + device
        val reply = requireNotNull(Fit3HealthCodec.handle(Fit3HealthCodec.State(), sync).reply)
        assertEquals(36, reply.size)
        assertEquals(3, reply[1].toInt())
        val request = byteArrayOf(3, 0xE0.toByte(), 0x28, 0x27) + device
        val requestReply = requireNotNull(Fit3HealthCodec.handle(Fit3HealthCodec.State(), request).reply)
        assertEquals(35, requestReply.size)
        assertTrue(Fit3HealthCodec.isRequestDataSuccess(requestReply, 10024))
        assertFalse(Fit3HealthCodec.isRequestDataSuccess(requestReply, 10001))
    }
}
