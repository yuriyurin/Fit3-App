package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream

/** Transport recovered from AZA3 and cross-checked against tools/fit3_sap_transport.py. */
object Fit3SapCodec {
    const val CAPABILITY_REQUEST = 0x14
    const val CAPABILITY_RESPONSE = 0x15
    const val SERVICE_OOBE = 1
    const val SERVICE_LOCATION = 4
    const val SERVICE_WEATHER = 5
    const val SERVICE_WATCHFACE = 6
    const val SERVICE_NOTIFICATIONS = 7
    const val SERVICE_CALENDAR = 8
    const val SERVICE_MEDIA = 9
    const val SERVICE_HEALTH = 10
    const val SERVICE_SETTINGS = 11
    const val SERVICE_WIDGETS = 15
    const val SERVICE_QUICK_MESSAGES = 18
    const val SERVICE_QUICK_PANEL = 19
    const val SERVICE_APPS = 24
    const val SERVICE_OTA_TRANSFER = 30

    // Read-only requests from the installed Fit3 plugin (message IDs 1 and 2).
    val watchInfoRequest = byteArrayOf(0x01, 0x04, 0x02, 0, 0, 0, 0x05, 0, 0x0e)
    val currentFaceRequest = byteArrayOf(0x02)
    val installedFacesRequest = byteArrayOf(0x01)
    val allFacesInfoRequest = byteArrayOf(0x00)

    data class InstalledFace(
        val id: Int,
        val sampler: Int,
        val name: String?,
        val current: Boolean,
        val version: String? = null,
    ) {
        val wireId: Int get() = id and 255
        val remotelyAddressable: Boolean get() = id in 1..255
    }

    data class CurrentFaceInfo(val id: Int, val sampler: Int)
    data class FacesInfo(val currentId: Int, val maximum: Int, val faces: List<InstalledFace>)
    data class DeleteFaceResult(val id: Int, val sampler: Int, val success: Boolean)

    fun deleteFaceRequest(id: Int, sampler: Int): ByteArray {
        require(id in 1..255 && sampler in 0..9)
        // UninstallBandFaceRequestBuilder; AZA3 FUN_0c1105f0.
        return byteArrayOf(5, 4, id.toByte(), 29, sampler.toByte())
    }

