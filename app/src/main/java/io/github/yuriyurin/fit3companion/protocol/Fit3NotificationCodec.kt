package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Notification Agent 7 packet subset recovered from the official Fit3 plugin. */
object Fit3NotificationCodec {
    const val NEW_WITH_POPUP = 0
    const val DELETE_FROM_MOBILE = 1
    const val DELETE_FROM_BAND = 2
    const val SHOW_ON_DEVICE = 4
    const val NEW_WITH_NO_POPUP = 5
    const val CLEAR_ALL_FROM_MOBILE = 8
    const val CLEAR_ALL_FROM_BAND = 9
    const val NOTIFICATION_ACTION = 10
    const val SYNC_DATA_AFTER_CONNECT = 14
    const val SYNC_APP_ICON = 19
    const val APP_ICON_REQUEST = 3

    sealed class BandCommand {
        data class Delete(val sequence: Int) : BandCommand()
        data class Reply(val sequence: Int, val text: String) : BandCommand()
        data class ShowOnPhone(val sequence: Int) : BandCommand()
        data object ClearAll : BandCommand()
    }

    /** Samsung's predefined Telegram ID and fallback ID rule for third-party apps. */
    fun appIdForPackage(packageName: String): Int = when (packageName) {
        // Telegram is present in Samsung's predefined map. Unknown apps use a stable private range.
        "org.telegram.messenger", "org.telegram.messenger.web" -> 226
        else -> Math.floorMod(packageName.hashCode(), 32517) + 33017
    }

    private fun utf8Prefix(value: String, maxBytes: Int): ByteArray {
        val text = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val character = String(Character.toChars(point))
            val encoded = character.toByteArray(Charsets.UTF_8)
            if (used + encoded.size > maxBytes) break
            text.append(character)
            used += encoded.size
            index += Character.charCount(point)
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    private fun ByteArrayOutputStream.fixed(id: Int, value: ByteArray) {
        write(id)
        write(value)
    }

    private fun ByteArrayOutputStream.shortText(id: Int, value: String) {
        val bytes = utf8Prefix(value, 60)
        write(id)
        write(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.body(value: String) {
        val bytes = utf8Prefix(value, 100)
        write(4)
        write(bytes.size and 0xff)
        write(bytes.size ushr 8)
        write(bytes)
    }

    private fun ByteArrayOutputStream.binary(id: Int, value: ByteArray) {
        require(value.size <= 0xffff)
        write(id)
        write(value.size and 0xff)
        write((value.size ushr 8) and 0xff)
        write((value.size ushr 16) and 0xff)
        write((value.size ushr 24) and 0xff)
        write(value)
    }

    fun newNotification(
        sequence: Int,
        title: String,
        text: String,
        appName: String,
        packageName: String,
        whenMillis: Long,
        appId: Int = appIdForPackage(packageName),
        popup: Boolean = true,
        category: String? = null,
        canReply: Boolean = false,
        iconUrl: String? = null,
        iconBinary: ByteArray? = null,
        iconNewlyAdded: Boolean = false,
    ): ByteArray {
        require(sequence > 0)
        val result = ByteArrayOutputStream()
        val command = if (popup) NEW_WITH_POPUP else NEW_WITH_NO_POPUP
        val extraCount = (if (!category.isNullOrBlank()) 1 else 0) +
            (if (canReply) 1 else 0) + (if (iconUrl != null) 1 else 0) +
            (if (iconUrl != null && iconNewlyAdded) 1 else 0) +
            (if (iconBinary != null) 1 else 0)
        result.write(0x80 or command)
        result.write(10 + extraCount)
        result.fixed(0, byteArrayOf(1)) // normal notification
        result.fixed(1, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).array())
        result.shortText(6, appId.toString())
        result.fixed(2, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(whenMillis).array())
        result.shortText(3, title)
        result.body(text)
        result.shortText(14, appName)
        result.fixed(15, byteArrayOf(1))
        result.fixed(16, byteArrayOf(0))
        if (!category.isNullOrBlank()) result.shortText(12, category)
        if (canReply) result.fixed(13, byteArrayOf(1))
        if (iconBinary != null) result.binary(7, iconBinary)
        if (iconUrl != null) result.shortText(9, iconUrl)
        if (iconUrl != null && iconNewlyAdded) result.fixed(19, byteArrayOf(1))
        result.shortText(18, packageName)
        return result.toByteArray()
    }

    data class IconCapability(val format: Int, val sizes: List<Int>)

    /** Stock fixed constructor does not serialize a parameter count for this request. */
    fun iconCapabilityRequest(): ByteArray = byteArrayOf(0x0d)

    /** Watch's capability packet (id 13): image type 5 and supported square sizes 6. */
    fun parseIconCapability(message: ByteArray): IconCapability? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.id != 13 || message.size < 2) return null
        var i = 2
        var format = 0
        var sizes = emptyList<Int>()
        repeat(message[1].toInt() and 0xff) {
            if (i + 1 >= message.size) return@repeat
            val id = message[i++].toInt() and 0xff
            val value = message[i++].toInt() and 0xff
            when (id) {
                5 -> format = value
                6 -> {
                    val end = (i + value).coerceAtMost(message.size)
                    sizes = (i until end).map { message[it].toInt() and 0xff }
                    i = end
                }
            }
        }
        return IconCapability(format, sizes)
    }

