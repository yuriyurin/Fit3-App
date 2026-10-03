package io.github.yuriyurin.fit3companion.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.Executors
import io.github.yuriyurin.fit3companion.MainActivity
import io.github.yuriyurin.fit3companion.R

/** Owns the one BLE session independently of Activity recreation and screen state. */
class Fit3ConnectionService : Service() {
    companion object {
        const val ACTION_CONNECT = "io.github.yuriyurin.fit3companion.CONNECT"
        @Volatile var active: Fit3ConnectionService? = null
            private set
        private const val CHANNEL = "fit3_connection"
        private const val PREFS = "fit3_connection"
        private const val ADDRESS = "watch_address"
    }

    inner class LocalBinder : Binder() { val service: Fit3ConnectionService get() = this@Fit3ConnectionService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ble: Fit3BleClient
    private var snapshot = BleSnapshot()
    private var listener: ((BleSnapshot) -> Unit)? = null
    private var foreground = false
    private val diagnosticWriter = Executors.newSingleThreadExecutor()
    private val weatherWorker = Executors.newSingleThreadExecutor()
    private val faceWorker = Executors.newSingleThreadExecutor()
    @Volatile private var faceDownloadBusy = false
    private lateinit var weatherRepository: Fit3WeatherRepository
    private var weatherForecast: WeatherForecast? = null
    private var weatherStatus = "Выберите город"
    private var weatherBusy = false
    private var weatherLocationRequested = false
    private var weatherAddCurrentPending = false
    private var lastProtocolEntry: String? = null
    private var adbLogEnabled = false
    private var lastPeriodicHealthPoll = 0L
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (snapshot.sapReady) handler.post { ble.syncTime() }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (snapshot.sapReady && !snapshot.refreshing &&
                now - lastPeriodicHealthPoll >= 5 * 60_000L) {
                lastPeriodicHealthPoll = now
                ble.refresh(manual = false)
            }
            if (snapshot.sapReady) refreshWeatherIfNeeded()
            maybeAutoBackup()
            handler.postDelayed(this, 60_000)
        }
    }
    private val mediaRefresh = object : Runnable {
        override fun run() {
            if (snapshot.sapReady) ble.syncMediaIfChanged()
            handler.postDelayed(this, 5_000)
        }
    }

    private fun maybeAutoBackup() {
        val prefs = getSharedPreferences("fit3_auto_backup", MODE_PRIVATE)
        val hours = prefs.getInt("hours", 0)
        if (hours <= 0 || !snapshot.sapReady) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0) < hours * 3_600_000L) return
        val copy = snapshot
        diagnosticWriter.execute {
            try {
                val json = Fit3BackupStore.exportJson(this, copy)
                val folder = prefs.getString("folder", null)
                if (folder == null) {
                    val directory = java.io.File(filesDir, "backups")
                    directory.mkdirs()
                    java.io.File(directory, "Fit3-App-auto.json").writeText(json)
                } else {
                    val document = prefs.getString("document", null)?.let(Uri::parse) ?: run {
                        val tree = Uri.parse(folder)
                        val parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree))
                        DocumentsContract.createDocument(contentResolver, parent,
                            "application/json", "Fit3-App-auto.json")?.also {
                            prefs.edit().putString("document", it.toString()).apply()
                        } ?: error("Не удалось создать файл в выбранной папке")
                    }
                    contentResolver.openOutputStream(document, "wt")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось записать копию")
                }
                prefs.edit().putLong("last", now).apply()
            } catch (_: Exception) {
                // Permission can be revoked in Android settings. Keep the last good backup.
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        weatherRepository = Fit3WeatherRepository(this)
        weatherForecast = weatherRepository.cachedForecast()
        weatherStatus = if (weatherRepository.selectedCity() == null) "Выберите город" else
            if (weatherForecast == null) "Ожидаем погоду" else weatherForecast!!.source
        adbLogEnabled = getSharedPreferences("fit3_debug", MODE_PRIVATE).getBoolean("adb_log", false)
        val clockFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(clockReceiver, clockFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(clockReceiver, clockFilter)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, io.github.yuriyurin.fit3companion.AppStrings.translate("Соединение с Galaxy Fit3"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            })
        var wasSapReady = false
        var wasReadyForSettings = false
        var wasReadyForNotifications = false
        ble = Fit3BleClient(applicationContext, onUpdate = { current ->
            snapshot = current.copy(weather = weatherForecast, weatherStatus = weatherStatus,
                weatherLocationRequested = weatherLocationRequested)
            val pending = if (adbLogEnabled) current.protocolLog.takeWhile { it != lastProtocolEntry }
                .asReversed() else emptyList()
            if (pending.isNotEmpty()) {
                diagnosticWriter.execute {
                    val log = java.io.File(filesDir, "protocol-monitor.log")
                    if (log.length() > 4_000_000) log.writeText("Журнал ограничен 4 МБ; ранние записи удалены.\n")
                    val now = System.currentTimeMillis()
                    log.appendText(pending.joinToString("") { "$now $it\n" })
                }
            }
            lastProtocolEntry = current.protocolLog.firstOrNull()
            listener?.invoke(snapshot)
            if (foreground) getSystemService(NotificationManager::class.java)
                .notify(1, notification(if (weatherLocationRequested)
                    "Откройте приложение и выберите город для погоды" else current.status))
            if (current.sapReady && !wasSapReady) {
                handler.postDelayed({
                    weatherForecast?.let(ble::sendWeather)
                    refreshWeatherIfNeeded()
                }, 1600)
            }
            val readyForSettings = current.sapReady && current.setupStage in
                setOf(SetupStage.IDLE, SetupStage.COMPLETE)
            val readyForNotifications = readyForSettings && current.notificationCapabilityReady
            if (readyForNotifications && !wasReadyForNotifications) {
                handler.postDelayed({
                    if (snapshot.sapReady && snapshot.notificationCapabilityReady &&
                        snapshot.setupStage in setOf(SetupStage.IDLE, SetupStage.COMPLETE))
                        Fit3NotificationListener.active?.resyncToWatch()
                }, 300)
            }
            wasReadyForNotifications = readyForNotifications
            if (readyForSettings) {
                val restore = getSharedPreferences("fit3_local_backup", MODE_PRIVATE)
                if (restore.getBoolean("apply_on_connect", false)) {
                    handler.postDelayed({
                        if (snapshot.sapReady && snapshot.setupStage in
                            setOf(SetupStage.IDLE, SetupStage.COMPLETE) &&
                            restore.getBoolean("apply_on_connect", false)) {
                            restore.edit().putBoolean("apply_on_connect", false).apply()
                            ble.restoreLocalBackup()
                        }
                    }, 1200)
                }
            }
            if (readyForSettings && !wasReadyForSettings) {
                // Cache the watch configuration so a full JSON backup can be made while offline.
                handler.postDelayed({ if (snapshot.sapReady && snapshot.setupStage in
                    setOf(SetupStage.IDLE, SetupStage.COMPLETE)) ble.requestFullSettings() }, 2_000)
                handler.postDelayed({ if (snapshot.sapReady && snapshot.setupStage in
                    setOf(SetupStage.IDLE, SetupStage.COMPLETE)) ble.requestWidgets() }, 2_400)
                handler.postDelayed({ if (snapshot.sapReady && snapshot.setupStage in
                    setOf(SetupStage.IDLE, SetupStage.COMPLETE)) ble.requestApps() }, 2_800)
                handler.postDelayed({ if (snapshot.sapReady && snapshot.setupStage in
                    setOf(SetupStage.IDLE, SetupStage.COMPLETE)) ble.requestQuickPanel() }, 3_200)
            }
            wasReadyForSettings = readyForSettings
            wasSapReady = current.sapReady
        }, onWatchNotificationCommand = { key, command ->
            handler.post { Fit3NotificationListener.active?.handleWatchCommand(key, command) }
        }, onWeatherRequest = { kind ->
            handler.post {
                if (kind == 0) {
                    // Samsung first marks an add-current-location request, then sends WEATHER_INFO_REQ.
                    weatherAddCurrentPending = true
                } else if (kind == 1 && weatherAddCurrentPending && weatherRepository.selectedCity() == null) {
                    weatherAddCurrentPending = false
                    weatherLocationRequested = true
                    publishWeather()
                } else if (kind == 1) {
                    weatherAddCurrentPending = false
                    weatherForecast?.let(ble::sendWeather)
                    refreshWeatherIfNeeded(force = true)
                }
            }
        })
        handler.postDelayed(refresh, 60_000)
        handler.postDelayed(mediaRefresh, 5_000)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = if (intent?.action == ACTION_CONNECT)
            intent.getStringExtra(ADDRESS) else getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(ADDRESS, null)
        if (address.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification("Подключение к Galaxy Fit3…"))
        foreground = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ADDRESS, address).apply()
        if (!snapshot.connected && !snapshot.sapReady) ble.connect(address)
        return START_STICKY
    }

    private fun notification(status: String): Notification {
        val showConnectionStatus = getSharedPreferences("fit3_notifications", MODE_PRIVATE)
            .getBoolean("show_connection_status", false)
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fit3)
            .setContentTitle("Fit3 App")
            .setContentText(if (showConnectionStatus || weatherLocationRequested)
                io.github.yuriyurin.fit3companion.AppStrings.translate(status) else "")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    fun updateConnectionNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val name = io.github.yuriyurin.fit3companion.AppStrings.translate("Соединение с Galaxy Fit3")
        manager.getNotificationChannel(CHANNEL)?.let { channel ->
            if (channel.name.toString() != name) {
                channel.name = name
                manager.createNotificationChannel(channel)
            }
        }
        if (foreground) getSystemService(NotificationManager::class.java).notify(1,
            notification(if (weatherLocationRequested)
                "Откройте приложение и выберите город для погоды" else snapshot.status))
    }

    fun observe(callback: ((BleSnapshot) -> Unit)?) {
        listener = callback
        callback?.invoke(snapshot)
    }

    private fun publishWeather() {
        snapshot = snapshot.copy(weather = weatherForecast, weatherStatus = weatherStatus,
            weatherLocationRequested = weatherLocationRequested)
        listener?.invoke(snapshot)
        if (foreground) getSystemService(NotificationManager::class.java).notify(1,
            notification(if (weatherLocationRequested) "Откройте приложение и выберите город для погоды"
                else snapshot.status))
    }

    fun setWeatherCity(city: WeatherCity) {
        weatherRepository.selectCity(city)
        weatherForecast = null
        weatherStatus = "Обновляем погоду…"
        weatherLocationRequested = false
        publishWeather()
        refreshWeatherIfNeeded(force = true)
    }

    fun consumeWeatherLocationRequest() {
        weatherLocationRequested = false
        publishWeather()
    }

    fun searchWeatherCities(query: String, callback: (List<WeatherCity>) -> Unit) {
        weatherWorker.execute {
            val results = runCatching { weatherRepository.search(query) }.getOrDefault(emptyList())
            handler.post { callback(results) }
        }
    }

    fun refreshWeatherIfNeeded(force: Boolean = false) {
        val city = weatherRepository.selectedCity() ?: return
        if (weatherBusy) return
        if (!force && weatherForecast != null &&
            System.currentTimeMillis() - weatherForecast!!.updatedAt < 60 * 60_000L) {
            return
        }
        weatherBusy = true
        weatherWorker.execute {
            val outcome = runCatching { weatherRepository.forecast(city) }
            handler.post {
                weatherBusy = false
                if (weatherRepository.selectedCity()?.id != city.id) return@post
                outcome.onSuccess { fresh ->
                    weatherRepository.save(fresh)
                    weatherForecast = fresh
                    weatherStatus = fresh.source
                    publishWeather()
                    if (snapshot.sapReady) ble.sendWeather(fresh)
                }.onFailure { error ->
                    weatherStatus = if (weatherForecast == null) "Не удалось загрузить погоду" else
                        "Показан последний прогноз"
                    publishWeather()
                }
            }
        }
    }

    fun startScan() = ble.startScan()
    fun refreshNow() = ble.refresh()
    fun requestHealthNow() = ble.requestHealthSnapshot()
    fun setActivityGoals(goals: io.github.yuriyurin.fit3companion.protocol.ActivityGoals) = ble.setActivityGoals(goals)
    fun requestHealthCapabilityForDebug() = ble.requestHealthCapabilityForDebug()
    fun reloadHealthFromStore() = ble.reloadHealthFromStore()
    fun requestInstalledFaces() = ble.requestInstalledFaces()
    fun syncWatchFaces() = ble.syncWatchFaces()
    fun selectInstalledFace(id: Int, sampler: Int) = ble.selectInstalledFace(id, sampler)
    fun deleteInstalledFace(id: Int, sampler: Int) = ble.deleteInstalledFace(id, sampler)
    fun installOfficialFace(face: OfficialFace, style: OfficialFaceStyle) {
        if (faceDownloadBusy || snapshot.faceInstallPending) return
        if (!snapshot.sapReady) { ble.reportFaceDownload("Сначала подключите часы"); return }
        faceDownloadBusy = true
        ble.reportFaceDownload("Загружаем циферблат из магазина…")
        faceWorker.execute {
            val outcome = runCatching { FacePackageRepository(this).download(face, style) }
            handler.post {
                faceDownloadBusy = false
                outcome.onSuccess { ble.installFace(it) }
                    .onFailure { ble.reportFaceDownload("Не удалось загрузить циферблат: ${it.message}") }
            }
        }
    }
    fun installLocalFace(binary: ByteArray, sampler: Int) {
        if (faceDownloadBusy || snapshot.faceInstallPending) return
        if (!snapshot.sapReady) { ble.reportFaceDownload("Сначала подключите часы"); return }
        faceDownloadBusy = true
        ble.reportFaceDownload("Повторно проверяем BIN перед передачей…")
        faceWorker.execute {
            val outcome = runCatching {
                val prepared = FaceBinValidator.prepareForInstallation(binary)
                val report = prepared.report
                require(sampler in report.styles) { "Стиль $sampler отсутствует внутри BIN" }
                val saved = CustomFaceLibrary(this).add(binary, report.canonicalFileName)
                InstalledFacePreviewStore(this).prepare(binary, saved.key, saved.name, listOf(sampler))
                FacePackageRepository.Payload(report.faceId, sampler,
                    report.canonicalFileName, prepared.binary, saved.key, prepared.repairedVariantCount)
            }
            handler.post {
                faceDownloadBusy = false
                outcome.onSuccess { ble.installFace(it) }
                    .onFailure { ble.reportFaceDownload("BIN отклонён проверкой: ${it.message}") }
            }
        }
    }
    fun requestFullSettings() = ble.requestFullSettings()
    fun setBrightness(value: Int) = ble.setBrightness(value)
    fun setAutoBrightness(value: Boolean) = ble.setAutoBrightness(value)
    fun setAlwaysOnDisplay(value: Boolean) = ble.setAlwaysOnDisplay(value)
    fun setRaiseToWake(value: Boolean) = ble.setRaiseToWake(value)
    fun setTouchToWake(value: Boolean) = ble.setTouchToWake(value)
    fun setRightWrist(value: Boolean) = ble.setRightWrist(value)
    fun setButtonOnRight(value: Boolean) = ble.setButtonOnRight(value)
    fun setAutoMediaControl(value: Boolean) = ble.setAutoMediaControl(value)
    fun setSleepMode(value: Boolean) = ble.setSleepMode(value)
    fun setTheatreMode(value: Boolean) = ble.setTheatreMode(value)
    fun setScreenTimeout(seconds: Int) = ble.setScreenTimeout(seconds)
    fun requestWidgets() = ble.requestWidgets()
    fun setWidgets(ids: List<Int>) = ble.setWidgets(ids)
    fun requestApps() = ble.requestApps()
    fun setApps(ids: List<Int>) = ble.setApps(ids)
    fun requestQuickPanel() = ble.requestQuickPanel()
    fun setQuickPanel(ids: List<Int>) = ble.setQuickPanel(ids)
    fun setQuickMessages(messages: List<String>) = ble.setQuickMessages(messages)
    fun syncTime() = ble.syncTime()
    fun syncBandLanguage() = ble.syncBandLanguage()
    fun clearProtocolLog() {
        ble.clearProtocolLog()
        diagnosticWriter.execute { java.io.File(filesDir, "protocol-monitor.log").writeText("") }
    }
    fun setAdbLogEnabled(enabled: Boolean) {
        adbLogEnabled = enabled
        getSharedPreferences("fit3_debug", MODE_PRIVATE).edit().putBoolean("adb_log", enabled).apply()
    }
    fun saveLocalBackup() = ble.saveLocalBackup()
    fun restoreLocalBackup() = ble.restoreLocalBackup()
    fun sendTestNotification() = ble.sendTestNotification()
    fun forwardNotification(
        appName: String,
        title: String,
        body: String,
        packageName: String,
        notificationKey: String? = null,
        popup: Boolean = true,
        category: String? = null,
        canReply: Boolean = false,
        appIconPng: ByteArray? = null,
    ) {
        handler.post {
            ble.forwardNotification(appName, title, body, packageName, notificationKey, popup,
                category, canReply, appIconPng)
        }
    }
    fun removeForwardedNotification(key: String) = handler.post { ble.removeForwardedNotification(key) }
    fun clearForwardedNotifications() = handler.post { ble.clearForwardedNotifications() }

    fun disconnectWatch() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(ADDRESS).apply()
        ble.disconnect()
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    override fun onDestroy() {
        if (active === this) active = null
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(mediaRefresh)
        try { unregisterReceiver(clockReceiver) } catch (_: Exception) { }
        ble.close()
        diagnosticWriter.shutdown()
        weatherWorker.shutdown()
        faceWorker.shutdown()
        super.onDestroy()
    }
}
