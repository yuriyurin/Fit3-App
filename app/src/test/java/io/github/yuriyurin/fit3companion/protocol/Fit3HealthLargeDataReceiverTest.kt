package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3HealthLargeDataReceiverTest {
    private fun chunk(flag: Int, index: Int, body: ByteArray): ByteArray = byteArrayOf(
        0x3f, (0x08 or flag).toByte(), index.toByte(), 0, 0, 0,
    ) + body

    private fun ByteArrayOutputStream.field(id: Int, value: Int) {
        write(id)
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun intervalPayload(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x82.toByte(), 3, 0xe0.toByte(), 0x6d, 0x28, 7))
        val times = listOf(
            "2020-01-01T21:10:00Z", "2020-01-01T21:20:00Z",
            "2020-01-01T21:30:00Z", "2020-01-01T22:00:00Z",
        )
        listOf(20, 30, 40, 30).forEachIndexed { index, steps ->
            val secondsSince1990 = (Instant.parse(times[index]).epochSecond - 631_152_000L).toInt()
            write(if (index == 3) 0 else 1)
            field(5, secondsSince1990)
            field(1, secondsSince1990)
            field(2, secondsSince1990)
            field(3, 0)
            field(16, steps)
            field(17, (steps * 0.6f).toBits())
            field(18, 0)
            field(19, 0)
            field(20, 0)
            field(21, steps)
            field(22, 60_000)
            write(byteArrayOf(23, 3))
        }
    }.toByteArray() + byteArrayOf(
        // A summary-looking byte sequence elsewhere in a bulk payload must not overwrite
        // today's synthetic 120 steps with a false 12,648-step reading.
        0x93.toByte(), 0x12, 0x68, 0x31, 0, 0,
    )

    @Test fun assemblesAndAcknowledgesSyntheticStepsFromTwoChunks() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        try {
            val payload = intervalPayload()
            val receiver = Fit3HealthLargeDataReceiver()
            val first = receiver.accept(chunk(0, 0, payload.copyOfRange(0, 90)))!!
            assertNull(first.payload)
            assertNull(first.reply)
            val last = receiver.accept(chunk(2, 1, payload.copyOfRange(90, payload.size)))!!
            assertArrayEquals(payload, last.payload)
            assertArrayEquals(byteArrayOf(0x3e, 0x09, 2, 0, 0, 0), last.reply)
            val incoming = Fit3HealthCodec.handle(
                Fit3HealthCodec.State(steps = 50, stepsPartial = true,
                    stepDay = java.time.LocalDate.of(2020, 1, 1).toEpochDay()),
                last.payload!!, Instant.parse("2020-01-01T23:05:00Z").toEpochMilli(),
            )
            assertEquals(120, incoming.state.steps)
            assertEquals(4, incoming.state.stepRecords.size)
            assertEquals(java.time.LocalDate.of(2020, 1, 2).toEpochDay(), incoming.state.stepDay)
            assertArrayEquals(byteArrayOf(
                0x42, 0x02, 0xE0.toByte(), 0x6D, 0x28,
                0x04, 0x02, 0x01, 0x01,
            ), incoming.reply)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun missingChunkProducesNackAndDoesNotPublishPartialData() {
        val receiver = Fit3HealthLargeDataReceiver()
        assertNull(receiver.accept(chunk(0, 0, byteArrayOf(1, 2)))!!.payload)
        val rejected = receiver.accept(chunk(2, 2, byteArrayOf(3)))!!
        assertNull(rejected.payload)
        assertArrayEquals(byteArrayOf(0x3e, 0x08, 2, 0, 0, 0), rejected.reply)
    }
}
