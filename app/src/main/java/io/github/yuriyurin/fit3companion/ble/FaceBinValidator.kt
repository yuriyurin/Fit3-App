package io.github.yuriyurin.fit3companion.ble

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Strict read-only validator for Galaxy Fit3 SM-R390 watch-face BIN containers.
 *
 * The checks are intentionally stronger than the transport needs: the complete OPPO v4
 * directory, every CRC16, tight packing, setting.bin identity, UTF-8 locale tables and the
 * style/widget/raster envelopes must all be internally consistent before a local BIN may be sent.
 */
internal object FaceBinValidator {
    const val MAX_BIN_BYTES = 4 * 1024 * 1024
    private const val HEADER_SIZE = 32
    private const val DIRECTORY_RECORD_SIZE = 74
    private const val SETTING_SIZE = 256

    data class Report(
        val faceId: Int,
        val canonicalFileName: String,
        val entryCount: Int,
        val styles: List<Int>,
        val localeFiles: List<String>,
        val containsVendorRaster88: Boolean,
    )

    private data class Entry(val path: String, val name: String, val offset: Int, val size: Int)

    private val pathRegex = Regex("^\\./SM-R390_(\\d{5})_256x402/([A-Za-z0-9_.-]+)$")
    private val canonicalNameRegex = Regex("^SM-R390_(\\d{5})_256x402\\.bin$")
    private val styleRegex = Regex("^style(\\d+)\\.bin$")
    private val bindingRegex = Regex("^font_\\d+\\.bin$")
    private val localeRegex = Regex("^font_[A-Za-z][A-Za-z0-9_]*\\.bin$")

