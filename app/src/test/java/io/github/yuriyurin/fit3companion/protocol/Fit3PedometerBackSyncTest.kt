package io.github.yuriyurin.fit3companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class Fit3PedometerBackSyncTest {
    private val zone = ZoneId.of("Europe/Moscow")
    // Synthetic scenarios, not original measurements or capture timestamps.
    private val now = Instant.parse("2020-01-02T14:30:01Z").toEpochMilli()
    private fun record(steps: Int) = Fit3HealthCodec.StepRecord(steps, steps, 0, steps * .7, steps * .03, 12000)
    private fun at(time: String) = Instant.parse("2020-01-02T${time}Z").toEpochMilli()

    @Test fun stockBinningRowsAre24BytesAndIndexIsLocalMinute() {
        val state = Fit3HealthCodec.State(stepDay = 20726, stepRecords = mapOf(
            at("14:02:00") to record(18), at("14:12:00") to record(19)))
        val bins = Fit3PedometerBackSync.bins(state.copy(stepDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()), now, zone)
        assertEquals(listOf(1022, 1032), bins.map { it.index })
        val request = Fit3PedometerBackSync.request(3, bins)
        assertArrayEquals(byteArrayOf(0x87.toByte(), 0xE0.toByte(), 3, 0, 29, 0x85.toByte(), 1,
            2, 0x4C, 0x27, 3, 5, 3, 30), request.copyOfRange(0, 14))
        val body = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(request.size - 18, body.getInt(14))
        assertEquals(17, request[18].toInt())
        assertEquals(48, body.getInt(19))
        assertEquals(1022, body.getInt(23)); assertEquals(18, body.getInt(27))
        assertEquals(.54f, body.getFloat(31), .0001f)
        assertEquals(12.6f, body.getFloat(35), .0001f)
        assertEquals(18, body.getInt(39)); assertEquals(0, body.getInt(43))
        assertEquals(1032, body.getInt(47)); assertEquals(19, body.getInt(51))
        assertEquals(18, request[71].toInt()); assertEquals(0, body.getInt(72))
        assertEquals(19, request[76].toInt())
        assertEquals("TODAY_PEDOMETER_SUMMARY", String(request.copyOfRange(81, request.size)))
    }

    @Test fun replayAndSameMinuteAfterResetNeverAddTwoValues() {
        val first = mapOf(at("14:02:01") to record(18))
        val newer = mapOf(at("14:02:48") to record(19))
        val merged = Fit3PedometerBackSync.mergeRecords(first, newer, now, zone)
        assertEquals(1, merged.size); assertEquals(19, merged.values.single().count)
        val replayed = Fit3PedometerBackSync.mergeRecords(merged, first, now, zone)
        assertEquals(merged, replayed)
        assertEquals(merged, Fit3PedometerBackSync.mergeRecords(merged, newer, now, zone))
    }

    @Test fun differentIntervalsSurviveResetAndCanBeRestored() {
        val merged = Fit3PedometerBackSync.mergeRecords(mapOf(at("07:00:00") to record(100)),
            mapOf(at("14:02:00") to record(18), at("14:12:00") to record(19)), now, zone)
        assertEquals(137, merged.values.sumOf { it.count })
        assertEquals(137, Fit3PedometerBackSync.mergeRecords(merged, merged, now, zone).values.sumOf { it.count })
    }

    @Test fun onlyTodayIsSentNotYesterdayFutureOrSyntheticTotal() {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val state = Fit3HealthCodec.State(stepDay = day, steps = 99999, stepRecords = mapOf(
            at("14:02:00") to record(18), (now - 86400000) to record(900), (now + 3600000) to record(900)))
        assertEquals(18, Fit3PedometerBackSync.bins(state, now, zone).sumOf { it.record.count })
        assertTrue(Fit3PedometerBackSync.bins(state.copy(stepDay = day - 1), now, zone).isEmpty())
        assertTrue(Fit3PedometerBackSync.bins(state.copy(stepRecords = emptyMap()), now, zone).isEmpty())
    }

    @Test fun firmwareArrayBoundsAndNonFiniteNumbersAreRejected() {
        for (index in listOf(-1, 1440, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                Fit3PedometerBackSync.request(1, listOf(Fit3PedometerBackSync.Bin(index, record(18))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Fit3PedometerBackSync.request(1, listOf(Fit3PedometerBackSync.Bin(0, record(18).copy(calories = Double.NaN))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Fit3PedometerBackSync.request(1, listOf(Fit3PedometerBackSync.Bin(0, record(18)), Fit3PedometerBackSync.Bin(0, record(19))))
        }
    }

    @Test fun fullDayUsesStockHealthChunksAndFitsSapFragmentLimit() {
        val payload = Fit3PedometerBackSync.request(42, (0..1439).map { Fit3PedometerBackSync.Bin(it, record(1)) })
        val chunks = Fit3PedometerBackSync.chunks(payload, 1024)
        assertTrue(chunks.size > 1)
        assertEquals(8, chunks.first()[1].toInt()); assertEquals(10, chunks.last()[1].toInt())
        chunks.forEach { assertTrue(it.size <= 1024); assertTrue(Fit3SapCodec.encodeMessage(10, it, 500).size <= 16) }
        assertArrayEquals(payload, chunks.flatMap { it.drop(6) }.toByteArray())
        val receiver = Fit3HealthLargeDataReceiver()
        var result: Fit3HealthLargeDataReceiver.Result? = null
        chunks.forEach { result = receiver.accept(it) }
        assertArrayEquals(payload, result!!.payload)
        assertTrue(Fit3PedometerBackSync.largeDataAccepted(result!!.reply!!, chunks.size))
        assertFalse(Fit3PedometerBackSync.largeDataAccepted(result!!.reply!!, chunks.size + 2))
        assertFalse(Fit3PedometerBackSync.largeDataAccepted(byteArrayOf(62, 8, 1, 0, 0, 0), 1))
    }

    @Test fun aza3LiveReceiptUsesLastChunkIndexNotCount() {
        val captured = byteArrayOf(62, 9, 1, 0, 0, 0)
        assertTrue(Fit3PedometerBackSync.largeDataAccepted(captured, 2))
        assertFalse(Fit3PedometerBackSync.largeDataAccepted(captured, 3))
        assertFalse(Fit3PedometerBackSync.largeDataAccepted(captured, 0))
        assertFalse(Fit3PedometerBackSync.largeDataAccepted(byteArrayOf(62, 8, 1, 0, 0, 0), 2))
    }

    @Test fun sessionRequiresMatchingApplicationSequenceNotTransportReceipt() {
        val bins = listOf(Fit3PedometerBackSync.Bin(1022, record(18)))
        val session = Fit3BackSyncSession()
        assertTrue(session.begin(bins, 100, 3, now))
        assertFalse(session.begin(bins, 100, 4, now + 5000))
        assertFalse(session.receive(byteArrayOf(62, 9, 1, 0, 0, 0)))
        assertFalse(session.receive(byteArrayOf(0xC7.toByte(), 0xE0.toByte(), 4, 0)))
        assertTrue(session.receive(byteArrayOf(0xC7.toByte(), 0xE0.toByte(), 3, 0)))
        assertFalse(session.begin(bins, 100, 4, now + 60000))
        assertTrue(session.begin(bins, 101, 5, now + 86400000))
        session.reset(); assertTrue(session.begin(bins, 101, 6, now))
    }

    @Test fun timeoutIsRateLimitedAndDoesNotLoop() {
        val bins = listOf(Fit3PedometerBackSync.Bin(1022, record(18)))
        val session = Fit3BackSyncSession()
        assertTrue(session.begin(bins, 1, 3, now))
        session.expire(4); assertTrue(session.pending)
        session.expire(3); assertFalse(session.pending)
        assertFalse(session.begin(bins, 1, 4, now + 30000))
        assertTrue(session.begin(bins, 1, 4, now + 60000))
    }

    @Test fun changedStepsCanBeSentWithoutWaitingAMinute() {
        val bins = listOf(Fit3PedometerBackSync.Bin(1022, record(18)))
        val session = Fit3BackSyncSession()
        assertTrue(session.begin(bins, 1, 3, now)); assertTrue(session.delivered(3))
        assertTrue(session.begin(listOf(Fit3PedometerBackSync.Bin(1022, record(19))), 1, 4, now + 10000))
    }

    @Test fun stockDeliveryWithoutC7DoesNotRetransmitUnchangedBins() {
        val bins = listOf(Fit3PedometerBackSync.Bin(1022, record(18)))
        val session = Fit3BackSyncSession()
        assertTrue(session.begin(bins, 1, 3, now))
        assertFalse(session.delivered(4)); assertTrue(session.delivered(3))
        assertFalse(session.pending)
        assertFalse(session.begin(bins, 1, 4, now + 60000))
        assertTrue(session.begin(listOf(Fit3PedometerBackSync.Bin(1022, record(19))), 1, 4, now + 60000))
    }

    @Test fun existingSnapshotIsReconciledWithoutDeletingHistoryOrVitals() {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val before = Fit3HealthCodec.State(steps = 37, stepsPartial = true, stepDay = day,
            stepRecords = mapOf(at("14:02:01") to record(18), at("14:02:48") to record(19)),
            sleepMinutes = 420, stepHistory = listOf(18, 37))
        val after = Fit3PedometerBackSync.reconcile(before, now, zone)
        assertEquals(19, after.steps); assertEquals(1, after.stepRecords.size)
        assertEquals(420, after.sleepMinutes); assertEquals(before.stepHistory, after.stepHistory)
        assertEquals(after, Fit3PedometerBackSync.reconcile(after, now, zone))
    }

    @Test fun responseDoesNotChangeAnyMeasurementAndUsesNoFieldCount() {
        val reply = byteArrayOf(0xC7.toByte(), 0xE0.toByte(), 3, 0)
        val old = Fit3HealthCodec.State(steps = 137)
        assertEquals(3, Fit3HealthCodec.sequence(reply))
        assertEquals(old.steps, Fit3HealthCodec.handle(old, reply, now).state.steps)
        assertNull(Fit3PedometerBackSync.responseSequence(byteArrayOf(0xC7.toByte())))
    }

    @Test fun differentWatchDoesNotReceiveOtherWatchIntervals() {
        val old = Fit3HealthCodec.State(steps = 137, stepSourceAddress = "AA:BB:CC:DD:EE:FF",
            stepRecords = mapOf(at("14:02:00") to record(137)), sleepMinutes = 420)
        val request = byteArrayOf(3, 0xE0.toByte(), 1, 0)
        val same = Fit3HealthAcceptance.accept(old, request, now, "aa:bb:cc:dd:ee:ff") { _, _ -> }
        assertEquals(137, same.state.steps)
        val other = Fit3HealthAcceptance.accept(old, request, now, "11:22:33:44:55:66") { _, _ -> }
        assertNull(other.state.steps); assertTrue(other.state.stepRecords.isEmpty())
        assertEquals(420, other.state.sleepMinutes)
    }
}
