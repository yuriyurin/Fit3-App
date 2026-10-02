package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.FaceBinValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class FaceBinValidatorTest {
    @Test fun acceptsFullFiveDigitContainerIdentityWithoutTruncatingIt() {
        for (id in listOf(255, 256, 40000, 99999)) {
            val name = "SM-R390_${id.toString().padStart(5, '0')}_256x402.bin"
            val report = FaceBinValidator.validate(container(id, "Mon".toByteArray()), name)
            assertEquals(id, report.faceId)
            assertEquals(name, report.canonicalFileName)
            val preview = io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(
                container(id, "Mon".toByteArray())).single()
            assertEquals(1, preview.width)
            assertEquals(1, preview.height)
            assertEquals(0xff00ff00.toInt(), preview.argb.single())
        }
        assertTrue(runCatching { FaceBinValidator.validate(container(0, "Mon".toByteArray())) }.isFailure)
        assertTrue(runCatching { FaceBinValidator.validate(container(100000, "Mon".toByteArray())) }.isFailure)
    }

    @Test fun acceptsStructurallyValidRussianEditedFace() {
        val bytes = container(22, byteArrayOf(0xd0.toByte(), 0x9f.toByte(), 0xd0.toByte(), 0xbd.toByte())) // "Пн"
        val report = FaceBinValidator.validate(bytes, sourceName = "DynamicDigital_RU.bin")
        assertEquals(22, report.faceId)
        assertEquals("SM-R390_00022_256x402.bin", report.canonicalFileName)
        assertEquals(listOf(0), report.styles)
        assertTrue("font_en.bin" in report.localeFiles)
    }

    @Test fun rejectsSingleByteCorruptionBeforeTransfer() {
        val bytes = container(22, "Mon".toByteArray())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        var rejected = false
        try { FaceBinValidator.validate(bytes) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun rejectsCanonicalNameForDifferentFaceId() {
        val bytes = container(22, "Mon".toByteArray())
        var rejected = false
        try { FaceBinValidator.validate(bytes, sourceName = "SM-R390_00023_256x402.bin") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun rejectsDirectoryTraversalAndUnknownFiles() {
        for (name in listOf("..", "unknown.bin")) {
            val bytes = container(22, "Mon".toByteArray())
            val record = 32 + 2 * 74 // preview.bin
            bytes.fill(0, record, record + 64)
            "./SM-R390_00022_256x402/$name".toByteArray().copyInto(bytes, record)
            putU16(bytes, 16, crc16(bytes, 32, bytes.size))
            var rejected = false
            try { FaceBinValidator.validate(bytes) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Unsafe entry $name must be rejected", rejected)
        }
    }

    @Test fun acceptsOfficialCorpusWhenProvided() {
        val directory = System.getenv("FIT3_OFFICIAL_FACE_CORPUS")?.let { java.io.File(it) } ?: return
        val files = directory.walkTopDown().filter { it.isFile &&
            it.name.matches(Regex("SM-R390_\\d{5}_256x402\\.bin")) }.toList()
        assertTrue("No official BINs found", files.isNotEmpty())
        files.forEach { FaceBinValidator.validate(it.readBytes(), sourceName = it.name) }
        println("Validated official Fit3 BIN corpus: ${files.size} files")
    }

    @Test fun strictTransferRejectsLegacyStudioZeroButLocalPreviewRemainsReadable() {
        val bytes = container(200, "Mon".toByteArray(), declaredCount = 0)
        assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.validate(bytes) }
        assertEquals(listOf(0), FaceBinValidator.validateLocal(bytes).styles)
        assertEquals(1, io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(bytes).single().width)
    }

    @Test fun repairsOnlyVariantCountAndCrcsWithoutMutatingOriginalOrPreview() {
        val bytes = container(201, "Mon".toByteArray(), declaredCount = 0)
        val original = bytes.copyOf()
        val before = io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(bytes).single()
        val prepared = FaceBinValidator.prepareForInstallation(bytes)
        assertTrue(prepared.repairedVariantCount)
        assertEquals(201, prepared.report.faceId)
        assertArrayEquals(original, bytes)
        val record = 32 + 3 * 74
        val settingOffset = readU32(bytes, record + 64)
        assertEquals(1, prepared.binary[settingOffset + 0x34].toInt())
        val allowedChanges = setOf(16, 17, record + 72, record + 73, settingOffset + 0x34)
        bytes.indices.forEach { index ->
            if (index !in allowedChanges) assertEquals("Byte $index changed", bytes[index], prepared.binary[index])
        }
        // validate() independently checks the per-file CRC and the global CRC after repair.
        assertEquals(prepared.report, FaceBinValidator.validate(prepared.binary))
        val after = io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(prepared.binary).single()
        assertArrayEquals(before.argb, after.argb)
        assertFalse(FaceBinValidator.prepareForInstallation(prepared.binary).repairedVariantCount)
    }

    @Test fun alreadyCorrectBinIsByteForByteUnchangedIncludingAllTenVariants() {
        for (styles in listOf(1, 4, 10)) {
            val bytes = container(22, "Mon".toByteArray(), styleCount = styles,
                declaredCount = styles, initialVariant = styles - 1)
            val prepared = FaceBinValidator.prepareForInstallation(bytes)
            assertFalse(prepared.repairedVariantCount)
            assertArrayEquals(bytes, prepared.binary)
            assertEquals((0 until styles).toList(), prepared.report.styles)
        }
    }

    @Test fun legacyRepairUsesActualNumberOfStylesNotHardCodedOne() {
        for (styles in listOf(1, 4, 10)) {
            val bytes = container(202, "Mon".toByteArray(), styleCount = styles,
                declaredCount = 0, initialVariant = styles - 1)
            val prepared = FaceBinValidator.prepareForInstallation(bytes)
            assertTrue(prepared.repairedVariantCount)
            assertEquals((0 until styles).toList(), FaceBinValidator.validate(prepared.binary).styles)
        }
    }

    @Test fun inconsistentNonzeroCountsAreNeverSilentlyRepaired() {
        for (count in listOf(2, 10, 255)) {
            val bytes = container(200, "Mon".toByteArray(), declaredCount = count)
            assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.validateLocal(bytes) }
            assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(bytes) }
        }
        val bytes = container(200, "Mon".toByteArray(), styleCount = 4, declaredCount = 1)
        assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(bytes) }
    }

    @Test fun invalidInitialVariantIsRejectedEvenInLegacyZeroCountBin() {
        for (count in listOf(0, 1)) for (sampler in listOf(1, 10, 255)) {
            val bytes = container(200, "Mon".toByteArray(), declaredCount = count, initialVariant = sampler)
            assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(bytes) }
        }
    }

    @Test fun corruptedLegacyBinAndWrongFilenameCannotUseRepairToBypassChecks() {
        val bytes = container(200, "Mon".toByteArray(), declaredCount = 0)
        assertThrows(IllegalArgumentException::class.java) {
            FaceBinValidator.prepareForInstallation(bytes, "SM-R390_00201_256x402.bin")
        }
        bytes[bytes.lastIndex] = 1
        assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(bytes) }
    }

    @Test fun preparationPreservesFullContainerIdWithoutTestingHardware() {
        val prepared = FaceBinValidator.prepareForInstallation(
            container(99999, "Mon".toByteArray(), declaredCount = 0))
        assertEquals(99999, prepared.report.faceId)
        assertEquals("SM-R390_99999_256x402.bin", prepared.report.canonicalFileName)
    }

    @Test fun repairDoesNotAcceptGapsOrMoreThanTenStyles() {
        val gap = container(200, "Mon".toByteArray(), styleCount = 2, declaredCount = 0)
        val record = 32 + 5 * 74 // style1.bin -> style2.bin, with a valid global CRC
        gap.fill(0, record, record + 64)
        "./SM-R390_00200_256x402/style2.bin".toByteArray().copyInto(gap, record)
        putU16(gap, 16, crc16(gap, 32, gap.size))
        assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(gap) }
        assertThrows(IllegalArgumentException::class.java) {
            FaceBinValidator.prepareForInstallation(container(200, "Mon".toByteArray(),
                styleCount = 11, declaredCount = 0))
        }
    }

    @Test fun repairRequiresValidSettingCrcNotJustValidGlobalCrc() {
        val bytes = container(200, "Mon".toByteArray(), declaredCount = 0)
        val settingRecord = 32 + 3 * 74
        bytes[settingRecord + 72] = (bytes[settingRecord + 72].toInt() xor 1).toByte()
        putU16(bytes, 16, crc16(bytes, 32, bytes.size))
        assertThrows(IllegalArgumentException::class.java) { FaceBinValidator.prepareForInstallation(bytes) }
    }

    @Test fun repairsRealStudioCorpusWhenProvided() {
        val directory = System.getenv("FIT3_LEGACY_FACE_CORPUS")?.let { java.io.File(it) } ?: return
        val files = directory.listFiles()!!.filter { it.extension == "bin" } +
            listOfNotNull(System.getenv("FIT3_LEGACY_FACE_SAMPLE")?.let { java.io.File(it) })
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val original = file.readBytes()
            val prepared = FaceBinValidator.prepareForInstallation(original, file.name)
            assertTrue(prepared.repairedVariantCount)
            assertArrayEquals(original, file.readBytes())
            assertEquals(FaceBinValidator.validateLocal(original), FaceBinValidator.validate(prepared.binary))
            val before = io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(original)
            val after = io.github.yuriyurin.fit3companion.ble.FacePreviewDecoder.decode(prepared.binary)
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (a, b) -> assertArrayEquals(a.argb, b.argb) }
        }
        println("Repaired real Studio BINs in memory: ${files.size}")
    }

    @Test fun realStockBinIsUnmodifiedWhenProvided() {
        val path = System.getenv("FIT3_STOCK_FACE_SAMPLE") ?: return
        val bytes = java.io.File(path).readBytes()
        val prepared = FaceBinValidator.prepareForInstallation(bytes)
        assertFalse(prepared.repairedVariantCount)
        assertArrayEquals(bytes, prepared.binary)
        assertEquals(4, prepared.report.styles.size)
    }

    private fun container(faceId: Int, text: ByteArray, styleCount: Int = 1,
                          declaredCount: Int = styleCount, initialVariant: Int = 0): ByteArray {
        val root = "SM-R390_${faceId.toString().padStart(5, '0')}_256x402"
        val setting = ByteArray(256)
        "LQ_WF".toByteArray().copyInto(setting, 0)
        putU32(setting, 12, 0x12345678)
        faceId.toString().padStart(5, '0').toByteArray().copyInto(setting, 16)
        setting[0x34] = declaredCount.toByte()
        setting[0x35] = initialVariant.toByte()
        val slot = ByteArray(64)
        root.toByteArray().copyInto(slot, 1)
        slot.copyInto(setting, 0x38)
        slot.copyInto(setting, 0x78)

        val style = ByteArray(24)
        putU32(style, 0, 0x12345678)
        putU32(style, 4, 0)
        putU32(style, 8, 0)
        putU32(style, 12, 0)
        putU32(style, 16, 0x200)
        putU32(style, 20, 24)

        val locale = ByteArray(32 + text.size)
        putU32(locale, 0, 0x12345678)
        putU32(locale, 4, 1)
        putU32(locale, 8, 1)
        putU32(locale, 24, text.size)
        putU32(locale, 28, 32)
        text.copyInto(locale, 32)

        val entries = listOf(
            "aod.bin" to style,
            "font_en.bin" to locale,
            "preview.bin" to byteArrayOf(1, 0, 1, 0, 0x82.toByte(), 0, 0, 0,
                6, 0, 0, 0, 0xe0.toByte(), 0x07, 0, 0, 0, 0),
            "setting.bin" to setting,
        ) + (0 until styleCount).map { "style$it.bin" to style }
        val directorySize = entries.size * 74
        val output = ByteArrayOutputStream()
        output.write(ByteArray(32 + directorySize))
        var payloadOffset = 32 + directorySize
        entries.forEach { (_, payload) -> output.write(payload); payloadOffset += payload.size }
        val bytes = output.toByteArray()
        "oppo".toByteArray().copyInto(bytes, 0)
        putU32(bytes, 4, 4)
        putU32(bytes, 8, bytes.size - 32)
        putU32(bytes, 12, entries.size)

        payloadOffset = 32 + directorySize
        entries.forEachIndexed { index, (name, payload) ->
            val record = 32 + index * 74
            val path = "./$root/$name".toByteArray()
            path.copyInto(bytes, record)
            putU32(bytes, record + 64, payloadOffset)
            putU32(bytes, record + 68, payload.size)
            putU16(bytes, record + 72, crc16(bytes, payloadOffset, payloadOffset + payload.size))
            payloadOffset += payload.size
        }
        putU16(bytes, 16, crc16(bytes, 32, bytes.size))
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
    }
    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { value, index -> value or ((bytes[offset + index].toInt() and 0xff) shl (8 * index)) }
    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun crc16(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = 0xffff
        for (offset in start until end) {
            crc = crc xor ((bytes[offset].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xffff
                else (crc shl 1) and 0xffff
            }
        }
        return crc
    }
}
