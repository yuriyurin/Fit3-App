package io.github.yuriyurin.fit3companion.protocol

import java.util.ArrayDeque

/** Agent-7 large messages, not SAP fragmentation and not the Health ACK dialect.
 * Stock OutgoingDataHandler: 3F, version*8 + first/middle/last, LE32 chunk number.
 * Stock NotiPacketParser: 3E, version*8 + result; nonzero result means success.
 */
class Fit3NotificationIconSender(private val maxPacket: Int = 980) {
    data class Batch(val version: Int, val packets: List<ByteArray>)
    data class Update(val batch: Batch? = null, val acceptedKey: String? = null,
                      val failedKey: String? = null)
    private data class Job(val key: String, val payload: ByteArray, val attempt: Int = 0)
    private val pending = ArrayDeque<Job>()
    private var current: Job? = null
    private var version = 0

    init { require(maxPacket in 64..980) }

    fun reset() { pending.clear(); current = null }

    fun isCurrent(expectedVersion: Int): Boolean = current != null && version == expectedVersion

    fun enqueue(key: String, payload: ByteArray): Update {
        require(payload.size in (maxPacket + 1)..65_536)
        if (current?.key == key || pending.any { it.key == key }) return Update()
        if (pending.size >= 32) return Update(failedKey = key)
        pending.addLast(Job(key, payload.copyOf()))
        return if (current == null) startNext() else Update()
    }

    fun acknowledge(message: ByteArray): Update {
        if (message.size != 6 || (message[0].toInt() and 0xff) != 62) return Update()
        val flag = message[1].toInt() and 0xff
        if ((flag ushr 3) != version || current == null) return Update()
        return finish((flag and 3) != 0)
    }

    fun timeout(expectedVersion: Int): Update =
        if (current != null && version == expectedVersion) finish(false) else Update()

    private fun finish(accepted: Boolean): Update {
        val job = current ?: return Update()
        current = null
        if (!accepted && job.attempt < 2) pending.addFirst(job.copy(attempt = job.attempt + 1))
        val next = startNext()
        return next.copy(acceptedKey = job.key.takeIf { accepted },
            failedKey = job.key.takeIf { !accepted && job.attempt >= 2 })
    }

    private fun startNext(): Update {
        if (pending.isEmpty()) return Update()
        val job = pending.removeFirst()
        current = job
        version = (version + 1) % 32
        val capacity = maxPacket - 6
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var number = 0
        while (offset < job.payload.size) {
            val count = minOf(capacity, job.payload.size - offset)
            val flag = if (number == 0) 0 else if (offset + count == job.payload.size) 2 else 1
            packets += byteArrayOf(63, ((version shl 3) or flag).toByte(),
                number.toByte(), (number ushr 8).toByte(), (number ushr 16).toByte(),
                (number ushr 24).toByte()) + job.payload.copyOfRange(offset, offset + count)
            offset += count
            number++
        }
        return Update(batch = Batch(version, packets))
    }
}
