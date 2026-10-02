package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherCity(
    val id: Int, val name: String, val region: String, val country: String,
    val latitude: Double, val longitude: Double, val timezone: String,
) {
    val label: String get() = listOf(name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    fun json() = JSONObject().put("id", id).put("name", name).put("region", region)
        .put("country", country).put("lat", latitude).put("lon", longitude).put("timezone", timezone)
    companion object {
        fun from(json: JSONObject) = WeatherCity(json.getInt("id"), json.getString("name"),
            json.optString("region"), json.optString("country"), json.getDouble("lat"),
            json.getDouble("lon"), json.getString("timezone"))
    }
}

data class WeatherHour(val time: Long, val temperature: Double, val code: Int,
    val isDay: Boolean, val rainPercent: Int)
data class WeatherDay(val time: Long, val high: Double, val low: Double, val code: Int)
data class WeatherForecast(
    val city: WeatherCity, val updatedAt: Long, val temperature: Double,
    val feelsLike: Double, val high: Double, val low: Double, val yesterdayHigh: Double,
    val yesterdayLow: Double, val humidity: Int, val windKmh: Double, val uvIndex: Double,
    val code: Int, val isDay: Boolean, val sunrise: Long, val sunset: Long,
    val hours: List<WeatherHour>, val days: List<WeatherDay>,
    val source: String = "Open-Meteo",
) {
    fun json() = JSONObject().put("city", city.json()).put("updatedAt", updatedAt)
        .put("temperature", temperature).put("feelsLike", feelsLike).put("high", high)
        .put("low", low).put("yesterdayHigh", yesterdayHigh).put("yesterdayLow", yesterdayLow)
        .put("humidity", humidity).put("windKmh", windKmh).put("uvIndex", uvIndex)
        .put("code", code).put("isDay", isDay).put("sunrise", sunrise).put("sunset", sunset)
        .put("source", source)
        .put("hours", JSONArray().also { a -> hours.forEach { h -> a.put(JSONObject()
            .put("time", h.time).put("temperature", h.temperature).put("code", h.code)
            .put("isDay", h.isDay).put("rainPercent", h.rainPercent)) } })
        .put("days", JSONArray().also { a -> days.forEach { d -> a.put(JSONObject()
            .put("time", d.time).put("high", d.high).put("low", d.low).put("code", d.code)) } })
    companion object {
        fun from(j: JSONObject): WeatherForecast {
            val hours = j.getJSONArray("hours")
            val days = j.getJSONArray("days")
            return WeatherForecast(WeatherCity.from(j.getJSONObject("city")), j.getLong("updatedAt"),
                j.getDouble("temperature"), j.getDouble("feelsLike"), j.getDouble("high"),
                j.getDouble("low"), j.getDouble("yesterdayHigh"), j.getDouble("yesterdayLow"),
                j.getInt("humidity"), j.getDouble("windKmh"), j.getDouble("uvIndex"),
                j.getInt("code"), j.getBoolean("isDay"), j.getLong("sunrise"), j.getLong("sunset"),
                (0 until hours.length()).map { i -> hours.getJSONObject(i).let { h -> WeatherHour(
                    h.getLong("time"), h.getDouble("temperature"), h.getInt("code"),
                    h.getBoolean("isDay"), h.getInt("rainPercent")) } },
                (0 until days.length()).map { i -> days.getJSONObject(i).let { d -> WeatherDay(
                    d.getLong("time"), d.getDouble("high"), d.getDouble("low"), d.getInt("code")) } },
                j.optString("source", "Open-Meteo"))
        }
    }
}

/** HTTPS-only Open-Meteo client; call network methods from a worker thread. */
class Fit3WeatherRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("fit3_weather", Context.MODE_PRIVATE)

    fun selectedCity(): WeatherCity? = prefs.getString("city", null)?.let {
        runCatching { WeatherCity.from(JSONObject(it)) }.getOrNull()
    }
    fun cachedForecast(): WeatherForecast? = prefs.getString("forecast", null)?.let {
        runCatching { WeatherForecast.from(JSONObject(it)) }.getOrNull()
    }?.takeIf { it.city.id == selectedCity()?.id }
    fun selectCity(city: WeatherCity) {
        prefs.edit().putString("city", city.json().toString()).remove("forecast").apply()
    }
    fun save(forecast: WeatherForecast) {
        if (forecast.city.id == selectedCity()?.id)
            prefs.edit().putString("forecast", forecast.json().toString()).apply()
    }

    fun search(query: String): List<WeatherCity> {
        val value = query.trim()
        if (value.length < 3) return emptyList() // Open-Meteo: 1 char is empty; 2 chars exact only.
        val language = io.github.yuriyurin.fit3companion.AppStrings.locale.language
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(value)}&count=8&language=$language&format=json"
        val results = fetchJson(url).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            runCatching {
                val j = results.getJSONObject(index)
                WeatherCity(j.getInt("id"), j.getString("name"), j.optString("admin1"),
                    j.optString("country"), j.getDouble("latitude"), j.getDouble("longitude"),
                    j.optString("timezone", "UTC"))
            }.getOrNull()
        }
    }

    fun forecast(city: WeatherCity): WeatherForecast =
        runCatching { standardForecast(city) }.getOrElse { ensembleForecast(city) }

    private fun standardForecast(city: WeatherCity): WeatherForecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,uv_index_max" +
            "&timezone=${Uri.encode(city.timezone)}&timeformat=unixtime&past_days=1&forecast_days=7"
        val root = fetchJson(url)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")
        val now = current.getLong("time")
        val hourTimes = hourly.getJSONArray("time")
        val hours = (0 until hourTimes.length()).filter { hourTimes.getLong(it) >= now - 3600 }
            .take(24).map { i -> WeatherHour(hourTimes.getLong(i),
                hourly.getJSONArray("temperature_2m").getDouble(i),
                hourly.getJSONArray("weather_code").getInt(i),
                hourly.getJSONArray("is_day").getInt(i) == 1,
                hourly.getJSONArray("precipitation_probability").optInt(i)) }
        val dayTimes = daily.getJSONArray("time")
        require(dayTimes.length() >= 2) { "Нет дневного прогноза" }
        val days = (1 until dayTimes.length()).take(6).map { i -> WeatherDay(dayTimes.getLong(i),
            daily.getJSONArray("temperature_2m_max").getDouble(i),
            daily.getJSONArray("temperature_2m_min").getDouble(i),
            daily.getJSONArray("weather_code").getInt(i)) }
        val today = 1 // past_days=1
        return WeatherForecast(city, System.currentTimeMillis(), current.getDouble("temperature_2m"),
            current.getDouble("apparent_temperature"),
            daily.getJSONArray("temperature_2m_max").getDouble(today),
            daily.getJSONArray("temperature_2m_min").getDouble(today),
            daily.getJSONArray("temperature_2m_max").getDouble(0),
            daily.getJSONArray("temperature_2m_min").getDouble(0),
            current.getInt("relative_humidity_2m"), current.getDouble("wind_speed_10m"),
            daily.getJSONArray("uv_index_max").optDouble(today, 0.0),
            current.getInt("weather_code"), current.getInt("is_day") == 1,
            daily.getJSONArray("sunrise").getLong(today), daily.getJSONArray("sunset").getLong(today),
            hours, days)
    }

    /** Official Open-Meteo ensemble-mean API is a separate host and survives regional routing failures. */
    private fun ensembleForecast(city: WeatherCity): WeatherForecast {
        val url = "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=${city.latitude}" +
            "&longitude=${city.longitude}&hourly=temperature_2m,relative_humidity_2m," +
            "apparent_temperature,is_day,weather_code,wind_speed_10m,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,uv_index_max" +
            "&models=dwd_icon_eps_ensemble_mean_seamless&forecast_days=7&past_days=1" +
            "&timeformat=unixtime&timezone=${Uri.encode(city.timezone)}"
        val root = fetchJson(url)
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")
        val times = hourly.getJSONArray("time")
        val dayFlags = hourly.getJSONArray("is_day")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val now = System.currentTimeMillis() / 1000
        val currentIndex = (0 until times.length()).lastOrNull { times.getLong(it) <= now } ?: 0
        val dayTimes = daily.getJSONArray("time")
        require(dayTimes.length() >= 2) { "Нет дневного прогноза" }
        val days = (1 until dayTimes.length()).take(6).map { day ->
            WeatherDay(dayTimes.getLong(day), daily.getJSONArray("temperature_2m_max").getDouble(day),
                daily.getJSONArray("temperature_2m_min").getDouble(day),
                daily.getJSONArray("weather_code").optInt(day))
        }
        val hours = (currentIndex until times.length()).take(24).map { i ->
            WeatherHour(times.getLong(i), temps.getDouble(i), codes.optInt(i),
                dayFlags.optInt(i) == 1,
                hourly.getJSONArray("precipitation_probability").optInt(i))
        }
        return WeatherForecast(city, System.currentTimeMillis(), temps.getDouble(currentIndex),
            hourly.getJSONArray("apparent_temperature").getDouble(currentIndex),
            days.first().high, days.first().low,
            daily.getJSONArray("temperature_2m_max").getDouble(0),
            daily.getJSONArray("temperature_2m_min").getDouble(0),
            hourly.getJSONArray("relative_humidity_2m").optInt(currentIndex),
            hourly.getJSONArray("wind_speed_10m").optDouble(currentIndex),
            daily.getJSONArray("uv_index_max").optDouble(1, 0.0),
            codes.optInt(currentIndex), dayFlags.optInt(currentIndex) == 1,
            daily.getJSONArray("sunrise").optLong(1),
            daily.getJSONArray("sunset").optLong(1), hours, days,
            "Open-Meteo · модельный прогноз")
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("User-Agent", "Fit3-App/0.6 (personal non-commercial)")
        }
        try {
            require(connection.responseCode == 200) { "Погода недоступна: HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            require(text.length < 1_000_000) { "Слишком большой ответ погоды" }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
