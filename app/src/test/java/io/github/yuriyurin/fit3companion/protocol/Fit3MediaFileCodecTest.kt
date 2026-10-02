package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.MediaFileTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class Fit3MediaFileCodecTest {
    @Test fun agentThirtyIsRestrictedToMediaFiles() {
        assertArrayEquals(byteArrayOf(1, 3, 9, 4, 8) + "test.art".toByteArray() +
            byteArrayOf(5, 100, 0, 0, 0), Fit3MediaFileCodec.fileInfoRequest("test.art", 100))
        assertEquals("33que,/user/list.que,100",
            MediaFileTransport.descriptor("list.que", 100).toString(Charsets.US_ASCII))
        assertEquals("33app,/user/com.spotify.music.app,100",
            MediaFileTransport.descriptor("com.spotify.music.app", 100).toString(Charsets.US_ASCII))
        var rejected = false
        try { MediaFileTransport.descriptor("firmware.bin", 100) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun fileInfoResponseMustAcceptBeforeSocketOpens() {
        assertTrue(Fit3MediaFileCodec.acceptedFileInfo(byteArrayOf(0x41, 1, 1)) == true)
        assertFalse(Fit3MediaFileCodec.acceptedFileInfo(byteArrayOf(0x41, 1, 0)) == true)
    }

    @Test fun socketMustWaitForWatchBluetoothOpenConfirmation() {
        assertEquals(true, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x42, 1, 1, 2, 0)))
        assertEquals(false, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x42, 1, 0, 2, 0)))
        assertEquals(null, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x41, 1, 1)))
        assertEquals(null, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x43, 1, 1)))
        assertEquals(null, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(2)))
        assertEquals(null, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x42)))
        assertEquals(null, Fit3MediaFileCodec.acceptedBluetoothOpen(byteArrayOf(0x42, 1, 2)))
    }

    @Test fun sppTransferIncludesCrcAndAcknowledgements() {
        val bytes = ByteArray(100) { it.toByte() }
        val output = ByteArrayOutputStream()
        MediaFileTransport.transfer(ByteArrayInputStream("300330310320340".toByteArray()),
            output, "cover.art", bytes) { false }
        val wire = output.toByteArray()
        val prefix = "3033art,/user/cover.art,100".toByteArray()
        assertArrayEquals(prefix, wire.copyOfRange(0, prefix.size))
        assertArrayEquals(bytes, wire.copyOfRange(prefix.size, prefix.size + bytes.size))
        val crc = CRC32().apply { update(bytes) }.value
        assertArrayEquals(ByteArray(4) { (crc ushr (8 * it)).toByte() },
            wire.copyOfRange(prefix.size + bytes.size, prefix.size + bytes.size + 4))
        assertEquals("3234", wire.copyOfRange(wire.size - 4, wire.size).toString(Charsets.US_ASCII))
    }
}
