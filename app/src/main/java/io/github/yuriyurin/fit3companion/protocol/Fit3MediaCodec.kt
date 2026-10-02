package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Fit3 music/media Agent 9.
 *
 * Request/response IDs and parameter IDs are recovered from Samsung's
 * SAMusicProviderImpl / SMediaMessageContracts.
 */
object Fit3MediaCodec {
    // Product limit; the queue announcement and file must report the same count.
    const val MAX_QUEUE_ITEMS = 20
    const val REQ_MEDIA_CHANGED = 1
    const val REQ_METADATA = 2
    const val REQ_PLAYBACK_STATE = 3
    const val REQ_REMOTE_CONTROL = 4
    const val REQ_CAPABILITY = 5
    const val REQ_VOLUME_INFO = 6
    const val REQ_VOLUME_CONTROL = 7
    const val REQ_APP_INFO = 8
    const val REQ_QUEUE = 9
    const val REQ_SKIP_TO_ITEM = 16

    const val RSP_METADATA = 17
    const val RSP_PLAYBACK_STATE = 18
    const val RSP_APP = 19
    const val RSP_CAPABILITY = 20
    const val RSP_VOLUME_INFO = 21
    const val RSP_APP_INFO = 22
    const val RSP_MEDIA_CHANGED = 23
    const val RSP_APP_COUNT = 24
    const val RSP_QUEUE = 25
    const val RSP_QUEUE_INFO = 32

    const val PLAYBACK_STOPPED = 1
    const val PLAYBACK_PAUSED = 2
    const val PLAYBACK_PLAYING = 3

    const val KEY_RELEASED = 1
    const val KEY_PRESSED = 2

    enum class RemoteAction(val code: Int) {
        STOP(1), PLAY_PAUSE(2), PREVIOUS(3), NEXT(4);
        companion object { fun from(code: Int) = entries.firstOrNull { it.code == code } }
    }

    data class RemoteCommand(val action: RemoteAction, val keyState: Int) {
        val pressed: Boolean get() = keyState == KEY_PRESSED
    }

    enum class VolumeAction(val code: Int) {
        SET(1), UP(2), DOWN(3), MUTE_ON(4), MUTE_OFF(5);
        companion object { fun from(code: Int) = entries.firstOrNull { it.code == code } }
    }

    data class VolumeCommand(val action: VolumeAction, val value: Int? = null)

    data class MediaState(
        val playbackState: Int,
        val positionMs: Long,
        val durationMs: Long,
        val title: String,
        val artist: String,
        val appId: String = "",
        val activeItemId: Long = -1L,
    )

    data class QueueItem(val id: Long, val title: String, val subtitle: String)
    data class QueueData(val appId: String, val title: String, val items: List<QueueItem>)

