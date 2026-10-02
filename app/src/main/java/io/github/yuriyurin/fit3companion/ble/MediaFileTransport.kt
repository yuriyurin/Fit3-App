package io.github.yuriyurin.fit3companion.ble

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Bounded Agent-9 media files only; never targets OTA, faces or arbitrary watch paths. */
internal object MediaFileTransport {
    private const val WINDOW_BYTES = 39_600
    private const val CHUNK_BYTES = 960

    fun descriptor(name: String, size: Int): ByteArray {
        require(name.matches(Regex("[A-Za-z0-9._-]+\\.(art|que|app)")))
        require(size in 1..512_000)
        val extension = name.substringAfterLast('.')
        return "33$extension,/user/$name,$size".toByteArray(Charsets.US_ASCII)
    }

    fun transfer(input: InputStream, output: OutputStream, name: String, bytes: ByteArray,
                 cancelled: () -> Boolean) {
        val descriptor = descriptor(name, bytes.size)
        fun send(data: ByteArray) { output.write(data); output.flush() }
        fun await(timeoutMs: Long, vararg expected: String): String {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            val received = StringBuilder()
            while (System.nanoTime() < deadline) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Передача отменена")
                if (input.available() == 0) { Thread.sleep(5); continue }
                val next = input.read()
                if (next < 0) throw IOException("Канал передачи закрыт")
                received.append(next.toChar())
                val value = received.toString()
                if (value in expected) return value
                if (received.length > 16 || expected.none { it.startsWith(value) })
                    throw IOException("Неожиданный ответ часов: $value")
            }
            throw IOException("Часы не ответили при передаче медиафайла")
        }

        send("30".toByteArray(Charsets.US_ASCII))
        await(8_000, "300")
        send(descriptor)
        await(8_000, "330")
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + WINDOW_BYTES, bytes.size)
            val crc = CRC32().apply { update(bytes, offset, end - offset) }.value
            var accepted = false
            repeat(4) {
                if (!accepted) {
                    if (cancelled()) throw IOException("Передача отменена")
                    var position = offset
                    while (position + CHUNK_BYTES <= end) {
                        output.write(bytes, position, CHUNK_BYTES)
                        output.flush()
                        position += CHUNK_BYTES
                    }
                    val tail = ByteArray(end - position + 4)
                    bytes.copyInto(tail, 0, position, end)
                    for (i in 0..3) tail[end - position + i] = (crc ushr (8 * i)).toByte()
                    send(tail)
                    accepted = await(12_000, "310", "311") == "310"
                }
            }
            if (!accepted) throw IOException("Часы не приняли блок медиафайла")
            offset = end
        }
        send("32".toByteArray(Charsets.US_ASCII))
        await(15_000, "320")
        Thread.sleep(250)
        send("34".toByteArray(Charsets.US_ASCII))
        await(8_000, "340")
    }
}
