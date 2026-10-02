package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.WeatherCity
import io.github.yuriyurin.fit3companion.ble.WeatherDay
import io.github.yuriyurin.fit3companion.ble.WeatherForecast
import io.github.yuriyurin.fit3companion.ble.WeatherHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fit3WeatherCodecTest {
    private val sample = WeatherForecast(
        WeatherCity(1, "Москва", "", "Россия", 55.7, 37.6, "Europe/Moscow"),
        1_700_000_000_000, -3.0, -6.0, 1.0, -7.0, 0.0, -5.0,
        80, 12.0, 1.0, 71, false, 1_700_000_000, 1_700_040_000,
        listOf(WeatherHour(1_700_000_000, -3.0, 71, false, 60)),
        listOf(WeatherDay(1_700_080_000, 1.0, -5.0, 3)),
    )

    @Test fun weatherPacketMatchesSamsungVariableResponseShape() {
        val bytes = Fit3WeatherCodec.response(sample)
        assertEquals(0xc1, bytes[0].toInt() and 0xff)
        val count = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
        assertEquals(6 + 17 + 1 + 6 + 1 + 7 + 11, count)
        assertEquals(8, bytes[3].toInt() and 0xff)
        // The plugin constructs LITTLE_ENDIAN values then writes them in reverse order.
        val timeParam = bytes.indexOf(19)
        assertTrue(timeParam > 0)
        assertEquals(0x65, bytes[timeParam + 1].toInt() and 0xff) // 0x6553f100 = 1_700_000_000
        val temperatureParam = timeParam + 5
        assertEquals(20, bytes[temperatureParam].toInt() and 0xff)
        assertEquals(0xfd, bytes[temperatureParam + 1].toInt() and 0xff) // -3°C, little-endian
        assertEquals(0xff, bytes[temperatureParam + 2].toInt() and 0xff)
        assertTrue(bytes.size < 980)
        assertTrue(bytes.toList().windowed("Москва".toByteArray().size).any {
            it.toByteArray().contentEquals("Москва".toByteArray())
        })
        val frames = Fit3SapCodec.encodeMessage(Fit3SapCodec.SERVICE_WEATHER, bytes, 64)
        assertTrue(frames.size > 1)
    }

    @Test fun recognizesWatchRequests() {
        assertEquals(0, Fit3WeatherCodec.requestKind(byteArrayOf(0)))
        assertEquals(1, Fit3WeatherCodec.requestKind(byteArrayOf(1, 0, 1)))
        assertEquals(null, Fit3WeatherCodec.requestKind(byteArrayOf(1, 0, 0)))
    }
}
