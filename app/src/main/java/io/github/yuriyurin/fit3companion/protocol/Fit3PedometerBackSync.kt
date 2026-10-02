package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

/** Stock AllStepWearableMessage (87), not a fabricated day-total record.
 * AZA3 0c1260cc reads 24-byte rows and 0c0d7ac8 indexes a 1440-row minute array.
 * The firmware ignores the date text and always writes TODAY: filter before encoding.
 */
object Fit3PedometerBackSync {
    data class Bin(val index: Int, val record: Fit3HealthCodec.StepRecord)

    fun reconcile(state: Fit3HealthCodec.State, now: Long,
                  zone: ZoneId = ZoneId.systemDefault()): Fit3HealthCodec.State {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        if (state.stepDay != today || state.stepRecords.isEmpty() || !state.stepsPartial) return state
        val records = mergeRecords(emptyMap(), state.stepRecords, now, zone)
        if (records.isEmpty()) return state
        return state.copy(stepRecords = records, steps = records.values.sumOf { it.count },
            walkSteps = records.values.sumOf { it.walk }, runSteps = records.values.sumOf { it.run },
            distanceMeters = records.values.sumOf { it.distanceMeters },
            activeCalories = records.values.sumOf { it.calories },
            activeMinutes = (records.values.sumOf { it.durationMillis.toLong() } / 60_000).coerceIn(0, 1440).toInt())
    }

    fun mergeRecords(old: Map<Long, Fit3HealthCodec.StepRecord>,
                     fresh: Map<Long, Fit3HealthCodec.StepRecord>, now: Long,
                     zone: ZoneId = ZoneId.systemDefault()): Map<Long, Fit3HealthCodec.StepRecord> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val result = sortedMapOf<Long, Fit3HealthCodec.StepRecord>()
        (old.entries + fresh.entries).forEach { (at, record) ->
            val local = Instant.ofEpochMilli(at).atZone(zone)
            if (local.toLocalDate() != day || at > now + 5 * 60_000 || !valid(record)) return@forEach
            val minute = at / 60_000 * 60_000
            val previous = result[minute]
            // Same minute is the same bin, including replay after reconnect/reset.
            // Match firmware's per-bin greater-step replacement, never add two totals.
            if (previous == null || record.count > previous.count ||
                record.count == previous.count && record.durationMillis >= previous.durationMillis) {
                result[minute] = record
            }
        }
        return result
    }

    fun bins(state: Fit3HealthCodec.State, now: Long,
             zone: ZoneId = ZoneId.systemDefault()): List<Bin> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (state.stepDay != day.toEpochDay()) return emptyList()
        val indexed = sortedMapOf<Int, Fit3HealthCodec.StepRecord>()
        mergeRecords(emptyMap(), state.stepRecords, now, zone).forEach { (at, record) ->
            if (record.count == 0 || at > now + 60_000) return@forEach
            val time = Instant.ofEpochMilli(at).atZone(zone)
            val index = time.hour * 60 + time.minute
            val previous = indexed[index]
            if (previous == null || record.count > previous.count) indexed[index] = record
        }
        require(indexed.size <= 1440)
        require(indexed.values.sumOf { it.count.toLong() } <= 250_000)
        // Duration is not a stock back-sync field; don't resend identical wire rows when
        // only the phone's locally derived activity-duration changes.
        return indexed.map { (index, record) -> Bin(index, record.copy(durationMillis = 0)) }
    }

    fun request(sequence: Int, bins: List<Bin>, device: Int = 10060): ByteArray {
        require(sequence in 1..65535 && device in 0..65535)
        require(bins.size in 1..1440 && bins.map { it.index }.distinct().size == bins.size)
        require(bins.all { it.index in 0..1439 && valid(it.record) })
        require(bins.sumOf { it.record.count.toLong() } <= 250_000)
        val rows = ByteBuffer.allocate(bins.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        bins.sortedBy { it.index }.forEach { (index, record) ->
            rows.putInt(index).putInt(record.count).putFloat(record.calories.toFloat())
                .putFloat(record.distanceMeters.toFloat()).putInt(record.walk).putInt(record.run)
        }
        val body = ByteArrayOutputStream().apply {
            write(SaMessageCodec.intParam(17, rows.capacity())); write(rows.array())
            // StepBackSyncMessage defaults: date="", message="TODAY_PEDOMETER_SUMMARY".
            write(SaMessageCodec.intParam(18, 0))
            val text = "TODAY_PEDOMETER_SUMMARY".toByteArray(Charsets.UTF_8)
            write(SaMessageCodec.intParam(19, text.size)); write(text)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x87); write(SaMessageCodec.shortParam(0xE0, sequence))
            write(byteArrayOf(29, 0x85.toByte(), 1))
            write(SaMessageCodec.shortParam(2, device)); write(byteArrayOf(3, 5, 3))
            write(SaMessageCodec.intParam(30, body.size)); write(body)
        }.toByteArray()
    }

    /** Application response, not a GATT write or large-data receipt. No count after C7. */
    fun responseSequence(message: ByteArray): Int? =
        if (message.size >= 4 && message[0].toInt() and 255 == 0xC7 &&
            message[1].toInt() and 255 == 0xE0) SaMessageCodec.readLeShort(message, 2) else null

    fun chunks(payload: ByteArray, maxMessageSize: Int): List<ByteArray> {
        require(payload.isNotEmpty() && payload.size <= 35_000 && maxMessageSize >= 16)
        if (payload.size <= maxMessageSize) return listOf(payload.copyOf())
        val capacity = maxMessageSize - 6
        return payload.toList().chunked(capacity).mapIndexed { index, part ->
            val last = (index + 1) * capacity >= payload.size
            byteArrayOf(63, (8 or if (last) 2 else if (index == 0) 0 else 1).toByte()) +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(index).array() +
                part.toByteArray()
        }
    }

    fun largeDataAccepted(message: ByteArray, chunkCount: Int): Boolean {
        if (chunkCount < 2 || message.size != 6 || message[0].toInt() and 255 != 62 ||
            message[1].toInt() and 255 != 9) return false
        val receipt = ByteBuffer.wrap(message, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // AZA3 replies with the last zero-based chunk number (live: two chunks -> 1).
        // Samsung's Android receiver replies with the chunk count. Accept both only
        // for the active transfer; the stock sender itself checks just the ACK flag.
        return receipt == chunkCount - 1 || receipt == chunkCount
    }

    private fun valid(r: Fit3HealthCodec.StepRecord): Boolean = r.count in 0..100_000 &&
        r.walk in 0..r.count && r.run in 0..r.count && r.walk.toLong() + r.run <= r.count &&
        r.calories.isFinite() && r.calories in 0.0..10_000.0 &&
        r.distanceMeters.isFinite() && r.distanceMeters in 0.0..100_000.0 &&
        r.durationMillis in 0..86_400_000
}
