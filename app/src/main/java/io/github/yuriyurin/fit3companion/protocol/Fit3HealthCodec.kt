package io.github.yuriyurin.fit3companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

/**
 * Fit3 Health transport (SAP service 10).
 *
 * Important: tracker feature ids (sleep=12, HR=26, floor=27, pedometer=28, stress=29)
 * are NOT SAP message ids.  The wire protocol uses the Moden command ids below.  Older
 * Companion builds sent the tracker ids as messages, so the watch only kept asking the phone
 * for Health capability and never started a real data sync.
 *
 * The command ids/ack headers and Health field ids here are recovered from Samsung's Fit3
 * plugin 2.2.14.26071041N.  Unknown record layouts are deliberately kept in Protocol Monitor;
 * we only publish a metric when both the enclosing data type and a plausible known field exist.
 */
object Fit3HealthCodec {
    // Outer Moden commands (Constants.ModenConstant / AcknowledgementReqRes).
    const val CMD_CHECK_STATUS = 1
    const val CMD_SYNC_DATA = 2
    const val CMD_REQUEST_DATA = 3
    const val CMD_WEARABLE_MSG = 4
    const val CMD_NETWORK_IMAGE_DOWNLOAD = 6
    const val CMD_PEDOMETER_BACK_SYNC = 7 // 0x87 request / 0xC7 response
    const val CMD_CYCLE_PREDICTION_WMSG = 8
    const val CMD_SOUND_RELAY_WMSG = 9
    const val CMD_WEARABLE_REQUEST_CAPABILITY = 32
    const val CMD_MOBILE_REQUEST_CAPABILITY = 33
    const val CMD_GM_REQUEST_CAPABILITY = 34
    const val CMD_GM_CAPABILITY_NOTI = 35
    const val CMD_PLUGIN_HEALTH_CAPABILITY_REQUEST = 52 // literal 0x34 from HealthDataManager

    // Health data type ids (ModenParameterConstants.FeatureDataType).
    const val DATA_PEDO_STEP_COUNT = 7
    const val DATA_HEART_RATE = 10
    const val DATA_SLEEP = 16
    const val DATA_SLEEP_STAGE = 17
    const val DATA_STRESS = 19
    const val DATA_CALORIES_BURNED = 23
    const val DATA_OXYGEN_SATURATION = 112
    const val DATA_ACTIVITY_DAY_SUMMARY = 126
    const val DATA_FLOORS_CLIMBED = 141
    const val DATA_PEDOMETER_DAY_SUMMARY = 147

    // GM capability parameter ids.
    private const val GM_STATUS = 0xD0
    private const val GM_RESULT = 0xD1
    private const val GM_HOST_SHEALTH_VERSION = 0xD2
    private const val GM_HOST_SHEALTH_TARGET_VERSION = 0xD3
    private const val GM_SEQUENCE_NUMBER = 0xE0
    private const val GM_HEALTH_PROTOCOL_VERSION = 0xE1
    private const val GM_SHEALTH_INSTALLED = 2

    // S Health version code announced in the GM capability reply (D2). 7.0.6.011 is what a
    // vendor phone sent (3B E7 6A 00). The Companion doesn't load or call Samsung Health itself.
    private const val EMULATED_SHEALTH_VERSION = 7_006_011

    // PedometerDaySummary fields.
    private const val PEDO_STEP_COUNT = 18
    private const val PEDO_WALK_STEP_COUNT = 19
    private const val PEDO_RUN_STEP_COUNT = 20
    private const val PEDO_CALORIE = 22
    private const val PEDO_DISTANCE = 23
    private const val PEDO_ACTIVE_TIME = 25

    // ActivityDaySummary fields.
    private const val ACT_ACTIVE_TIME = 45
    private const val ACT_STEP_COUNT = 49
    private const val ACT_CALORIE = 50
    private const val ACT_DISTANCE = 51
    private const val ACT_FLOOR_COUNT = 78

    private const val FLOOR_VALUE = 16
    private const val HR_VALUE = 32
    private const val HR_MIN = 33
    private const val HR_MAX = 34
    private const val STRESS_VALUE = 96
    private const val STRESS_MIN = 102
    private const val STRESS_MAX = 103
    private const val SPO2_VALUE = 169

    /** Official generator emits a field count and E0 sequence, even for an empty CHECK_STATUS. */
    fun checkStatusRequest(sequence: Int): ByteArray {
        require(sequence in 1..0xffff)
        return byteArrayOf(
            SaMessageCodec.header(SaMessageCodec.FORMAT_FIXED, SaMessageCodec.TYPE_REQUEST, CMD_CHECK_STATUS),
            0x01, 0xE0.toByte(), sequence.toByte(), (sequence ushr 8).toByte(),
        )
    }

    /** The official plugin sends this one-byte request when registering a new Fit3 host. */
    val mobileCapabilityRequest: ByteArray = SaMessageCodec.fixedRequest(CMD_PLUGIN_HEALTH_CAPABILITY_REQUEST)

