package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test

class ExtendedFaceInstallTest {
    private fun entry(id: Int, current: Boolean): ByteArray {
        val name = "wf_name-${id.toString().padStart(5, '0')}\u0000".toByteArray()
        return byteArrayOf(24, 4, 4, id.toByte(), 6, name.size.toByte()) + name +
            byteArrayOf(8, 0, 11, if (current) 1 else 0)
    }

    @Test fun fullNamesDistinguishIdenticalLowByteIdsIncludingCatalogueFace() {
        val bytes = byteArrayOf(0xc0.toByte(), 0, 159.toByte(), 1, 10, 2, 2, 3, 2) +
            entry(99999, true) + entry(159, false)
        val info = Fit3SapCodec.parseAllFacesInfo(bytes)!!
        assertEquals(99999, info.currentId)
        assertEquals(listOf(99999, 159), info.faces.map { it.id })
        assertEquals(159, info.faces.first().wireId)
        assertFalse(info.faces.first().remotelyAddressable)
        assertTrue(info.faces.last().remotelyAddressable)
        assertNull(Fit3SapCodec.resolveCurrentFace(Fit3SapCodec.CurrentFaceInfo(159, 0), info.faces))
        assertEquals(Fit3SapCodec.CurrentFaceInfo(99999, 0), Fit3SapCodec.resolveCurrentFace(
            Fit3SapCodec.CurrentFaceInfo(159, 0), info.faces.take(1)))
        assertFalse(Fit3FacePolicy.canDelete(info.faces, 99999, 0, 159, 0, true))
        assertFalse(Fit3FacePolicy.hasRoom(info.faces, 2, 99743, 0))
    }

    @Test fun conflictingNameNeverBecomesACatalogueIdentity() {
        val wrong = entry(99999, true).also { it[3] = 158.toByte() }
        assertNull(Fit3SapCodec.parseInstalledFaces(byteArrayOf(0xc1.toByte(), 3, 1) + wrong))
        for (id in listOf(256, 99999)) {
            assertTrue(runCatching { Fit3SapCodec.installFaceRequest(id, 0) }.isFailure)
            assertTrue(runCatching { Fit3SapCodec.setCurrentFaceRequest(id, 0) }.isFailure)
            assertTrue(runCatching { Fit3SapCodec.deleteFaceRequest(id, 0) }.isFailure)
        }
    }

    @Test fun autoInstallNeedsBothCompleteTransferAndMatchingAckInEitherOrder() {
        val ack = Fit3SapCodec.InstallFaceResult(159, 0, 1)
        val first = ExtendedFaceInstall(99999, 0)
        first.response(ack)
        assertNull(first.statusAfterTransfer)
        first.transferred()
        assertEquals(1, first.statusAfterTransfer)
        val second = ExtendedFaceInstall(99999, 0)
        second.transferred()
        assertNull(second.statusAfterTransfer)
        second.response(ack.copy(id = 158))
        second.response(ack.copy(sampler = 1))
        assertNull(second.statusAfterTransfer)
        second.response(ack)
        assertEquals(1, second.statusAfterTransfer)
        val rejected = ExtendedFaceInstall(99999, 0)
        rejected.response(ack.copy(status = 4))
        rejected.transferred()
        assertEquals(4, rejected.statusAfterTransfer)
        assertTrue(runCatching { ExtendedFaceInstall(99999, 1) }.isFailure)
    }

    @Test fun aza3AutomaticAckFilenameBugStillRequiresTransferAndExactFullList() {
        assertEquals(2, ExtendedFaceInstall.automaticAckId(99999))
        assertEquals(0, ExtendedFaceInstall.automaticAckId(40000))
        assertEquals(201, ExtendedFaceInstall.automaticAckId(201))
        assertEquals(2, ExtendedFaceInstall.automaticAckId(12345))
        val tracker = ExtendedFaceInstall(99999, 0)
        val ack = Fit3SapCodec.parseInstallFaceResponse(byteArrayOf(0x44, 0x16, 1, 4, 2, 0x1d, 0))!!
        tracker.response(ack.copy(sampler = 1))
        tracker.transferred()
        assertNull(tracker.statusAfterTransfer)
        tracker.response(ack)
        assertEquals(1, tracker.statusAfterTransfer)
        // Real 01 October capture: ACK says 2, full list says 99999, never 2 or 159.
        val packet = "C0 00 9F 01 0A 02 02 03 02 18 08 04 9F 05 01 30 06 0E 77 66 5F 6E 61 6D 65 2D 39 39 39 39 39 00 08 00 09 00 0A 00 0B 01 0C 00 18 08 04 C9 05 01 30 06 0E 77 66 5F 6E 61 6D 65 2D 30 30 32 30 31 00 08 00 09 01 0A 00 0B 00 0C 00"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val info = Fit3SapCodec.parseAllFacesInfo(packet)!!
        assertEquals(99999, info.currentId)
        assertEquals(listOf(99999, 201), info.faces.map { it.id })
        assertEquals(0, info.faces.first().sampler)
        assertFalse(info.faces.any { it.id == ack.id || it.id == 159 })
    }
}
