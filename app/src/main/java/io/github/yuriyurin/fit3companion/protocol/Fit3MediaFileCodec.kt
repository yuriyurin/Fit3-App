package io.github.yuriyurin.fit3companion.protocol

/** Agent 30 announces files belonging to media Agent 9 before the SPP transfer. */
object Fit3MediaFileCodec {
    fun fileInfoRequest(name: String, size: Int): ByteArray {
        require(name.matches(Regex("[A-Za-z0-9._-]+\\.(art|que|app)")))
        require(size in 1..512_000)
        val bytes = name.toByteArray(Charsets.US_ASCII)
        return SaMessageCodec.fixedRequest(1,
            SaMessageCodec.byteParam(3, 9),
            byteArrayOf(4, bytes.size.toByte()) + bytes,
            SaMessageCodec.intParam(5, size))
    }

    /** A rejected Agent-30 file-info response must never open the SPP writer. */
    fun acceptedFileInfo(response: ByteArray): Boolean? {
        val header = SaMessageCodec.parseHeader(response) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 1) return null
        return response.size >= 3 && (response[1].toInt() and 0xff) == 1 &&
            (response[2].toInt() and 0xff) == 1
    }

    /** FTCompanion opens RFCOMM only after a successful MGR_BT_OPEN response. */
    fun acceptedBluetoothOpen(response: ByteArray): Boolean? {
        val header = SaMessageCodec.parseHeader(response) ?: return null
        if (header.type != SaMessageCodec.TYPE_RESPONSE || header.id != 2 ||
            response.size < 3 || response[1].toInt() != 1) return null
        return when (response[2].toInt()) { 1 -> true; 0 -> false; else -> null }
    }
}