    private val crcTable = IntArray(256).also { table ->
        for (i in table.indices) {
            var crc = i shl 8
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xffff
                else (crc shl 1) and 0xffff
            }
            table[i] = crc
        }
    }

    fun validate(bytes: ByteArray, sourceName: String? = null, expectedFaceId: Int? = null): Report {
        return validateContainer(bytes, sourceName, expectedFaceId, allowLegacyZeroVariants = false)
    }

    /** Import/display compatibility ONLY for the confirmed Studio zero-count defect.
     * Every CRC and structure check still runs. These bytes must not be sent unchanged.
     */
    fun validateLocal(bytes: ByteArray, sourceName: String? = null, expectedFaceId: Int? = null): Report =
        validateContainer(bytes, sourceName, expectedFaceId, allowLegacyZeroVariants = true)

    data class Prepared(val binary: ByteArray, val report: Report, val repairedVariantCount: Boolean)

    /** Return a verified transmission copy, without rewriting the user's BIN/content SHA.
     * Correct only a zero variant count; inconsistent nonzero counts/samplers are rejected.
     */
    fun prepareForInstallation(bytes: ByteArray, sourceName: String? = null): Prepared {
        val copy = bytes.copyOf()
        val report = validateLocal(copy, sourceName)
        val settingRecord = (0 until u32(copy, 12).toInt()).map { HEADER_SIZE + it * DIRECTORY_RECORD_SIZE }
            .single { asciiCString(copy, it, 64).endsWith("/setting.bin") }
        val settingOffset = u32(copy, settingRecord + 64).toInt()
        val repair = copy[settingOffset + 0x34] == 0.toByte()
        if (repair) {
            copy[settingOffset + 0x34] = report.styles.size.toByte()
            putU16(copy, settingRecord + 72, crc16(copy, settingOffset, settingOffset + SETTING_SIZE))
            putU16(copy, 16, crc16(copy, HEADER_SIZE, copy.size))
        }
        return Prepared(copy, validate(copy, sourceName, report.faceId), repair)
    }

    private fun validateContainer(bytes: ByteArray, sourceName: String?, expectedFaceId: Int?,
                                  allowLegacyZeroVariants: Boolean): Report {
        require(bytes.size in HEADER_SIZE..MAX_BIN_BYTES) {
            "BIN должен быть не больше 4 МиБ (${MAX_BIN_BYTES} байт)"
        }
        require(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "oppo") {
            "Неизвестный формат: отсутствует заголовок OPPO"
        }
        require(u32(bytes, 4) == 4L) { "Поддерживается только контейнер OPPO v4" }
        require(u32(bytes, 8) == bytes.size.toLong() - HEADER_SIZE) {
            "Размер payload в заголовке не совпадает с размером BIN"
        }
        val count = u32(bytes, 12).toInt()
        require(count in 1..1000) { "Недопустимое число файлов в контейнере: $count" }
        val directoryEnd = HEADER_SIZE.toLong() + count.toLong() * DIRECTORY_RECORD_SIZE
        require(directoryEnd <= bytes.size) { "Каталог OPPO выходит за границы файла" }
        require(bytes.copyOfRange(18, HEADER_SIZE).all { it == 0.toByte() }) {
            "Зарезервированные байты заголовка повреждены"
        }
        require(u16(bytes, 16) == crc16(bytes, HEADER_SIZE, bytes.size)) {
            "Не совпадает общая CRC16 контейнера"
        }

        val entries = ArrayList<Entry>(count)
        val seenNames = HashSet<String>(count)
        val faceIds = linkedSetOf<Int>()
        var expectedOffset = directoryEnd.toInt()
        repeat(count) { index ->
            val record = HEADER_SIZE + index * DIRECTORY_RECORD_SIZE
            val pathField = bytes.copyOfRange(record, record + 64)
            val nul = pathField.indexOf(0)
            require(nul >= 0) { "Путь записи #${index + 1} не завершён NUL" }
            require(pathField.copyOfRange(nul, pathField.size).all { it == 0.toByte() }) {
                "В padding пути записи #${index + 1} есть посторонние байты"
            }
            require(pathField.copyOfRange(0, nul).all { (it.toInt() and 0xff) in 0x20..0x7e }) {
                "Путь записи #${index + 1} не ASCII"
            }
            val path = pathField.copyOfRange(0, nul).toString(Charsets.US_ASCII)
            val match = pathRegex.matchEntire(path) ?: error("Недопустимый путь внутри BIN: $path")
            val faceId = match.groupValues[1].toInt()
            val name = match.groupValues[2]
            require(name == "setting.bin" || name == "preview.bin" || name == "aod.bin" ||
                styleRegex.matches(name) || bindingRegex.matches(name) || localeRegex.matches(name)) {
                "Неподдерживаемый внутренний файл BIN: $name"
            }
            faceIds += faceId
            require(seenNames.add(name)) { "Повторяющийся файл внутри BIN: $name" }

            val offset = u32(bytes, record + 64)
            val size = u32(bytes, record + 68)
            val end = offset + size
            require(offset == expectedOffset.toLong()) {
                "Контейнер не плотно упакован перед $name"
            }
            require(offset >= directoryEnd && end >= offset && end <= bytes.size) {
                "Файл $name выходит за границы контейнера"
            }
            require(u16(bytes, record + 72) == crc16(bytes, offset.toInt(), end.toInt())) {
                "Не совпадает CRC16 файла $name"
            }
            entries += Entry(path, name, offset.toInt(), size.toInt())
            expectedOffset = end.toInt()
        }
        require(expectedOffset == bytes.size) { "После последней записи есть непроверенный хвост" }
        require(faceIds.size == 1) { "В контейнере смешаны разные ID циферблатов" }
        val faceId = faceIds.first()
        // Container identity is five decimal digits / uint32 in AZA3, not the SAP byte ID.
        require(faceId in 1..99999) { "ID циферблата должен быть от 1 до 99999" }
        if (expectedFaceId != null) require(faceId == expectedFaceId) {
            "ID BIN ($faceId) не совпадает с ожидаемым ID ($expectedFaceId)"
        }
        canonicalNameRegex.matchEntire(sourceName.orEmpty())?.let { source ->
            require(source.groupValues[1].toInt() == faceId) {
                "Имя выбранного BIN относится к другому циферблату"
            }
        }

        val byName = entries.associateBy { it.name }
        listOf("setting.bin", "preview.bin", "aod.bin", "style0.bin").forEach { required ->
            require(byName.containsKey(required)) { "В BIN отсутствует обязательный $required" }
        }
        validateSetting(bytes, byName.getValue("setting.bin"), faceId)

        val styles = ArrayList<Int>()
        val locales = ArrayList<String>()
        var vendorRaster88 = false
        for (entry in entries) {
            when {
                bindingRegex.matches(entry.name) -> validateFontBinding(bytes, entry)
                localeRegex.matches(entry.name) -> {
                    validateLocaleTable(bytes, entry)
                    locales += entry.name
                }
                entry.name == "aod.bin" || styleRegex.matches(entry.name) -> {
                    vendorRaster88 = validateStyle(bytes, entry) || vendorRaster88
                    styleRegex.matchEntire(entry.name)?.let { styles += it.groupValues[1].toInt() }
                }
            }
        }
        styles.sort()
        require(styles.isNotEmpty() && styles.first() == 0) { "Нет style0.bin" }
        require(styles == (0..styles.last()).toList()) { "Номера style*.bin имеют пропуски" }
        require(styles.last() < 10) { "Fit3 поддерживает варианты от 0 до 9" }
        val settingOffset = byName.getValue("setting.bin").offset
        val variantCount = bytes[settingOffset + 0x34].toInt() and 0xff
        require(variantCount == styles.size || (allowLegacyZeroVariants && variantCount == 0)) {
            "Число вариантов в setting.bin ($variantCount) не совпадает с числом style*.bin (${styles.size})"
        }
        val initialVariant = bytes[settingOffset + 0x35].toInt() and 0xff
        require(initialVariant in styles) { "Начальный вариант $initialVariant отсутствует внутри BIN" }

        return Report(
            faceId = faceId,
            canonicalFileName = "SM-R390_${faceId.toString().padStart(5, '0')}_256x402.bin",
            entryCount = count,
            styles = styles,
            localeFiles = locales.sorted(),
            containsVendorRaster88 = vendorRaster88,
        )
    }

    private fun validateSetting(bytes: ByteArray, entry: Entry, faceId: Int) {
        require(entry.size == SETTING_SIZE) { "setting.bin имеет неожиданный размер ${entry.size}" }
        val start = entry.offset
        require(String(bytes, start, 5, Charsets.US_ASCII) == "LQ_WF") {
            "setting.bin не содержит маркер LQ_WF"
        }
        require(u32(bytes, start + 12) == 0x12345678L) { "setting.bin повреждён" }
        val id = asciiCString(bytes, start + 16, 16)
        require(id == faceId.toString().padStart(5, '0')) {
            "ID в setting.bin ($id) не совпадает с ID каталога ($faceId)"
        }
        require(bytes[start + 0x38] == 0.toByte() && bytes[start + 0x78] == 0.toByte()) {
            "Повреждены name slots в setting.bin"
        }
        require(bytes.copyOfRange(start + 0x38, start + 0x78)
            .contentEquals(bytes.copyOfRange(start + 0x78, start + 0xb8))) {
            "Две копии имени в setting.bin расходятся"
        }
    }

    private fun validateFontBinding(bytes: ByteArray, entry: Entry) {
        require(entry.size == 92) { "${entry.name} должен иметь размер 92 байта" }
        val role = bytes.copyOfRange(entry.offset + 0x48, entry.offset + 0x58)
        val nul = role.indexOf(0).let { if (it < 0) role.size else it }
        require(role.copyOfRange(0, nul).all { (it.toInt() and 0xff) in 0x20..0x7e }) {
            "${entry.name}: повреждена роль системного шрифта"
        }
        val pointSize = u32(bytes, entry.offset + 0x58)
        require(pointSize in 1..200) { "${entry.name}: недопустимый размер шрифта $pointSize" }
    }

    private fun validateLocaleTable(bytes: ByteArray, entry: Entry) {
        require(entry.size >= 24) { "${entry.name}: слишком короткая таблица локализации" }
        val start = entry.offset
        val end = start + entry.size
        require(u32(bytes, start) == 0x12345678L) { "${entry.name}: неверный magic" }
        val groupCount = u32(bytes, start + 8).toInt()
        require(groupCount in 1..1024) { "${entry.name}: неверное число строк" }
        require(bytes.copyOfRange(start + 12, start + 24).all { it == 0.toByte() }) {
            "${entry.name}: повреждён reserved header"
        }
        val descriptorEnd = start.toLong() + 24L + groupCount.toLong() * 8L
        require(descriptorEnd <= end) { "${entry.name}: таблица descriptors выходит за границы" }
        var previousOffset = -1L
        var lastEnd = descriptorEnd
        repeat(groupCount) { index ->
            val descriptor = start + 24 + index * 8
            val length = u32(bytes, descriptor)
            val relativeOffset = u32(bytes, descriptor + 4)
            val absolute = start.toLong() + relativeOffset
            val textEnd = absolute + length
            require(absolute >= descriptorEnd && textEnd >= absolute && textEnd <= end) {
                "${entry.name}: строка #$index выходит за границы"
            }
            if (index == 0) require(absolute == descriptorEnd) {
                "${entry.name}: первая строка начинается не после descriptors"
            }
            if (previousOffset >= 0) require(absolute >= previousOffset) {
                "${entry.name}: offsets строк идут в обратном порядке"
            }
            decodeUtf8Strict(bytes, absolute.toInt(), textEnd.toInt(), entry.name, index)
            previousOffset = absolute
            lastEnd = textEnd
        }
        require(lastEnd == end.toLong()) { "${entry.name}: после UTF-8 строк есть непроверенный хвост" }
    }

    /** Returns true when the official-corpus vendor raster format 0x88 is present. */
    private fun validateStyle(bytes: ByteArray, entry: Entry): Boolean {
        require(entry.size >= 24) { "${entry.name}: слишком короткий style container" }
        val start = entry.offset
        val end = start + entry.size
        require(u32(bytes, start) == 0x12345678L) { "${entry.name}: неверный style magic" }
        val widgetCount = u32(bytes, start + 4).toInt()
        val widgetBytes = u32(bytes, start + 8)
        val imageBytes = u32(bytes, start + 12)
        val imageOffset = u32(bytes, start + 20)
        require(widgetCount in 0..2000) { "${entry.name}: неверное число widgets" }
        require(imageOffset == 24L + widgetBytes && imageOffset + imageBytes == entry.size.toLong()) {
            "${entry.name}: повреждены размеры секций widgets/images"
        }

        val widgetsEnd = start.toLong() + imageOffset
        var cursor = start + 24
        repeat(widgetCount) { index ->
            require(cursor.toLong() + 36 <= widgetsEnd) { "${entry.name}: widget #$index обрезан" }
            val recordSize = (u32(bytes, cursor + 12) and 0xffff).toInt()
            require(recordSize in 36..512 && cursor.toLong() + recordSize <= widgetsEnd) {
                "${entry.name}: widget #$index имеет неверный размер $recordSize"
            }
            cursor += recordSize
        }
        require(cursor.toLong() == widgetsEnd) { "${entry.name}: widget section разобран не полностью" }

        var has88 = false
        cursor = widgetsEnd.toInt()
        while (cursor < end) {
            require(cursor + 12 <= end) { "${entry.name}: обрезан raster header" }
            val width = u16(bytes, cursor)
            val height = u16(bytes, cursor + 2)
            val format = u16(bytes, cursor + 4)
            val reserved = u16(bytes, cursor + 6)
            val dataSize = u32(bytes, cursor + 8)
            require(width > 0 && height > 0 && reserved == 0) { "${entry.name}: повреждён raster header" }
            require(format == 0x80 || format == 0x82 || format == 0x88) {
                "${entry.name}: неизвестный raster format 0x${format.toString(16)}"
            }
            require(dataSize >= 4 && cursor.toLong() + 12L + dataSize <= end) {
                "${entry.name}: raster выходит за границы"
            }
            if (format == 0x80 || format == 0x82) {
                val bpp = if (format == 0x80) 3L else 2L
                val expected = width.toLong() * height.toLong() * bpp + 4L
                require(dataSize == expected) { "${entry.name}: неверный размер raster payload" }
            } else {
                has88 = true // seen in an untouched Samsung catalogue BIN (00002)
            }
            cursor = (cursor.toLong() + 12L + dataSize).toInt()
        }
        require(cursor == end) { "${entry.name}: image section разобран не полностью" }
        return has88
    }

    private fun asciiCString(bytes: ByteArray, start: Int, length: Int): String {
        val field = bytes.copyOfRange(start, start + length)
        val nul = field.indexOf(0).let { if (it < 0) field.size else it }
        require(field.copyOfRange(0, nul).all { (it.toInt() and 0xff) in 0x20..0x7e }) {
            "Повреждена ASCII-строка"
        }
        return field.copyOfRange(0, nul).toString(Charsets.US_ASCII)
    }

    private fun decodeUtf8Strict(bytes: ByteArray, start: Int, end: Int, name: String, index: Int) {
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, start, end - start))
        } catch (_: Exception) {
            error("$name: строка #$index содержит неверный UTF-8")
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun crc16(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = 0xffff
        for (offset in start until end) {
            val index = ((crc ushr 8) xor (bytes[offset].toInt() and 0xff)) and 0xff
            crc = ((crc shl 8) xor crcTable[index]) and 0xffff
        }
        return crc
    }
}
