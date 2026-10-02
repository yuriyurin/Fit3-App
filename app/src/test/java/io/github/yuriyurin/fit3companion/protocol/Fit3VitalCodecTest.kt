package io.github.yuriyurin.fit3companion.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3VitalCodecTest {
    private val now = Instant.parse("2020-01-02T08:00:00Z").toEpochMilli()

    private fun le4(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(),
        (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun time(millis: Long): ByteArray = le4((millis / 1000L - 631_152_000L).toInt())

    private fun field(id: Int, value: ByteArray) = byteArrayOf(id.toByte()) + value

    private fun packet(type: Int, vararg fields: ByteArray): ByteArray {
        val record = fields.fold(byteArrayOf()) { acc, next -> acc + next }
        return byteArrayOf(0x82.toByte(), 3, 0xE0.toByte(), 1, 0, type.toByte(), 0) +
            le4(record.size) + record + byteArrayOf(5, 0)
    }

    @Test fun heartRateUsesOneByteMeasurement() {
        val bytes = packet(10,
            field(1, time(now - 120_000L)), field(2, time(now - 60_000L)),
            field(32, byteArrayOf(75)), field(33, byteArrayOf(60)), field(34, byteArrayOf(90)))
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), bytes, now)
        assertEquals(75, result.state.heartRate)
        assertEquals(60, result.state.heartRateMin)
        assertEquals(90, result.state.heartRateMax)
        assertEquals(now - 60_000L, result.state.heartRateAt)
    }

    @Test fun stressAndOxygenUseOneByteMeasurements() {
        val stress = packet(19, field(1, time(now - 60_000L)), field(96, byteArrayOf(42)),
            field(102, byteArrayOf(20)), field(103, byteArrayOf(70)))
        val oxygen = packet(112, field(1, time(now - 30_000L)),
            field(169, byteArrayOf(97)), field(170, byteArrayOf(78)))
        val first = Fit3HealthCodec.handle(Fit3HealthCodec.State(), stress, now)
        val second = Fit3HealthCodec.handle(first.state, oxygen, now)
        assertEquals(42, second.state.stress)
        assertEquals(97, second.state.spo2)
        assertEquals(20, second.state.stressMin)
    }

    @Test fun sleepIsOnlyTheMeasuredWindowUntilStagesAreVerified() {
        val end = now - 60_000L
        val start = end - 440 * 60_000L
        val bytes = packet(16, field(1, time(start)), field(2, time(end)),
            field(83, byteArrayOf(82)))
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), bytes, now)
        assertEquals(440, result.state.sleepMinutes)
        assertEquals(82, result.state.sleepScore)
    }

    @Test fun malformedRecordCannotBecomeAHealthValue() {
        val bytes = packet(112, field(1, time(now)), field(169, byteArrayOf(98)))
        bytes[7] = 0x7f // Claim a record longer than the packet.
        val result = Fit3HealthCodec.handle(Fit3HealthCodec.State(), bytes, now)
        assertNull(result.state.spo2)
    }
}
