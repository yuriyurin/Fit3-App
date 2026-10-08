package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fit3SapCodecTest {
    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun watchInfoFrameMatchesLivePcVector() {
        val encoded = Fit3SapCodec.encodeSingle(
            Fit3SapCodec.SERVICE_OOBE, Fit3SapCodec.watchInfoRequest, 500)
        assertArrayEquals("000dc5c10140010101040200000005000ee1e8".hex(), encoded)
        val decoded = Fit3SapCodec.decodeFrame(encoded)
        assertEquals(1, decoded.serviceId)
        assertArrayEquals(Fit3SapCodec.watchInfoRequest, decoded.payload)
    }

    @Test fun capabilityReplyOnlyChangesOpcode() {
        val request = ByteArray(105)
        request[0] = 104
        request[1] = Fit3SapCodec.CAPABILITY_REQUEST.toByte()
        request[0x2c] = 1
        request[0x2d] = 0xf4.toByte()
        assertEquals(500, Fit3SapCodec.negotiatedTransportMtu(request))
        val response = Fit3SapCodec.capabilityResponse(request)
        assertEquals(Fit3SapCodec.CAPABILITY_RESPONSE, response[1].toInt() and 0xff)
        assertTrue(request.indices.filter { it != 1 }.all { request[it] == response[it] })
    }

    @Test fun phoneFramesMatchVendorMuteWrite() {
        // fit3_sap_protocol.md §7.2: phone TYPE 01, SEQ d0, mute on = 03 00 01.
        assertArrayEquals("0007c24101d00b0b0300017595".hex(),
            Fit3SapCodec.encodeSingle(11, "030001".hex(), 500, 0xd0))
    }

    @Test fun sequenceSkipsZeroAndStepsLikeTheVendorApp() {
        val sequence = SapSequence()
        val values = List(13) { sequence.next() }
        assertEquals(listOf(0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x40), values)
    }

    @Test fun fragmentedMessageUsesFirstAndContinuationTypes() {
        val message = ByteArray(900) { it.toByte() }
        val frames = Fit3SapCodec.encodeMessage(5, message, 500, 0x50)
        assertEquals(2, frames.size)
        val first = Fit3SapCodec.decodeFrame(frames[0])
        val last = Fit3SapCodec.decodeFrame(frames[1])
        assertEquals(0x03, frames[0][4].toInt() and 0xff)
        assertEquals(0x50, frames[0][5].toInt() and 0xff)
        assertEquals(0x07, frames[1][4].toInt() and 0xff)
        assertEquals(0x51, frames[1][5].toInt() and 0xff)
        val assembler = SapReassembler()
        assertNull(assembler.push(first))
        val (service, body) = requireNotNull(assembler.push(last))
        assertEquals(5, service)
        assertArrayEquals(message, body)
    }

    @Test fun currentFaceParserUsesApkOffsets() {
        assertEquals(22 to 3, Fit3SapCodec.parseCurrentFace("4204161d03".hex()))
        assertNull(Fit3SapCodec.parseCurrentFace("420516".hex()))
    }

    @Test fun watchFaceSelectionMatchesPluginPacket() {
        assertArrayEquals("0304161d03".hex(), Fit3SapCodec.setCurrentFaceRequest(22, 3))
        assertEquals(22 to 3, Fit3SapCodec.parseSetCurrentFaceResponse("4304161d03".hex()))
        assertNull(Fit3SapCodec.parseSetCurrentFaceResponse("4304161c03".hex()))
    }

    @Test fun installedFaceListUsesWatchReportedIdsOnly() {
        // One face: two stable fields (WF_ID 22, WF_SAMPLER_ID 3).
        val faces = Fit3SapCodec.parseInstalledFaces("410301180204161d03".hex())
        assertEquals(listOf(Fit3SapCodec.InstalledFace(22, 3, null, false)), faces)
        // Unknown widths cannot be safely skipped when deletion is available.
        assertNull(Fit3SapCodec.parseInstalledFaces("41030118020416ff03".hex()))
    }

    @Test fun aza3ListUsesStyleIdAndSkipsEntireStrings() {
        // Actual RX: C1 03 01 18 08 ... WF_NAME includes its trailing NUL.
        val entry = "18080450050130060e77665f6e616d652d303030383000080209000a020b010c00".hex()
        val face = Fit3SapCodec.InstalledFace(80, 2, "wf_name-00080", true, "0")
        assertEquals(listOf(face), Fit3SapCodec.parseInstalledFaces("c10301".hex() + entry))
        // GET_ALL_INFO adds current ID, maximum, count and list count.
        assertEquals(Fit3SapCodec.FacesInfo(80, 10, listOf(face)),
            Fit3SapCodec.parseAllFacesInfo("c00050010a02010301".hex() + entry))
        // Raw customization/name bytes that resemble fields must not become identities.
        assertEquals(listOf(Fit3SapCodec.InstalledFace(22, 3, "\u0004\u001d\u0018", false)),
            Fit3SapCodec.parseInstalledFaces("c10301180304160603041d180803".hex()))
    }

    @Test fun malformedFaceListNeverProducesPartialDeletionTargets() {
        assertNull(Fit3SapCodec.parseInstalledFaces("c10302180204160800".hex())) // missing second entry
        assertNull(Fit3SapCodec.parseInstalledFaces("c1030118020416080000".hex())) // trailing data
        assertNull(Fit3SapCodec.parseInstalledFaces("c103011803041608001d01".hex())) // conflicting sampler
        assertNull(Fit3SapCodec.parseInstalledFaces("c1030118020416060901".hex())) // truncated name
        assertNull(Fit3SapCodec.parseAllFacesInfo("c00016010a02020300".hex())) // counts differ
        assertNull(Fit3SapCodec.parseAllFacesInfo("c00016010002000300".hex())) // no usable maximum
        assertEquals(emptyList<Fit3SapCodec.InstalledFace>(), Fit3SapCodec.parseInstalledFaces("c10300".hex()))
        assertNull(Fit3SapCodec.parseInstalledFaces("c10300ff".hex()))
    }

    @Test fun uninstallMatchesPluginAndFirmwareExactly() {
        assertArrayEquals("0504161d03".hex(), Fit3SapCodec.deleteFaceRequest(22, 3))
        assertEquals(Fit3SapCodec.DeleteFaceResult(22, 3, true),
            Fit3SapCodec.parseDeleteFaceResponse("4504161d031a01".hex()))
        assertEquals(Fit3SapCodec.DeleteFaceResult(22, 3, false),
            Fit3SapCodec.parseDeleteFaceResponse("4504161d031a00".hex()))
        assertNull(Fit3SapCodec.parseDeleteFaceResponse("0504161d031a01".hex())) // request isn't ACK
        assertNull(Fit3SapCodec.parseDeleteFaceResponse("4504161d031a02".hex()))
        assertNull(Fit3SapCodec.parseDeleteFaceResponse("4504161d031701".hex()))
        assertNull(Fit3SapCodec.parseDeleteFaceResponse("4504161d031a".hex()))
        assertNull(Fit3SapCodec.parseCurrentFace("42041d1d04".hex() + byteArrayOf(0)))
        assertEquals(29 to 4, Fit3SapCodec.parseCurrentFace("42041d1d04".hex()))
    }

    @Test fun capacityAndDeletionProtectCurrentAndFinalVariant() {
        val current = Fit3SapCodec.InstalledFace(22, 0, null, true)
        val alternate = current.copy(sampler = 1, current = false)
        val faces = listOf(current, alternate)
        assertTrue(Fit3FacePolicy.canDelete(faces, 22, 1, 22, 0, true))
        assertEquals(false, Fit3FacePolicy.canDelete(faces, 22, 0, 22, 0, true))
        assertEquals(false, Fit3FacePolicy.canDelete(faces, 22, 1, null, 0, true))
        assertEquals(false, Fit3FacePolicy.canDelete(faces, 22, 1, 22, 0, false))
        assertEquals(false, Fit3FacePolicy.canDelete(listOf(alternate), 22, 1, 22, 0, true))
        assertEquals(false, Fit3FacePolicy.hasRoom(faces, 2, 23, 0))
        assertTrue(Fit3FacePolicy.hasRoom(faces, 2, 22, 1)) // existing variant can be replaced
        assertTrue(Fit3FacePolicy.hasRoom(faces, 10, 23, 0))
        assertEquals(false, Fit3FacePolicy.hasRoom(faces, null, 23, 0))
    }

    @Test fun deletionNeedsMatchingSuccessfulAckThenACompleteFreshList() {
        val face = Fit3SapCodec.InstalledFace(22, 1, null, false)
        val target = face.id to face.sampler
        assertEquals(false, Fit3FacePolicy.deletionConfirmed(target, FaceDeleteState.CHECKING, emptyList()))
        assertEquals(false, Fit3FacePolicy.deletionConfirmed(target, FaceDeleteState.SENDING, emptyList()))
        assertEquals(false, Fit3FacePolicy.deletionConfirmed(target, FaceDeleteState.FAILED, emptyList()))
        assertEquals(false, Fit3FacePolicy.deletionConfirmed(target, FaceDeleteState.VERIFYING, listOf(face)))
        assertTrue(Fit3FacePolicy.deletionConfirmed(target, FaceDeleteState.VERIFYING, listOf(face.copy(sampler = 0))))
        assertNull(Fit3SapCodec.parseAllFacesInfo("c00017010a020103011803041608000b01".hex())) // inconsistent current ID
    }

    @Test fun badCrcIsRejected() {
        val frame = Fit3SapCodec.encodeSingle(6, Fit3SapCodec.currentFaceRequest, 500)
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        var rejected = false
        try { Fit3SapCodec.decodeFrame(frame) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun assemblerHandlesSingleFrame() {
        val frame = Fit3SapCodec.encodeSingle(6, Fit3SapCodec.currentFaceRequest, 500)
        val assembled = SapReassembler().push(Fit3SapCodec.decodeFrame(frame))
        assertEquals(6, assembled?.first)
        assertArrayEquals(Fit3SapCodec.currentFaceRequest, assembled?.second)
    }
}
