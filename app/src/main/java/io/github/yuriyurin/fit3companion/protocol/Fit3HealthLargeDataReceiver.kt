package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream

/** Reassembles Health large-data chunks (id 63) before the normal Health decoder sees them. */
class Fit3HealthLargeDataReceiver {
    data class Result(val payload: ByteArray? = null, val reply: ByteArray? = null)

    private val chunks = ByteArrayOutputStream()
    private var nextChunk = 0

    fun reset() {
        chunks.reset()
        nextChunk = 0
    }

    /** Returns null for a packet that is not a Health large-data chunk. */
    fun accept(message: ByteArray): Result? {
        if (message.isEmpty() || (message[0].toInt() and 0x3f) != CHUNK_ID) return null
        if (message.size < HEADER_SIZE) {
            reset()
            return Result(reply = acknowledgement(0, accepted = false))
        }
        val flag = message[1].toInt() and 0x03
        val number = (message[2].toLong() and 0xff) or
            ((message[3].toLong() and 0xff) shl 8) or
            ((message[4].toLong() and 0xff) shl 16) or
            ((message[5].toLong() and 0xff) shl 24)
        if (flag == FIRST && number == 0L) reset()
        if (flag !in FIRST..LAST || (flag == FIRST) != (number == 0L) ||
            number != nextChunk.toLong() ||
            nextChunk >= MAX_CHUNKS || chunks.size() + message.size - HEADER_SIZE > MAX_BYTES) {
            reset()
            return Result(reply = acknowledgement(number.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), accepted = false))
        }
        chunks.write(message, HEADER_SIZE, message.size - HEADER_SIZE)
        nextChunk++
        if (flag != LAST) return Result()
        val payload = chunks.toByteArray()
        val reply = acknowledgement(nextChunk, accepted = true)
        reset()
        return Result(payload = payload, reply = reply)
    }

    private fun acknowledgement(chunkCount: Int, accepted: Boolean): ByteArray = byteArrayOf(
        ACK_ID.toByte(), if (accepted) 0x09 else 0x08,
        chunkCount.toByte(), (chunkCount ushr 8).toByte(),
        (chunkCount ushr 16).toByte(), (chunkCount ushr 24).toByte(),
    )

    private companion object {
        const val CHUNK_ID = 63
        const val ACK_ID = 62
        const val HEADER_SIZE = 6
        const val FIRST = 0
        const val LAST = 2
        const val MAX_CHUNKS = 256
        const val MAX_BYTES = 1024 * 1024
    }
}