    fun parseDeleteFaceResponse(message: ByteArray): DeleteFaceResult? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 5 || message.size != 7 ||
            message[1] != 4.toByte() || message[3] != 29.toByte() || message[5] != 26.toByte() ||
            (message[6].toInt() and 255) !in 0..1) return null
        return DeleteFaceResult(message[2].toInt() and 255, message[4].toInt() and 255, message[6] == 1.toByte())
    }

    fun parseAllFacesInfo(message: ByteArray): FacesInfo? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 0 || message.size < 9 ||
            message[1] != 0.toByte() || message[3] != 1.toByte() || message[5] != 2.toByte() ||
            message[7] != 3.toByte() || message[6] != message[8]) return null
        val maximum = message[4].toInt() and 255
        val count = message[8].toInt() and 255
        if (maximum == 0 || count > maximum) return null
        val faces = parseFaceEntries(message, 9, count) ?: return null
        if (faces.isNotEmpty() && (faces.count { it.current } != 1 ||
                faces.single { it.current }.wireId != (message[2].toInt() and 255))) return null
        return FacesInfo(faces.singleOrNull { it.current }?.id ?: (message[2].toInt() and 255), maximum, faces)
    }

    fun setCurrentFaceRequest(id: Int, sampler: Int): ByteArray {
        require(id in 1..255 && sampler in 0..9)
        // Samsung SetCurrentBandFaceRequestBuilder: message 3, WF_ID 4,
        // WF_SAMPLER_ID 29. This selects an already installed face only.
        return byteArrayOf(0x03, 0x04, id.toByte(), 0x1d, sampler.toByte())
    }

    fun installFaceRequest(id: Int, sampler: Int): ByteArray {
        require(id in 1..255 && sampler in 0..9)
        return byteArrayOf(0x04, 0x04, id.toByte(), 0x1d, sampler.toByte())
    }

    data class InstallFaceResult(val id: Int, val sampler: Int, val status: Int)

    fun parseInstallFaceResponse(message: ByteArray): InstallFaceResult? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 4 || message.size < 7) return null
        if ((message[1].toInt() and 255) != 22 || (message[3].toInt() and 255) != 4 ||
            (message[5].toInt() and 255) != 29) return null
        return InstallFaceResult(message[4].toInt() and 255, message[6].toInt() and 255,
            message[2].toInt() and 255)
    }

    fun parseSetCurrentFaceResponse(message: ByteArray): Pair<Int, Int>? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 3) return null
        if (message.drop(1).none { (it.toInt() and 0xff) == 29 }) return null
        return findFaceIdAndSampler(message)
    }

    /** Schema-based parsing: never interpret string/customization bytes as deletion targets. */
    fun parseInstalledFaces(message: ByteArray): List<InstalledFace>? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 1) return null
        if (message.size < 3 || (message[1].toInt() and 0xff) != 3) return null // WATCHFACE_LIST
        val count = message[2].toInt() and 0xff
        return parseFaceEntries(message, 3, count)
    }

    private fun parseFaceEntries(message: ByteArray, start: Int, count: Int): List<InstalledFace>? {
        if (count > 100) return null
        var offset = start
        val result = mutableListOf<InstalledFace>()
        repeat(count) {
            if (offset + 2 > message.size || message[offset++] != 24.toByte()) return null
            val fields = message[offset++].toInt() and 255
            if (fields !in 1..32) return null
            val seen = mutableSetOf<Int>()
            var id: Int? = null
            var sampler: Int? = null
            var name: String? = null
            var version: String? = null
            var current = false
            repeat(fields) {
                if (offset + 2 > message.size) return null
                val field = message[offset++].toInt() and 255
                if (!seen.add(field)) return null
                val value = message[offset++].toInt() and 255
                when (field) {
                    5, 6, 7, 19 -> { // version, name, description, background: length + bytes
                        if (offset + value > message.size) return null
                        if (field == 6) name = message.copyOfRange(offset, offset + value)
                            .toString(Charsets.UTF_8).trimEnd('\u0000').trim().takeIf { it.isNotBlank() }
                        if (field == 5) version = message.copyOfRange(offset, offset + value)
                            .toString(Charsets.UTF_8).trimEnd('\u0000')
                        offset += value
                    }
                    17 -> { // widget index: count + raw option bytes
                        if (offset + value > message.size) return null
                        offset += value
                    }
                    4, in 8..16, 18, 29, 30 -> {
                        when (field) {
                            4 -> id = value
                            8, 29 -> {
                                if (sampler != null && sampler != value) return null
                                sampler = value // AZA3 list uses STYLE_ID=8, not WF_SAMPLER_ID=29.
                            }
                            11 -> {
                                if (value !in 0..1) return null
                                current = value == 1
                            }
                        }
                    }
                    else -> return null // Unknown widths: fail closed, don't scan for IDs.
                }
            }
            val wireId = id ?: return null
            // Firmware sends the low byte in WF_ID, but its generated name can retain
            // all five digits. Require both to agree; never identify a BIN by low byte alone.
            val namedId = name?.let { Regex("^wf_name-(\\d{5})$").matchEntire(it)
                ?.groupValues?.get(1)?.toIntOrNull() }
            if (namedId != null && (namedId !in 1..99999 || (namedId and 255) != wireId)) return null
            result += InstalledFace(namedId ?: wireId, sampler ?: return null, name, current, version)
        }
        if (offset != message.size || result.distinctBy { it.id to it.sampler }.size != count ||
            result.count { it.current } > 1) return null
        return result
    }

    /** GET_CURRENT alone has no full identity. Ambiguous low-byte aliases need GET_ALL_INFO. */
    fun resolveCurrentFace(info: CurrentFaceInfo, faces: List<InstalledFace>): CurrentFaceInfo? {
        val candidates = faces.filter { it.wireId == info.id && it.sampler == info.sampler }
        return when {
            candidates.size == 1 -> CurrentFaceInfo(candidates.single().id, info.sampler)
            candidates.size > 1 -> null
            else -> info
        }
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xa001 else crc ushr 1
            }
        }
        return crc and 0xffff
    }

    private fun crcBytes(data: ByteArray): ByteArray {
        val value = crc16(data)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    fun capabilityResponse(request: ByteArray): ByteArray {
        require(request.size == 105 && (request[0].toInt() and 0xff) == 104) {
            "Invalid Fit3 capability length"
        }
        require((request[1].toInt() and 0xff) == CAPABILITY_REQUEST) {
            "Not a capability request"
        }
        return request.copyOf().also { it[1] = CAPABILITY_RESPONSE.toByte() }
    }

    fun negotiatedTransportMtu(request: ByteArray): Int {
        require(request.size == 105)
        return ((request[0x2c].toInt() and 0xff) shl 8) or
            (request[0x2d].toInt() and 0xff)
    }

    fun encodeSingle(serviceId: Int, message: ByteArray, transportMtu: Int): ByteArray {
        require(serviceId in 1..31 && message.isNotEmpty())
        require(transportMtu in 15..500)
        val body = byteArrayOf(0, 0x40, serviceId.toByte(), serviceId.toByte()) + message
        val frame = encodeTransportBody(body, transportMtu)
        require(frame.size <= transportMtu) { "Fragmentation required" }
        return frame
    }

    /**
     * Encode one SAP message into one or more transport frames.  The previous Companion only
     * supported a single frame, which prevented app icons, album art and other large payloads.
     * The fragment flags mirror decodeFrame(): FIRST=1, CONTINUE=2, LAST=3.
     */
    fun encodeMessage(serviceId: Int, message: ByteArray, transportMtu: Int, stream: Int = 0): List<ByteArray> {
        require(serviceId in 1..31 && message.isNotEmpty())
        require(transportMtu in 15..500)
        require(stream in 0..7)
        runCatching { encodeSingle(serviceId, message, transportMtu) }.getOrNull()?.let { return listOf(it) }

        val prefixSize = if (transportMtu < 256) 2 else 4
        val crcSize = 2
        val firstHeader = 4
        val continueHeader = 2
        val firstCapacity = transportMtu - prefixSize - crcSize - firstHeader
        val continueCapacity = transportMtu - prefixSize - crcSize - continueHeader
        require(firstCapacity > 0 && continueCapacity > 0)

        val out = mutableListOf<ByteArray>()
        var offset = 0
        var sequence = 0
        val firstTake = minOf(firstCapacity, message.size)
        val firstBody = byteArrayOf(
            (1 shl 1).toByte(), // FIRST
            ((stream shl 5) or sequence).toByte(),
            serviceId.toByte(), serviceId.toByte(),
        ) + message.copyOfRange(0, firstTake)
        out += encodeTransportBody(firstBody, transportMtu)
        offset = firstTake
        sequence++

        while (offset < message.size) {
            require(sequence <= 15) { "SAP message needs more than 16 fragments" }
            val take = minOf(continueCapacity, message.size - offset)
            val last = offset + take >= message.size
            val kind = if (last) 3 else 2
            val body = byteArrayOf(
                (kind shl 1).toByte(),
                ((stream shl 5) or sequence).toByte(),
            ) + message.copyOfRange(offset, offset + take)
            out += encodeTransportBody(body, transportMtu)
            offset += take
            sequence++
        }
        return out
    }

    private fun encodeTransportBody(body: ByteArray, transportMtu: Int): ByteArray {
        val prefixSize = if (transportMtu < 256) 2 else 4
        val prefix = if (prefixSize == 2) {
            require(body.size <= 255)
            byteArrayOf(body.size.toByte(), body.size.toByte())
        } else {
            require(body.size <= 0xffff)
            val length = byteArrayOf((body.size ushr 8).toByte(), body.size.toByte())
            length + crcBytes(length)
        }
        return prefix + body + crcBytes(body)
    }

    data class Fragment(
        val kind: Int,
        val sequence: Int,
        val stream: Int,
        val serviceId: Int?,
        val payload: ByteArray,
    )

    fun decodeFrame(frame: ByteArray): Fragment {
        require(frame.size >= 8) { "Frame too short" }
        val shortLength = frame[0].toInt() and 0xff
        val shortOk = frame[0] == frame[1] && shortLength + 4 == frame.size
        val longLength = (shortLength shl 8) or (frame[1].toInt() and 0xff)
        val longOk = frame.size >= 10 && longLength + 6 == frame.size &&
            frame.sliceArray(2..3).contentEquals(crcBytes(frame.sliceArray(0..1)))
        val prefixSize = when {
            shortOk -> 2
            longOk -> 4
            else -> error("Invalid SAP frame prefix")
        }
        val body = frame.copyOfRange(prefixSize, frame.size - 2)
        require(frame.copyOfRange(frame.size - 2, frame.size).contentEquals(crcBytes(body))) {
            "Invalid SAP frame CRC"
        }
        require(body.size >= 2)
        val flags = body[0].toInt() and 0xff
        require((flags ushr 6) == 0) { "Unsupported SAP control frame" }
        val kind = (flags ushr 1) and 3
        val sequenceFlags = body[1].toInt() and 0xff
        val first = kind == 0 || kind == 1
        if (first) require(body.size >= 4 && body[2] == body[3])
        return Fragment(
            kind = kind,
            sequence = sequenceFlags and 0x0f,
            stream = sequenceFlags ushr 5,
            serviceId = if (first) body[2].toInt() and 0xff else null,
            payload = body.copyOfRange(if (first) 4 else 2, body.size),
        )
    }

    fun parseCurrentFace(message: ByteArray): Pair<Int, Int>? =
        parseCurrentFaceInfo(message)?.let { it.id to it.sampler }

    fun parseCurrentFaceInfo(message: ByteArray): CurrentFaceInfo? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 2) return null
        return findFaceInfo(message)
    }

    private fun findFaceIdAndSampler(message: ByteArray): Pair<Int, Int>? =
        findFaceInfo(message)?.let { it.id to it.sampler }

    private fun findFaceInfo(message: ByteArray): CurrentFaceInfo? {
        if (message.size != 5) return null
        var id: Int? = null
        var sampler: Int? = null
        for (i in 1 until message.lastIndex step 2) {
            when (message[i].toInt() and 0xff) {
                4 -> { if (id != null) return null; id = message[i + 1].toInt() and 0xff }
                29 -> { if (sampler != null) return null; sampler = message[i + 1].toInt() and 0xff }
                else -> return null
            }
        }
        return CurrentFaceInfo(id ?: return null, sampler ?: return null)
    }

    fun describeWatchFace(message: ByteArray): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        return when (h.id) {
            0 -> "[FACE_INFO]"
            1 -> "[FACE_LIST]"
            2 -> parseCurrentFaceInfo(message)?.let { info ->
                "[CURRENT_FACE id=${info.id} sampler=${info.sampler}]"
            } ?: "[CURRENT_FACE]"
            3 -> findFaceInfo(message)?.let { "[SET_FACE id=${it.id} sampler=${it.sampler}]" } ?: "[SET_FACE]"
            5 -> parseDeleteFaceResponse(message)?.let { "[DELETE_FACE id=${it.id} sampler=${it.sampler} success=${it.success}]" }
                ?: "[DELETE_FACE]"
            else -> "[FACE_MSG id=${h.id}]"
        }
    }

    fun findSoftwareVersion(message: ByteArray): String? {
        val ascii = message.toString(Charsets.ISO_8859_1)
        return Regex("R390[A-Z0-9]{7,12}").find(ascii)?.value
    }
}

/** Assembles one SAP response at a time; invalid continuations are dropped. */
class SapReassembler {
    private var serviceId: Int? = null
    private var stream = -1
    private var nextSequence = 0
    private val data = ByteArrayOutputStream()

    fun push(fragment: Fit3SapCodec.Fragment): Pair<Int, ByteArray>? {
        if (fragment.kind == 0) {
            reset()
            return fragment.serviceId?.let { it to fragment.payload }
        }
        if (fragment.kind == 1) {
            reset()
            if (fragment.sequence != 0 || fragment.serviceId == null) return null
            serviceId = fragment.serviceId
            stream = fragment.stream
            nextSequence = 1
            data.write(fragment.payload)
            return null
        }
        val id = serviceId ?: return null
        if (fragment.stream != stream || fragment.sequence != nextSequence ||
            fragment.kind !in 2..3 || data.size() + fragment.payload.size > 4096) {
            reset()
            return null
        }
        data.write(fragment.payload)
        nextSequence++
        if (fragment.kind != 3) return null
        val result = id to data.toByteArray()
        reset()
        return result
    }

    private fun reset() {
        serviceId = null
        stream = -1
        nextSequence = 0
        data.reset()
    }
}
