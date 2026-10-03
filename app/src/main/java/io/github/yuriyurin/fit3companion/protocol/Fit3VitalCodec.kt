package io.github.yuriyurin.fit3companion.protocol

/** Strict decoder for the length-prefixed Health records used by Fit3.
 * Field widths and feature ids follow the stock plugin's ConsolidatedSyncDataParser and
 * ModenByteToJsonDataParser. Unknown layouts are rejected, never guessed from byte windows.
 */
object Fit3VitalCodec {
    data class Sample(
        val type: Int,
        val startMillis: Long?,
        val endMillis: Long?,
        val value: Int?,
        val min: Int?,
        val max: Int?,
        val score: Int?,
        val uuid: String? = null,
    ) {
        val measuredAt: Long? get() = endMillis ?: startMillis
    }

    fun parse(message: ByteArray): List<Sample>? {
        if (message.size < 12 || (message[0].toInt() and 0xff) != 0x82 ||
            (message[2].toInt() and 0xff) != 0xE0) return null
        val type = message[5].toInt() and 0xff
        if (type !in setOf(10, 16, 19, 112)) return null
        var offset = 6
        val samples = ArrayList<Sample>()
        repeat(128) {
            if (offset + 5 > message.size) return null
            val more = message[offset++].toInt() and 0xff
            if (more !in 0..1) return null
            val length = le32(message, offset)
            offset += 4
            if (length <= 0 || length > 65_536 || offset + length > message.size) return null
            val sample = parseRecord(type, message, offset, offset + length) ?: return null
            samples += sample
            offset += length
            if (more == 0) return samples
        }
        return null
    }

    private fun parseRecord(type: Int, bytes: ByteArray, start: Int, end: Int): Sample? {
        val fields = HashMap<Int, ByteArray>()
        var offset = start
        while (offset < end) {
            val id = bytes[offset++].toInt() and 0xff
            val width = fieldWidth(type, id)
            if (width == 0) return null
            if (width < 0) {
                if (offset + 4 > end) return null
                val blobLength = le32(bytes, offset)
                offset += 4
                if (blobLength < 0 || blobLength > 65_536 || offset + blobLength > end) return null
                offset += blobLength
            } else {
                if (offset + width > end) return null
                fields[id] = bytes.copyOfRange(offset, offset + width)
                offset += width
            }
        }
        val startTime = fields[1]?.let(::healthTime)
        val endTime = fields[2]?.let(::healthTime)
        val value = when (type) {
            10 -> fields[32]?.unsignedByte()
            19 -> fields[96]?.unsignedByte()
            112 -> fields[169]?.unsignedByte()
            else -> null
        }
        val min = when (type) {
            10 -> fields[33]?.unsignedByte()
            19 -> fields[102]?.unsignedByte()
            112 -> fields[171]?.unsignedByte()
            else -> null
        }
        val max = when (type) {
            10 -> fields[34]?.unsignedByte()
            19 -> fields[103]?.unsignedByte()
            112 -> fields[172]?.unsignedByte()
            else -> null
        }
        return Sample(type, startTime, endTime, value, min, max,
            if (type == 16) fields[83]?.unsignedByte() else null,
            fields[117]?.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun fieldWidth(type: Int, id: Int): Int {
        if (id in setOf(1, 2, 3, 5, 151, 153)) return 4
        return when (type) {
            10 -> when (id) {
                32, 33, 34, 35 -> 1
                36 -> -1 // minute bins: 4-byte length, then packed entries
                37 -> 2
                else -> 0
            }
            19 -> when (id) {
                96, 102, 103 -> 1
                97 -> 4
                98 -> -1
                else -> 0
            }
            112 -> when (id) {
                117 -> 16
                169, 170, 171, 172 -> 1
                173 -> 2
                174 -> 4
                175 -> -1
                else -> 0
            }
            16 -> when (id) {
                in 28..37 -> 2
                40, 80 -> 4
                81, 83, 86 -> 1
                82, 117 -> 16
                else -> 0
            }
            else -> 0
        }
    }

    private fun ByteArray.unsignedByte(): Int = this[0].toInt() and 0xff

    private fun healthTime(raw: ByteArray): Long =
        ((le32(raw, 0).toLong() and 0xffff_ffffL) + 631_152_000L) * 1000L

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
