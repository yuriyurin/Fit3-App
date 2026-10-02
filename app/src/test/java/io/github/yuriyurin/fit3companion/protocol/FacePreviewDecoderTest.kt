package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder
import io.github.yuriyurin.fit3companion.ble.FaceBinValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FacePreviewDecoderTest {
    private fun raster(format: Int, payload: ByteArray): ByteArray = byteArrayOf(
        1, 0, 1, 0, format.toByte(), 0, 0, 0,
        (payload.size + 4).toByte(), ((payload.size + 4) ushr 8).toByte(), 0, 0) + payload + ByteArray(4)
    @Test fun decodes565AndAlphaAndMultipleStylePreviews() {
        val frames = FacePreviewDecoder.decodeRasters(raster(0x82, byteArrayOf(0, 0xF8.toByte())) +
            raster(0x80, byteArrayOf(0xE0.toByte(), 7, 127)))
        assertEquals(2, frames.size)
        assertEquals(0xFFFF0000.toInt(), frames[0].argb[0])
        assertEquals(0x7F00FF00, frames[1].argb[0])
    }
    @Test fun decodesBgraPaletteWithoutSwappingRedAndBlue() {
        val palette = ByteArray(1025).apply {
            this[4] = 0x33; this[5] = 0x22; this[6] = 0x11; this[7] = 0xFF.toByte()
            this[1024] = 1
        }
        assertEquals(0xFF112233.toInt(), FacePreviewDecoder.decodeRasters(raster(0x88, palette)).first().argb[0])
    }
    @Test fun rejectsTruncatedUnknownAndOversizedRasters() {
        val valid = raster(0x82, byteArrayOf(0, 0))
        for (bad in listOf(valid.copyOf(10), valid.copyOf(valid.size - 1),
            valid.copyOf().apply { this[4] = 0x7F },
            valid.copyOf().apply { this[1] = 0x7F }, valid + byteArrayOf(0))) {
            assertThrows(Exception::class.java) { FacePreviewDecoder.decodeRasters(bad) }
        }
    }
    @Test fun suppliedCustomBinContainsRealPreview() {
        val sample = System.getenv("FIT3_CUSTOM_FACE_SAMPLE") ?: return
        val bytes = File(sample).readBytes()
        assertEquals(80, FaceBinValidator.validateLocal(bytes).faceId)
        val frame = FacePreviewDecoder.decode(bytes).first()
        assertEquals(178, frame.width); assertEquals(280, frame.height)
        assertTrue(frame.argb.toSet().size > 50)
        // Desktop-only diagnostic export; Android's compile bootclasspath has no java.desktop.
        val imageType = Class.forName("java.awt.image.BufferedImage")
        val image = imageType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).newInstance(frame.width, frame.height, 2)
        imageType.getMethod("setRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
        val output = File("build/reports/custom-face-preview.png")
        output.parentFile.mkdirs()
        check(Class.forName("javax.imageio.ImageIO").getMethod("write",
            Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
            .invoke(null, image, "PNG", output) == true)
    }
}
