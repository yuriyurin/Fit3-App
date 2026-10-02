package io.github.yuriyurin.fit3companion.ble

/** Embedded preview.bin rasters. The container is CRC-validated before offsets are used. */
internal object FacePreviewDecoder {
    data class Frame(val width: Int, val height: Int, val argb: IntArray)
    fun decode(bytes: ByteArray): List<Frame> {
        FaceBinValidator.validateLocal(bytes)
        val count = u32(bytes, 12).toInt()
        val record = (0 until count).map { 32 + it * 74 }.single {
            String(bytes, it, 64, Charsets.US_ASCII).trimEnd('\u0000').endsWith("/preview.bin")
        }
        val offset = u32(bytes, record + 64).toInt()
        val length = u32(bytes, record + 68).toInt()
        return decodeRasters(bytes.copyOfRange(offset, offset + length))
    }
    internal fun decodeRasters(bytes: ByteArray): List<Frame> {
        val frames = mutableListOf<Frame>()
        var pos = 0
        while (pos < bytes.size) {
            require(frames.size < 256 && pos + 12 <= bytes.size) { "Invalid preview header" }
            val width = u16(bytes, pos); val height = u16(bytes, pos + 2)
            val format = u16(bytes, pos + 4)
            require(width in 1..512 && height in 1..512 && u16(bytes, pos + 6) == 0) { "Invalid preview size" }
            val payload = u32(bytes, pos + 8)
            val expected = when (format) {
                0x82 -> width * height * 2
                0x80 -> width * height * 3
                0x88 -> 1024 + width * height
                else -> error("Unsupported preview format")
            }
            require(payload in expected.toLong()..expected.toLong() + 4 &&
                pos.toLong() + 12 + payload <= bytes.size) { "Truncated preview" }
            val start = pos + 12
            val pixels = IntArray(width * height) { i ->
                if (format == 0x88) {
                    val entry = start + (bytes[start + 1024 + i].toInt() and 255) * 4
                    ((bytes[entry + 3].toInt() and 255) shl 24) or
                        ((bytes[entry + 2].toInt() and 255) shl 16) or
                        ((bytes[entry + 1].toInt() and 255) shl 8) or (bytes[entry].toInt() and 255)
                } else {
                    val pixel = start + i * if (format == 0x80) 3 else 2
                    val rgb = u16(bytes, pixel)
                    val r = (rgb ushr 11) and 31; val g = (rgb ushr 5) and 63; val b = rgb and 31
                    val alpha = if (format == 0x80) bytes[pixel + 2].toInt() and 255 else 255
                    (alpha shl 24) or (((r shl 3) or (r ushr 2)) shl 16) or
                        (((g shl 2) or (g ushr 4)) shl 8) or ((b shl 3) or (b ushr 2))
                }
            }
            frames += Frame(width, height, pixels)
            pos += 12 + payload.toInt()
        }
        require(frames.isNotEmpty()) { "Missing preview" }
        return frames
    }
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, p: Int) = (u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16))
}
