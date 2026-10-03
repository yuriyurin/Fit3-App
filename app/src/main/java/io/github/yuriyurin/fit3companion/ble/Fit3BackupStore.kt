package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import io.github.yuriyurin.fit3companion.HomeDashboardLayout
import io.github.yuriyurin.fit3companion.protocol.Fit3OrderedItem
import io.github.yuriyurin.fit3companion.protocol.Fit3SettingsCodec
import org.json.JSONArray
import org.json.JSONObject

/** Local-only backup. No Samsung account/cloud dependency. */
object Fit3BackupStore {
    private const val PREFS = "fit3_local_backup"
    private const val KEY = "backup_v1"
    private const val WATCH_STATE_KEY = "last_watch_state"

    data class Backup(
        val settings: Fit3SettingsCodec.State,
        val widgets: List<Int>,
        val apps: List<Int>,
        val quickPanel: List<Int>,
        val quickMessages: List<String>,
    )

    fun save(context: Context, snapshot: BleSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, exportJson(context, snapshot)).apply()
    }

    /** Keeps the last confirmed watch configuration available for an offline export. */
    fun rememberWatchState(context: Context, snapshot: BleSnapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(WATCH_STATE_KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())
        val settings = root.optJSONObject("settings") ?: JSONObject()
        putSettings(settings, snapshot.fullSettings)
        if (settings.length() > 0) root.put("settings", settings)
        if (snapshot.widgets.isNotEmpty()) root.put("widgets",
            JSONArray(snapshot.widgets.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
        if (snapshot.apps.isNotEmpty()) root.put("apps",
            JSONArray(snapshot.apps.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
        if (snapshot.quickPanel.isNotEmpty()) root.put("quickPanel",
            JSONArray(snapshot.quickPanel.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
        if (snapshot.quickMessages.isNotEmpty()) root.put("quickMessages", JSONArray(snapshot.quickMessages))
        prefs.edit().putString(WATCH_STATE_KEY, root.toString()).apply()
    }

    fun exportJson(context: Context, snapshot: BleSnapshot): String {
        val cachedWatch = runCatching {
            JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(WATCH_STATE_KEY, null) ?: "{}")
        }.getOrDefault(JSONObject())
        val settings = cachedWatch.optJSONObject("settings") ?: JSONObject()
        putSettings(settings, snapshot.fullSettings)
        fun rememberedList(name: String, current: List<Int>): JSONArray =
            if (current.isNotEmpty()) JSONArray(current) else cachedWatch.optJSONArray(name) ?: JSONArray()
        val cachedHealth = Fit3HealthStore.load(context)
        val health = if ((snapshot.health.lastSyncMillis ?: 0) >= (cachedHealth.lastSyncMillis ?: 0))
            snapshot.health else cachedHealth
        val preferences = JSONObject().apply {
            val goals = Fit3ActivityGoalStore.load(context).goals
            put("stepGoal", goals.steps)
            put("activityGoals", JSONObject().put("steps", goals.steps)
                .put("minutes", goals.minutes).put("calories", goals.calories))
            val appearance = context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE)
            put("theme", appearance.getString("theme", "system"))
            put("accent", appearance.getString("accent", "adaptive"))
            put("language", io.github.yuriyurin.fit3companion.AppLanguage.selection(context))
            put("bandLanguage", io.github.yuriyurin.fit3companion.BandLanguage.selection(context))
            put("homeTiles", appearance.getString("home_tiles", HomeDashboardLayout.encode(HomeDashboardLayout.default)))
            put("homeDashboardEnabled", appearance.getBoolean("home_dashboard_enabled", false))
            val notifications = context.getSharedPreferences("fit3_notifications", Context.MODE_PRIVATE)
            put("forwardNotifications", notifications.getBoolean("enabled", true))
            put("showConnectionStatus", notifications.getBoolean("show_connection_status", false))
            context.getSharedPreferences("fit3_weather", Context.MODE_PRIVATE)
                .getString("city", null)?.let { put("weatherCity", JSONObject(it)) }
            put("autoBackupHours", context.getSharedPreferences("fit3_auto_backup", Context.MODE_PRIVATE)
                .getInt("hours", 0))
        }
        val daily = JSONObject().apply {
            context.getSharedPreferences("fit3_daily_steps", Context.MODE_PRIVATE).all.forEach { (day, count) ->
                if (count is Int && day.toLongOrNull() != null && count in 0..250_000) put(day, count)
            }
        }
        val root = JSONObject().apply {
            put("version", 3)
            put("createdAt", System.currentTimeMillis())
            put("settings", settings)
            put("widgets", rememberedList("widgets", snapshot.widgets.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
            put("apps", rememberedList("apps", snapshot.apps.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
            put("quickPanel", rememberedList("quickPanel", snapshot.quickPanel.sortedBy(Fit3OrderedItem::order).map(Fit3OrderedItem::id)))
            put("quickMessages", if (snapshot.quickMessages.isNotEmpty()) JSONArray(snapshot.quickMessages)
                else cachedWatch.optJSONArray("quickMessages") ?: JSONArray())
            put("health", Fit3HealthStore.export(context, health))
            put("dailySteps", daily)
            put("preferences", preferences)
        }
        return root.toString(2)
    }

    private fun putSettings(target: JSONObject, s: Fit3SettingsCodec.State) {
        fun put(name: String, value: Any?) { if (value != null) target.put(name, value) }
        put("brightness", s.brightness)
        put("autoBrightness", s.autoBrightness)
        put("alwaysOnDisplay", s.alwaysOnDisplay)
        put("raiseToWake", s.raiseToWake)
        put("touchToWake", s.touchToWake)
        put("autoMediaControl", s.autoMediaControl)
        put("timeoutSeconds", s.timeoutSeconds)
        put("doublePressAction", s.doublePressAction)
        put("statusIndicator", s.statusIndicator)
        put("vibrationIntensity", s.vibrationIntensity)
        put("sleepMode", s.sleepMode)
        put("theatreMode", s.theatreMode)
        put("rightWrist", s.rightWrist)
        put("buttonOnRight", s.buttonOnRight)
    }

    fun importJson(context: Context, raw: String): Boolean = try {
        require(raw.length <= 64_000_000) { "Слишком большая копия" }
        val root = JSONObject(raw)
        require(root.optInt("version") in 1..3 && root.has("settings"))
        val restoredHealth = root.optJSONObject("health")?.let(Fit3HealthStore::decode)
        val daily = root.optJSONObject("dailySteps")
        require(daily == null || daily.length() <= 3650)
        val preferences = root.optJSONObject("preferences")
        val restoredGoals = preferences?.optJSONObject("activityGoals")?.let {
            io.github.yuriyurin.fit3companion.protocol.ActivityGoals(it.getInt("steps"),
                it.getInt("minutes"), it.getInt("calories")).also { goals -> require(goals.valid()) }
        }
        preferences?.optJSONObject("weatherCity")?.let(WeatherCity::from)
        if (restoredHealth != null) Fit3HealthStore.import(context, root.getJSONObject("health"))
        if (daily != null) {
            val editor = context.getSharedPreferences("fit3_daily_steps", Context.MODE_PRIVATE).edit().clear()
            daily.keys().forEach { key ->
                val count = daily.optInt(key, -1)
                if (key.toLongOrNull() != null && count in 0..250_000) editor.putInt(key, count)
            }
            editor.commit()
        }
        if (preferences != null) {
            if (restoredGoals != null) Fit3ActivityGoalStore.save(context, restoredGoals)
            else preferences.optInt("stepGoal", 0).takeIf { it in 1000..100_000 }?.let {
                context.getSharedPreferences("fit3_activity", Context.MODE_PRIVATE).edit()
                    .putInt("step_goal", it).commit()
            }
            val appearance = context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE).edit()
            preferences.optString("theme").takeIf { it in setOf("system", "dark", "light", "amoled") }
                ?.let { appearance.putString("theme", it) }
            preferences.optString("accent").takeIf { it.isNotBlank() && it.length <= 40 }
                ?.let { appearance.putString("accent", it) }
            preferences.optString("language").takeIf { it in setOf("system", "ru", "en") }
                ?.let { appearance.putString("language", it) }
            preferences.optString("homeTiles").takeIf { it.isNotBlank() && it.length <= 1_000 }
                ?.let { appearance.putString("home_tiles", HomeDashboardLayout.encode(HomeDashboardLayout.decode(it))) }
            appearance.putBoolean("home_dashboard_enabled", preferences.optBoolean("homeDashboardEnabled", false))
            appearance.commit()
            preferences.optString("bandLanguage").takeIf {
                io.github.yuriyurin.fit3companion.protocol.Fit3Languages.isSelection(it)
            }?.let { io.github.yuriyurin.fit3companion.BandLanguage.set(context, it) }
            val notificationPrefs = context.getSharedPreferences("fit3_notifications", Context.MODE_PRIVATE).edit()
            if (preferences.has("forwardNotifications")) notificationPrefs.putBoolean("enabled",
                preferences.optBoolean("forwardNotifications"))
            if (preferences.has("showConnectionStatus")) notificationPrefs.putBoolean("show_connection_status",
                preferences.optBoolean("showConnectionStatus"))
            notificationPrefs.commit()
            preferences.optJSONObject("weatherCity")?.let { city ->
                WeatherCity.from(city)
                context.getSharedPreferences("fit3_weather", Context.MODE_PRIVATE).edit()
                    .putString("city", city.toString()).remove("forecast").commit()
            }
            preferences.optInt("autoBackupHours", 0).takeIf { it in setOf(0, 3, 6, 12, 24, 72, 168) }
                ?.let { context.getSharedPreferences("fit3_auto_backup", Context.MODE_PRIVATE).edit()
                    .putInt("hours", it).remove("folder").remove("document").commit() }
        }
        val watchState = JSONObject().put("settings", root.getJSONObject("settings"))
        for (name in listOf("widgets", "apps", "quickPanel", "quickMessages"))
            root.optJSONArray(name)?.let { watchState.put(name, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, raw).putString(WATCH_STATE_KEY, watchState.toString()).commit()
    } catch (_: Exception) {
        false
    }

    fun load(context: Context): Backup? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val s = root.optJSONObject("settings") ?: JSONObject()
            Backup(
                settings = Fit3SettingsCodec.State(
                    brightness = s.optNullableInt("brightness"),
                    autoBrightness = s.optNullableBoolean("autoBrightness"),
                    alwaysOnDisplay = s.optNullableBoolean("alwaysOnDisplay"),
                    raiseToWake = s.optNullableBoolean("raiseToWake"),
                    touchToWake = s.optNullableBoolean("touchToWake"),
                    autoMediaControl = s.optNullableBoolean("autoMediaControl"),
                    timeoutSeconds = s.optNullableInt("timeoutSeconds"),
                    doublePressAction = s.optNullableInt("doublePressAction"),
                    statusIndicator = s.optNullableInt("statusIndicator"),
                    vibrationIntensity = s.optNullableInt("vibrationIntensity"),
                    sleepMode = s.optNullableBoolean("sleepMode"),
                    theatreMode = s.optNullableBoolean("theatreMode"),
                    rightWrist = s.optNullableBoolean("rightWrist"),
                    buttonOnRight = s.optNullableBoolean("buttonOnRight"),
                ),
                widgets = root.optJSONArray("widgets").toIntList(),
                apps = root.optJSONArray("apps").toIntList(),
                quickPanel = root.optJSONArray("quickPanel").toIntList(),
                quickMessages = root.optJSONArray("quickMessages").toStringList(),
            )
        } catch (_: Exception) { null }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value != null) put(key, value)
    }
    private fun JSONObject.optNullableInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optNullableBoolean(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
    private fun JSONArray?.toIntList(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }
    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
