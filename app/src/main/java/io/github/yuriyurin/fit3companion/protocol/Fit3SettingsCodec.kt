package io.github.yuriyurin.fit3companion.protocol

/** Agent 11 (Full Settings). Packet IDs/parameters are taken from the official Fit3 plugin. */
object Fit3SettingsCodec {
    const val MSG_RTC = 0
    const val MSG_LANGUAGE = 1
    const val MSG_BATTERY = 2
    const val MSG_VIBRATION = 3
    const val MSG_BRIGHTNESS = 5
    const val MSG_DISPLAY = 6
    const val MSG_DISPLAY_LIST = 7
    const val MSG_ADVANCED = 8
    const val MSG_MODES = 9
    const val MSG_TIMEOUT = 10
    const val MSG_SLEEP_MODE_NOW = 11
    const val MSG_THEATRE_MODE_NOW = 13
    const val MSG_FULL_SETTINGS = 32
    const val MSG_RESET = 33
    const val MSG_WRIST_DETECTION = 35
    const val MSG_DND = 36
    const val MSG_PRESS_HOLD = 38
    const val MSG_STATUS_INDICATOR = 39
    const val MSG_RESTART = 43

    const val P_VIBRATION_INTENSITY = 2
    const val P_BRIGHTNESS = 7
    const val P_AUTO_BRIGHTNESS = 8
    const val P_AOD = 9
    const val P_RAISE_WAKE = 10
    const val P_TOUCH_WAKE = 11
    const val P_AUTO_MEDIA = 12
    const val P_TIMEOUT = 14
    const val P_SHOW_LAST_APP = 15
    const val P_DISCONNECT_ALERT = 16
    const val P_DOUBLE_PRESS = 17
    const val P_STATUS_INDICATOR = 18
    const val P_SYNC_SLEEP_MODE = 19
    const val P_WRIST_INFO = 20
    const val P_BUTTON_POSITION = 21
    const val P_SLEEP_MODE_ON = 22
    const val P_THEATRE_MODE_ON = 27

    data class State(
        val brightness: Int? = null,
        val autoBrightness: Boolean? = null,
        val alwaysOnDisplay: Boolean? = null,
        val raiseToWake: Boolean? = null,
        val touchToWake: Boolean? = null,
        val autoMediaControl: Boolean? = null,
        val timeoutSeconds: Int? = null,
        val doublePressAction: Int? = null,
        val statusIndicator: Int? = null,
        val vibrationIntensity: Int? = null,
        val sleepMode: Boolean? = null,
        val theatreMode: Boolean? = null,
        val rightWrist: Boolean? = null,
        val buttonOnRight: Boolean? = null,
    )

    val fullSettingsRequest = SaMessageCodec.fixedRequest(MSG_FULL_SETTINGS)
    val batteryRequest = SaMessageCodec.fixedRequest(MSG_BATTERY)
    val brightnessRequest = SaMessageCodec.fixedRequest(MSG_BRIGHTNESS)
    val displayRequest = SaMessageCodec.fixedRequest(MSG_DISPLAY)
    val advancedRequest = SaMessageCodec.fixedRequest(MSG_ADVANCED)
    val vibrationRequest = SaMessageCodec.fixedRequest(MSG_VIBRATION)
    val displayListRequest = SaMessageCodec.fixedRequest(MSG_DISPLAY_LIST)
    val sleepModeRequest = SaMessageCodec.fixedRequest(MSG_SLEEP_MODE_NOW)
    val theatreModeRequest = SaMessageCodec.fixedRequest(MSG_THEATRE_MODE_NOW)

    fun setBrightness(value: Int): ByteArray {
        // Fit3 uses brightness levels 1..10 (Samsung's default is level 7), not 0..100.
        require(value in 1..10)
        return SaMessageCodec.fixedRequest(MSG_BRIGHTNESS, SaMessageCodec.byteParam(P_BRIGHTNESS, value))
    }

    // Fit3 writes the Display toggles through DISPLAY(6).  MSG_BRIGHTNESS(5) is used
    // for the numeric brightness value; using 5 for these booleans was ACKed but did not
    // change the user's AZA3 watch.
    fun setAutoBrightness(enabled: Boolean): ByteArray = displayBool(P_AUTO_BRIGHTNESS, enabled)
    fun setAlwaysOnDisplay(enabled: Boolean): ByteArray = displayBool(P_AOD, enabled)
    fun setRaiseToWake(enabled: Boolean): ByteArray = displayBool(P_RAISE_WAKE, enabled)
    fun setTouchToWake(enabled: Boolean): ByteArray = displayBool(P_TOUCH_WAKE, enabled)
    fun setRightWrist(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_TIMEOUT, SaMessageCodec.boolParam(P_WRIST_INFO, enabled))
    fun setButtonOnRight(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_TIMEOUT, SaMessageCodec.boolParam(P_BUTTON_POSITION, enabled))
    fun setAutoMediaControl(enabled: Boolean): ByteArray = displayBool(P_AUTO_MEDIA, enabled)
    fun setSleepMode(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_SLEEP_MODE_NOW, SaMessageCodec.boolParam(P_SLEEP_MODE_ON, enabled))
    fun setTheatreMode(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_THEATRE_MODE_NOW, SaMessageCodec.boolParam(P_THEATRE_MODE_ON, enabled))

