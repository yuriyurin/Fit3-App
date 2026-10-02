package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal implementation of Samsung SAMessageData used by Fit3 small control packets. */
object SaMessageCodec {
    const val FORMAT_FIXED = 0
    const val FORMAT_VARIABLE = 1
    const val TYPE_REQUEST = 0
    const val TYPE_RESPONSE = 1

    data class Header(val format: Int, val type: Int, val id: Int)

    fun header(format: Int, type: Int, id: Int): Byte {
        require(format in 0..1 && type in 0..1 && id in 0..63)
        return ((format shl 7) or (type shl 6) or id).toByte()
    }

    fun parseHeader(message: ByteArray): Header? {
        val value = message.firstOrNull()?.toInt()?.and(0xff) ?: return null
        return Header((value ushr 7) and 1, (value ushr 6) and 1, value and 0x3f)
    }

    fun fixedRequest(id: Int, vararg params: ByteArray): ByteArray = fixed(TYPE_REQUEST, id, *params)
    fun fixedResponse(id: Int, vararg params: ByteArray): ByteArray = fixed(TYPE_RESPONSE, id, *params)

    fun fixed(type: Int, id: Int, vararg params: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(header(FORMAT_FIXED, type, id).toInt())
            params.forEach { write(it) }
        }.toByteArray()

    fun byteParam(id: Int, value: Int): ByteArray {
        require(id in 0..255 && value in 0..255)
        return byteArrayOf(id.toByte(), value.toByte())
    }

    fun boolParam(id: Int, value: Boolean): ByteArray = byteParam(id, if (value) 1 else 0)

    fun shortParam(id: Int, value: Int): ByteArray {
        require(id in 0..255 && value in 0..0xffff)
        return byteArrayOf(id.toByte(), (value and 0xff).toByte(), ((value ushr 8) and 0xff).toByte())
    }

    fun intParam(id: Int, value: Int): ByteArray = byteArrayOf(id.toByte()) +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun longParam(id: Int, value: Long): ByteArray = byteArrayOf(id.toByte()) +
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** Samsung's variable-length fixed-format parameter: id, length (1 byte), bytes. */
    fun stringParam(id: Int, value: String, maxBytes: Int = 255): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8).let { if (it.size <= maxBytes) it else it.copyOf(maxBytes) }
        require(raw.size <= 255)
        return byteArrayOf(id.toByte(), raw.size.toByte()) + raw
    }

    /** Parse a packet where every parameter in the requested schema is one byte. Unknown bytes are skipped by resyncing. */
    fun byteParams(message: ByteArray, acceptedIds: Set<Int>, start: Int = 1): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = start
        while (i + 1 < message.size) {
            val id = message[i].toInt() and 0xff
            if (id in acceptedIds) {
                out += id to (message[i + 1].toInt() and 0xff)
                i += 2
            } else i++
        }
        return out
    }

    fun readLeShort(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    fun readLeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
