package io.github.yuriyurin.fit3companion.protocol

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Samsung sleep (16) and eight-field, non-length-prefixed sleep stages (17).
 * Stage values match HealthConstants.SleepStage; no inference from arbitrary bytes.
 */
object Fit3Sleep {
    data class Episode(val id: String, val start: Long, val end: Long)
    data class Stage(val sleepId: String, val start: Long, val end: Long, val kind: Int)
    data class Records(val episodes: List<Episode>, val stages: List<Stage>)
    const val AWAKE = 40001
    const val LIGHT = 40002
    const val DEEP = 40003
    const val REM = 40004

    fun merge(episodes: List<Episode>, stages: List<Stage>, packet: ByteArray, now: Long): Records {
        val incoming = Fit3VitalCodec.parse(packet)?.filter { it.type == 16 }?.mapNotNull { sample ->
            val start = sample.startMillis ?: return@mapNotNull null
            val end = sample.endMillis ?: return@mapNotNull null
            if (!valid(start, end, now)) return@mapNotNull null
            Episode(sample.uuid ?: "$start:$end", start, end)
        }.orEmpty()
        val incomingStages = parseStages(packet)?.filter { valid(it.start, it.end, now) }.orEmpty()
        return Records((episodes + incoming).associateBy { it.id }.values.sortedBy { it.start },
            (stages + incomingStages).associateBy { Triple(it.sleepId, it.start, it.end) }.values.sortedBy { it.start })
    }

    private fun valid(start: Long, end: Long, now: Long) =
        start >= 946684800000L && end > start && end - start <= 36 * 3600000L && end <= now + 300000L

    fun parseStages(packet: ByteArray): List<Stage>? {
        if (packet.size < 6 || packet[0].toInt() and 255 != 0x82 ||
            packet[2].toInt() and 255 != 0xE0 || packet[5].toInt() and 255 != 17) return null
        var offset = 6
        val result = mutableListOf<Stage>()
        repeat(10000) {
            if (offset >= packet.size) return null
            val more = packet[offset++].toInt() and 255
            if (more !in 0..1) return null
            val fields = mutableMapOf<Int, ByteArray>()
            repeat(8) {
                if (offset >= packet.size) return null
                val id = packet[offset++].toInt() and 255
                val width = when (id) { 1, 2, 3, 5, 82, 151, 153 -> 4; 85 -> 16; else -> return null }
                if (id in fields || offset + width > packet.size) return null
                fields[id] = packet.copyOfRange(offset, offset + width)
                offset += width
            }
            val start = fields[1]?.let(::time) ?: return null
            val end = fields[2]?.let(::time) ?: return null
            val kind = fields[82]?.let(::int) ?: return null
            val id = fields[85]?.joinToString("") { "%02x".format(it.toInt() and 255) } ?: return null
            result += Stage(id, start, end, kind)
            if (more == 0) return result
        }
        return null
    }

    private fun int(bytes: ByteArray): Int = (bytes[0].toInt() and 255) or
        ((bytes[1].toInt() and 255) shl 8) or ((bytes[2].toInt() and 255) shl 16) or ((bytes[3].toInt() and 255) shl 24)
    private fun time(bytes: ByteArray) = ((int(bytes).toLong() and 0xffffffffL) + 631152000L) * 1000L

    fun forDay(episodes: List<Episode>, day: LocalDate, zone: ZoneId): List<Episode> =
        episodes.filter { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() == day }

    // Label by the midpoint of the episode, not by when the user synced the bracelet.
    fun isNight(episode: Episode, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(episode.start + (episode.end - episode.start) / 2).atZone(zone).hour
        return hour < 10 || hour >= 20
    }

    fun stagesFor(episode: Episode, stages: List<Stage>): List<Stage> = stages.filter {
        it.sleepId == episode.id && it.start < episode.end && it.end > episode.start
    }.map { it.copy(start = maxOf(it.start, episode.start), end = minOf(it.end, episode.end)) }

    fun unionMillis(intervals: List<Pair<Long, Long>>): Long {
        var end = Long.MIN_VALUE
        var total = 0L
        intervals.filter { it.second > it.first }.sortedBy { it.first }.forEach { (a, b) ->
            total += (b - maxOf(a, end)).coerceAtLeast(0)
            end = maxOf(end, b)
        }
        return total
    }

    fun completeStages(episode: Episode, stages: List<Stage>): Boolean {
        val rows = stagesFor(episode, stages)
        val latestEnds = mutableMapOf<Int, Long>()
        val contradictory = rows.sortedBy { it.start }.any { row ->
            val conflict = latestEnds.any { (kind, end) -> kind != row.kind && end > row.start }
            latestEnds[row.kind] = maxOf(latestEnds[row.kind] ?: Long.MIN_VALUE, row.end)
            conflict
        }
        return !contradictory && rows.isNotEmpty() && rows.all { it.kind in AWAKE..REM } &&
            unionMillis(rows.map { it.start to it.end }) == episode.end - episode.start
    }

    fun awakenings(episode: Episode, stages: List<Stage>): Int {
        val awake = stagesFor(episode, stages).filter { it.kind == AWAKE }.sortedBy { it.start }
        var end = Long.MIN_VALUE
        var count = 0
        awake.forEach { if (it.start > end) count++; end = maxOf(end, it.end) }
        return count
    }

    fun durationMillis(episodes: List<Episode>, stages: List<Stage>): Long = unionMillis(episodes.flatMap { episode ->
        if (completeStages(episode, stages)) stagesFor(episode, stages).filter { it.kind != AWAKE }.map { it.start to it.end }
        else listOf(episode.start to episode.end)
    })

    fun todayMinutes(episodes: List<Episode>, stages: List<Stage>, now: Long, zone: ZoneId): Int? {
        val today = forDay(episodes, Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone)
        return today.takeIf { it.isNotEmpty() }?.let { (durationMillis(it, stages) / 60000).toInt() }
    }
}