    private fun displayBool(param: Int, enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_DISPLAY, SaMessageCodec.boolParam(param, enabled))

    val FIT3_SCREEN_TIMEOUTS_SECONDS = listOf(4, 7, 10, 15, 30)

    fun setScreenTimeout(seconds: Int): ByteArray {
        require(seconds in FIT3_SCREEN_TIMEOUTS_SECONDS)
        // Verified against Samsung SAMessageDataPacketConstructor.constructScreenTimeoutInfoPacket():
        // message 7, parameter 14, SHORT. The response uses a BYTE for the same parameter.
        return SaMessageCodec.fixedRequest(MSG_DISPLAY_LIST, SaMessageCodec.shortParam(P_TIMEOUT, seconds))
    }

    fun setDoublePressAction(action: Int): ByteArray =
        // Samsung constructor sends these setters as SHORT; the reply reports them as BYTE.
        SaMessageCodec.fixedRequest(MSG_ADVANCED, SaMessageCodec.shortParam(P_DOUBLE_PRESS, action))

    fun setStatusIndicator(value: Int): ByteArray =
        SaMessageCodec.fixedRequest(MSG_ADVANCED, SaMessageCodec.shortParam(P_STATUS_INDICATOR, value))

    fun setVibrationIntensity(enabled: Boolean): ByteArray =
        SaMessageCodec.fixedRequest(MSG_VIBRATION, SaMessageCodec.boolParam(P_VIBRATION_INTENSITY, enabled))

    /**
     * Merge a Samsung Full Settings reply into the current state.
     *
     * Important Fit3 quirk recovered from the official plugin:
     * - MSG_FULL_SETTINGS (0x60) starts with a parameter count byte.
     * - parameters 0..21 are id/value pairs. The Fit3 packet schema from Samsung's
     *   FullSettingPacketInfo uses little-endian SHORT for 5, 6, 14, 15, 16, 17 and 18;
     *   the remaining parameters are BYTE values. This matches the AZA3 packets captured
     *   from the user's watch (for example 0E 3C 00 for a 60-second timeout).
     * - standalone MSG_DISPLAY_LIST response returns timeout (param 14) as BYTE even though
     *   the setter sends it as SHORT.
     */
    fun merge(old: State, message: ByteArray): State {
        val h = SaMessageCodec.parseHeader(message) ?: return old
        if (h.type != SaMessageCodec.TYPE_RESPONSE) return old
        fun bool(v: Int) = v != 0
        return when (h.id) {
            MSG_FULL_SETTINGS -> {
                val p = parseFullSettings(message)
                old.copy(
                    brightness = p[P_BRIGHTNESS] ?: old.brightness,
                    autoBrightness = p[P_AUTO_BRIGHTNESS]?.let(::bool) ?: old.autoBrightness,
                    alwaysOnDisplay = p[P_AOD]?.let(::bool) ?: old.alwaysOnDisplay,
                    raiseToWake = p[P_RAISE_WAKE]?.let(::bool) ?: old.raiseToWake,
                    touchToWake = p[P_TOUCH_WAKE]?.let(::bool) ?: old.touchToWake,
                    autoMediaControl = p[P_AUTO_MEDIA]?.let(::bool) ?: old.autoMediaControl,
                    timeoutSeconds = p[P_TIMEOUT] ?: old.timeoutSeconds,
                    doublePressAction = p[P_DOUBLE_PRESS] ?: old.doublePressAction,
                    statusIndicator = p[P_STATUS_INDICATOR] ?: old.statusIndicator,
                    vibrationIntensity = p[P_VIBRATION_INTENSITY] ?: old.vibrationIntensity,
                    rightWrist = p[P_WRIST_INFO]?.let(::bool) ?: old.rightWrist,
                    buttonOnRight = p[P_BUTTON_POSITION]?.let(::bool) ?: old.buttonOnRight,
                )
            }
            MSG_BRIGHTNESS -> {
                val p = byteParam(message, P_BRIGHTNESS)
                if (p != null) old.copy(brightness = p) else old
            }
            MSG_DISPLAY -> {
                val params = SaMessageCodec.byteParams(message, setOf(
                    P_AUTO_BRIGHTNESS, P_AOD, P_RAISE_WAKE, P_TOUCH_WAKE, P_AUTO_MEDIA
                )).toMap()
                old.copy(
                    autoBrightness = params[P_AUTO_BRIGHTNESS]?.let(::bool) ?: old.autoBrightness,
                    alwaysOnDisplay = params[P_AOD]?.let(::bool) ?: old.alwaysOnDisplay,
                    raiseToWake = params[P_RAISE_WAKE]?.let(::bool) ?: old.raiseToWake,
                    touchToWake = params[P_TOUCH_WAKE]?.let(::bool) ?: old.touchToWake,
                    autoMediaControl = params[P_AUTO_MEDIA]?.let(::bool) ?: old.autoMediaControl,
                )
            }
            MSG_VIBRATION -> {
                val p = byteParam(message, P_VIBRATION_INTENSITY)
                if (p != null) old.copy(vibrationIntensity = p) else old
            }
            MSG_DISPLAY_LIST -> {
                // Official PacketParser uses getParamByteValue(14) for the response.
                val timeout = byteParam(message, P_TIMEOUT)
                if (timeout != null) old.copy(timeoutSeconds = timeout) else old
            }
            MSG_ADVANCED -> {
                // Official PacketParser uses BYTE for 17/18 in this reply.
                old.copy(
                    doublePressAction = byteParam(message, P_DOUBLE_PRESS) ?: old.doublePressAction,
                    statusIndicator = byteParam(message, P_STATUS_INDICATOR) ?: old.statusIndicator,
                )
            }
            MSG_TIMEOUT -> {
                // General orientation uses message 10 rather than the display message.
                val params = SaMessageCodec.byteParams(message,
                    setOf(P_WRIST_INFO, P_BUTTON_POSITION)).toMap()
                old.copy(
                    rightWrist = params[P_WRIST_INFO]?.let(::bool) ?: old.rightWrist,
                    buttonOnRight = params[P_BUTTON_POSITION]?.let(::bool) ?: old.buttonOnRight,
                )
            }
            MSG_SLEEP_MODE_NOW -> {
                val p = byteParam(message, P_SLEEP_MODE_ON)
                if (p != null) old.copy(sleepMode = bool(p)) else old
            }
            MSG_THEATRE_MODE_NOW -> {
                val p = byteParam(message, P_THEATRE_MODE_ON)
                if (p != null) old.copy(theatreMode = bool(p)) else old
            }
            else -> old
        }
    }

