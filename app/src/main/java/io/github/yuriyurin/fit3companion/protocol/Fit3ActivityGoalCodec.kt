package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Daily goals, not exercise targets. Ranges follow AZA3 FUN_0c0e5ba8. */
data class ActivityGoals(val steps: Int = 6000, val minutes: Int = 90, val calories: Int = 500) {
    fun valid() = steps in 1000..50_000 && minutes in 30..360 && calories in 100..5000
}

object Fit3ActivityGoalCodec {
    private const val EPOCH = 631_152_000_000L
    data class Observation(val type: Int, val value: Int, val updatedAt: Long)

    /** Stock ModenStringToByteParser / generateTheSyncByte. No record-length prefixes.
     * GOAL_TYPE selects steps / active time / active calories (all zero), not workouts.
     * UUID bytes are reversed exactly like add16ByteStringFromStringUUID.
     */
    fun request(sequence: Int, goals: ActivityGoals, updatedAt: Long, offsetMillis: Int,
                uuid: UUID): ByteArray {
        require(sequence in 1..65535 && goals.valid())
        require(updatedAt >= EPOCH && (updatedAt - EPOCH) / 1000 <= 0xffff_ffffL)
        require(offsetMillis in -50_400_000..50_400_000)
        val time = ((updatedAt - EPOCH) / 1000).toInt()
        val hex = uuid.toString().replace("-", "")
        val id = ByteArray(16) { hex.substring((15-it)*2, (15-it)*2+2).toInt(16).toByte() }
        return ByteArrayOutputStream().apply {
            fun field(tag: Int, value: Int) { write(SaMessageCodec.intParam(tag, value)) }
            fun common(type: Int, startTag: Int) {
                write(type); write(0); write(0x75); write(id)
                field(5, time); field(startTag, time)
            }
            write(byteArrayOf(0x82.toByte(), 5, 0xE0.toByte(), sequence.toByte(), (sequence ushr 8).toByte()))
            common(9, 6); field(0x17, goals.steps); field(3, offsetMillis)
            common(0x7C, 1); field(3, offsetMillis); field(0x2B, goals.calories)
            common(0x7D, 1); field(3, offsetMillis); field(0x2B, goals.minutes)
            common(0xA2, 6); field(3, offsetMillis)
            write(byteArrayOf(0x10, 0, 0x11, 0, 0x12, 0))
        }.toByteArray()
    }

    /** Accept ONLY the result of our exact sequence. Never treat TX or bare 42 as success. */
    fun result(message: ByteArray, sequence: Int): Boolean? {
        if (message.size < 9 ||
            (message[2].toInt() and 255) != 0xE0 ||
            SaMessageCodec.readLeShort(message, 3) != sequence ||
            !message.copyOfRange(5, 8).contentEquals(byteArrayOf(4, 2, 1))) return null
        val fixed = (message[0].toInt() and 255) == 0x42 && message[1].toInt() == 2 && message.size == 9
        // AZA3's real goal acknowledgement is variable C2/count=3, followed by
        // the 05/length/device-info field. Do not accept a truncated or unrelated tail.
        val variable = (message[0].toInt() and 255) == 0xC2 && when (message[1].toInt()) {
            2 -> message.size == 9
            3 -> message.size >= 11 && message[9].toInt() == 5 &&
                message.size == 11 + (message[10].toInt() and 255)
            else -> false
        }
        if (!fixed && !variable) return null
        return message[8].toInt() == 1
    }

    /** Only traverse known dataset boundaries; do not scan arbitrary bytes for goal ids. */
    fun observations(message: ByteArray): List<Observation> {
        if (message.size < 6 || (message[0].toInt() and 255) !in setOf(0x82, 0x42) ||
            (message[2].toInt() and 255) != 0xE0) return emptyList()
        var pos = 5
        val values = mutableListOf<Observation>()
        repeat(((message[1].toInt() and 255).coerceAtMost(32) - 1).coerceAtLeast(0)) {
            val type = message.getOrNull(pos++)?.toInt()?.and(255) ?: return emptyList()
            if (type !in setOf(9, 0x7C, 0x7D, 0xA2)) return values
            var more: Int
            var count = 0
            do {
                if (++count > 128) return emptyList()
                more = message.getOrNull(pos++)?.toInt()?.and(255) ?: return emptyList()
                if (more !in 0..1) return emptyList()
                val fields = mutableMapOf<Int, ByteArray>()
                val widths = when (type) {
                    9 -> mapOf(0x75 to 16, 5 to 4, 6 to 4, 0x17 to 4, 3 to 4)
                    0xA2 -> mapOf(0x75 to 16, 5 to 4, 6 to 4, 3 to 4, 0x10 to 1, 0x11 to 1, 0x12 to 1)
                    else -> mapOf(0x75 to 16, 5 to 4, 1 to 4, 3 to 4, 0x2B to 4)
                }
                repeat(widths.size) {
                    val tag = message.getOrNull(pos++)?.toInt()?.and(255) ?: return emptyList()
                    val width = widths[tag] ?: return emptyList()
                    if (tag in fields || pos + width > message.size) return emptyList()
                    fields[tag] = message.copyOfRange(pos, pos + width); pos += width
                }
                if (type != 0xA2) {
                    val raw = requireNotNull(SaMessageCodec.readLeInt(fields.getValue(if (type == 9) 0x17 else 0x2B), 0))
                    val allowed = when (type) { 9 -> 1000..50_000; 0x7C -> 100..5000; else -> 30..360 }
                    val timestamp = requireNotNull(SaMessageCodec.readLeInt(fields.getValue(5), 0))
                    if (raw in allowed) values += Observation(type, raw,
                        (timestamp.toLong() and 0xffff_ffffL) * 1000 + EPOCH)
                }
            } while (more == 1)
        }
        return values
    }
}
