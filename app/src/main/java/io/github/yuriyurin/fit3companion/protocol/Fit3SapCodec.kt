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

    /**
     * The phone's plaintext HELLO reply to the band's 105-byte HELLO. It is not a copy
     * of the band's frame: only the 7-byte parameter block at offset 43 is echoed.
     * Layout (vendor capture): header 68 15 04 01 00 ff 03, "SAP_" + 32 hex chars at 7,
     * band parameter block at 43, phone model at 50, vendor at 66 and again at 82
     * (16 bytes each, NUL padded), phone address at 98 (left zero), trailer 02 at 104.
     */
    fun capabilityResponse(request: ByteArray, token: String, model: String, vendor: String): ByteArray {
        require(request.size == 105 && (request[0].toInt() and 0xff) == 104) {
            "Invalid Fit3 capability length"
        }
        require((request[1].toInt() and 0xff) == CAPABILITY_REQUEST) {
            "Not a capability request"
        }
        require(token.length == 32 && token.all { it in '0'..'9' || it in 'A'..'F' })
        val reply = ByteArray(105)
        byteArrayOf(0x68, CAPABILITY_RESPONSE.toByte(), 0x04, 0x01, 0x00, 0xff.toByte(), 0x03)
            .copyInto(reply, 0)
        ("SAP_$token").toByteArray(Charsets.US_ASCII).copyInto(reply, 7)
        request.copyInto(reply, 43, 43, 50)
        fun field(offset: Int, value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            bytes.copyInto(reply, offset, 0, minOf(bytes.size, 15)) // keep a NUL inside the field
        }
        field(50, model)
        field(66, vendor)
        field(82, vendor)
        reply[104] = 0x02
        return reply
    }

    /** 16 random bytes as 32 upper-case hex characters: the session id in the phone HELLO. */
    fun randomHelloToken(random: java.util.Random = java.security.SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02X".format(it.toInt() and 0xff) }

    fun negotiatedTransportMtu(request: ByteArray): Int {
        require(request.size == 105)
        return ((request[0x2c].toInt() and 0xff) shl 8) or
            (request[0x2d].toInt() and 0xff)
    }

    // Session TYPE byte: bits 1-2 are the fragment kind (0 single, 1 first, 2 middle,
    // 3 last), bit 0 is the originator (1 = phone). The band sends 00/02/06, the
    // phone 01/03/07. An ACK is 88 [SEQ] [80 single | c0 fragment] 00.
    private const val ORIGIN_PHONE = 1
    const val TYPE_ACK = 0x88
    private const val ACK_SINGLE = 0x80
    private const val ACK_FRAGMENT = 0xc0

    fun encodeSingle(serviceId: Int, message: ByteArray, transportMtu: Int, sequence: Int = SapSequence.START): ByteArray {
        require(serviceId in 1..31 && message.isNotEmpty())
        require(transportMtu in 15..500)
        require(sequence in 0..255)
        val body = byteArrayOf(ORIGIN_PHONE.toByte(), sequence.toByte(), serviceId.toByte(), serviceId.toByte()) + message
        val frame = encodeTransportBody(body, transportMtu)
        require(frame.size <= transportMtu) { "Fragmentation required" }
        return frame
    }

    /**
     * Encode one SAP message into one or more transport frames. A message that doesn't fit
     * becomes a first fragment (TYPE 03, SEQ) plus continuations (SEQ | 1), which is the
     * vendor app's shape. Only the two-frame form (first + last 07) appears in captures;
     * messages long enough for more mark the middle frames 05.
     */
    fun encodeMessage(serviceId: Int, message: ByteArray, transportMtu: Int,
                      sequence: Int = SapSequence.START): List<ByteArray> {
        require(serviceId in 1..31 && message.isNotEmpty())
        require(transportMtu in 15..500)
        require(sequence in 0..255)
        runCatching { encodeSingle(serviceId, message, transportMtu, sequence) }.getOrNull()?.let { return listOf(it) }

        val prefixSize = if (transportMtu < 256) 2 else 4
        val crcSize = 2
        val firstHeader = 4
        val continueHeader = 2
        val firstCapacity = transportMtu - prefixSize - crcSize - firstHeader
        val continueCapacity = transportMtu - prefixSize - crcSize - continueHeader
        require(firstCapacity > 0 && continueCapacity > 0)

        val out = mutableListOf<ByteArray>()
        var offset = 0
        val firstTake = minOf(firstCapacity, message.size)
        val firstBody = byteArrayOf(
            ((1 shl 1) or ORIGIN_PHONE).toByte(), // FIRST
            sequence.toByte(),
            serviceId.toByte(), serviceId.toByte(),
        ) + message.copyOfRange(0, firstTake)
        out += encodeTransportBody(firstBody, transportMtu)
        offset = firstTake

        while (offset < message.size) {
            require(out.size < 16) { "SAP message needs more than 16 fragments" }
            val take = minOf(continueCapacity, message.size - offset)
            val last = offset + take >= message.size
            val kind = if (last) 3 else 2
            val body = byteArrayOf(
                ((kind shl 1) or ORIGIN_PHONE).toByte(),
                (sequence or 1).toByte(),
            ) + message.copyOfRange(offset, offset + take)
            out += encodeTransportBody(body, transportMtu)
            offset += take
        }
        return out
    }

    /** Transport ACK for one received data frame: 88 [its SEQ] [80 single | c0 fragment] 00. */
    fun encodeAck(sequence: Int, fragment: Boolean, transportMtu: Int): ByteArray {
        require(sequence in 0..255)
        val body = byteArrayOf(TYPE_ACK.toByte(), sequence.toByte(),
            (if (fragment) ACK_FRAGMENT else ACK_SINGLE).toByte(), 0)
        return encodeTransportBody(body, transportMtu)
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
        /** The raw SEQ byte: what an ACK echoes and what continuations derive from. */
        val sequenceByte: Int = 0,
        val isAck: Boolean = false,
    ) {
        /** Every data frame is ACKed; single frames with 80, fragments with c0. */
        val needsAck: Boolean get() = !isAck
        val isFragment: Boolean get() = kind != 0
    }

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
        if (flags == TYPE_ACK) {
            return Fragment(kind = -1, sequence = 0, stream = 0, serviceId = null,
                payload = ByteArray(0), sequenceByte = body[1].toInt() and 0xff, isAck = true)
        }
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
            sequenceByte = sequenceFlags,
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

/**
 * Assembles one SAP message at a time; invalid continuations are dropped. A continuation
 * carries its first fragment's SEQ with bit 0 set (40 -> 41), not a running index.
 */
class SapReassembler {
    private var serviceId: Int? = null
    private var continuationSequence = -1
    private val data = ByteArrayOutputStream()

    fun push(fragment: Fit3SapCodec.Fragment): Pair<Int, ByteArray>? {
        if (fragment.isAck) return null
        if (fragment.kind == 0) {
            reset()
            return fragment.serviceId?.let { it to fragment.payload }
        }
        if (fragment.kind == 1) {
            reset()
            if (fragment.serviceId == null) return null
            serviceId = fragment.serviceId
            continuationSequence = fragment.sequenceByte or 1
            data.write(fragment.payload)
            return null
        }
        val id = serviceId ?: return null
        if (fragment.sequenceByte != continuationSequence ||
            fragment.kind !in 2..3 || data.size() + fragment.payload.size > 4096) {
            reset()
            return null
        }
        data.write(fragment.payload)
        if (fragment.kind != 3) return null
        val result = id to data.toByteArray()
        reset()
        return result
    }

    private fun reset() {
        serviceId = null
        continuationSequence = -1
        data.reset()
    }
}

/**
 * Outgoing SAP SEQ. The vendor app never uses 00: it starts at 40 and moves in steps of
 * 10 (40, 50, ... f0, then 40 again). Continuations use SEQ | 1 (see encodeMessage).
 */
class SapSequence {
    private var value = START
    fun reset() { value = START }
    fun next(): Int {
        val current = value
        value = (value + STEP) and 0xff
        if (value == 0) value = START
        return current
    }
    companion object {
        const val START = 0x40
        private const val STEP = 0x10
    }
}