    /** Human-readable summary for Protocol Monitor. */
    fun describe(message: ByteArray): String? {
        val h = SaMessageCodec.parseHeader(message) ?: return null
        if (h.type != SaMessageCodec.TYPE_RESPONSE) return null
        return when (h.id) {
            MSG_FULL_SETTINGS -> {
                val p = parseFullSettings(message)
                if (p.isEmpty()) "FULL_SETTINGS (не удалось разобрать параметры)" else buildString {
                    append("FULL_SETTINGS")
                    p[P_BRIGHTNESS]?.let { append(" brightness=$it") }
                    p[P_AUTO_BRIGHTNESS]?.let { append(" autoBrightness=${it != 0}") }
                    p[P_AOD]?.let { append(" AOD=${it != 0}") }
                    p[P_RAISE_WAKE]?.let { append(" raiseWake=${it != 0}") }
                    p[P_TOUCH_WAKE]?.let { append(" touchWake=${it != 0}") }
                    p[P_AUTO_MEDIA]?.let { append(" media=${it != 0}") }
                    p[P_TIMEOUT]?.let { append(" timeout=${it}s") }
                    p[P_DOUBLE_PRESS]?.let { append(" doublePress=$it") }
                    p[P_STATUS_INDICATOR]?.let { append(" statusIndicator=$it") }
                }
            }
            MSG_BATTERY -> "BATTERY"
            MSG_BRIGHTNESS -> byteParam(message, P_BRIGHTNESS)?.let { "BRIGHTNESS value=$it" } ?: "BRIGHTNESS"
            MSG_DISPLAY -> "DISPLAY"
            MSG_DISPLAY_LIST -> byteParam(message, P_TIMEOUT)?.let { "DISPLAY_LIST timeout=${it}s" } ?: "DISPLAY_LIST"
            MSG_ADVANCED -> "ADVANCED"
            MSG_TIMEOUT -> {
                val params = SaMessageCodec.byteParams(message,
                    setOf(P_WRIST_INFO, P_BUTTON_POSITION)).toMap()
                "ORIENTATION wrist=${params[P_WRIST_INFO]} button=${params[P_BUTTON_POSITION]}"
            }
            MSG_SLEEP_MODE_NOW -> "SLEEP_MODE"
            MSG_THEATRE_MODE_NOW -> "THEATRE_MODE"
            else -> "SETTINGS msg=${h.id}"
        }
    }

    private fun byteParam(message: ByteArray, id: Int): Int? =
        SaMessageCodec.byteParams(message, setOf(id)).lastOrNull { it.first == id }?.second

    private val FULL_SETTINGS_SHORT_PARAMS = setOf(5, 6, 14, 15, 16, 17, 18)

    private fun parseFullSettings(message: ByteArray): Map<Int, Int> {
        if (message.size < 2) return emptyMap()
        val count = message[1].toInt() and 0xff
        if (count !in 1..64) return emptyMap()
        val out = linkedMapOf<Int, Int>()
        var i = 2
        for (index in 0 until count) {
            if (i >= message.size) break
            val id = message[i++].toInt() and 0xff
            val value = if (id in FULL_SETTINGS_SHORT_PARAMS) {
                if (i + 1 >= message.size) break
                val v = SaMessageCodec.readLeShort(message, i) ?: break
                i += 2
                v
            } else {
                if (i >= message.size) break
                message[i++].toInt() and 0xff
            }
            out[id] = value
        }
        return out
    }
}
