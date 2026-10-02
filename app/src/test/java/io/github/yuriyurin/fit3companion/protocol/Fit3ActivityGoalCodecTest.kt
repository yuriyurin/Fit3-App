package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class Fit3ActivityGoalCodecTest {
    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val time = 631_152_000_000L + 0x451ea77eL * 1000
    @Test fun stockSerializationHasReversedUuidAndNoLengthPrefixes() {
        val request = Fit3ActivityGoalCodec.request(10001, ActivityGoals(), time, 10_800_000, id)
        // 82 + count + E0/seq; four complete stock datasets: 39 + 39 + 39 + 40 bytes.
        assertEquals(162, request.size)
        assertEquals("8205E01127090075FFEEDDCCBBAA99887766554433221100057EA71E45067EA71E4517701700000380CBA400",
            request.take(44).joinToString("") { "%02X".format(it.toInt() and 255) })
        assertEquals(0x7C, request[44].toInt() and 255)
        assertEquals(0x7D, request[83].toInt() and 255)
        assertEquals(0xA2, request[122].toInt() and 255)
        assertArrayEquals(byteArrayOf(0x10, 0, 0x11, 0, 0x12, 0), request.takeLast(6).toByteArray())
        assertEquals(listOf(9, 124, 125), Fit3ActivityGoalCodec.observations(request).map { it.type })
        assertEquals(listOf(6000, 500, 90), Fit3ActivityGoalCodec.observations(request).map { it.value })
    }
    @Test fun firmwareRangesAreValidatedInsteadOfSilentlyWrapping() {
        assertTrue(ActivityGoals(1000, 30, 100).valid())
        assertTrue(ActivityGoals(50_000, 360, 5000).valid())
        for (bad in listOf(ActivityGoals(50_001), ActivityGoals(minutes=361), ActivityGoals(calories=99))) {
            assertFalse(bad.valid())
            assertThrows(IllegalArgumentException::class.java) { Fit3ActivityGoalCodec.request(1, bad, time, 0, id) }
        }
    }
    @Test fun onlyMatchingSequenceAndFullSuccessCanConfirmGoals() {
        val reply = byteArrayOf(0x42, 2, 0xE0.toByte(), 0x11, 0x27, 4, 2, 1, 1)
        assertEquals(true, Fit3ActivityGoalCodec.result(reply, 10001))
        assertNull(Fit3ActivityGoalCodec.result(reply, 10002))
        assertNull(Fit3ActivityGoalCodec.result(byteArrayOf(0x42), 10001))
        assertNull(Fit3ActivityGoalCodec.result(reply.copyOf(8), 10001))
        assertEquals(false, Fit3ActivityGoalCodec.result(reply.copyOf().apply { this[8] = 2 }, 10001))
        assertNull(Fit3ActivityGoalCodec.result(reply.copyOf().apply { this[0] = 0x82.toByte() }, 10001))
    }
    @Test fun malformedOrTruncatedGoalsNeverBecomeObservations() {
        val packet = Fit3ActivityGoalCodec.request(1, ActivityGoals(), time, 0, id)
        assertTrue(Fit3ActivityGoalCodec.observations(packet.copyOf(15)).isEmpty())
        assertTrue(Fit3ActivityGoalCodec.observations(packet.copyOf().apply { this[7] = 0x40 }).isEmpty())
        // A goal-looking byte inside unknown Health data is not scanned as a goal.
        assertTrue(Fit3ActivityGoalCodec.observations(packet.copyOf().apply { this[5] = 7 }).isEmpty())
    }
    @Test fun actualAza3VariableResponseIncludesLengthPrefixedDeviceInfo() {
        val hex = "C203E020270402010105190119024C27030503040DB21E450538B21E450638B21E450701"
        val response = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(true, Fit3ActivityGoalCodec.result(response, 10016))
        assertNull(Fit3ActivityGoalCodec.result(response, 10017))
        assertNull(Fit3ActivityGoalCodec.result(response.copyOf(response.size - 1), 10016))
        assertNull(Fit3ActivityGoalCodec.result(response.copyOf().apply { this[9] = 9 }, 10016))
        assertNull(Fit3ActivityGoalCodec.result(response.copyOf().apply { this[10] = 24 }, 10016))
        assertEquals(false, Fit3ActivityGoalCodec.result(response.copyOf().apply { this[8] = 2 }, 10016))
    }
}