    /**
     * DEVICE_INFO record (marker 05, 25-byte body) as the phone sends it: tag 4
     * last_sync_time is the phone's sync watermark (1990-epoch seconds), start/end are
     * zero. DeviceInfo's version minor is decimal (createDeviceInfoByte), unlike CAPA's BCD.
     */
    fun deviceInfo(lastSyncTime: Long): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(5, 25, 1, 0, 2, 0, 0, 3, 4, 51))
        write(SaMessageCodec.intParam(4, lastSyncTime.coerceIn(0, 0xffff_ffffL).toInt()))
        write(SaMessageCodec.intParam(5, 0))
        write(SaMessageCodec.intParam(6, 0))
        write(byteArrayOf(7, 1))
    }.toByteArray()

    /** Stock REQUEST_DATA has no count byte. The band only sends records newer than
     * [lastSyncTime]; 0 asks for everything it still holds. Never claim more than was
     * actually consumed, or the band withholds the difference. */
    fun requestData(sequence: Int, lastSyncTime: Long): ByteArray {
        require(sequence in 1..0xffff)
        return byteArrayOf(3) + SaMessageCodec.shortParam(0xE0, sequence) + deviceInfo(lastSyncTime)
    }

    /**
     * The band's own clock from the DEVICE_INFO record that ends its pushes (tag 6,
     * 1990-epoch seconds), or null. Acking with it as the watermark tells the band
     * that everything up to then has been consumed.
     */
    fun trailingBandTime(payload: ByteArray): Long? {
        if (payload.size < 27) return null
        val at = payload.size - 27
        val tags = intArrayOf(1, 2, 3, 4, 5, 6, 7)
        val offsets = intArrayOf(2, 4, 7, 10, 15, 20, 25)
        if ((payload[at].toInt() and 0xff) != 5 || (payload[at + 1].toInt() and 0xff) != 25 ||
            tags.indices.any { (payload[at + offsets[it]].toInt() and 0xff) != tags[it] }) return null
        return (SaMessageCodec.readLeInt(payload, at + 21) ?: return null).toLong() and 0xffff_ffffL
    }

    fun sequence(message: ByteArray): Int? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        val offset = if (h.id == CMD_PEDOMETER_BACK_SYNC) 1 else
            if (h.id == CMD_SYNC_DATA || h.format == SaMessageCodec.FORMAT_VARIABLE) 2 else 1
        if (message.getOrNull(offset)?.toInt()?.and(0xff) != 0xE0) return null
        return SaMessageCodec.readLeShort(message, offset + 1)
    }

    fun isRequestDataSuccess(message: ByteArray, expectedSequence: Int): Boolean =
        message.size >= 8 && message[0].toInt() == 0x43 && sequence(message) == expectedSequence &&
            message.copyOfRange(4, 8).contentEquals(byteArrayOf(4, 2, 1, 1))

    enum class Event {
        CHECK_STATUS,
        GM_CAPABILITY_REQUEST,
        WEARABLE_CAPABILITY_REQUEST,
        CAPABILITY,
        SYNC_DATA,
        PEDOMETER_BACK_SYNC,
        REQUEST_DATA,
        WEARABLE_MESSAGE,
        OTHER,
    }

    data class State(
        val steps: Int? = null,
        val walkSteps: Int? = null,
        val runSteps: Int? = null,
        val stepGoal: Int? = null,
        val distanceMeters: Double? = null,
        val activeCalories: Double? = null,
        val activeMinutes: Int? = null,
        val floors: Double? = null,
        val heartRate: Int? = null,
        val heartRateMin: Int? = null,
        val heartRateMax: Int? = null,
        val heartRateAt: Long? = null,
        val stress: Int? = null,
        val stressMin: Int? = null,
        val stressMax: Int? = null,
        val stressAt: Long? = null,
        val spo2: Int? = null,
        val spo2At: Long? = null,
        val sleepMinutes: Int? = null,
        val sleepScore: Int? = null,
        val sleepEndAt: Long? = null,
        val sleepEpisodes: List<Fit3Sleep.Episode> = emptyList(),
        val sleepStages: List<Fit3Sleep.Stage> = emptyList(),
        val stepHistory: List<Int> = emptyList(),
        val stepRecords: Map<Long, StepRecord> = emptyMap(),
        val stepSourceAddress: String? = null,
        val stepsPartial: Boolean = false,
        val stepDay: Long? = null,
        val sessionReady: Boolean = false,
        val healthProtocolVersion: Int? = null,
        val lastDataTypes: Set<Int> = emptySet(),
        val lastTransportMillis: Long? = null,
        val lastSyncMillis: Long? = null,
        val lastSuccessfulSyncMillis: Long? = null,
        /** Sync watermark (1990-epoch seconds) sent in every DEVICE_INFO; not persisted. */
        val syncWatermark: Long = 0,
    ) {
        val hasActivityData: Boolean get() = steps != null || distanceMeters != null ||
            activeCalories != null || floors != null
    }

    data class StepRecord(
        val count: Int,
        val walk: Int,
        val run: Int,
        val distanceMeters: Double,
        val calories: Double,
        val durationMillis: Int,
    )

    data class Incoming(
        val state: State,
        val reply: ByteArray? = null,
        val status: String,
        val event: Event = Event.OTHER,
        val parsedDataTypes: Set<Int> = emptySet(),
    )

    private data class GmRequest(val sequence: Int, val protocolVersion: Int)

    fun handle(previous: State, message: ByteArray, now: Long = System.currentTimeMillis()): Incoming {
        val h = SaMessageCodec.parseHeader(message)
            ?: return Incoming(previous, status = "Некорректный Health-пакет")
        val request = h.type == SaMessageCodec.TYPE_REQUEST
        return when (h.id) {
            CMD_GM_REQUEST_CAPABILITY -> {
                val gm = parseGmRequest(message)
                if (request && gm != null) {
                    val state = previous.copy(
                        sessionReady = true,
                        healthProtocolVersion = gm.protocolVersion,
                        lastTransportMillis = now,
                    )
                    Incoming(
                        state = state,
                        reply = gmCapabilityResponse(gm),
                        status = "Health handshake: Fit3 protocol ${gm.protocolVersion}, standalone host подтверждён",
                        event = Event.GM_CAPABILITY_REQUEST,
                    )
                } else Incoming(
                    previous.copy(lastTransportMillis = now),
                    status = "Получен ответ Health capability",
                    event = Event.CAPABILITY,
                )
            }
            CMD_WEARABLE_REQUEST_CAPABILITY -> {
                val sequence = parseCapabilitySequence(message)
                if (request && sequence != null) {
                    Incoming(
                        state = previous.copy(
                            sessionReady = true,
                            lastTransportMillis = now,
                        ),
                        reply = wearableCapabilityResponse(sequence),
                        status = "Fit3 запросил Samsung Health capability: ответ 4.51 отправлен; ждём SYNC_DATA",
                        event = Event.WEARABLE_CAPABILITY_REQUEST,
                    )
                } else Incoming(
                    previous.copy(lastTransportMillis = now),
                    status = "Получен Health wearable capability response",
                    event = Event.CAPABILITY,
                )
            }
            CMD_GM_CAPABILITY_NOTI, CMD_MOBILE_REQUEST_CAPABILITY -> Incoming(
                previous.copy(lastTransportMillis = now),
                reply = null,
                status = "Health capability exchange получен",
                event = Event.CAPABILITY,
            )
            CMD_PLUGIN_HEALTH_CAPABILITY_REQUEST -> Incoming(
                previous.copy(lastTransportMillis = now),
                status = "Ответ на запрос возможностей Health получен",
                event = Event.CAPABILITY,
            )
            CMD_CHECK_STATUS -> Incoming(
                previous.copy(lastTransportMillis = now),
                reply = if (request) SaMessageCodec.fixedResponse(CMD_CHECK_STATUS) else null,
                status = "Канал Fit3 Health отвечает",
                event = Event.CHECK_STATUS,
            )
            CMD_SYNC_DATA -> processSync(previous, message, now, request, Event.SYNC_DATA)
            CMD_PEDOMETER_BACK_SYNC -> Incoming(previous.copy(lastTransportMillis = now),
                status = "Получен ответ обратной синхронизации шагов",
                event = Event.PEDOMETER_BACK_SYNC)
            CMD_REQUEST_DATA -> Incoming(
                previous.copy(lastTransportMillis = now),
                reply = if (request) requestDataEmptySuccessResponse(message, previous.syncWatermark) else null,
                status = "Fit3 запросил Health data у телефона",
                event = Event.REQUEST_DATA,
            )
            CMD_WEARABLE_MSG -> Incoming(
                previous.copy(lastTransportMillis = now),
                reply = if (request) wearableMessageResponse(message) else null,
                status = "Получено Health wearable-message",
                event = Event.WEARABLE_MESSAGE,
            )
            else -> Incoming(
                previous.copy(lastTransportMillis = now),
                status = "Неизвестная команда Health ${h.id}; пакет сохранён в Protocol Monitor",
            )
        }
    }

    /** Compatibility shim for old call sites/tests. */
    fun merge(previous: State, message: ByteArray, now: Long = System.currentTimeMillis()): State =
        handle(previous, message, now).state

    private fun processSync(
        previous: State,
        message: ByteArray,
        now: Long,
        request: Boolean,
        event: Event,
    ): Incoming {
        val (parsed, types) = mergeSyncPayload(previous, message, now)
        // Claim the band's own clock from this push once it is handled, never earlier.
        val merged = if (request) parsed.copy(syncWatermark = maxOf(parsed.syncWatermark,
            trailingBandTime(message) ?: parsed.syncWatermark)) else parsed
        val changedMetrics = metricsChanged(previous, merged)
        val status = when {
            changedMetrics -> "Fit3 Health: получены реальные данные ${types.joinToString { dataTypeName(it) }}"
            types.isNotEmpty() -> "Fit3 Health sync: типы ${types.joinToString { dataTypeName(it) }}; поля сохранены для разбора"
            else -> "Fit3 Health sync получен; формат записи сохранён в Protocol Monitor"
        }
        return Incoming(
            state = merged.copy(
                sessionReady = true,
                lastTransportMillis = now,
                lastDataTypes = if (types.isEmpty()) merged.lastDataTypes else types,
            ),
            reply = if (request && event == Event.SYNC_DATA) {
                // The plugin forwards this request to Samsung Health and then returns its
                // sequence-numbered SUCCESS result. A bare 0x42 does not acknowledge which
                // batch was stored, so the watch can keep replaying the old intervals.
                syncDataSuccessResponse(message, merged.syncWatermark)
            } else null,
            status = status,
            event = event,
            parsedDataTypes = types,
        )
    }

    /** Plugin generateTheSyncByte(): 42 03 E0 <seq LE> 04 02 01 SUCCESS + DEVICE_INFO.
     * The DEVICE_INFO's last_sync_time is the watermark. Without it the band never
     * records the phone's progress: it re-sends the same objects and then stops syncing.
     */
    private fun syncDataSuccessResponse(request: ByteArray, watermark: Long): ByteArray? {
        if (request.size < 5 || (request[2].toInt() and 0xff) != GM_SEQUENCE_NUMBER) return null
        val sequence = SaMessageCodec.readLeShort(request, 3) ?: return null
        return byteArrayOf(
            0x42, 0x03, GM_SEQUENCE_NUMBER.toByte(),
            sequence.toByte(), (sequence ushr 8).toByte(),
            0x04, 0x02, 0x01, 0x01,
        ) + deviceInfo(watermark)
    }

    /**
     * The plugin's generateTheSyncByte() omits the field-count byte for REQUEST_DATA, but
     * still echoes E0, includes MessageInfo SUCCESS and the DEVICE_INFO watermark
     * (43 E0 <seq> 04 02 01 01 05 19 ...). We have no Samsung Health database to
     * return, so the response has no other records.
     */
    private fun requestDataEmptySuccessResponse(request: ByteArray, watermark: Long): ByteArray? {
        if (request.size < 4 || (request[1].toInt() and 0xff) != GM_SEQUENCE_NUMBER) return null
        val sequence = SaMessageCodec.readLeShort(request, 2) ?: return null
        return byteArrayOf(
            0x43, GM_SEQUENCE_NUMBER.toByte(),
            sequence.toByte(), (sequence ushr 8).toByte(),
            0x04, 0x02, 0x01, 0x01,
        ) + deviceInfo(watermark)
    }

    /**
     * WEARABLE_MSG (0x04) reply: specific request offsets copied into a 12-byte response,
     * reconstructed from captures. The 46-byte form (a workout announcement) uses the same
     * map and must be answered before the band releases that workout.
     */
    fun wearableMessageResponse(request: ByteArray): ByteArray? {
        if (request.size < 22) return null
        return byteArrayOf(0x44) + intArrayOf(1, 2, 3, 13, 15, 16, 20, 21, 17, 18, 19)
            .map { request[it] }.toByteArray()
    }

    /** Extracts the E0 sequence number that starts Fit3 capability requests. */
    private fun parseCapabilitySequence(message: ByteArray): Int? {
        if (message.size >= 5 && (message[2].toInt() and 0xff) == GM_SEQUENCE_NUMBER) {
            return SaMessageCodec.readLeShort(message, 3)
        }
        // Resync defensively for future firmware revisions.
        for (i in 2 until message.size - 2) {
            if ((message[i].toInt() and 0xff) == GM_SEQUENCE_NUMBER) {
                return SaMessageCodec.readLeShort(message, i + 1)
            }
        }
        return null
    }

    /**
     * Reply to the band's WEARABLE_REQUEST_CAPABILITY (A0): the vendor phone's capability
     * table, replayed byte for byte as captured (2026-07-12). It is binary [tag][value]
     * data (a1 protocol 4.33, a2 S Health version, ...), not JSON, and carries no E0
     * echo. The vendor sent the identical table in independent sessions whatever the
     * band announced, so nothing in it is computed here.
     */
    private val wearableCapabilityTemplate = intArrayOf(
        0xe0, 0x0b, 0xa1, 0x04, 0x33, 0xc0, 0x01, 0xc1, 0x00, 0xc2, 0x02, 0xa2, 0x2b, 0xd0, 0x6a,
        0x00, 0xb1, 0x01, 0xae, 0x01, 0x04, 0x03, 0x0a, 0xaf, 0x07, 0x00, 0xb1, 0x01, 0xb2, 0x97,
        0x32, 0x15, 0x8e, 0x08, 0x16, 0x8e, 0x08, 0x14, 0x8e, 0x08, 0x2d, 0x8e, 0x08, 0x2e, 0x01,
        0x00, 0x2f, 0x01, 0x00, 0x31, 0x8e, 0x08, 0x32, 0x01, 0x00, 0x33, 0x01, 0x00, 0x30, 0x8e,
        0x08, 0x45, 0x07, 0x00, 0x46, 0x8e, 0x08, 0x47, 0x07, 0x00, 0x51, 0x07, 0x00, 0x52, 0x07,
        0x00, 0x3a, 0x8e, 0x08, 0x3b, 0x8e, 0x08, 0x1d, 0x8e, 0x08, 0x4c, 0x8e, 0x08, 0x4d, 0x8e,
        0x08, 0x4e, 0x8e, 0x08, 0x4f, 0x8e, 0x08, 0x50, 0x8e, 0x08, 0x3c, 0x07, 0x00, 0x3d, 0x01,
        0x00, 0x0e, 0x07, 0x00, 0x0f, 0x01, 0x00, 0x4b, 0x0e, 0x00, 0x53, 0x0e, 0x00, 0x20, 0x01,
        0x00, 0x1f, 0x01, 0x00, 0x2c, 0x01, 0x00, 0x07, 0x01, 0x00, 0x06, 0x01, 0x00, 0x37, 0x01,
        0x00, 0x09, 0x8e, 0x08, 0x39, 0x01, 0x00, 0x48, 0x23, 0x00, 0x49, 0x23, 0x00, 0x1c, 0x07,
        0x00, 0x03, 0x8e, 0x08, 0x23, 0x8e, 0x08, 0x40, 0x8e, 0x08, 0x41, 0x8e, 0x08, 0x18, 0x8e,
        0x08, 0x19, 0x8e, 0x08, 0x42, 0x8e, 0x08, 0x12, 0x01, 0x00, 0x28, 0x8e, 0x08, 0x3f, 0x10,
        0x27, 0xb3, 0x0d, 0x12, 0x06, 0xd0, 0x07, 0x01, 0x00,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun wearableCapabilityResponse(sequence: Int): ByteArray =
        ByteArray(wearableCapabilityTemplate.size) { wearableCapabilityTemplate[it].toByte() }

    private fun metricsChanged(a: State, b: State): Boolean =
        a.steps != b.steps || a.distanceMeters != b.distanceMeters || a.activeCalories != b.activeCalories ||
            a.activeMinutes != b.activeMinutes || a.floors != b.floors || a.heartRate != b.heartRate ||
            a.stress != b.stress || a.spo2 != b.spo2 || a.sleepMinutes != b.sleepMinutes ||
            a.sleepEpisodes != b.sleepEpisodes || a.sleepStages != b.sleepStages

    private fun parseGmRequest(message: ByteArray): GmRequest? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.id != CMD_GM_REQUEST_CAPABILITY || h.format != SaMessageCodec.FORMAT_VARIABLE || message.size < 2) return null
        val count = message[1].toInt() and 0xff
        var i = 2
        var seq: Int? = null
        var protocol: Int? = null
        repeat(count) {
            if (i >= message.size) return@repeat
            when (message[i++].toInt() and 0xff) {
                GM_SEQUENCE_NUMBER -> {
                    seq = SaMessageCodec.readLeShort(message, i)
                    i += 2
                }
                GM_HEALTH_PROTOCOL_VERSION -> {
                    protocol = SaMessageCodec.readLeShort(message, i)
                    i += 2
                }
                GM_STATUS, GM_RESULT -> i += 1
                GM_HOST_SHEALTH_VERSION, GM_HOST_SHEALTH_TARGET_VERSION -> i += 4
                else -> {
                    // Unknown GM request params cannot be safely sized. The Fit3 request observed on
                    // AZA3 contains only E0/E1, so stop rather than desynchronise the parser.
                    return@repeat
                }
            }
        }
        return GmRequest(seq ?: return null, protocol ?: return null)
    }

    /**
     * GM capability response as the vendor phone sends it: the request's E0 sequence and
     * E1 protocol version echoed, then D0 = 2 (S Health installed) and D2 = its version
     * code. Captured: E2 04 E0 5D 27 E1 2D 01 D0 02 D2 3B E7 6A 00.
     */
    private fun gmCapabilityResponse(req: GmRequest): ByteArray = ByteArrayOutputStream().apply {
        write(SaMessageCodec.header(SaMessageCodec.FORMAT_VARIABLE, SaMessageCodec.TYPE_RESPONSE, CMD_GM_REQUEST_CAPABILITY).toInt())
        write(4)
        write(SaMessageCodec.shortParam(GM_SEQUENCE_NUMBER, req.sequence))
        write(SaMessageCodec.shortParam(GM_HEALTH_PROTOCOL_VERSION, req.protocolVersion))
        write(SaMessageCodec.byteParam(GM_STATUS, GM_SHEALTH_INSTALLED))
        write(SaMessageCodec.intParam(GM_HOST_SHEALTH_VERSION, EMULATED_SHEALTH_VERSION))
    }.toByteArray()

    private fun mergeSyncPayload(previous: State, message: ByteArray, now: Long): Pair<State, Set<Int>> {
        var next = previous
        val types = linkedSetOf<Int>()

        // Actual AZA3 captures contain type 7 interval records before any day-summary packet.
        // Canonicalise start times to firmware minute bins. Retransmission or a reset must
        // not add a second copy of the same bin. Distinct bins survive for back-sync.
        parsePedometerIntervals(message)?.let { intervals ->
            types += DATA_PEDO_STEP_COUNT
            val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val base = if (previous.stepDay != null &&
                previous.stepDay != day.toEpochDay()) previous.copy(
                    steps = null, walkSteps = null, runSteps = null,
                    distanceMeters = null, activeCalories = null, activeMinutes = null,
                    stepHistory = emptyList(), stepRecords = emptyMap(), stepsPartial = false,
                ) else previous
            val records = Fit3PedometerBackSync.mergeRecords(base.stepRecords, intervals, now)
            next = base.copy(stepRecords = records, stepDay = day.toEpochDay())
            val values = records.values
            if (values.isNotEmpty()) {
                val partialSteps = values.sumOf { it.count }
                next = next.copy(
                    steps = partialSteps,
                    stepsPartial = true,
                    walkSteps = values.sumOf { it.walk },
                    runSteps = values.sumOf { it.run },
                    distanceMeters = values.sumOf { it.distanceMeters },
                    activeCalories = values.sumOf { it.calories },
                    // Type-7 field 22 is the official pedometer interval duration in ms.
                    // It is a lower-bound for movement time; other exercise may be absent.
                    activeMinutes = (values.sumOf { it.durationMillis.toLong() } / 60_000L)
                        .coerceIn(0L, 1_440L).toInt(),
                    stepHistory = addStepHistory(base.stepHistory, base.steps, partialSteps),
                    lastSyncMillis = now,
                )
            }
        }

        // Do not scan arbitrary payload bytes for day-summary ids. Their record boundaries and
        // dates are not decoded yet; a byte inside a type-7 or vital record can look like a
        // summary and produce a plausible but entirely false today's step count.

        if (message.size > 5 && (message[5].toInt() and 0xff) == DATA_FLOORS_CLIMBED) {
            findTypeWindow(message, DATA_FLOORS_CLIMBED)?.let { (start, end) ->
                types += DATA_FLOORS_CLIMBED
                findNumber(message, start, end, FLOOR_VALUE, 0.0..500.0)?.let {
                    next = next.copy(floors = it, lastSyncMillis = now)
                }
            }
        }

        // Unlike step intervals, these feature records carry a 4-byte length before each
        // payload. Their measurement fields are one byte; the old 4-byte window scan could
        // never decode them reliably and could mistake timestamps for feature ids.
        val sleep = Fit3Sleep.merge(next.sleepEpisodes, next.sleepStages, message, now)
        next = next.copy(sleepEpisodes = sleep.episodes, sleepStages = sleep.stages)
        if (sleep.episodes != previous.sleepEpisodes || sleep.stages != previous.sleepStages)
            next = next.copy(lastSyncMillis = now)
        if (sleep.stages != previous.sleepStages) types += DATA_SLEEP_STAGE
        Fit3VitalCodec.parse(message)?.let { samples ->
            val type = samples.first().type
            types += type
            val sample = samples.maxByOrNull { it.measuredAt ?: Long.MIN_VALUE } ?: return@let
            val at = sample.measuredAt ?: return@let
            val maxFuture = now + 5 * 60_000L
            val maxAge = if (type == DATA_SLEEP) 48 * 60 * 60_000L else 24 * 60 * 60_000L
            if (at !in (now - maxAge)..maxFuture) return@let
            when (type) {
                DATA_HEART_RATE -> {
                    val hr = sample.value?.takeIf { it in 25..250 }
                    if (hr != null && (next.heartRateAt ?: Long.MIN_VALUE) <= at) {
                        next = next.copy(
                            heartRate = hr,
                            heartRateMin = sample.min?.takeIf { it in 25..250 },
                            heartRateMax = sample.max?.takeIf { it in 25..250 },
                            heartRateAt = at,
                            lastSyncMillis = now,
                        )
                    }
                }
                DATA_STRESS -> {
                    val stress = sample.value?.takeIf { it in 0..100 }
                    if (stress != null && (next.stressAt ?: Long.MIN_VALUE) <= at) {
                        next = next.copy(
                            stress = stress,
                            stressMin = sample.min?.takeIf { it in 0..100 },
                            stressMax = sample.max?.takeIf { it in 0..100 },
                            stressAt = at,
                            lastSyncMillis = now,
                        )
                    }
                }
                DATA_OXYGEN_SATURATION -> {
                    val spo2 = sample.value?.takeIf { it in 50..100 }
                    if (spo2 != null && (next.spo2At ?: Long.MIN_VALUE) <= at) {
                        next = next.copy(spo2 = spo2, spo2At = at, lastSyncMillis = now)
                    }
                }
                DATA_SLEEP -> {
                    val start = sample.startMillis
                    val end = sample.endMillis
                    val minutes = if (start != null && end != null && end > start)
                        ((end - start) / 60_000L).toInt() else null
                    if (minutes != null && minutes in 15..1080 &&
                        (next.sleepEndAt ?: Long.MIN_VALUE) <= at) {
                        next = next.copy(
                            sleepMinutes = minutes,
                            sleepScore = sample.score?.takeIf { it in 0..100 },
                            sleepEndAt = at,
                            lastSyncMillis = now,
                        )
                    }
                }
            }
        }

        if (next.sleepEpisodes.isNotEmpty()) next = next.copy(sleepMinutes =
            Fit3Sleep.todayMinutes(next.sleepEpisodes, next.sleepStages, now, java.time.ZoneId.systemDefault()))
        return next to types
    }

    /** Samsung's type-7 PedometerStepCount: continuation byte, then 12 fixed fields. */
    private fun parsePedometerIntervals(message: ByteArray): Map<Long, StepRecord>? {
        if (message.size < 8 || (message[0].toInt() and 0xff) != 0x82 ||
            (message[2].toInt() and 0xff) != GM_SEQUENCE_NUMBER ||
            (message[5].toInt() and 0xff) != DATA_PEDO_STEP_COUNT) return null
        val result = linkedMapOf<Long, StepRecord>()
        var i = 6
        repeat(128) {
            if (i >= message.size) return result.takeIf { it.isNotEmpty() }
            val more = message[i++].toInt() and 0xff
            if (more !in 0..1) return null
            var start: Long? = null
            var count: Int? = null
            var walk = 0
            var run = 0
            var distance = 0.0
            var calories = 0.0
            var duration = 0
            val fields = intArrayOf(5, 1, 2, 3, 16, 17, 18, 19, 20, 21, 22, 23)
            for (field in fields) {
                if (i >= message.size || (message[i++].toInt() and 0xff) != field) return null
                val width = if (field == 23) 1 else 4
                if (i + width > message.size) return null
                if (width == 4) {
                    val raw = ByteBuffer.wrap(message, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    when (field) {
                        1 -> start = (raw.toLong() and 0xffffffffL) * 1000L + 631_152_000_000L
                        16 -> count = raw.takeIf { it in 0..100_000 }
                        17 -> distance = Float.fromBits(raw).toDouble()
                        19 -> calories = Float.fromBits(raw).toDouble()
                        20 -> run = raw
                        21 -> walk = raw
                        22 -> duration = raw
                    }
                }
                i += width
            }
            val key = start ?: return null
            val steps = count ?: return null
            if (walk !in 0..steps || run !in 0..steps ||
                !distance.isFinite() || distance !in 0.0..100_000.0 ||
                !calories.isFinite() || calories !in 0.0..10_000.0 ||
                duration !in 0..86_400_000) return null
            result[key] = StepRecord(steps, walk, run, distance, calories, duration)
            if (more == 0) return result
        }
        return null
    }

    /** Window from a distinctive feature type byte to the next distinctive feature type. */
    private fun findTypeWindow(bytes: ByteArray, type: Int): Pair<Int, Int>? {
        val pos = bytes.indexOfFirst { (it.toInt() and 0xff) == type }
        if (pos < 0) return null
        val distinctive = setOf(DATA_OXYGEN_SATURATION, DATA_ACTIVITY_DAY_SUMMARY, DATA_FLOORS_CLIMBED, DATA_PEDOMETER_DAY_SUMMARY)
        var end = minOf(bytes.size, pos + 768)
        for (i in pos + 1 until end) {
            val value = bytes[i].toInt() and 0xff
            if (value in distinctive && value != type) { end = i; break }
        }
        return (pos + 1) to end
    }

    private fun findLowTypeRecord(bytes: ByteArray, type: Int, characteristicField: Int): Pair<Int, Int>? {
        for (pos in 1 until bytes.size) {
            if ((bytes[pos].toInt() and 0xff) != type) continue
            val end = minOf(bytes.size, pos + 256)
            for (i in pos + 1 until end) {
                if ((bytes[i].toInt() and 0xff) == characteristicField) return (pos + 1) to end
            }
        }
        return null
    }

    private fun addStepHistory(history: List<Int>, previousSteps: Int?, newSteps: Int?): List<Int> =
        if (newSteps != null && newSteps != previousSteps) (history + newSteps).takeLast(24) else history

    private fun findInt(bytes: ByteArray, start: Int, end: Int, paramId: Int, range: IntRange): Int? {
        var i = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(bytes.size)
        while (i + 1 < limit) {
            if ((bytes[i].toInt() and 0xff) == paramId) {
                if (i + 5 <= limit) {
                    val v = ByteBuffer.wrap(bytes, i + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (v in range) return v
                }
                if (i + 3 <= limit) {
                    val v = (bytes[i + 1].toInt() and 0xff) or ((bytes[i + 2].toInt() and 0xff) shl 8)
                    if (v in range) return v
                }
                val v = bytes[i + 1].toInt() and 0xff
                if (v in range && range.last <= 500) return v
            }
            i++
        }
        return null
    }

    private fun findNumber(
        bytes: ByteArray,
        start: Int,
        end: Int,
        paramId: Int,
        range: ClosedFloatingPointRange<Double>,
    ): Double? {
        var i = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(bytes.size)
        while (i + 1 < limit) {
            if ((bytes[i].toInt() and 0xff) == paramId) {
                if (i + 5 <= limit) {
                    val bits = ByteBuffer.wrap(bytes, i + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val asFloat = Float.fromBits(bits).toDouble()
                    if (asFloat.isFinite() && asFloat in range) return asFloat
                    val asInt = bits.toLong() and 0xffffffffL
                    if (asInt.toDouble() in range) return asInt.toDouble()
                }
                if (i + 3 <= limit) {
                    val v = (bytes[i + 1].toInt() and 0xff) or ((bytes[i + 2].toInt() and 0xff) shl 8)
                    if (v.toDouble() in range) return v.toDouble()
                }
            }
            i++
        }
        return null
    }

    private fun normalizeDistance(raw: Double?): Double? {
        raw ?: return null
        return if (raw in 0.0..300.0 && raw % 1.0 != 0.0) raw * 1000.0 else raw
    }

    private fun normalizeActiveMinutes(raw: Int?): Int? {
        raw ?: return null
        return when {
            raw > 172_800 -> raw / 60_000 // milliseconds
            raw > 1_440 -> raw / 60       // seconds
            else -> raw                   // already minutes
        }.coerceIn(0, 1_440)
    }

    fun describe(message: ByteArray): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        val dir = if (h.type == SaMessageCodec.TYPE_REQUEST) "REQ" else "RSP"
        val name = when (h.id) {
            CMD_CHECK_STATUS -> "CHECK_STATUS"
            CMD_SYNC_DATA -> "SYNC_DATA"
            CMD_REQUEST_DATA -> "REQUEST_DATA"
            CMD_WEARABLE_MSG -> "WEARABLE_MSG"
            CMD_NETWORK_IMAGE_DOWNLOAD -> "NETWORK_IMAGE"
            CMD_PEDOMETER_BACK_SYNC -> "PEDOMETER_BACK_SYNC"
            CMD_CYCLE_PREDICTION_WMSG -> "CYCLE_WMSG"
            CMD_SOUND_RELAY_WMSG -> "SOUND_RELAY"
            CMD_WEARABLE_REQUEST_CAPABILITY -> "WEARABLE_CAPABILITY"
            CMD_MOBILE_REQUEST_CAPABILITY -> "MOBILE_CAPABILITY"
            CMD_GM_REQUEST_CAPABILITY -> "GM_REQUEST_CAPABILITY"
            CMD_GM_CAPABILITY_NOTI -> "GM_CAPABILITY_NOTI"
            CMD_PLUGIN_HEALTH_CAPABILITY_REQUEST -> "HOST_CAPABILITY"
            else -> return "[HEALTH id=${h.id} $dir fmt=${h.format}]"
        }
        val extra = if (h.id == CMD_GM_REQUEST_CAPABILITY) {
            parseGmRequest(message)?.let { " seq=${it.sequence} proto=${it.protocolVersion}" }.orEmpty()
        } else ""
        return "[$name $dir$extra]"
    }

    fun dataTypeName(type: Int): String = when (type) {
        DATA_PEDO_STEP_COUNT -> "steps"
        DATA_HEART_RATE -> "heart-rate"
        DATA_SLEEP -> "sleep"
        DATA_SLEEP_STAGE -> "sleep-stage"
        DATA_STRESS -> "stress"
        DATA_CALORIES_BURNED -> "calories"
        DATA_OXYGEN_SATURATION -> "SpO₂"
        DATA_ACTIVITY_DAY_SUMMARY -> "activity-summary"
        DATA_FLOORS_CLIMBED -> "floors"
        DATA_PEDOMETER_DAY_SUMMARY -> "pedometer-summary"
        else -> "type-$type"
    }
}
