package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream

data class Fit3OrderedItem(val id: Int, val order: Int)

private fun parseOrdered(
    message: ByteArray,
    responseId: Int,
    totalParam: Int,
    itemParam: Int,
    orderParam: Int,
): List<Fit3OrderedItem>? {
    val h = SaMessageCodec.parseHeader(message) ?: return null
    // Fit3 is inconsistent here: Apps answers with a RESPONSE/variable header (0xC0),
    // while Tiles on AZA3 answers with a REQUEST/fixed header (0x00). The official
    // parsers key on message id, not on this bit, so accept both directions.
    if (h.id != responseId) return null
    var expected: Int? = null
    var pendingId: Int? = null
    val out = mutableListOf<Fit3OrderedItem>()
    var i = 1
    while (i + 1 < message.size) {
        val id = message[i].toInt() and 0xff
        val value = message[i + 1].toInt() and 0xff
        when (id) {
            totalParam -> expected = value
            itemParam -> pendingId = value
            orderParam -> pendingId?.let { out += Fit3OrderedItem(it, value); pendingId = null }
        }
        i += 2
    }
    if (out.isEmpty() && (expected ?: 0) > 0) return null
    val sorted = out.sortedBy { it.order }
    val limit = expected
    return if (limit != null) sorted.take(limit) else sorted
}

object Fit3WidgetsCodec {
    val request = SaMessageCodec.fixedRequest(0)
    fun set(items: List<Fit3OrderedItem>): ByteArray = ByteArrayOutputStream().apply {
        write(SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_REQUEST, 1).toInt())
        write(1); write(items.size)
        items.sortedBy { it.order }.forEach { item ->
            write(3); write(item.id)
            write(2); write(item.order)
        }
    }.toByteArray()
    fun parse(message: ByteArray) = parseOrdered(message, 0, 1, 3, 2)

    val names = mapOf(
        1 to "Будильник", 2 to "Погода", 3 to "Календарь", 4 to "Таймер",
        5 to "Мировое время", 6 to "Музыка", 7 to "Прогноз погоды", 8 to "События",
        9 to "Батарея", 20 to "Шаги", 21 to "Тренировки", 22 to "Стресс",
        23 to "Активность", 24 to "Цикл", 25 to "Пульс", 26 to "Вода",
        27 to "Еда", 28 to "Сон", 29 to "Здоровье", 30 to "Кислород", 31 to "Together",
    )
}

object Fit3QuickPanelCodec {
    val request = SaMessageCodec.fixedRequest(0)

    // Fit3 quick-setting IDs. The mapping matches Samsung's 12-item Fit3 Quick Panel
    // and the ID order returned by AZA3. Keep the numeric ID in the UI as a fallback.
    val names = mapOf(
        1 to "Always On Display",
        2 to "Яркость",
        3 to "Не беспокоить",
        4 to "Найти телефон",
        5 to "Звук / вибрация",
        6 to "Блокировка воды",
        10 to "Режим сна",
        13 to "Питание",
        14 to "Настройки",
        15 to "Фонарик",
        16 to "Режим Театр",
        17 to "Авиарежим",
    )
    fun set(items: List<Fit3OrderedItem>): ByteArray = ByteArrayOutputStream().apply {
        write(SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_REQUEST, 1).toInt())
        write(1); write(items.size)
        items.sortedBy { it.order }.forEach { item ->
            write(3); write(item.id)
            write(2); write(item.order)
        }
    }.toByteArray()
    fun parse(message: ByteArray) = parseOrdered(message, 0, 1, 3, 2)
}

object Fit3AppsCodec {
    val request = SaMessageCodec.fixedRequest(0)

    /** Reorderable Fit3 app IDs. IDs 2..5 are confirmed from the user's AZA3 order + watch UI.
     * The remaining names follow Samsung's stock Fit3 app set; numeric ID is kept in UI for diagnostics. */
    val names = mapOf(
        1 to "Таймер",
        2 to "Контроллер мультимедиа",
        3 to "Найти мой телефон",
        4 to "Погода",
        5 to "Календарь",
        6 to "Камера",
        7 to "Будильник",
        8 to "Секундомер",
        9 to "Мировое время",
        12 to "Калькулятор",
    )
    fun set(appIds: List<Int>): ByteArray = ByteArrayOutputStream().apply {
        write(SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_REQUEST, 1).toInt())
        // Official constructAppsPacketSetInfo: app version=1, total apps, then app id/order pairs.
        write(2); write(1)
        write(3); write(appIds.size)
        appIds.forEachIndexed { index, id ->
            // AppsPacketParser constants: APP_ID=0, APP_ORDER=1. The watch uses
            // one-based order values in its response, so mirror that on SET.
            write(0); write(id)
            write(1); write(index + 1)
        }
    }.toByteArray()
    fun parse(message: ByteArray) = parseOrdered(message, 0, 3, 0, 1)
}

object Fit3QuickMessagesCodec {
    /** Official constructQuickMessagePacketSetInfo: msg 0, param 0=count, repeated param 1=str. */
    fun set(messages: List<String>): ByteArray = ByteArrayOutputStream().apply {
        write(SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_REQUEST, 0).toInt())
        write(0); write(messages.size.coerceAtMost(20))
        messages.take(20).forEach { text -> write(SaMessageCodec.stringParam(1, text, 120)) }
    }.toByteArray()

    fun setEnabled(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(2, SaMessageCodec.boolParam(3, enabled))

    fun parse(message: ByteArray): List<String>? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_RESPONSE || h.id != 0) return null
        var i = 1
        var count: Int? = null
        val out = mutableListOf<String>()
        while (i < message.size) {
            when (message[i++].toInt() and 0xff) {
                0 -> if (i < message.size) count = message[i++].toInt() and 0xff else return null
                1 -> {
                    if (i >= message.size) return null
                    val len = message[i++].toInt() and 0xff
                    if (i + len > message.size) return null
                    out += message.copyOfRange(i, i + len).toString(Charsets.UTF_8)
                    i += len
                }
                else -> { /* unknown field: stop rather than mis-align */ return out.takeIf { it.isNotEmpty() } }
            }
        }
        val limit = count
        return if (limit == null) out else out.take(limit)
    }
}
