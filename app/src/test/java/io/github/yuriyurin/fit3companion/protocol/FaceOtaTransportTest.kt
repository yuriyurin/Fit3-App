package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.FaceOtaTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class FaceOtaTransportTest {
    @Test fun installCommandAndResponseAreDistinctFromSelection() {
        assertArrayEquals(byteArrayOf(4, 4, 112, 29, 2),
            Fit3SapCodec.installFaceRequest(112, 2))
        assertEquals(Fit3SapCodec.InstallFaceResult(112, 2, 1),
            Fit3SapCodec.parseInstallFaceResponse(byteArrayOf(0x44, 22, 1, 4, 112, 29, 2)))
        assertEquals(null, Fit3SapCodec.parseInstallFaceResponse(
            Fit3SapCodec.setCurrentFaceRequest(112, 2)))
    }

    @Test fun descriptorCannotTargetFirmware() {
        assertEquals("33bin,/user/wf/SM-R390_00112_256x402.bin,5",
            FaceOtaTransport.descriptor("SM-R390_00112_256x402.bin", 5).toString(Charsets.US_ASCII))
        var rejected = false
        try { FaceOtaTransport.descriptor("R390_AZA3.bin", 5) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertEquals(true, rejected)
    }

    @Test fun windowCrcIsLittleEndian() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(1_000, FaceOtaTransport.writeWindow(output, bytes, 0))
        val crc = CRC32().apply { update(bytes) }.value
        assertArrayEquals(bytes.copyOfRange(0, 960), output.toByteArray().copyOfRange(0, 960))
        assertArrayEquals(bytes.copyOfRange(960, 1000), output.toByteArray().copyOfRange(960, 1000))
        assertArrayEquals(ByteArray(4) { (crc ushr (8 * it)).toByte() },
            output.toByteArray().copyOfRange(1000, 1004))
    }

    @Test fun transferRequiresEveryAcknowledgement() {
        val bytes = ByteArray(100) { it.toByte() }
        val output = ByteArrayOutputStream()
        var sent = 0
        FaceOtaTransport.transfer(ByteArrayInputStream("300330310320340".toByteArray()),
            output, "SM-R390_00112_256x402.bin", bytes, progress = { value, _ -> sent = value })
        assertEquals(100, sent)
        val wire = output.toByteArray()
        assertEquals("30", wire.copyOfRange(0, 2).toString(Charsets.US_ASCII))
        assertEquals("34", wire.copyOfRange(wire.size - 2, wire.size).toString(Charsets.US_ASCII))
    }
}
