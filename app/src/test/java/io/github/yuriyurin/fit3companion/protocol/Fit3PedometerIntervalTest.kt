package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class Fit3PedometerIntervalTest {
    // Synthetic protocol records only: no device logs, original measurements or timestamps.
    private fun record(start: String, steps: Int, more: Boolean): ByteArray {
        val seconds = (Instant.parse(start).epochSecond - 631_152_000L).toInt()
        val fields = linkedMapOf(5 to seconds + 60, 1 to seconds, 2 to seconds + 60,
            3 to 60_000, 16 to steps, 17 to (steps * .5f).toRawBits(),
            18 to (steps * .03f).toRawBits(), 19 to (steps * .1f).toRawBits(),
            20 to 0, 21 to steps, 22 to 60_000)
        val body = fields.entries.fold(byteArrayOf(if (more) 1 else 0)) { bytes, field ->
            bytes + byteArrayOf(field.key.toByte()) + ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(field.value).array()
        }
        return body + byteArrayOf(23, 0)
    }

    private fun packet(vararg records: ByteArray) =
        records.fold(byteArrayOf(0x82.toByte(), 3, 0xE0.toByte(), 1, 0, 7)) { bytes, next -> bytes + next }

    @Test fun decodesSyntheticIntervalsWithoutDoubleCounting() {
        val packet = packet(record("2020-01-02T10:00:00Z", 10, true),
            record("2020-01-02T10:10:00Z", 20, false))
        val now = Instant.parse("2020-01-02T10:30:00Z").toEpochMilli()
        val first = Fit3HealthCodec.handle(Fit3HealthCodec.State(), packet, now)
        assertEquals(30, first.state.steps)
        assertEquals(30, first.state.walkSteps)
        assertTrue(first.state.stepsPartial)
        assertTrue(Fit3HealthCodec.DATA_PEDO_STEP_COUNT in first.parsedDataTypes)
        val repeated = Fit3HealthCodec.handle(first.state, packet, now + 1_000L)
        assertEquals(30, repeated.state.steps)
        assertEquals(2, repeated.state.stepRecords.size)
    }

    @Test fun decodesFourSyntheticIntervalsAcrossMidnight() {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Moscow"))
        try {
            val packet = packet(record("2020-01-01T20:40:00Z", 40, true),
                record("2020-01-01T20:50:00Z", 50, true),
                record("2020-01-01T21:10:00Z", 30, true),
                record("2020-01-01T21:20:00Z", 40, false))
            val now = Instant.parse("2020-01-01T21:30:00Z").toEpochMilli()
            val incoming = Fit3HealthCodec.handle(Fit3HealthCodec.State(), packet, now)
            assertEquals(70, incoming.state.steps)
            assertEquals(70, incoming.state.walkSteps)
            assertEquals(2, incoming.state.stepRecords.size)
            assertTrue(incoming.state.stepsPartial)
        } finally {
            java.util.TimeZone.setDefault(originalZone)
        }
    }
}