    /** The watch requests an APP_ICON_<id> URL with command 3, parameter 9 and size 35. */
    fun parseIconRequest(message: ByteArray): Pair<String, Int>? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.id != APP_ICON_REQUEST || h.type != SaMessageCodec.TYPE_REQUEST) return null
        var i = 1
        var url: String? = null
        var size = 35
        repeat(2) {
            if (i >= message.size) return@repeat
            when (message[i++].toInt() and 0xff) {
                9 -> if (i < message.size) {
                    val len = message[i++].toInt() and 0xff
                    if (i + len <= message.size) {
                        url = message.copyOfRange(i, i + len).toString(Charsets.UTF_8)
                        i += len
                    }
                }
                35 -> if (i < message.size) size = message[i++].toInt() and 0xff
            }
        }
        // Fit3 advertises 52 and 112. Do not silently resize its 112px request to 64px.
        return url?.takeIf { it.startsWith("APP_ICON_") && size in 16..112 }?.let { it to size }
    }

    /** Samsung's makeAdditionInfoResponse: response id 3 with URL, raw image and requested size. */
    fun iconResponse(url: String, rawImage: ByteArray, size: Int): ByteArray {
        require(rawImage.size <= 0xffff)
        return ByteArrayOutputStream().apply {
            write(0x40 or APP_ICON_REQUEST)
            shortText(9, url)
            binary(7, rawImage)
            write(SaMessageCodec.intParam(35, size))
        }.toByteArray()
    }

    /** Official makeNotiDeletedFromMobileComponent: fixed request message 1, param 1=sequence(int32 LE). */
    fun deleteFromMobile(sequence: Int): ByteArray =
        SaMessageCodec.fixedRequest(DELETE_FROM_MOBILE, SaMessageCodec.intParam(1, sequence))

    fun clearAllFromMobile(): ByteArray = SaMessageCodec.fixedRequest(CLEAR_ALL_FROM_MOBILE)

    fun parseAck(message: ByteArray): Pair<Int, Boolean>? {
        if (message.size != 8 || (message[0].toInt() and 0xff) != 0x40 ||
            (message[1].toInt() and 0xff) != 1 ||
            (message[6].toInt() and 0xff) != 11) return null
        val sequence = ByteBuffer.wrap(message, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return sequence to (message[7].toInt() == 1)
    }

    /** Parse the watch-originated subset needed for delete/clear/show/reply. */
    fun parseBandCommand(message: ByteArray): BandCommand? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_REQUEST) return null
        fun findSequence(): Int? {
            for (i in 1..message.size - 5) {
                if ((message[i].toInt() and 0xff) == 1) {
                    SaMessageCodec.readLeInt(message, i + 1)?.let { if (it > 0) return it }
                }
            }
            return null
        }
        return when (h.id) {
            DELETE_FROM_BAND -> findSequence()?.let(BandCommand::Delete)
            CLEAR_ALL_FROM_BAND -> BandCommand.ClearAll
            SHOW_ON_DEVICE -> findSequence()?.let(BandCommand::ShowOnPhone)
            NOTIFICATION_ACTION -> {
                val sequence = findSequence() ?: return null
                // Reply text is parameter 10. In modern notification packets it is length-prefixed.
                var reply: String? = null
                var i = if (h.format == SaMessageCodec.FORMAT_VARIABLE && message.size > 1) 2 else 1
                while (i + 1 < message.size) {
                    val id = message[i].toInt() and 0xff
                    if (id == 10) {
                        val one = message[i + 1].toInt() and 0xff
                        if (i + 2 + one <= message.size) {
                            reply = message.copyOfRange(i + 2, i + 2 + one).toString(Charsets.UTF_8)
                            break
                        }
                    }
                    i++
                }
                reply?.takeIf { it.isNotBlank() }?.let { BandCommand.Reply(sequence, it) }
            }
            else -> null
        }
    }
    fun describe(message: ByteArray): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        return when (h.id) {
            NEW_WITH_POPUP -> "[NEW_NOTIFICATION popup]"
            NEW_WITH_NO_POPUP -> "[NEW_NOTIFICATION silent]"
            DELETE_FROM_MOBILE -> "[DELETE_FROM_MOBILE]"
            DELETE_FROM_BAND -> "[DELETE_FROM_BAND]"
            NOTIFICATION_ACTION -> "[NOTIFICATION_ACTION]"
            SYNC_DATA_AFTER_CONNECT -> "[SYNC_AFTER_CONNECT]"
            SYNC_APP_ICON -> "[APP_ICON_SYNC bytes=${message.size}]"
            APP_ICON_REQUEST -> "[APP_ICON_REQUEST bytes=${message.size}]"
            CLEAR_ALL_FROM_MOBILE -> "[CLEAR_ALL_FROM_MOBILE]"
            CLEAR_ALL_FROM_BAND -> "[CLEAR_ALL_FROM_BAND]"
            else -> "[NOTI id=${h.id} type=${h.type} fmt=${h.format}]"
        }
    }

}