    /** Samsung's media provider names each transfer from an MD5 of its identity and time. */
    fun mediaFileName(appId: String, first: String, second: String?, extension: String,
                      timestampMs: Long): String {
        require(extension == "art" || extension == "que")
        val seed = if (second == null) "${appId}_${first}_$timestampMs"
            else "${appId}_${first}_${second}_$timestampMs"
        val digest = MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16) + ".$extension"
    }

    fun parseRemoteControl(message: ByteArray): RemoteCommand? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_REQUEST || h.id != REQ_REMOTE_CONTROL) return null
        var action: RemoteAction? = null
        var keyState = KEY_PRESSED
        var i = 1
        while (i + 1 < message.size) {
            val id = message[i].toInt() and 0xff
            val value = message[i + 1].toInt() and 0xff
            when (id) {
                1 -> action = RemoteAction.from(value)
                2 -> keyState = value
            }
            i += 2
        }
        return action?.let { RemoteCommand(it, keyState) }
    }

    fun parseVolumeControl(message: ByteArray): VolumeCommand? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_REQUEST || h.id != REQ_VOLUME_CONTROL) return null
        if (message.size < 3 || (message[1].toInt() and 0xff) != 1) return null
        val action = VolumeAction.from(message[2].toInt() and 0xff) ?: return null
        var value: Int? = null
        // The first parameter is one byte; the watch sends the optional value as LE int.
        var i = 3
        while (i + 1 < message.size) {
            if ((message[i].toInt() and 0xff) == 2) {
                value = SaMessageCodec.readLeInt(message, i + 1)
                    ?: (message[i + 1].toInt() and 0xff)
                break
            }
            i += 2
        }
        return VolumeCommand(action, value)
    }

    fun requestAppId(message: ByteArray, id: Int): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_REQUEST || h.id != id) return null
        val offset = if (h.format == SaMessageCodec.FORMAT_VARIABLE) 2 else 1
        if (message.size < offset + 2 || (message[offset].toInt() and 0xff) != 1) return null
        val size = message[offset + 1].toInt() and 0xff
        if (offset + 2 + size > message.size) return null
        return String(message, offset + 2, size, Charsets.UTF_8)
    }

    fun skipToItemRequest(message: ByteArray): Pair<String, Long>? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_REQUEST || h.id != REQ_SKIP_TO_ITEM) return null
        var offset = if (h.format == SaMessageCodec.FORMAT_VARIABLE) 2 else 1
        val values = ArrayList<String>(2)
        for (paramId in 1..2) {
            if (offset + 2 > message.size || (message[offset].toInt() and 0xff) != paramId) return null
            val size = message[offset + 1].toInt() and 0xff
            offset += 2
            if (offset + size > message.size) return null
            values += String(message, offset, size, Charsets.UTF_8)
            offset += size
        }
        return values[0] to (values[1].toLongOrNull() ?: return null)
    }

    fun requestId(message: ByteArray): Int? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        return h.id.takeIf { h.type == SaMessageCodec.TYPE_REQUEST }
    }

    fun playbackStateResponse(state: MediaState): ByteArray = variableResponse(
        RSP_PLAYBACK_STATE,
        SaMessageCodec.byteParam(1, state.playbackState.coerceIn(PLAYBACK_STOPPED, PLAYBACK_PLAYING)),
        SaMessageCodec.longParam(2, state.positionMs.coerceAtLeast(0L)),
        SaMessageCodec.longParam(4, state.activeItemId),
        safeStringParam(3, state.appId),
    )

    fun volumeInfoResponse(volume: Int, connected: Boolean = true): ByteArray =
        fixedMediaResponse(
            RSP_VOLUME_INFO,
            SaMessageCodec.intParam(1, volume.coerceAtLeast(0)),
            SaMessageCodec.byteParam(2, if (connected) 2 else 1),
        )

    fun capabilityResponse(maxVolume: Int, warningVolume: Int): ByteArray =
        fixedMediaResponse(
            RSP_CAPABILITY,
            SaMessageCodec.intParam(1, maxVolume.coerceAtLeast(1)),
            SaMessageCodec.intParam(2, warningVolume.coerceIn(0, maxVolume.coerceAtLeast(1))),
        )

    fun mediaChangedResponse(wantedState: Int = 1): ByteArray =
        fixedMediaResponse(RSP_MEDIA_CHANGED, SaMessageCodec.byteParam(1, wantedState.coerceIn(0, 2)))

    /** Stock Agent 9 responses explicitly set NumberOfParam even with format=0.
     * Do not change the shared fixed codec: incoming music commands and other services
     * use different schemas and do not necessarily carry this count byte.
     */
    private fun fixedMediaResponse(id: Int, vararg params: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            require(params.size in 1..255)
            write(SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_RESPONSE, id).toInt())
            write(params.size)
            params.forEach { write(it) }
        }.toByteArray()

    /** The watch sends 0=closed, 1=visible, 2=AOD; Samsung echoes this state. */
    fun mediaChangedRequestState(message: ByteArray): Int? {
        if (requestId(message) != REQ_MEDIA_CHANGED || message.size < 3 ||
            (message[1].toInt() and 0xff) != 1) return null
        return (message[2].toInt() and 0xff).takeIf { it in 0..2 }
    }

    /** Advertise player availability before metadata; otherwise the watch can ignore the track. */
    fun appCountChangedResponse(appId: String): ByteArray = variableResponse(
        RSP_APP_COUNT,
        SaMessageCodec.byteParam(1, if (appId.isNotEmpty()) 1 else 0),
        safeStringParam(2, appId),
    )

    /** Samsung announces the artwork filename here and transfers the PNG via Agent 30/SPP. */
    fun metadataResponse(state: MediaState, artworkName: String = ""): ByteArray = variableResponse(
        RSP_METADATA,
        SaMessageCodec.longParam(1, state.durationMs.coerceAtLeast(0L)),
        safeStringParam(2, state.title, 100),
        safeStringParam(3, state.artist, 100),
        safeStringParam(4, artworkName),
        safeStringParam(5, state.appId),
    )

    fun queueInfoResponse(queue: QueueData): ByteArray = variableResponse(
        RSP_QUEUE_INFO,
        safeStringParam(1, queue.appId),
        safeStringParam(2, queue.title, 100),
        SaMessageCodec.intParam(3, queue.items.size.coerceAtMost(MAX_QUEUE_ITEMS)),
    )

    fun appInfoResponse(appId: String, title: String, iconFileName: String = ""): ByteArray = variableResponse(
        RSP_APP_INFO,
        safeStringParam(1, appId),
        safeStringParam(2, title, 100),
        safeStringParam(3, iconFileName),
    )

    fun queueResponse(queue: QueueData, fileName: String): ByteArray = variableResponse(
        RSP_QUEUE,
        safeStringParam(1, queue.appId),
        safeStringParam(2, queue.title, 100),
        SaMessageCodec.intParam(3, queue.items.size.coerceAtMost(MAX_QUEUE_ITEMS)),
        safeStringParam(4, fileName),
    )

    /** Samsung stores the complete SAMessage as Base64 text in the .que file (NO_WRAP). */
    fun queueFile(queue: QueueData, fileName: String): ByteArray {
        val items = queue.items.take(MAX_QUEUE_ITEMS)
        val message = ByteArrayOutputStream().apply {
            write(SaMessageCodec.header(SaMessageCodec.FORMAT_VARIABLE, SaMessageCodec.TYPE_RESPONSE, RSP_QUEUE).toInt())
            write(items.size + 4)
            write(safeStringParam(1, queue.appId))
            write(safeStringParam(2, queue.title, 100))
            write(SaMessageCodec.intParam(3, items.size))
            write(safeStringParam(4, fileName))
            items.forEachIndexed { index, item ->
                write(index + 5)
                write(3)
                listOf(item.id.toString(), item.title, item.subtitle).forEach { field ->
                    val bytes = safeBytes(field, 100)
                    write(bytes.size)
                    write(bytes)
                }
            }
        }.toByteArray()
        return Base64.getEncoder().encode(message)
    }

    private fun variableResponse(id: Int, vararg params: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(SaMessageCodec.header(SaMessageCodec.FORMAT_VARIABLE, SaMessageCodec.TYPE_RESPONSE, id).toInt())
            write(params.size)
            params.forEach { write(it) }
        }.toByteArray()

    private fun safeStringParam(id: Int, value: String, maxBytes: Int = 255): ByteArray {
        val bytes = safeBytes(value, maxBytes)
        return byteArrayOf(id.toByte(), bytes.size.toByte()) + bytes
    }

    private fun safeBytes(value: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        value.codePoints().forEach { codePoint ->
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size <= maxBytes) out.write(bytes)
        }
        return out.toByteArray()
    }

    fun describe(message: ByteArray): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        return when (h.id) {
            REQ_MEDIA_CHANGED -> "[MEDIA_CHANGED]"
            REQ_METADATA -> "[METADATA]"
            REQ_PLAYBACK_STATE -> "[PLAYBACK_STATE]"
            REQ_REMOTE_CONTROL -> parseRemoteControl(message)?.let {
                "[REMOTE_CONTROL ${it.action.name} ${if (it.pressed) "PRESSED" else "RELEASED"}]"
            } ?: "[REMOTE_CONTROL]"
            REQ_CAPABILITY -> "[CAPABILITY]"
            REQ_VOLUME_INFO -> "[VOLUME_INFO]"
            REQ_VOLUME_CONTROL -> parseVolumeControl(message)?.let {
                "[VOLUME_CONTROL ${it.action.name}${it.value?.let { v -> " value=$v" } ?: ""}]"
            } ?: "[VOLUME_CONTROL]"
            REQ_APP_INFO -> "[APP_INFO]"
            REQ_QUEUE -> "[QUEUE]"
            REQ_SKIP_TO_ITEM -> "[SKIP_TO_ITEM]"
            RSP_METADATA -> "[METADATA_RESPONSE]"
            RSP_PLAYBACK_STATE -> "[PLAYBACK_STATE_RESPONSE]"
            RSP_CAPABILITY -> "[CAPABILITY_RESPONSE]"
            RSP_VOLUME_INFO -> "[VOLUME_INFO_RESPONSE]"
            RSP_MEDIA_CHANGED -> "[MEDIA_CHANGED_RESPONSE]"
            RSP_QUEUE -> "[QUEUE_RESPONSE]"
            RSP_QUEUE_INFO -> "[QUEUE_INFO]"
            RSP_APP_COUNT -> "[APP_COUNT_CHANGED]"
            else -> "[MEDIA id=${h.id} type=${h.type} fmt=${h.format}]"
        }
    }
}
