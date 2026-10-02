package io.github.yuriyurin.fit3companion.ble

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** File transport shared with Fit3 FOTA, but restricted to /user/wf and never sends AT^OTA_UPDATE. */
internal object FaceOtaTransport {
    const val WINDOW_BYTES = 39_600
    const val CHUNK_BYTES = 960
    private const val MAX_RETRIES = 3

    fun descriptor(name: String, size: Int): ByteArray {
        require(name.matches(Regex("SM-R390_\\d{5}_256x402\\.bin")))
        require(size in 1..FaceBinValidator.MAX_BIN_BYTES)
        return "33bin,/user/wf/$name,$size".toByteArray(Charsets.US_ASCII)
    }

    fun transfer(input: InputStream, output: OutputStream, name: String, data: ByteArray,
                 cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val fileDescriptor = descriptor(name, data.size)
        write(output, "30".toByteArray(Charsets.US_ASCII))
        await(input, 8_000, cancelled, "300")
        write(output, fileDescriptor)
        await(input, 8_000, cancelled, "330")
        val windows = (data.size + WINDOW_BYTES - 1) / WINDOW_BYTES
        for (index in 0 until windows) {
            var accepted = false
            repeat(MAX_RETRIES + 1) {
                if (!accepted) {
                    checkCancel(cancelled)
                    writeWindow(output, data, index)
                    when (await(input, 12_000, cancelled, "310", "311")) {
                        "310" -> {
                            accepted = true
                            progress(minOf((index + 1) * WINDOW_BYTES, data.size), data.size)
                        }
                    }
                }
            }
            if (!accepted) throw IOException("Часы не приняли блок ${index + 1} из $windows")
        }
        write(output, "32".toByteArray(Charsets.US_ASCII))
        await(input, 15_000, cancelled, "320")
        Thread.sleep(250)
        write(output, "34".toByteArray(Charsets.US_ASCII))
        await(input, 8_000, cancelled, "340")
    }

    internal fun writeWindow(output: OutputStream, data: ByteArray, index: Int): Int {
        val start = Math.multiplyExact(index, WINDOW_BYTES)
        require(start in data.indices)
        val length = minOf(WINDOW_BYTES, data.size - start)
        val end = start + length
        var offset = start
        while (offset + CHUNK_BYTES <= end) {
            output.write(data, offset, CHUNK_BYTES)
            output.flush()
            offset += CHUNK_BYTES
        }
        val tail = end - offset
        val final = ByteArray(tail + 4)
        data.copyInto(final, 0, offset, end)
        val crc = CRC32().apply { update(data, start, length) }.value
        for (i in 0..3) final[tail + i] = (crc ushr (8 * i)).toByte()
        write(output, final)
        return length
    }

    private fun write(output: OutputStream, data: ByteArray) {
        output.write(data)
        output.flush()
    }

    private fun checkCancel(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("Передача отменена")
    }

    private fun await(input: InputStream, timeoutMs: Long, cancelled: () -> Boolean,
                      vararg expected: String): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val buffer = StringBuilder()
        while (System.nanoTime() < deadline) {
            checkCancel(cancelled)
            if (input.available() == 0) {
                Thread.sleep(5)
                continue
            }
            val next = input.read()
            if (next < 0) throw IOException("Канал передачи закрыт")
            if (buffer.length >= 128) throw IOException("Неверный ответ часов")
            buffer.append(next.toChar())
            val token = buffer.toString()
            if (token in expected) return token
            if (expected.none { it.startsWith(token) }) throw IOException("Неожиданный ответ часов: $token")
        }
        throw IOException("Часы не ответили при передаче циферблата")
    }
}
