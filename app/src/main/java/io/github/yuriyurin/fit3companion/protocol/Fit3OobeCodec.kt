package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/** Samsung plugin's four-message OOBE exchange, without Android dependencies. */
object Fit3OobeCodec {
    val deviceStatusRequest = byteArrayOf(0x02, 0x01, 0x00)
    val userAgreementRequest = byteArrayOf(0x84.toByte(), 0x01, 0x01)

    fun initSettingsRequest(
        instant: Instant,
        zone: ZoneId,
        localeId: Int,
        hour24: Boolean = true,
    ): ByteArray {
        require(localeId in 0..0xffff)
        val offset = zone.rules.getOffset(instant).totalSeconds
        require(kotlin.math.abs(offset) <= 0xffff)
        val epoch = instant.epochSecond.toString().toByteArray(Charsets.US_ASCII)
        require(epoch.size <= 255)
        return ByteArrayOutputStream().apply {
            write(0x83) // variable request, message ID 3
            write(1); write(localeId and 0xff); write(localeId ushr 8)
            write(2); write(0) // roaming off
            write(3); write(epoch.size); write(epoch)
            write(4); write(if (offset < 0) 1 else 0)
            write(kotlin.math.abs(offset) and 0xff)
            write(kotlin.math.abs(offset) ushr 8)
            write(5); write(if (hour24) 1 else 0)
        }.toByteArray()
    }

    fun responseId(message: ByteArray): Int? {
        if (message.isEmpty()) return null
        val header = message[0].toInt() and 0xff
        return if (header in 0x41..0x44) header and 0x3f else null
    }

    /**
     * Param 4 of the WatchInfo response is the initialized (EULA accepted) flag.
     * OOBEPacketInfo's watch-info schema, in order: 1 firmware version (string),
     * 2 device name (string), 3 model number (fixed 7), 4 initialized (byte).
     * Strings are [id][len:1][bytes]. Captured reply:
     * 41 01 0c "R390XXU0AZA3" 02 0b "Galaxy Fit3" 03 "SM-R390" 04 01 ...
     */
    fun isInitialized(message: ByteArray): Boolean? {
        if (responseId(message) != 1) return null
        var offset = 1
        for (id in 1..2) {
            if (offset + 2 > message.size || message[offset++].toInt() != id) return null
            val length = message[offset++].toInt() and 0xff
            offset += length
        }
        if (offset + 8 > message.size || message[offset++].toInt() != 3) return null
        offset += 7
        if (offset + 2 > message.size || message[offset++].toInt() != 4) return null
        return when (message[offset].toInt() and 0xff) { 0 -> false; 1 -> true; else -> null }
    }
}
