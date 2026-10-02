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
        localeId: Int = 57, // ru_RU in the installed plugin's LocaleUtils.
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

    /** Param 4 of WatchInfo response is the initialized flag. */
    fun isInitialized(message: ByteArray): Boolean? {
        if (responseId(message) != 1) return null
        var offset = 1
        // OOBEPacketInfo: param 1 fixed 12, param 2 variable string,
        // param 3 fixed 7, param 4 fixed boolean.
        if (offset + 13 > message.size || message[offset++].toInt() != 1) return null
        offset += 12
        if (offset + 2 > message.size || message[offset++].toInt() != 2) return null
        val modelSize = message[offset++].toInt() and 0xff
        offset += modelSize
        if (offset + 8 > message.size || message[offset++].toInt() != 3) return null
        offset += 7
        if (offset + 2 > message.size || message[offset++].toInt() != 4) return null
        return when (message[offset].toInt() and 0xff) { 0 -> false; 1 -> true; else -> null }
    }
}
