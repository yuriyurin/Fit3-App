package io.github.yuriyurin.fit3companion.protocol

/** Full Settings service 11, MSG_BATTERY_INFO (ID 2). */
object Fit3BatteryCodec {
    val request = SaMessageCodec.fixedRequest(2)

    data class Reading(val percent: Int, val charging: Boolean)

    /**
     * Official plugin reads parameters by ID (5=level, 6=charging mode), not by absolute offsets.
     * Older Companion required exactly [42 05 level 06 charging], which silently dropped otherwise-valid
     * AZA3 replies when parameter order/additional provider fields differed.
     */
    fun parse(message: ByteArray): Reading? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 2) return null
        var level: Int? = null
        var charging: Int? = null
        var i = 1
        while (i + 1 < message.size) {
            val id = message[i].toInt() and 0xff
            val value = message[i + 1].toInt() and 0xff
            when (id) {
                5 -> if (value in 0..100) level = value
                6 -> if (value in 0..3) charging = value
            }
            i += 2
        }
        // Resync fallback for packets containing an extra fixed field of a different width.
        if (level == null || charging == null) {
            for (p in 1 until message.lastIndex) {
                when (message[p].toInt() and 0xff) {
                    5 -> (message[p + 1].toInt() and 0xff).takeIf { it <= 100 }?.let { level = it }
                    6 -> (message[p + 1].toInt() and 0xff).takeIf { it <= 3 }?.let { charging = it }
                }
            }
        }
        return if (level != null && charging != null) Reading(level, charging != 0) else null
    }
}
