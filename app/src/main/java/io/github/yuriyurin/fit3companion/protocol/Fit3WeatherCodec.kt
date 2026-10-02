package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.WeatherForecast
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Service 5 packet layout follows Samsung SAWeatherMessageData.buildMSGData(WEATHER_INFO). */
object Fit3WeatherCodec {
    /** Current-location/open request (0), or weather-refresh request (1, param value 1). */
    fun requestKind(message: ByteArray): Int? {
        val header = SaMessageCodec.parseHeader(message) ?: return null
        if (header.type != SaMessageCodec.TYPE_REQUEST) return null
        return when (header.id) {
            0 -> 0
            1 -> if (message.size >= 3 && (message[2].toInt() and 0xff) == 1) 1 else null
            else -> null
        }
    }

    /** Samsung WXCode, not the WMO code number supplied by Open-Meteo. */
    fun samsungIcon(wmo: Int, isDay: Boolean): Int = when (wmo) {
        0 -> if (isDay) 0 else 0
        1 -> 1
        2 -> 1
        3 -> 2
        45, 48 -> 3
        51, 53, 55, 56, 57, 61, 63, 80, 81 -> 5
        65, 82 -> 21
        66, 67 -> 16
        71, 73, 75, 77, 85, 86 -> 13
        95, 96, 99 -> 8
        else -> 2
    }

    fun response(forecast: WeatherForecast): ByteArray {
        val out = ByteArrayOutputStream()
        var params = 0
        fun one(id: Int, value: Int) { out.write(id); out.write(value and 0xff); params++ }
        fun word(id: Int, value: Int) {
            // Fit3's weather screen decodes 16-bit measurements little-endian.
            // Sending 20 as 00 14 was displayed on the watch as 5120°C.
            out.write(id); out.write(value and 0xff); out.write((value ushr 8) and 0xff); params++
        }
        fun dword(id: Int, value: Long) {
            out.write(id); for (shift in 3 downTo 0) out.write(((value ushr (shift * 8)) and 0xff).toInt()); params++
        }
        fun text(id: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8).take(80).toByteArray()
            out.write(id); out.write(bytes.size); out.write(bytes); params++
        }
        out.write(0xc1) // VARIABLE, RESPONSE, WEATHER_INFO(1)
        out.write(0); out.write(0) // total parameter count, filled at the end
        one(8, 0) // TWC-compatible condition indices
        one(6, 0) // Celsius
        one(9, 0) // no Samsung location prompt
        one(10, 1) // location enabled
        one(7, 1) // refresh duration
        one(11, 1) // one city
        one(12, 0) // city index
        one(18, 1) // selected/current city
        text(13, forecast.city.name)
        text(14, forecast.city.region)
        text(15, forecast.city.country)
        one(16, samsungIcon(forecast.code, forecast.isDay))
        dword(19, forecast.updatedAt / 1000)
        word(20, forecast.temperature.roundToInt())
        word(21, forecast.high.roundToInt())
        word(22, forecast.low.roundToInt())
        word(23, forecast.feelsLike.roundToInt())
        word(24, forecast.yesterdayHigh.roundToInt())
        word(25, forecast.yesterdayLow.roundToInt())
        dword(26, forecast.sunrise)
        dword(27, forecast.sunset)
        text(28, forecast.city.timezone)
        one(17, if (forecast.isDay) 1 else 0)
        one(29, forecast.hours.size.coerceAtMost(24))
        forecast.hours.take(24).forEachIndexed { index, hour ->
            one(30, index)
            one(34, if (hour.isDay) 1 else 0)
            dword(31, hour.time)
            one(32, samsungIcon(hour.code, hour.isDay))
            word(33, hour.temperature.roundToInt())
            one(35, hour.rainPercent.coerceIn(0, 100))
        }
        one(36, forecast.days.size.coerceAtMost(6))
        forecast.days.take(6).forEachIndexed { index, day ->
            one(37, index)
            dword(38, day.time)
            one(39, samsungIcon(day.code, true))
            one(40, samsungIcon(day.code, true))
            one(41, samsungIcon(day.code, false))
            word(42, day.high.roundToInt())
            word(43, day.low.roundToInt())
        }
        // Air quality is unavailable in this forecast; keep optional AQI fields empty.
        for (id in 44..49) word(id, 0)
        word(50, forecast.uvIndex.roundToInt())
        text(51, when {
            forecast.uvIndex < 3 -> "Низкий"
            forecast.uvIndex < 6 -> "Средний"
            forecast.uvIndex < 8 -> "Высокий"
            forecast.uvIndex < 11 -> "Очень высокий"
            else -> "Экстремальный"
        })
        word(52, forecast.windKmh.roundToInt())
        text(53, "") // wind text
        word(54, forecast.humidity.coerceIn(0, 100))
        val result = out.toByteArray()
        require(result.size <= 980) { "Weather packet too large: ${result.size}" }
        result[1] = ((params ushr 8) and 0xff).toByte()
        result[2] = (params and 0xff).toByte()
        return result
    }
}
