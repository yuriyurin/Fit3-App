package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class Fit3SleepTest {
    private val zone = ZoneId.of("UTC")
    private fun at(value: String) = Instant.parse("2026-10-${value}:00Z").toEpochMilli()
    private val night = Fit3Sleep.Episode("n", at("02T23:00"), at("03T07:00"))
    private val nap = Fit3Sleep.Episode("d", at("03T14:00"), at("03T15:00"))
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun time(value: Long) = le((value / 1000 - 631152000L).toInt())
    private fun field(id: Int, bytes: ByteArray) = byteArrayOf(id.toByte()) + bytes
    private fun stagePacket(): ByteArray = byteArrayOf(0x82.toByte(), 3, 0xe0.toByte(), 1, 0, 17, 0) +
        field(1, time(night.start)) + field(2, time(night.end)) + field(3, le(0)) + field(5, time(night.end)) +
        field(82, le(Fit3Sleep.DEEP)) + field(85, ByteArray(16) { 1 }) + field(151, le(1)) + field(153, le(1))

    @Test fun nightBelongsToWakeDateAndNapIsSeparate() {
        assertEquals(listOf(night, nap), Fit3Sleep.forDay(listOf(night, nap), LocalDate.parse("2026-10-03"), zone))
        assertTrue(Fit3Sleep.isNight(night, zone))
        assertFalse(Fit3Sleep.isNight(nap, zone))
        assertEquals(540, Fit3Sleep.todayMinutes(listOf(night, nap), emptyList(), at("03T18:00"), zone))
    }
    @Test fun overlapsAndRetransmissionsDoNotDoubleCount() {
        assertEquals(8 * 3600000L, Fit3Sleep.durationMillis(listOf(night, night,
            night.copy(id = "other", start = night.start + 60000)), emptyList()))
        val first = Fit3Sleep.merge(emptyList(), emptyList(), stagePacket(), at("03T18:00"))
        val second = Fit3Sleep.merge(first.episodes, first.stages, stagePacket(), at("03T18:00"))
        assertEquals(first, second)
    }
    @Test fun stagesUseOfficialFixedEightFieldLayout() {
        val stage = Fit3Sleep.parseStages(stagePacket())!!.single()
        assertEquals(night.start, stage.start)
        assertEquals(night.end, stage.end)
        assertEquals(Fit3Sleep.DEEP, stage.kind)
        assertEquals("01".repeat(16), stage.sleepId)
    }
    @Test fun truncatedAndUnknownLayoutsAreRejected() {
        assertNull(Fit3Sleep.parseStages(stagePacket().dropLast(1).toByteArray()))
        val unknown = stagePacket().also { it[7] = 99 }
        assertNull(Fit3Sleep.parseStages(unknown))
    }
    @Test fun awakeIsSubtractedOnlyWithCompleteCoverage() {
        val awake = Fit3Sleep.Stage("n", night.start, night.start + 3600000, Fit3Sleep.AWAKE)
        val light = Fit3Sleep.Stage("n", awake.end, night.end, Fit3Sleep.LIGHT)
        assertTrue(Fit3Sleep.completeStages(night, listOf(awake, light)))
        assertEquals(7 * 3600000L, Fit3Sleep.durationMillis(listOf(night), listOf(awake, light)))
        assertEquals(8 * 3600000L, Fit3Sleep.durationMillis(listOf(night), listOf(awake)))
    }
    @Test fun stagesCannotLeakBetweenEpisodes() {
        val other = Fit3Sleep.Stage("another", night.start, night.end, Fit3Sleep.AWAKE)
        assertTrue(Fit3Sleep.stagesFor(night, listOf(other)).isEmpty())
    }
    @Test fun oldEpisodesRemainInHistoryButNotToday() {
        assertNull(Fit3Sleep.todayMinutes(listOf(night), emptyList(), at("04T18:00"), zone))
    }
    @Test fun contradictoryStagesAreNotTreatedAsComplete() {
        val awake = Fit3Sleep.Stage("n", night.start, night.end, Fit3Sleep.AWAKE)
        val light = Fit3Sleep.Stage("n", night.start, night.end, Fit3Sleep.LIGHT)
        assertFalse(Fit3Sleep.completeStages(night, listOf(awake, light)))
    }
    @Test fun adjacentAwakeBinsAreOneAwakening() {
        val a = Fit3Sleep.Stage("n", night.start, night.start + 60000, Fit3Sleep.AWAKE)
        val b = a.copy(start = a.end, end = a.end + 60000)
        assertEquals(1, Fit3Sleep.awakenings(night, listOf(a, b, a)))
    }
    @Test fun entireSleepBatchIsKept() {
        fun record(episode: Fit3Sleep.Episode) = field(1, time(episode.start)) + field(2, time(episode.end))
        val a = record(night)
        val b = record(nap)
        val packet = byteArrayOf(0x82.toByte(), 3, 0xe0.toByte(), 1, 0, 16, 1) + le(a.size) + a +
            byteArrayOf(0) + le(b.size) + b
        val result = Fit3Sleep.merge(emptyList(), emptyList(), packet, at("03T18:00"))
        assertEquals(2, result.episodes.size)
        assertEquals(9 * 3600000L, Fit3Sleep.durationMillis(result.episodes, emptyList()))
    }
}
