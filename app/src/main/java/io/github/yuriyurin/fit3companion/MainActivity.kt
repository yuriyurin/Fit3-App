package io.github.yuriyurin.fit3companion

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.NotificationManager
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import io.github.yuriyurin.fit3companion.protocol.Fit3SapCodec
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.provider.Settings
import android.os.IBinder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest
import java.util.regex.Pattern
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.yuriyurin.fit3companion.ble.BleSnapshot
import io.github.yuriyurin.fit3companion.ble.Fit3BackupStore
import io.github.yuriyurin.fit3companion.ble.Fit3BleClient
import io.github.yuriyurin.fit3companion.ble.Fit3ConnectionService
import io.github.yuriyurin.fit3companion.ble.FaceBinValidator
import io.github.yuriyurin.fit3companion.ble.WeatherCity
import io.github.yuriyurin.fit3companion.ble.Fit3NotificationListener
import io.github.yuriyurin.fit3companion.ble.WatchCandidate
import io.github.yuriyurin.fit3companion.ble.OfficialFace
import io.github.yuriyurin.fit3companion.ble.OfficialFaceStyle
import io.github.yuriyurin.fit3companion.ble.OfficialFaceCatalog
import io.github.yuriyurin.fit3companion.ble.knownFaceName
import io.github.yuriyurin.fit3companion.protocol.Fit3WidgetsCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3AppsCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3QuickPanelCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3OrderedItem
import io.github.yuriyurin.fit3companion.protocol.ActivityGoals
import io.github.yuriyurin.fit3companion.ble.Fit3ActivityGoalStore
import io.github.yuriyurin.fit3companion.ble.GoalSyncState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", page.name)
        super.onSaveInstanceState(outState)
    }

    private var connectionService: Fit3ConnectionService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connectionService = (binder as Fit3ConnectionService.LocalBinder).service
            connectionService?.observe {
                bleSnapshot = it
                stepGoal = it.activityGoals.steps
                it.health.steps?.let { count ->
                    val day = it.health.stepDay ?: return@let
                    if (count != dailySteps[day]) {
                        dailySteps = dailySteps + (day to count)
                        getSharedPreferences("fit3_daily_steps", MODE_PRIVATE).edit()
                            .putInt(day.toString(), count).apply()
                    }
                }
            }
            val savedAddress = getSharedPreferences("fit3_connection", MODE_PRIVATE)
                .getString("watch_address", null)
            if (savedAddress.isNullOrBlank()) {
                if (page == Page.HOME) page = Page.DISCOVERY
                scanWithPermission()
            } else if (!bleSnapshot.connected && bleSnapshot.status == "Не подключено") {
                connectWatch(savedAddress)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            connectionService = null
            bleSnapshot = bleSnapshot.copy(connected = false, sapReady = false,
                status = "Сервис связи остановлен")
        }
    }
    private var bleSnapshot by mutableStateOf(BleSnapshot())
    private var page by mutableStateOf(Page.HOME)
    private var notificationForwarding by mutableStateOf(false)
    private var notificationAccess by mutableStateOf(false)
    private var notificationReadingEnabled by mutableStateOf(true)
    private var notificationPostPermission by mutableStateOf(false)
    private var connectionStatusDetails by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var themeMode by mutableStateOf("system")
    private var accentMode by mutableStateOf("adaptive")
    private var languageMode by mutableStateOf("system")
    private var homeTiles by mutableStateOf(HomeDashboardLayout.default)
    private var homeDashboardEnabled by mutableStateOf(false)
    private var adbLogEnabled by mutableStateOf(false)
    private var stepGoal by mutableStateOf(6000)
    private var dailySteps by mutableStateOf<Map<Long, Int>>(emptyMap())
    private var backupMessage by mutableStateOf("")
    private var autoBackupHours by mutableStateOf(0)
    private var autoBackupFolder by mutableStateOf<String?>(null)
    private var firstRunPermissions = false
    private var showListenerAccessPrompt by mutableStateOf(false)
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(Fit3BackupStore.exportJson(this, bleSnapshot).toByteArray(Charsets.UTF_8))
            } ?: error("Не удалось открыть файл")
            backupMessage = "Копия сохранена"
        } catch (error: Exception) { backupMessage = "Не удалось сохранить: ${error.message}" }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Не удалось прочитать файл")
            if (!Fit3BackupStore.importJson(this, raw)) error("Неверный формат копии")
            dailySteps = getSharedPreferences("fit3_daily_steps", MODE_PRIVATE).all.mapNotNull { (key, value) ->
                (value as? Int)?.let { key.toLongOrNull()?.let { day -> day to it } }
            }.toMap()
            stepGoal = Fit3ActivityGoalStore.load(this).goals.steps
            getSharedPreferences("fit3_appearance", MODE_PRIVATE).also {
                themeMode = it.getString("theme", "system") ?: "system"
                accentMode = it.getString("accent", "adaptive") ?: "adaptive"
                homeTiles = HomeDashboardLayout.decode(it.getString("home_tiles", null))
                homeDashboardEnabled = it.getBoolean("home_dashboard_enabled", false)
            }
            AppLanguage.set(this, getSharedPreferences("fit3_appearance", MODE_PRIVATE)
                .getString("language", "system") ?: "system")
            languageMode = AppLanguage.selection(this)
            connectionService?.syncBandLanguage()
            if (Build.VERSION.SDK_INT < 33) recreate()
            getSharedPreferences("fit3_notifications", MODE_PRIVATE).also {
                notificationForwarding = it.getBoolean("enabled", true)
                connectionStatusDetails = it.getBoolean("show_connection_status", false)
            }
            autoBackupHours = getSharedPreferences("fit3_auto_backup", MODE_PRIVATE).getInt("hours", 0)
            autoBackupFolder = null
            bleSnapshot = bleSnapshot.copy(health = io.github.yuriyurin.fit3companion.ble.Fit3HealthStore.load(this))
            connectionService?.reloadHealthFromStore()
            if (bleSnapshot.sapReady) {
                connectionService?.restoreLocalBackup()
                backupMessage = "Данные восстановлены; настройки браслета отправлены"
            } else {
                getSharedPreferences("fit3_local_backup", MODE_PRIVATE).edit()
                    .putBoolean("apply_on_connect", true).apply()
                backupMessage = "Данные восстановлены; настройки браслета применятся после подключения"
            }
        } catch (error: Exception) { backupMessage = "Не удалось восстановить: ${error.message}" }
    }
    private val chooseBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            autoBackupFolder = uri.toString()
            getSharedPreferences("fit3_auto_backup", MODE_PRIVATE).edit()
                .putString("folder", uri.toString()).remove("document").apply()
            backupMessage = "Папка для автоматических копий выбрана"
        } catch (error: Exception) { backupMessage = "Нет доступа к папке: ${error.message}" }
    }
    private var firmwareFile by mutableStateOf<FirmwareFile?>(null)
    private val chooseFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectFirmware(uri)
    }
    private val customFaceLibrary by lazy { io.github.yuriyurin.fit3companion.ble.CustomFaceLibrary(this) }
    private var customFaces by mutableStateOf<List<io.github.yuriyurin.fit3companion.ble.CustomFace>>(emptyList())
    private var customImporting by mutableStateOf(false)
    private var customImportFailed by mutableStateOf(false)
    private var localFaceSelectionToken = 0
    private val chooseLocalFace = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectLocalFace(uri)
    }
    private val chooseCompanion = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val found = if (Build.VERSION.SDK_INT >= 33)
                result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
            else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)
            }
            if (found != null) {
                connectWatch(found.device.address)
                page = Page.HOME
            } else bleSnapshot = bleSnapshot.copy(status = "Устройство не выбрано")
        }
    }

    private val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) connectionService?.startScan()
        else bleSnapshot = bleSnapshot.copy(status = "Разрешите доступ к устройствам рядом")
        if (firstRunPermissions) continueFirstRunPermissions()
    }
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPostPermission = granted
        if (firstRunPermissions) finishFirstRunPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.initialize(this)
        languageMode = AppLanguage.selection(this)
        page = savedInstanceState?.getString("screen")?.let { saved ->
            Page.entries.firstOrNull { it.name == saved }
        } ?: Page.HOME
        serviceBound = bindService(Intent(this, Fit3ConnectionService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE)
        notificationForwarding = getSharedPreferences("fit3_notifications", MODE_PRIVATE)
            .getBoolean("enabled", false)
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).also {
            notificationReadingEnabled = it.getBoolean("read_enabled", true)
            connectionStatusDetails = it.getBoolean("show_connection_status", false)
        }
        getSharedPreferences("fit3_appearance", MODE_PRIVATE).also {
            themeMode = it.getString("theme", "system") ?: "system"
            accentMode = it.getString("accent", "adaptive") ?: "adaptive"
            homeTiles = HomeDashboardLayout.decode(it.getString("home_tiles", null))
            homeDashboardEnabled = it.getBoolean("home_dashboard_enabled", false)
        }
        adbLogEnabled = getSharedPreferences("fit3_debug", MODE_PRIVATE).getBoolean("adb_log", false)
        val savedGoals = Fit3ActivityGoalStore.load(this)
        stepGoal = savedGoals.goals.steps
        bleSnapshot = bleSnapshot.copy(activityGoals = savedGoals.goals,
            goalSyncState = if (savedGoals.pending) GoalSyncState.PENDING else GoalSyncState.LOCAL)
        dailySteps = getSharedPreferences("fit3_daily_steps", MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val day = key.toLongOrNull()
            val count = value as? Int
            if (day != null && count != null) day to count else null
        }.toMap()
        getSharedPreferences("fit3_auto_backup", MODE_PRIVATE).also {
            autoBackupHours = it.getInt("hours", 0)
            autoBackupFolder = it.getString("folder", null)
        }
        updateNotificationAccess()
        setContent {
            Fit3Theme(themeMode, accentMode) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalCustomFaceLibrary provides customFaceLibrary,
                    LocalInstalledFaceKeys provides bleSnapshot.customFaceKeys,
                    LocalCurrentFaceSampler provides bleSnapshot.currentFaceSampler) {
                CompanionScreen(
                    snapshot = bleSnapshot,
                    page = page,
                    onPage = {
                        page = it
                        if (bleSnapshot.connected) when (it) {
                            Page.ACTIVITY, Page.SLEEP -> connectionService?.requestHealthNow()
                            Page.FACE_GALLERY, Page.CUSTOM_FACES -> connectionService?.syncWatchFaces()
                            Page.WATCH_SETTINGS -> connectionService?.requestFullSettings()
                            Page.WIDGETS -> connectionService?.requestWidgets()
                            Page.APPS -> connectionService?.requestApps()
                            Page.QUICK_PANEL -> connectionService?.requestQuickPanel()
                            else -> Unit
                        }
                    },
                    onScan = ::scanWithPermission,
                    onSystemDiscover = ::discoverWithAndroid,
                    onConnect = ::connectWatch,
                    onDisconnect = { connectionService?.disconnectWatch() },
                    onRefresh = { connectionService?.refreshNow() },
                    onRefreshWeather = { connectionService?.refreshWeatherIfNeeded(force = true) },
                    onSearchWeatherCities = { query, callback ->
                        connectionService?.searchWeatherCities(query, callback) ?: callback(emptyList())
                    },
                    onChooseWeatherCity = { connectionService?.setWeatherCity(it) },
                    onConsumeWeatherLocationRequest = { connectionService?.consumeWeatherLocationRequest() },
                    onRequestHealth = { connectionService?.requestHealthNow() },
                    onRequestHealthCapabilityForDebug = { connectionService?.requestHealthCapabilityForDebug() },
                    stepGoal = stepGoal,
                    dailySteps = dailySteps,
                    homeTiles = homeTiles,
                    homeDashboardEnabled = homeDashboardEnabled,
                    onHomeTiles = { changed ->
                        homeTiles = changed
                        homeDashboardEnabled = true
                        getSharedPreferences("fit3_appearance", MODE_PRIVATE).edit()
                            .putString("home_tiles", HomeDashboardLayout.encode(changed))
                            .putBoolean("home_dashboard_enabled", true).apply()
                    },
                    onResetHomeTiles = {
                        homeTiles = HomeDashboardLayout.default
                        homeDashboardEnabled = false
                        getSharedPreferences("fit3_appearance", MODE_PRIVATE).edit()
                            .remove("home_tiles").putBoolean("home_dashboard_enabled", false).apply()
                    },
                    onActivityGoals = { goals ->
                        stepGoal = goals.steps
                        connectionService?.setActivityGoals(goals) ?: run {
                            Fit3ActivityGoalStore.save(this, goals)
                            bleSnapshot = bleSnapshot.copy(activityGoals = goals, goalSyncState = GoalSyncState.PENDING)
                        }
                    },
                    onRequestFaces = { connectionService?.syncWatchFaces() },
                    onSelectFace = { id, sampler -> connectionService?.selectInstalledFace(id, sampler) },
                    onDeleteFace = { id, sampler -> connectionService?.deleteInstalledFace(id, sampler) },
                    onInstallFace = { face, style -> connectionService?.installOfficialFace(face, style) },
                    onRequestFullSettings = { connectionService?.requestFullSettings() },
                    onBrightness = { connectionService?.setBrightness(it) },
                    onAutoBrightness = { connectionService?.setAutoBrightness(it) },
                    onAod = { connectionService?.setAlwaysOnDisplay(it) },
                    onRaiseWake = { connectionService?.setRaiseToWake(it) },
                    onTouchWake = { connectionService?.setTouchToWake(it) },
                    onRightWrist = { connectionService?.setRightWrist(it) },
                    onButtonOnRight = { connectionService?.setButtonOnRight(it) },
                    onAutoMedia = { connectionService?.setAutoMediaControl(it) },
                    onSleepMode = { connectionService?.setSleepMode(it) },
                    onTheatreMode = { connectionService?.setTheatreMode(it) },
                    onTimeout = { connectionService?.setScreenTimeout(it) },
                    onSyncTime = { connectionService?.syncTime() },
                    onSyncBandLanguage = { connectionService?.syncBandLanguage() },
                    onRequestWidgets = { connectionService?.requestWidgets() },
                    onSetWidgets = { connectionService?.setWidgets(it) },
                    onRequestApps = { connectionService?.requestApps() },
                    onSetApps = { connectionService?.setApps(it) },
                    onRequestQuickPanel = { connectionService?.requestQuickPanel() },
                    onSetQuickPanel = { connectionService?.setQuickPanel(it) },
                    onSetQuickMessages = { connectionService?.setQuickMessages(it) },
                    onClearProtocolLog = { connectionService?.clearProtocolLog() },
                    onCopyProtocolLog = {
                        val report = buildString {
                            appendLine("Fit3 App protocol log")
                            appendLine("Version: ${getInstalledVersionName(this@MainActivity)}")
                            appendLine("Connection: ${bleSnapshot.status}")
                            appendLine("Health: ${bleSnapshot.healthStatus}")
                            appendLine()
                            var remaining = 180_000
                            for (line in bleSnapshot.protocolLog) {
                                if (line.length > remaining) break
                                appendLine(line)
                                remaining -= line.length + 1
                            }
                        }
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Fit3 protocol log", report))
                    },
                    onCopyCompactProtocolLog = {
                        val report = buildCompactProtocolReport(
                            bleSnapshot, getInstalledVersionName(this@MainActivity))
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Fit3 short protocol log", report))
                    },
                    onSaveBackup = { createBackup.launch("Fit3-App-${LocalDate.now()}.json") },
                    onRestoreBackup = { openBackup.launch(arrayOf("application/json", "*/*")) },
                    backupMessage = backupMessage,
                    autoBackupHours = autoBackupHours,
                    autoBackupFolder = autoBackupFolder,
                    onAutoBackupHours = { hours ->
                        autoBackupHours = hours
                        getSharedPreferences("fit3_auto_backup", MODE_PRIVATE).edit().putInt("hours", hours).apply()
                    },
                    onChooseBackupFolder = { chooseBackupFolder.launch(null) },
                    showListenerAccessPrompt = showListenerAccessPrompt,
                    onListenerAccessPrompt = {
                        showListenerAccessPrompt = false
                        if (it) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    firmwareFile = firmwareFile,
                    onChooseFirmware = { chooseFirmware.launch(arrayOf("application/octet-stream", "*/*")) },
                    customLibrary = customFaceLibrary,
                    customFaces = customFaces,
                    customImporting = customImporting,
                    customImportFailed = customImportFailed,
                    onCustomFacesChanged = { customFaces = customFaceLibrary.list() },
                    onInstallCustomFace = { face, style ->
                        Thread {
                            val bytes = runCatching { customFaceLibrary.binary(face.key) }.getOrNull()
                            runOnUiThread {
                                if (bytes != null) connectionService?.installLocalFace(bytes, style)
                                else customImportFailed = true
                            }
                        }.start()
                    },
                    onChooseLocalFace = { chooseLocalFace.launch(arrayOf("application/octet-stream", "*/*")) },
                    notificationForwarding = notificationForwarding,
                    notificationAccess = notificationAccess,
                    notificationReadingEnabled = notificationReadingEnabled,
                    notificationPostPermission = notificationPostPermission,
                    connectionStatusDetails = connectionStatusDetails,
                    batteryUnrestricted = batteryUnrestricted,
                    onEnableNotifications = ::enableNotifications,
                    onOpenNotificationAccess = ::toggleNotificationReading,
                    onDisableNotifications = ::disableNotifications,
                    onRequestNotificationPermission = ::requestAppNotificationPermission,
                    onConnectionStatusDetails = ::updateConnectionStatusPreference,
                    onOpenAppPowerSettings = ::openAppPowerSettings,
                    onOpenBatteryOptimization = ::openBatteryOptimization,
                    onOpenBatterySaver = ::openBatterySaver,
                    onSendTestNotification = { connectionService?.sendTestNotification() },
                    themeMode = themeMode,
                    accentMode = accentMode,
                    languageMode = languageMode,
                    onLanguageMode = { selected ->
                        languageMode = selected
                        AppLanguage.set(this@MainActivity, selected)
                        connectionService?.syncBandLanguage()
                        if (Build.VERSION.SDK_INT < 33) recreate()
                        connectionService?.updateConnectionNotification()
                    },
                    onThemeMode = { selected, origin ->
                        changeThemeWithReveal(selected, origin)
                    },
                    onAccentMode = {
                        accentMode = it
                        getSharedPreferences("fit3_appearance", MODE_PRIVATE).edit().putString("accent", it).apply()
                    },
                    adbLogEnabled = adbLogEnabled,
                    onAdbLogEnabled = {
                        adbLogEnabled = it
                        connectionService?.setAdbLogEnabled(it)
                        getSharedPreferences("fit3_debug", MODE_PRIVATE).edit().putBoolean("adb_log", it).apply()
                    },
                )
                }
            }
        }
        if (!getSharedPreferences("fit3_onboarding", MODE_PRIVATE).getBoolean("permissions_asked", false)) {
            firstRunPermissions = true
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
            else continueFirstRunPermissions()
        }
    }

    private fun continueFirstRunPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPostPermission)
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else finishFirstRunPermissions()
    }

    private fun finishFirstRunPermissions() {
        firstRunPermissions = false
        getSharedPreferences("fit3_onboarding", MODE_PRIVATE).edit().putBoolean("permissions_asked", true).apply()
        if (!notificationAccess) showListenerAccessPrompt = true
    }

    private fun scanWithPermission() {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            connectionService?.startScan()
        } else requestPermissions.launch(permissions)
    }

    private fun discoverWithAndroid() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(CompanionDeviceManager::class.java) ?: return
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(".*(Fit3|R390).*", Pattern.CASE_INSENSITIVE))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).build()
        if (Build.VERSION.SDK_INT >= 33) {
            manager.associate(request, java.util.concurrent.Executor { command -> runOnUiThread(command) },
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(chooserLauncher: IntentSender) {
                        chooseCompanion.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }
                    override fun onFailure(error: CharSequence?) {
                        bleSnapshot = bleSnapshot.copy(
                            status = "Системный поиск: ${error ?: "устройство не найдено"}")
                    }
                })
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    runOnUiThread {
                        chooseCompanion.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                    }
                }
                override fun onFailure(error: CharSequence?) {
                    runOnUiThread { bleSnapshot = bleSnapshot.copy(
                        status = "Системный поиск: ${error ?: "устройство не найдено"}") }
                }
            }, null)
        }
    }

    private fun connectWatch(address: String) {
        val intent = Intent(this, Fit3ConnectionService::class.java)
            .setAction(Fit3ConnectionService.ACTION_CONNECT)
            .putExtra("watch_address", address)
        startForegroundService(intent)
    }

    private fun updateNotificationAccess() {
        notificationAccess = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, Fit3NotificationListener::class.java))
        notificationPostPermission = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun changeThemeWithReveal(selected: String, origin: Offset) {
        if (selected == themeMode) return
        val root = window.decorView as? android.view.ViewGroup
        if (root == null || root.width <= 0 || root.height <= 0) {
            themeMode = selected
        } else {
            val oldFrame = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(AndroidCanvas(oldFrame))
            val centerX = origin.x.coerceIn(0f, root.width.toFloat())
            val centerY = origin.y.coerceIn(0f, root.height.toFloat())
            val cover = object : View(this) {
                var radius = 0f
                private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                override fun onDraw(canvas: AndroidCanvas) {
                    val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                    canvas.drawBitmap(oldFrame, 0f, 0f, imagePaint)
                    canvas.drawCircle(centerX, centerY, radius, erasePaint)
                    canvas.restoreToCount(layer)
                }
            }
            root.addView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
            themeMode = selected
            val farthest = max(max(hypot(centerX, centerY), hypot(root.width - centerX, centerY)),
                max(hypot(centerX, root.height - centerY),
                    hypot(root.width - centerX, root.height - centerY)))
            ValueAnimator.ofFloat(0f, farthest).apply {
                duration = 420L
                addUpdateListener { cover.radius = it.animatedValue as Float; cover.invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        root.removeView(cover)
                        oldFrame.recycle()
                    }
                })
                start()
            }
        }
        getSharedPreferences("fit3_appearance", MODE_PRIVATE).edit().putString("theme", selected).apply()
    }

    private fun requestAppNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPostPermission)
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun enableNotifications() {
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        notificationForwarding = true
        if (!notificationAccess) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun toggleNotificationReading() {
        if (!notificationAccess) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        notificationReadingEnabled = !notificationReadingEnabled
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).edit()
            .putBoolean("read_enabled", notificationReadingEnabled).apply()
        if (notificationReadingEnabled) Fit3NotificationListener.active?.resyncToWatch()
        else connectionService?.clearForwardedNotifications()
    }


    private fun disableNotifications() {
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        notificationForwarding = false
    }

    private fun updateConnectionStatusPreference(enabled: Boolean) {
        connectionStatusDetails = enabled
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).edit()
            .putBoolean("show_connection_status", enabled).apply()
        connectionService?.updateConnectionNotification()
    }

    private fun openAppPowerSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)))

    private fun openBatteryOptimization() = startActivity(Intent(
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun openBatterySaver() = startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))

    override fun onResume() {
        super.onResume()
        AppStrings.initialize(this)
        languageMode = AppLanguage.selection(this)
        updateNotificationAccess()
        batteryUnrestricted = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }

    private fun inspectFirmware(uri: Uri) {
        firmwareFile = FirmwareFile("Проверка файла…", 0, "", false)
        Thread {
            val result = try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Выбранный файл"
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > 32L * 1024 * 1024) error("Файл больше 32 МБ")
                        digest.update(buffer, 0, read)
                    }
                } ?: error("Не удалось открыть файл")
                FirmwareFile(name, size, digest.digest().joinToString("") { "%02x".format(it) }, true)
            } catch (error: Exception) {
                FirmwareFile("Ошибка проверки", 0, error.message ?: "Неизвестная ошибка", false)
            }
            runOnUiThread { firmwareFile = result }
        }.start()
    }

    private fun inspectLocalFace(uri: Uri) {
        val selectionToken = ++localFaceSelectionToken
        customImporting = true
        customImportFailed = false
        Thread {
            var selectedName = "Выбранный BIN"
            val result = try {
                selectedName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: selectedName
                val digest = MessageDigest.getInstance("SHA-256")
                val out = java.io.ByteArrayOutputStream()
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(out.size() + read <= FaceBinValidator.MAX_BIN_BYTES) {
                            "BIN больше аппаратного лимита 4 МиБ"
                        }
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                } ?: error("Не удалось открыть BIN")
                val bytes = out.toByteArray()
                val report = FaceBinValidator.validateLocal(bytes, sourceName = selectedName)
                customFaceLibrary.add(bytes, selectedName)
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                LocalFaceFile(
                    sourceName = selectedName,
                    size = bytes.size,
                    sha256 = sha,
                    valid = true,
                    faceId = report.faceId,
                    canonicalName = report.canonicalFileName,
                    styles = report.styles,
                    entryCount = report.entryCount,
                    locales = report.localeFiles,
                    vendorRaster88 = report.containsVendorRaster88,
                )
            } catch (error: Exception) {
                LocalFaceFile(selectedName, valid = false,
                    error = error.message ?: "Неизвестная ошибка проверки BIN")
            }
            runOnUiThread {
                if (selectionToken != localFaceSelectionToken) return@runOnUiThread
                customImporting = false
                customImportFailed = !result.valid
                if (result.valid) customFaces = customFaceLibrary.list()
            }
        }.start()
    }

    override fun onDestroy() {
        connectionService?.observe(null)
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }
}

private data class FirmwareFile(val name: String, val size: Long, val sha256: String, val valid: Boolean)
private data class LocalFaceFile(
    val sourceName: String,
    val size: Int = 0,
    val sha256: String = "",
    val valid: Boolean,
    val error: String = "",
    val faceId: Int? = null,
    val canonicalName: String = "",
    val styles: List<Int> = emptyList(),
    val entryCount: Int = 0,
    val locales: List<String> = emptyList(),
    val vendorRaster88: Boolean = false,
)

private enum class Page(val label: String) {
    HOME("Главная"), ACTIVITY("Активность"), FACES("Браслет"),
    DISCOVERY("Подключение браслета"), FACE_GALLERY("Циферблаты"),
    FACE_VARIANTS("Варианты циферблата"), CUSTOM_FACES("Установленные"),
    SETTINGS("Настройки приложения"), WATCH_SETTINGS("Экран"), WIDGETS("Карточки"),
    APPS("Приложения"), QUICK_PANEL("Быстрая панель"), QUICK_MESSAGES("Быстрые ответы"),
    BACKUP("Резервная копия"), ABOUT("О приложении"), DEBUG("Отладка"),
    LICENSES("Лицензии и благодарности"),
    NOTIFICATIONS("Уведомления"), POWER("Энергосбережение"),
    FLASHER("Прошивальщик"), APPEARANCE("Внешний вид"),
    HEALTH_SYNC("Синхронизация Fit3 Health"), SLEEP("Сон"),
    WATCH_MODES("Режимы"), WATCH_LAYOUT("Меню и карточки"), WATCH_TIME("Время"),
    WATCH_ORIENTATION("Ношение браслета"),
    PROTOCOL_LOG("Журнал протокола")
}

private fun Page.backDestination(): Page? = when (this) {
    Page.HOME -> null
    Page.ACTIVITY, Page.FACES, Page.SETTINGS, Page.DISCOVERY -> Page.HOME
    Page.FLASHER -> Page.DEBUG
    Page.PROTOCOL_LOG -> Page.DEBUG
    Page.FACE_GALLERY, Page.CUSTOM_FACES, Page.WATCH_SETTINGS, Page.WATCH_MODES, Page.WATCH_LAYOUT,
    Page.WATCH_TIME, Page.WATCH_ORIENTATION -> Page.FACES
    Page.FACE_VARIANTS -> Page.FACE_GALLERY
    Page.WIDGETS, Page.APPS, Page.QUICK_PANEL, Page.QUICK_MESSAGES -> Page.WATCH_LAYOUT
    Page.BACKUP, Page.ABOUT, Page.DEBUG, Page.NOTIFICATIONS, Page.APPEARANCE,
    Page.POWER -> Page.SETTINGS
    Page.LICENSES -> Page.ABOUT
    Page.HEALTH_SYNC -> Page.DEBUG
    Page.SLEEP -> Page.ACTIVITY
}

private fun Page.depth(): Int = when (this) {
    Page.HOME, Page.ACTIVITY, Page.FACES -> 0
    Page.SETTINGS -> 1
    Page.FLASHER, Page.PROTOCOL_LOG, Page.WIDGETS, Page.APPS, Page.QUICK_PANEL,
    Page.QUICK_MESSAGES -> 3
    else -> 2
}

private fun Page.navIcon(): Int = when (this) {
    Page.HOME -> R.drawable.ic_nav_home
    Page.ACTIVITY -> R.drawable.ic_nav_activity
    Page.FACES -> R.drawable.ic_nav_faces
    else -> R.drawable.ic_settings_rounded
}

@Composable
private fun Fit3Theme(mode: String, accent: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "amoled" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val primary = when (accent) {
        "blue" -> Color(0xFF4B81D8)
        "green" -> Color(0xFF32A773)
        "purple" -> Color(0xFF9267CF)
        "orange" -> Color(0xFFD5823E)
        "pink" -> Color(0xFFD46A91)
        else -> Color(0xFF69BFA8)
    }
    val context = LocalContext.current
    val colors = when {
        mode == "amoled" -> {
            val base = if (accent == "adaptive" && Build.VERSION.SDK_INT >= 31)
                dynamicDarkColorScheme(context) else darkColorScheme(primary = primary, secondary = primary)
            base.copy(background = Color.Black, surface = Color.Black,
                surfaceContainer = Color(0xFF101010), surfaceContainerHigh = Color(0xFF181818))
        }
        accent == "adaptive" && Build.VERSION.SDK_INT >= 31 && dark ->
            dynamicDarkColorScheme(context)
        accent == "adaptive" && Build.VERSION.SDK_INT >= 31 ->
            dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = primary, secondary = primary,
            primaryContainer = primary.copy(alpha = .26f), secondaryContainer = primary.copy(alpha = .20f))
        else -> lightColorScheme(primary = primary, secondary = primary,
            primaryContainer = primary.copy(alpha = .22f), secondaryContainer = primary.copy(alpha = .17f))
    }
    val activity = LocalContext.current as? ComponentActivity
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colors.surface.toArgb()
            window.navigationBarColor = colors.surfaceContainer.toArgb()
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    snapshot: BleSnapshot,
    page: Page,
    onPage: (Page) -> Unit,
    onScan: () -> Unit,
    onSystemDiscover: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshWeather: () -> Unit,
    onSearchWeatherCities: (String, (List<WeatherCity>) -> Unit) -> Unit,
    onChooseWeatherCity: (WeatherCity) -> Unit,
    onConsumeWeatherLocationRequest: () -> Unit,
    onRequestHealth: () -> Unit,
    onRequestHealthCapabilityForDebug: () -> Unit,
    stepGoal: Int,
    dailySteps: Map<Long, Int>,
    homeTiles: List<HomeTile>,
    homeDashboardEnabled: Boolean,
    onHomeTiles: (List<HomeTile>) -> Unit,
    onResetHomeTiles: () -> Unit,
    onActivityGoals: (ActivityGoals) -> Unit,
    onRequestFaces: () -> Unit,
    onSelectFace: (Int, Int) -> Unit,
    onDeleteFace: (Int, Int) -> Unit,
    onInstallFace: (OfficialFace, OfficialFaceStyle) -> Unit,
    onRequestFullSettings: () -> Unit,
    onBrightness: (Int) -> Unit,
    onAutoBrightness: (Boolean) -> Unit,
    onAod: (Boolean) -> Unit,
    onRaiseWake: (Boolean) -> Unit,
    onTouchWake: (Boolean) -> Unit,
    onRightWrist: (Boolean) -> Unit,
    onButtonOnRight: (Boolean) -> Unit,
    onAutoMedia: (Boolean) -> Unit,
    onSleepMode: (Boolean) -> Unit,
    onTheatreMode: (Boolean) -> Unit,
    onTimeout: (Int) -> Unit,
    onSyncTime: () -> Unit,
    onSyncBandLanguage: () -> Unit,
    onRequestWidgets: () -> Unit,
    onSetWidgets: (List<Int>) -> Unit,
    onRequestApps: () -> Unit,
    onSetApps: (List<Int>) -> Unit,
    onRequestQuickPanel: () -> Unit,
    onSetQuickPanel: (List<Int>) -> Unit,
    onSetQuickMessages: (List<String>) -> Unit,
    onClearProtocolLog: () -> Unit,
    onCopyProtocolLog: () -> Unit,
    onCopyCompactProtocolLog: () -> Unit,
    onSaveBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    backupMessage: String,
    autoBackupHours: Int,
    autoBackupFolder: String?,
    onAutoBackupHours: (Int) -> Unit,
    onChooseBackupFolder: () -> Unit,
    showListenerAccessPrompt: Boolean,
    onListenerAccessPrompt: (Boolean) -> Unit,
    firmwareFile: FirmwareFile?,
    onChooseFirmware: () -> Unit,
    customLibrary: io.github.yuriyurin.fit3companion.ble.CustomFaceLibrary,
    customFaces: List<io.github.yuriyurin.fit3companion.ble.CustomFace>,
    customImporting: Boolean,
    customImportFailed: Boolean,
    onCustomFacesChanged: () -> Unit,
    onInstallCustomFace: (io.github.yuriyurin.fit3companion.ble.CustomFace, Int) -> Unit,
    onChooseLocalFace: () -> Unit,
    notificationForwarding: Boolean,
    notificationAccess: Boolean,
    notificationReadingEnabled: Boolean,
    notificationPostPermission: Boolean,
    connectionStatusDetails: Boolean,
    batteryUnrestricted: Boolean,
    onEnableNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onDisableNotifications: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onConnectionStatusDetails: (Boolean) -> Unit,
    onOpenAppPowerSettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenBatterySaver: () -> Unit,
    onSendTestNotification: () -> Unit,
    themeMode: String,
    accentMode: String,
    languageMode: String,
    onLanguageMode: (String) -> Unit,
    onThemeMode: (String, Offset) -> Unit,
    onAccentMode: (String) -> Unit,
    adbLogEnabled: Boolean,
    onAdbLogEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(customLibrary) { onCustomFacesChanged() }
    val installedVersionName = remember(context) { getInstalledVersionName(context) }
    val uiScope = rememberCoroutineScope()
    val gearAngle = remember { Animatable(0f) }
    var manualRefreshing by remember { mutableStateOf(false) }
    var manualRefreshStarted by remember { mutableStateOf(0L) }
    var manualRefreshWaitsForHealth by remember { mutableStateOf(false) }
    var homeEditing by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.health.lastSyncMillis, snapshot.lastRefreshMillis,
        manualRefreshStarted, manualRefreshWaitsForHealth) {
        val completedAt = if (manualRefreshWaitsForHealth)
            snapshot.health.lastSyncMillis ?: 0L else snapshot.lastRefreshMillis ?: 0L
        if (manualRefreshing && completedAt >= manualRefreshStarted) {
            manualRefreshing = false
        }
    }
    val activePage by rememberUpdatedState(page)
    var showBackupInterval by remember { mutableStateOf(false) }
    var orientationDialog by remember { mutableStateOf<String?>(null) }
    var showCityDialog by remember { mutableStateOf(false) }
    var selectedFace by remember { mutableStateOf<OfficialFace?>(null) }
    var customMenu by remember { mutableStateOf<io.github.yuriyurin.fit3companion.ble.CustomFace?>(null) }
    var customMenuAction by remember { mutableStateOf("") }
    val faceHaptic = LocalHapticFeedback.current
    var customVariants by remember { mutableStateOf<io.github.yuriyurin.fit3companion.ble.CustomFace?>(null) }
    var watchDeleteCandidate by remember { mutableStateOf<Fit3SapCodec.InstalledFace?>(null) }
    var watchDeleteConfirmation by remember { mutableStateOf(false) }
    var extendedFaceInfo by remember { mutableStateOf<Fit3SapCodec.InstalledFace?>(null) }
    var officialFaces by remember { mutableStateOf<List<OfficialFace>>(emptyList()) }
    var officialLoadError by remember { mutableStateOf<String?>(null) }
    var officialLoading by remember { mutableStateOf(false) }
    val faceCatalog = remember(context) { OfficialFaceCatalog(context) }
    val currentOfficialFace = officialFaces.firstOrNull { it.id == snapshot.currentFaceId }
    val currentOfficialStyle = currentOfficialFace?.styles?.firstOrNull {
        it.id == snapshot.currentFaceSampler
    } ?: currentOfficialFace?.styles?.firstOrNull()
    LaunchedEffect(page) {
        if (officialFaces.isEmpty()) {
            officialLoading = true
            officialLoadError = null
            try { officialFaces = withContext(Dispatchers.IO) { faceCatalog.load() } }
            catch (error: Exception) { officialLoadError = error.message ?: "Не удалось загрузить каталог" }
            finally { officialLoading = false }
        }
    }
    var cityQuery by remember { mutableStateOf("") }
    var citySuggestions by remember { mutableStateOf<List<WeatherCity>>(emptyList()) }
    LaunchedEffect(snapshot.weatherLocationRequested) {
        if (snapshot.weatherLocationRequested) {
            showCityDialog = true
            onConsumeWeatherLocationRequest()
        }
    }
    LaunchedEffect(cityQuery, showCityDialog) {
        if (!showCityDialog) return@LaunchedEffect
        val query = cityQuery.trim()
        if (query.length < 3) {
            citySuggestions = popularWeatherCities.filter {
                it.name.startsWith(query, ignoreCase = true)
            }.take(6)
        } else {
            delay(350)
            onSearchWeatherCities(query) { result ->
                if (cityQuery.trim() == query) citySuggestions = result
            }
        }
    }

    BackHandler(enabled = homeEditing || page != Page.HOME) {
        if (homeEditing) homeEditing = false else page.backDestination()?.let(onPage)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (homeEditing) "Редактор главной"
            else if (page in listOf(Page.HOME, Page.ACTIVITY, Page.FACES)) "Fit3 App"
            else if (page == Page.CUSTOM_FACES) stringResource(R.string.custom_faces_title) else page.label) }, navigationIcon = {
            if (homeEditing) IconButton(onClick = { homeEditing = false }) {
                Text("‹", style = MaterialTheme.typography.headlineLarge)
            } else if (page !in listOf(Page.HOME, Page.ACTIVITY, Page.FACES)) IconButton(onClick = {
                page.backDestination()?.let(onPage)
            }) { Text("‹", style = MaterialTheme.typography.headlineLarge) }
        }, actions = {
            if (homeEditing) TextButton(onClick = { homeEditing = false }) { Text("Готово") }
            else if (page in listOf(Page.HOME, Page.ACTIVITY, Page.FACES, Page.SETTINGS)) IconButton(onClick = {
                uiScope.launch {
                    gearAngle.snapTo(-45f)
                    gearAngle.animateTo(315f, tween(360))
                    gearAngle.snapTo(0f)
                }
                onPage(if (page == Page.SETTINGS) Page.HOME else Page.SETTINGS)
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_rounded),
                    contentDescription = AppStrings.translate("Настройки"),
                    modifier = Modifier.size(27.dp).graphicsLayer(rotationZ = gearAngle.value),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }) },
        bottomBar = {
            val haptic = LocalHapticFeedback.current
            if (!homeEditing && page in listOf(Page.HOME, Page.ACTIVITY, Page.FACES)) Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically) {
                    Page.entries.take(3).forEach { destination ->
                        val selected = destination == page
                        val pillWidth by animateFloatAsState(
                            targetValue = if (selected) 1f else .18f,
                            animationSpec = tween(230), label = "navPillWidth")
                        val pillAlpha by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = tween(160), label = "navPillAlpha")
                        Column(
                            modifier = Modifier.weight(1f).fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPage(destination)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier.size(64.dp, 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.fillMaxSize()
                                    .graphicsLayer(scaleX = pillWidth, alpha = pillAlpha)
                                    .background(MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(50)))
                                Icon(painter = painterResource(destination.navIcon()),
                                    contentDescription = AppStrings.translate(destination.label),
                                    modifier = Modifier.size(22.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(destination.label, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = manualRefreshing,
            onRefresh = {
                if (!homeEditing) {
                    manualRefreshing = true
                    val started = System.currentTimeMillis()
                    manualRefreshStarted = started
                    manualRefreshWaitsForHealth = page == Page.HOME || page == Page.ACTIVITY || page == Page.SLEEP
                    onRefresh()
                    onRefreshWeather()
                    when (page) {
                        Page.FACE_GALLERY, Page.FACE_VARIANTS, Page.CUSTOM_FACES -> onRequestFaces()
                        Page.WIDGETS -> onRequestWidgets()
                        Page.APPS -> onRequestApps()
                        Page.QUICK_PANEL -> onRequestQuickPanel()
                        else -> Unit
                    }
                    uiScope.launch {
                        // A new SpO2 record can arrive well after the watch accepts CHECK_STATUS.
                        delay(if (manualRefreshWaitsForHealth) 25_000 else 8_000)
                        if (manualRefreshStarted == started) manualRefreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
          AnimatedContent(
            targetState = page,
            modifier = Modifier.pointerInput(page) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (kotlin.math.abs(dragged) > 90f) {
                            val tabs = listOf(Page.HOME, Page.ACTIVITY, Page.FACES)
                            val position = tabs.indexOf(activePage)
                            if (position >= 0) {
                                val next = (position + if (dragged < 0f) 1 else -1)
                                if (next in tabs.indices) onPage(tabs[next])
                            }
                        }
                        dragged = 0f
                    },
                ) { _, amount -> dragged += amount }
            },
            transitionSpec = {
                val tabs = listOf(Page.HOME, Page.ACTIVITY, Page.FACES)
                val fromTab = tabs.indexOf(initialState)
                val toTab = tabs.indexOf(targetState)
                val forward = if (fromTab >= 0 && toTab >= 0) toTab > fromTab
                    else targetState.depth() >= initialState.depth()
                if (forward) {
                    (slideInHorizontally { it / 7 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 10 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 7 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 10 } + fadeOut())
                }
            },
            label = "pageTransition",
          ) { targetPage ->
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            when (targetPage) {
                Page.HOME -> {
                    if (homeEditing || homeDashboardEnabled) {
                        item { TodayHeader() }
                        if (!homeEditing) item {
                            val updatedAt = listOfNotNull(snapshot.health.lastSuccessfulSyncMillis,
                                snapshot.lastRefreshMillis, snapshot.health.lastSyncMillis).maxOrNull()
                            Text(updatedAt?.let { "Данные обновлены: ${dashboardTime(it)}" }
                                ?: "Ожидаем данные с браслета", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (homeEditing) item {
                            Text("Удерживайте карточку, чтобы переместить. Размер выбирается на самой карточке.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item(key = "home-dashboard") {
                            HomeDashboard(homeTiles, homeEditing, snapshot, stepGoal,
                                currentOfficialStyle, faceCatalog,
                                onTilesChanged = onHomeTiles,
                                onOpenBand = { onPage(Page.FACES) },
                                onOpenDiscovery = { onPage(Page.DISCOVERY); onScan() },
                                onOpenActivity = { onPage(Page.ACTIVITY) },
                                onChooseCity = { showCityDialog = true })
                        }
                        if (homeEditing) item {
                            OutlinedButton(onClick = { onResetHomeTiles(); homeEditing = false },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Сбросить · вернуть обычный главный экран")
                            }
                        }
                    } else {
                        item { HomeWatchCard(snapshot, currentOfficialStyle, faceCatalog,
                            { onPage(Page.FACES) }, { onPage(Page.DISCOVERY); onScan() }) }
                        item { TodayHeader() }
                        item { HomeWeatherCard(snapshot) { showCityDialog = true } }
                        item { HomeHealthSummary(snapshot) { onPage(Page.ACTIVITY) } }
                    }
                }
                Page.DISCOVERY -> {
                    item { DiscoveryIntro(snapshot) }
                    item { Button(onClick = onSystemDiscover) { Text("Найти через Android") } }
                    if (snapshot.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (snapshot.devices.isEmpty()) item {
                        Text(if (snapshot.scanning) "Ищем Galaxy Fit3 рядом…" else
                            "Браслет не найден. Проверьте Bluetooth и повторите поиск.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(snapshot.devices.sortedWith(compareByDescending<WatchCandidate> { it.paired }
                        .thenByDescending { it.rssi ?: -999 }), key = WatchCandidate::address) { candidate ->
                        CandidateCard(candidate) { address -> onConnect(address); onPage(Page.HOME) }
                    }
                    item { FilledTonalButton(onClick = onScan, enabled = !snapshot.scanning) {
                        Text(if (snapshot.scanning) "Поиск…" else "Найти снова")
                    } }
                }
                Page.ACTIVITY -> {
                    item { StepsHeroCard(snapshot, stepGoal, dailySteps, onActivityGoals) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) {
                                MetricCard("Дистанция", snapshot.health.distanceMeters?.let(::formatDistance) ?: "—",
                                    "за сегодня")
                            }
                            Box(Modifier.weight(1f)) {
                                MetricCard("Калории", snapshot.health.activeCalories?.let { "${it.roundToInt()} ккал" } ?: "—",
                                    "активные")
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) {
                                MetricCard("Этажи", snapshot.health.floors?.let { formatCompact(it) } ?: "0",
                                    if (snapshot.health.floors == null) "нет данных с браслета" else "подъёмы")
                            }
                            Box(Modifier.weight(1f)) {
                                MetricCard("Активность", snapshot.health.activeMinutes?.let { "$it мин" } ?: "—", "движение")
                            }
                        }
                    }
                    item { WeeklyStepsCard(dailySteps, stepGoal) }
                    item { Text("Здоровье", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    item { HealthVitalsCard(snapshot) }
                    item { SleepSummaryCard(snapshot.health) { onPage(Page.SLEEP) } }
                }
                Page.SLEEP -> {
                    item { SleepDetails(snapshot.health) }
                }
                Page.FACES -> {
                    item { CurrentFaceCard(snapshot, currentOfficialStyle, currentOfficialFace?.name, faceCatalog,
                        onClick = { onPage(Page.FACE_GALLERY) }) }
                    item { SettingsChoice(stringResource(R.string.custom_faces_title),
                        stringResource(R.string.custom_faces_subtitle), { onPage(Page.CUSTOM_FACES) }) }
                    item { Text("Настройки браслета", style = MaterialTheme.typography.titleLarge) }
                    item { SettingsChoice("Экран", "Яркость, пробуждение и AOD", { onPage(Page.WATCH_SETTINGS) }) }
                    item { SettingsChoice("Ношение браслета", "Запястье и положение кнопки",
                        { onPage(Page.WATCH_ORIENTATION) }) }
                    item { SettingsChoice("Режимы", "Сон, театр и управление музыкой", { onPage(Page.WATCH_MODES) }) }
                    item { SettingsChoice("Меню и карточки", "Карточки, приложения, быстрая панель и ответы",
                        { onPage(Page.WATCH_LAYOUT) }) }
                    item { SettingsChoice("Время", "Синхронизация с телефоном", { onPage(Page.WATCH_TIME) }) }
                    item { Text("Подключение", style = MaterialTheme.typography.titleLarge) }
                    item { FilledTonalButton(onClick = onDisconnect, enabled = snapshot.connected) {
                        Text("Отключить браслет")
                    } }
                    item { TextButton(onClick = { onDisconnect(); onPage(Page.DISCOVERY); onScan() }) {
                        Text("Подключить другой браслет")
                    } }
                }
                Page.CUSTOM_FACES -> {
                    val availableLocalFaces = customFaces.filterNot { face ->
                        io.github.yuriyurin.fit3companion.protocol.Fit3FacePolicy.localCopyIsInstalled(
                            face.faceId, face.key, face.styles, snapshot.installedFaces, snapshot.customFaceKeys)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.watch_faces_heading), style = MaterialTheme.typography.titleMedium)
                            snapshot.faceMaximum?.let { maximum ->
                                Text("${snapshot.installedFaces.size}/$maximum", localize = false,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (snapshot.facesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!snapshot.sapReady) item { Text(stringResource(R.string.watch_faces_offline),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (!snapshot.facesLoading && !snapshot.facesReady) item {
                        Text(stringResource(R.string.watch_faces_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(snapshot.installedFaces.chunked(3)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { installed ->
                                val face = officialFaces.firstOrNull { it.id == installed.id }
                                    ?: OfficialFace(installed.id, knownFaceName(installed.id)
                                        ?: installed.name?.takeIf { !it.startsWith("wf_name-") }
                                        ?: stringResource(R.string.watch_faces_generic), emptyList())
                                val selected = installed.current || (snapshot.currentFaceId == installed.id &&
                                    snapshot.currentFaceSampler == installed.sampler)
                                val sourceKey = snapshot.customFaceKeys[installed.id to installed.sampler]
                                val customName = installedFaceName(sourceKey)
                                    ?: if (sourceKey != null) stringResource(R.string.watch_faces_generic) else null
                                InstalledWatchFaceCell(if (customName != null) face.copy(name = customName) else face,
                                    installed, faceCatalog, Modifier.weight(1f), selected,
                                    onClick = {
                                        if (!installed.remotelyAddressable) extendedFaceInfo = installed
                                        else if (snapshot.sapReady && snapshot.facesReady && !snapshot.facesLoading && !selected)
                                            onSelectFace(installed.id, installed.sampler)
                                    }, onLongClick = {
                                        faceHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        watchDeleteConfirmation = false
                                        watchDeleteCandidate = installed
                                    }, menuOpen = watchDeleteCandidate?.let {
                                        it.id == installed.id && it.sampler == installed.sampler
                                    } == true || extendedFaceInfo?.let {
                                        it.id == installed.id && it.sampler == installed.sampler
                                    } == true || (sourceKey != null && customMenu?.key == sourceKey))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (snapshot.faceDeleteState !in listOf(
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.IDLE,
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.DELETED)) item {
                        Text(stringResource(when (snapshot.faceDeleteState) {
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.CHECKING,
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.SENDING,
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.VERIFYING -> R.string.watch_faces_deleting
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.DELETED -> R.string.watch_faces_deleted
                            io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.TIMEOUT -> R.string.watch_faces_delete_timeout
                            else -> R.string.watch_faces_delete_failed
                        }), style = MaterialTheme.typography.bodySmall)
                    }
                    item { HorizontalDivider() }
                    item { Text(stringResource(R.string.local_faces_heading), style = MaterialTheme.typography.titleMedium) }
                    if (availableLocalFaces.any { it.faceId > 255 }) item {
                        Text(stringResource(R.string.custom_faces_extended_import),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { FilledTonalButton(onClick = onChooseLocalFace, enabled = !customImporting) {
                        Text(stringResource(R.string.custom_faces_import))
                    } }
                    if (customImporting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (customImportFailed) item { Text(stringResource(R.string.custom_faces_import_error),
                        color = MaterialTheme.colorScheme.error) }
                    if (customFaces.isEmpty()) item { Text(stringResource(R.string.custom_faces_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(availableLocalFaces.chunked(3)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { face ->
                                CustomFaceCell(face, customLibrary, 0, Modifier.weight(1f),
                                    onClick = {
                                        if (face.styles.size > 1) customVariants = face
                                        else if (snapshot.sapReady && !snapshot.faceInstallPending &&
                                            (snapshot.batteryPercent == null || snapshot.batteryPercent >= 30))
                                            onInstallCustomFace(face, face.styles.first())
                                    }, onLongClick = {
                                        faceHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        customMenuAction = ""
                                        customMenu = face
                                    }, menuOpen = customMenu?.key == face.key)
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (!snapshot.sapReady) item { Text(stringResource(R.string.custom_faces_connect),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else if (snapshot.batteryPercent != null && snapshot.batteryPercent < 30) item {
                        Text(stringResource(R.string.custom_faces_battery), color = MaterialTheme.colorScheme.error)
                    }
                    if (snapshot.faceInstallPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (snapshot.faceInstallStatus.isNotBlank() && snapshot.faceInstallStatus != "Циферблат установлен") item {
                        Text(snapshot.faceInstallStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Page.FACE_GALLERY -> {
                    if (snapshot.installedFaces.isEmpty()) {
                        item { Text("Подключите браслет, чтобы увидеть установленные циферблаты.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        val installedFamilies = snapshot.installedFaces.groupBy { it.id }.values.map { variants ->
                            variants.firstOrNull { it.current || (it.id == snapshot.currentFaceId &&
                                it.sampler == snapshot.currentFaceSampler) } ?: variants.first()
                        }
                        items(installedFamilies.chunked(3)) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { installed ->
                                    val face = officialFaces.firstOrNull { it.id == installed.id }
                                        ?: OfficialFace(installed.id,
                                            knownFaceName(installed.id)
                                                ?: installed.name?.takeIf(String::isNotBlank) ?: "Циферблат",
                                            emptyList())
                                    val sourceKey = snapshot.customFaceKeys[installed.id to installed.sampler]
                                    val displayFace = if (sourceKey != null) face.copy(name = installedFaceName(sourceKey)
                                        ?: stringResource(R.string.watch_faces_generic)) else face
                                    FaceFamilyCell(displayFace, faceCatalog, Modifier.weight(1f), installed.sampler) {
                                        selectedFace = displayFace
                                        onPage(Page.FACE_VARIANTS)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    // Store faces are part of the regular catalog, not a debug feature.
                    item { Text("Другие циферблаты Samsung", style = MaterialTheme.typography.titleLarge) }
                    if (officialLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    officialLoadError?.let { error -> item {
                        Text("Каталог не загрузился: $error", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    items(officialFaces.filterNot { face ->
                        snapshot.installedFaces.any { it.id == face.id }
                    }.chunked(3)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { face ->
                                FaceFamilyCell(face, faceCatalog, Modifier.weight(1f)) {
                                    selectedFace = face
                                    onPage(Page.FACE_VARIANTS)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    item { Text("Выберите циферблат, чтобы увидеть его варианты. Для нового варианта потребуется загрузка из магазина.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Page.FACE_VARIANTS -> {
                    val face = officialFaces.firstOrNull { it.id == selectedFace?.id } ?: selectedFace
                    if (face == null) {
                        item { Text("Вернитесь в каталог и выберите циферблат") }
                    } else {
                        item { Text(selectedFace?.name ?: face.name, localize = false,
                            style = MaterialTheme.typography.headlineMedium) }
                        val installedStyles = snapshot.installedFaces.filter { it.id == face.id }
                            .distinctBy { it.sampler }.map { OfficialFaceStyle(it.sampler, "") }
                        val styles = face.styles + installedStyles.filterNot { installed ->
                            face.styles.any { it.id == installed.id }
                        }
                        items(styles.chunked(3)) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { style ->
                                    val installed = snapshot.installedFaces.firstOrNull {
                                        it.id == face.id && it.sampler == style.id
                                    }
                                    val selected = snapshot.currentFaceId == face.id &&
                                        snapshot.currentFaceSampler == style.id
                                    FaceStyleCell(face, style, faceCatalog, Modifier.weight(1f),
                                        installed != null, selected,
                                        snapshot.faceChangePending || snapshot.faceInstallPending) {
                                        if (installed != null) onSelectFace(installed.id, installed.sampler)
                                        else onInstallFace(face, style)
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (snapshot.faceInstallPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (snapshot.faceInstallStatus.isNotBlank()) item {
                            Text(snapshot.faceInstallStatus, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Page.SETTINGS -> {
                    item { SettingsChoice("Уведомления", "Доступ и передача уведомлений", { onPage(Page.NOTIFICATIONS) }) }
                    item { SettingsChoice("Энергосбережение", "Работа в фоне и ограничения батареи",
                        { onPage(Page.POWER) }) }
                    item { SettingsChoice("Внешний вид", stringResource(R.string.appearance_subtitle), { onPage(Page.APPEARANCE) }) }
                    item { SettingsChoice("Резервная копия", "Бэкап настроек браслета", { onPage(Page.BACKUP) }) }
                    item { SettingsChoice("Режим отладки", "Проверки, журнал и прошивальщик", { onPage(Page.DEBUG) }) }
                    item { SettingsChoice("О приложении", "Fit3 App", { onPage(Page.ABOUT) }) }
                }
                Page.WATCH_SETTINGS -> {
                    item { BandLanguageSelector(onSyncBandLanguage) }
                    item { SettingSwitch("Always On Display", snapshot.fullSettings.alwaysOnDisplay, onAod) }
                    item { SettingSwitch("Автояркость", snapshot.fullSettings.autoBrightness, onAutoBrightness) }
                    item { SettingSwitch("Поднять запястье для пробуждения", snapshot.fullSettings.raiseToWake, onRaiseWake) }
                    item { SettingSwitch("Касание для пробуждения", snapshot.fullSettings.touchToWake, onTouchWake) }
                    item {
                        DiscreteIntSettingSlider(
                            title = "Яркость",
                            value = snapshot.fullSettings.brightness,
                            values = (1..10).toList(),
                            enabled = snapshot.sapReady,
                            valueLabel = { it.toString() },
                            onCommit = onBrightness,
                        )
                    }
                    item {
                        DiscreteIntSettingSlider(
                            title = "Таймаут экрана",
                            value = snapshot.fullSettings.timeoutSeconds,
                            values = listOf(4, 7, 10, 15, 30),
                            enabled = snapshot.sapReady,
                            valueLabel = { "${it}с" },
                            onCommit = onTimeout,
                        )
                    }
                }
                Page.WATCH_MODES -> {
                    item { SettingSwitch("Режим сна", snapshot.fullSettings.sleepMode, onSleepMode) }
                    item { SettingSwitch("Режим Театр", snapshot.fullSettings.theatreMode, onTheatreMode) }
                }
                Page.WATCH_ORIENTATION -> {
                    item { SettingsChoice("Предпочитаемое запястье",
                        when (snapshot.fullSettings.rightWrist) { true -> "Правое"; false -> "Левое"; null -> "Не определено" },
                        { orientationDialog = "wrist" }) }
                    item { SettingsChoice("Положение кнопки",
                        when (snapshot.fullSettings.buttonOnRight) { true -> "Справа"; false -> "Слева"; null -> "Не определено" },
                        { orientationDialog = "button" }) }
                }
                Page.WATCH_LAYOUT -> {
                    item { SettingsChoice("Карточки", "Состав и порядок", { onPage(Page.WIDGETS) }) }
                    item { SettingsChoice("Приложения", "Порядок в меню браслета", { onPage(Page.APPS) }) }
                    item { SettingsChoice("Быстрая панель", "Порядок переключателей", { onPage(Page.QUICK_PANEL) }) }
                    item { SettingsChoice("Быстрые ответы", "Шаблоны сообщений", { onPage(Page.QUICK_MESSAGES) }) }
                }
                Page.WATCH_TIME -> {
                    item { Text("Время синхронизируется при подключении и после изменения времени, часового пояса или языка телефона.") }
                    item { FilledTonalButton(onClick = onSyncTime, enabled = snapshot.sapReady) {
                        Text("Синхронизировать сейчас")
                    } }
                }
                Page.WIDGETS -> {
                    item { Text("Порядок карточек на браслете", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (snapshot.widgets.isNotEmpty()) item {
                        WatchLayoutPreview(snapshot.widgets, false) { id -> Fit3WidgetsCodec.names[id] ?: "Карточка" }
                    }
                    if (snapshot.widgets.isEmpty()) item { Text("Список ещё не получен") }
                    items(snapshot.widgets.sortedBy { it.order }, key = { "w${it.id}" }) { item ->
                        OrderedItemCard(item, Fit3WidgetsCodec.names[item.id] ?: "Tile ${item.id}", snapshot.widgets, onSetWidgets)
                    }
                }
                Page.APPS -> {
                    if (snapshot.apps.isEmpty()) item { Text("Список ещё не получен") }
                    items(snapshot.apps.sortedBy { it.order }, key = { "a${it.id}" }) { item ->
                        OrderedItemCard(item, "${Fit3AppsCodec.names[item.id] ?: "Приложение"} · ID ${item.id}", snapshot.apps, onSetApps)
                    }
                }
                Page.QUICK_PANEL -> {
                    if (snapshot.quickPanel.isNotEmpty()) item {
                        WatchLayoutPreview(snapshot.quickPanel, true) { id ->
                            Fit3QuickPanelCodec.names[id] ?: "Переключатель"
                        }
                    }
                    if (snapshot.quickPanel.isEmpty()) item { Text("Список ещё не получен") }
                    items(snapshot.quickPanel.sortedBy { it.order }, key = { "q${it.id}" }) { item ->
                        OrderedItemCard(item, Fit3QuickPanelCodec.names[item.id] ?: "Быстрая настройка #${item.id}", snapshot.quickPanel, onSetQuickPanel)
                    }
                }
                Page.QUICK_MESSAGES -> {
                    item { QuickMessagesEditor(snapshot.quickMessages, snapshot.sapReady, onSetQuickMessages) }
                }
                Page.BACKUP -> {
                    item { Text("Сохраните данные здоровья и настройки в файл. На новом телефоне выберите этот файл для восстановления.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Button(onClick = onSaveBackup) { Text("Сохранить копию") } }
                    item { Button(onClick = onRestoreBackup) { Text("Восстановить из файла") } }
                    item { SettingsChoice("Автоматическое резервное копирование",
                        when (autoBackupHours) {
                            0 -> "Выключено"
                            24 -> "Раз в день"
                            72 -> "Раз в 3 дня"
                            168 -> "Раз в 7 дней"
                            else -> "Раз в $autoBackupHours ч"
                        },
                        { showBackupInterval = true }) }
                    if (autoBackupHours > 0) {
                        item { Text("Копия сохраняется в памяти приложения. Чтобы она осталась после удаления приложения, выберите папку телефона.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { FilledTonalButton(onClick = onChooseBackupFolder) {
                            Text(if (autoBackupFolder == null) "Выбрать папку" else "Изменить папку")
                        } }
                    }
                    if (backupMessage.isNotBlank()) item { Text(backupMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Page.NOTIFICATIONS -> {
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                NotificationToggleRow("Доступ к уведомлениям", notificationAccess && notificationReadingEnabled,
                                    onOpenNotificationAccess)
                                if (!notificationAccess) Text(
                                    "Этот системный доступ нужен также для распознавания Spotify и других плееров на браслете.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                NotificationToggleRow("Передача на Fit3", notificationForwarding,
                                    { if (notificationForwarding) onDisableNotifications() else onEnableNotifications() })
                                NotificationToggleRow("Статус Fit3 App в шторке", connectionStatusDetails,
                                    { onConnectionStatusDetails(!connectionStatusDetails) })
                                Text("При выключении подробный статус скрыт, но Android оставляет служебную строку для стабильной связи с браслетом.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = onRequestNotificationPermission) {
                                    Text(if (notificationPostPermission) "Настроить уведомления приложения" else
                                        "Разрешить уведомления приложения")
                                }
                            }
                        }
                    }
                }
                Page.POWER -> {
                    item { Text("Для стабильной связи разрешите Fit3 App работу в фоне. Некоторые оболочки дополнительно ограничивают автозапуск.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { SettingsChoice("Питание приложения", "Откройте настройки Fit3 App и выберите «Без ограничений»",
                        onOpenAppPowerSettings) }
                    item { SettingsChoice("Оптимизация батареи",
                        if (batteryUnrestricted) "Fit3 App в списке исключений" else "Добавьте Fit3 App в исключения",
                        onOpenBatteryOptimization) }
                    item { SettingsChoice("Режим энергосбережения", "Проверьте системный режим экономии заряда",
                        onOpenBatterySaver) }
                    item { Text("Автозапуск в OnePlus, Xiaomi и других оболочках может находиться внутри настроек приложения. Android не даёт универсальной ссылки на этот пункт.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Page.APPEARANCE -> {
                    item { LanguageSelector(languageMode, onLanguageMode) }
                    item { Text("Тема", style = MaterialTheme.typography.titleLarge) }
                    item { ThemeSelector(themeMode, onThemeMode) }
                    item { Text("Цветовой акцент", style = MaterialTheme.typography.titleLarge) }
                    item { AccentSelector(accentMode, onAccentMode) }
                    item { Text("А — адаптивные цвета обоев Android. На Android 10–11 используется цвет приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { SettingsChoice("Карточки главного экрана (БЕТА)", "Порядок и размер на сетке",
                        { homeEditing = true; onPage(Page.HOME) }) }
                }
                Page.HEALTH_SYNC -> {
                    item { HealthSyncStatusCard(snapshot) }
                    item { FilledTonalButton(onClick = onRequestHealth, enabled = snapshot.sapReady) {
                        Text("Запросить данные")
                    } }
                }
                Page.DEBUG -> {
                    item { Card {
                        Column(Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Состояние связи", style = MaterialTheme.typography.titleMedium)
                            Text(snapshot.status)
                            Text("Канал данных: ${if (snapshot.sapReady) "готов" else "не готов"} · пакетов в памяти: ${snapshot.protocolLog.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Health: ${snapshot.healthStatus}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onRefresh, enabled = snapshot.sapReady) {
                            Text("Обновить всё")
                        }
                        OutlinedButton(onClick = onRequestHealth, enabled = snapshot.sapReady) {
                            Text("Запрос Health")
                        }
                    } }
                    item { OutlinedButton(onClick = onRequestHealthCapabilityForDebug, enabled = snapshot.sapReady) {
                        Text("Проверить обмен Health")
                    } }
                    item { SettingsChoice("Прошивальщик", "Выбор и проверка пакета", { onPage(Page.FLASHER) }) }
                    item { SettingsChoice("Синхронизация Fit3 Health", "Проверка обмена данными здоровья",
                        { onPage(Page.HEALTH_SYNC) }) }
                    item { SettingsChoice("Журнал протокола", "Пакеты обмена, копирование и очистка",
                        { onPage(Page.PROTOCOL_LOG) }) }
                    item { Text("Проверка уведомлений", style = MaterialTheme.typography.titleMedium) }
                    item { FilledTonalButton(onClick = onSendTestNotification, enabled = snapshot.sapReady) {
                        Text("Отправить тест на браслет")
                    } }
                    item { Text(snapshot.notificationStatus, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { SettingSwitch("Сохранять журнал для ADB", adbLogEnabled, onAdbLogEnabled) }
                    item { Text("В отладочной сборке журнал можно снять командой: adb shell run-as io.github.yuriyurin.fit3companion cat files/protocol-monitor.log. Файл может содержать данные здоровья; запись выключена по умолчанию и ограничена 4 МБ.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Page.PROTOCOL_LOG -> {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCopyCompactProtocolLog, enabled = snapshot.protocolLog.isNotEmpty()) { Text("Краткий") }
                        OutlinedButton(onClick = onCopyProtocolLog, enabled = snapshot.protocolLog.isNotEmpty()) { Text("Полный") }
                        TextButton(onClick = onClearProtocolLog) { Text("Очистить") }
                    } }
                    item { Text("Журнал может содержать сведения о здоровье. Передавайте его только тем, кому доверяете.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (snapshot.protocolLog.isEmpty()) item { Text("Пакетов пока нет") }
                    items(snapshot.protocolLog.take(60)) { line ->
                        Text(line.take(800) + if (line.length > 800) " …" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Page.ABOUT -> {
                    item {
                        Box(Modifier.fillParentMaxHeight().fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Fit3 App", style = MaterialTheme.typography.titleLarge)
                                Text("Независимое приложение для Galaxy Fit3. Подключение и доступные функции работают без облака Samsung.")
                                Text("Автор: yuriyurin",
                                    modifier = Modifier.clickable { openAboutLink(context, AboutLinks.AUTHOR) },
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                                FilledTonalButton(onClick = { openAboutLink(context, AboutLinks.SUPPORT) }) {
                                    Icon(painterResource(R.drawable.ic_heart_outline), contentDescription = null,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.about_support))
                                }
                                SettingsChoice("Лицензии и благодарности", "Источник погоды и условия использования",
                                    { onPage(Page.LICENSES) })
                            }
                            Text("Версия $installedVersionName", modifier = Modifier.align(Alignment.BottomCenter),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                        }
                    }
                }
                Page.LICENSES -> {
                    item { Text("Погода: Open-Meteo", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Данные Open-Meteo используются в Fit3 App в личных некоммерческих целях. Спасибо Open-Meteo и поставщикам погодных моделей за открытый прогноз.") }
                    item { Text("Географический поиск основан на данных GeoNames. Прогноз может отличаться от фактической погоды.") }
                }
                Page.FLASHER -> {
                    item { Text("Выберите пакет с телефона, чтобы проверить его размер и SHA-256. Запись на часы в этой версии отключена: защищённый протокол передачи ещё не перенесён с ПК.") }
                    item { Button(onClick = onChooseFirmware) { Text("Выбрать файл прошивки") } }
                    if (firmwareFile != null) {
                        item { Card { Column(Modifier.padding(16.dp)) {
                            Text(firmwareFile.name, style = MaterialTheme.typography.titleMedium)
                            if (firmwareFile.valid) {
                                Text("Размер: ${firmwareFile.size} байт")
                                Text("SHA-256: ${firmwareFile.sha256}", style = MaterialTheme.typography.bodySmall)
                            } else Text(firmwareFile.sha256)
                        } } }
                    }
                    item { Button(onClick = {}, enabled = false) { Text("Прошить часы — пока недоступно") } }
                    item { Button(onClick = { onPage(Page.DEBUG) }) { Text("Назад к отладке") } }
                }
            }
            }
          }
        }
    }
    customMenu?.let { face -> CustomFaceActionsDialog(face, customLibrary,
        onDismiss = { customMenu = null }, onChanged = { onCustomFacesChanged(); customMenu = null },
        initialAction = customMenuAction) }
    extendedFaceInfo?.let {
        AlertDialog(onDismissRequest = { extendedFaceInfo = null },
            title = { Text(stringResource(R.string.custom_faces_extended_title)) },
            text = { Text(stringResource(R.string.custom_faces_extended_info)) },
            confirmButton = { TextButton(onClick = { extendedFaceInfo = null }) {
                Text(stringResource(android.R.string.ok))
            } })
    }
    watchDeleteCandidate?.let { face ->
        val sourceKey = snapshot.customFaceKeys[face.id to face.sampler]
        val localCopy = customFaces.firstOrNull { it.key == sourceKey && it.faceId == face.id }
        val canDelete = io.github.yuriyurin.fit3companion.protocol.Fit3FacePolicy.canDelete(
            snapshot.installedFaces, face.id, face.sampler, snapshot.currentFaceId,
            snapshot.currentFaceSampler, snapshot.facesReady && snapshot.sapReady && !snapshot.facesLoading) &&
            !snapshot.faceChangePending && !snapshot.faceInstallPending && snapshot.faceDeleteState !in listOf(
                io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.CHECKING,
                io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.SENDING,
                io.github.yuriyurin.fit3companion.protocol.FaceDeleteState.VERIFYING)
        if (!watchDeleteConfirmation) CompactFaceMenu(
            onDismiss = { watchDeleteCandidate = null },
            onRename = localCopy?.let { copy -> { customMenuAction = "rename"; customMenu = copy; watchDeleteCandidate = null } },
            onDelete = { watchDeleteConfirmation = true }, deleteLabel = R.string.watch_faces_delete_action,
            deleteEnabled = canDelete,
            onDeleteLocal = localCopy?.let { copy -> { customMenuAction = "delete"; customMenu = copy; watchDeleteCandidate = null } },
            onVariants = localCopy?.takeIf { it.styles.size > 1 }?.let { copy ->
                { customVariants = copy; watchDeleteCandidate = null }
            })
        else AlertDialog(onDismissRequest = { watchDeleteCandidate = null },
            title = { Text(stringResource(R.string.watch_faces_delete_title)) },
            text = { Text(stringResource(if (canDelete) R.string.watch_faces_delete_question else R.string.watch_faces_delete_protected)) },
            confirmButton = { TextButton(enabled = canDelete, onClick = {
                watchDeleteCandidate = null; onDeleteFace(face.id, face.sampler)
            }) { Text(stringResource(R.string.custom_faces_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { watchDeleteCandidate = null }) {
                Text(stringResource(R.string.custom_faces_cancel))
            } })
    }
    customVariants?.let { face -> CustomFaceVariantsDialog(face, customLibrary,
        enabled = snapshot.sapReady && !snapshot.faceInstallPending &&
            (snapshot.batteryPercent == null || snapshot.batteryPercent >= 30),
        onDismiss = { customVariants = null }, onSelect = { style ->
            customVariants = null; onInstallCustomFace(face, style)
        }) }
    if (showBackupInterval) AlertDialog(
        onDismissRequest = { showBackupInterval = false },
        title = { Text("Автоматическое копирование") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(0 to "Нет", 3 to "Раз в 3 часа", 6 to "Раз в 6 часов",
                12 to "Раз в 12 часов", 24 to "Раз в день", 72 to "Раз в 3 дня",
                168 to "Раз в 7 дней").forEach { (hours, label) ->
                TextButton(onClick = { onAutoBackupHours(hours); showBackupInterval = false },
                    modifier = Modifier.fillMaxWidth()) {
                    Text((if (autoBackupHours == hours) "✓  " else "   ") + label)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { showBackupInterval = false }) { Text("Готово") } },
    )
    if (orientationDialog != null) AlertDialog(
        onDismissRequest = { orientationDialog = null },
        title = { Text(if (orientationDialog == "wrist") "Предпочитаемое запястье" else "Положение кнопки") },
        text = { Column {
            listOf(false, true).forEach { right ->
                TextButton(onClick = {
                    if (orientationDialog == "wrist") onRightWrist(right) else onButtonOnRight(right)
                    orientationDialog = null
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (orientationDialog == "wrist") (if (right) "Правое" else "Левое")
                        else (if (right) "Справа" else "Слева"))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { orientationDialog = null }) { Text("Отмена") } },
    )
    if (showCityDialog) AlertDialog(
        onDismissRequest = { showCityDialog = false },
        title = { Text("Выбрать город") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = cityQuery, onValueChange = { cityQuery = it },
                label = { Text("Город") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (cityQuery.length in 1..2 && citySuggestions.isEmpty())
                Text("Введите ещё буквы для поиска", style = MaterialTheme.typography.bodySmall)
            citySuggestions.forEach { city ->
                TextButton(onClick = {
                    onChooseWeatherCity(city)
                    showCityDialog = false
                }, modifier = Modifier.fillMaxWidth()) { Text(city.label, localize = false) }
            }
        } },
        confirmButton = { TextButton(onClick = { showCityDialog = false }) { Text("Закрыть") } },
    )
    if (showListenerAccessPrompt) AlertDialog(
        onDismissRequest = { onListenerAccessPrompt(false) },
        title = { Text("Уведомления и музыка на браслете") },
        text = { Text("Разрешите Fit3 App системный доступ к уведомлениям. Он нужен для передачи сообщений и определения активного плеера, например Spotify. Позже доступ можно включить в настройках приложения.") },
        confirmButton = { TextButton(onClick = { onListenerAccessPrompt(true) }) { Text("Открыть настройки") } },
        dismissButton = { TextButton(onClick = { onListenerAccessPrompt(false) }) { Text("Позже") } },
    )
}

private fun buildCompactProtocolReport(snapshot: BleSnapshot, version: String): String = buildString {
    val health = snapshot.health
    val zone = ZoneId.systemDefault()
    appendLine("Fit3 App · краткий журнал")
    appendLine("Версия: $version")
    appendLine("Время телефона: ${Instant.now().atZone(zone)}")
    appendLine("Соединение: ${snapshot.status}")
    appendLine("Health: ${snapshot.healthStatus}")
    appendLine("Шаги: ${health.steps ?: "—"}${if (health.stepsPartial) " (неполная сумма)" else ""}")
    appendLine("Интервалов: ${health.stepRecords.size}; день: ${health.stepDay?.let { LocalDate.ofEpochDay(it) } ?: "—"}")
    appendLine("Дистанция: ${health.distanceMeters ?: "—"}; калории: ${health.activeCalories ?: "—"}")
    appendLine("Пульс: ${health.heartRate ?: "—"}; стресс: ${health.stress ?: "—"}; SpO₂: ${health.spo2 ?: "—"}")
    appendLine("Период сна: ${health.sleepMinutes ?: "—"} мин; оценка: ${health.sleepScore ?: "—"}")
    appendLine("Батарея часов: ${snapshot.batteryPercent ?: "—"}%")
    appendLine("Типы Health: ${health.lastDataTypes.joinToString().ifEmpty { "—" }}")
    appendLine("Пакетов в журнале: ${snapshot.protocolLog.size}")
    appendLine()
    appendLine("Последние важные пакеты (повторы свёрнуты):")
    val syncPrefix = "RX S10/Health [SYNC_DATA REQ]"
    val syncLines = snapshot.protocolLog.filter { it.startsWith(syncPrefix) }
    for (type in listOf(7, 10, 16, 17, 18, 19, 112, 137, 139, 140)) {
        val matches = syncLines.filter { line ->
            line.substringAfter("  ", "").split(' ').getOrNull(5)?.toIntOrNull(16) == type
        }
        if (matches.isEmpty()) continue
        val payload = matches.first().substringAfter("  ", "")
        appendLine("SYNC_DATA type=$type ×${matches.size}: ${payload.take(950)}${if (payload.length > 950) " …" else ""}")
    }
    val prefixes = listOf(
        "RX S10/Health [REQUEST_DATA REQ]",
        "RX S10/Health [WEARABLE_CAPABILITY REQ]",
        "TX S10/Health [WEARABLE_CAPABILITY RSP]",
        "RX S11/Settings BATTERY",
    )
    for (prefix in prefixes) {
        val matches = snapshot.protocolLog.filter { it.startsWith(prefix) }
        if (matches.isEmpty()) continue
        val latest = matches.first()
        val payload = latest.substringAfter("  ", "")
        appendLine("$prefix ×${matches.size}: ${payload.take(90)}${if (payload.length > 90) " …" else ""}")
    }
}

private fun getInstalledVersionName(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: "unknown"
} catch (_: Exception) {
    "unknown"
}

@Composable
private fun DiscreteIntSettingSlider(
    title: String,
    value: Int?,
    values: List<Int>,
    enabled: Boolean,
    valueLabel: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    require(values.size >= 2)
    val haptic = LocalHapticFeedback.current
    val initialIndex = value?.let { current ->
        values.indices.minByOrNull { kotlin.math.abs(values[it] - current) }
    } ?: 0
    var index by remember(value, values) { mutableStateOf(initialIndex) }
    var lastHapticIndex by remember(value, values) { mutableStateOf(initialIndex) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$title: ${valueLabel(values[index.coerceIn(values.indices)])}",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = index.toFloat(),
                onValueChange = { raw ->
                    val next = raw.roundToInt().coerceIn(values.indices)
                    if (next != index) index = next
                    if (next != lastHapticIndex) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastHapticIndex = next
                    }
                },
                onValueChangeFinished = { onCommit(values[index.coerceIn(values.indices)]) },
                valueRange = 0f..values.lastIndex.toFloat(),
                steps = (values.size - 2).coerceAtLeast(0),
                enabled = enabled,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                values.forEachIndexed { i, v ->
                    if (i == 0 || i == values.lastIndex || values.size <= 5) {
                        Text(valueLabel(v), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean?, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (value == null) Text("Состояние ещё не прочитано", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = value == true,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(it)
                },
                enabled = value != null,
            )
        }
    }
}

@Composable
private fun OrderedItemCard(
    item: Fit3OrderedItem,
    title: String,
    all: List<Fit3OrderedItem>,
    onSet: (List<Int>) -> Unit,
) {
    val ordered = all.sortedBy { it.order }
    val index = ordered.indexOfFirst { it.id == item.id }
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}. $title", modifier = Modifier.weight(1f))
            Button(onClick = {
                if (index > 0) {
                    val ids = ordered.map { it.id }.toMutableList()
                    val tmp = ids[index - 1]; ids[index - 1] = ids[index]; ids[index] = tmp
                    onSet(ids)
                }
            }, enabled = index > 0) { Text("↑") }
            Button(onClick = {
                if (index >= 0 && index < ordered.lastIndex) {
                    val ids = ordered.map { it.id }.toMutableList()
                    val tmp = ids[index + 1]; ids[index + 1] = ids[index]; ids[index] = tmp
                    onSet(ids)
                }
            }, enabled = index >= 0 && index < ordered.lastIndex) { Text("↓") }
        }
    }
}

@Composable
private fun WatchLayoutPreview(items: List<Fit3OrderedItem>, quickPanel: Boolean,
                               titleFor: (Int) -> String) {
    val ordered = items.sortedBy { it.order }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (quickPanel) "Как выглядит на браслете" else "Карточки на браслете",
                style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val pages = if (quickPanel) ordered.chunked(6) else ordered.map { listOf(it) }
                items(pages) { page ->
                    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0D1016),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        if (quickPanel) {
                            Column(Modifier.size(180.dp, 244.dp).padding(16.dp),
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                page.chunked(2).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                        row.forEach { entry ->
                                            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Surface(shape = CircleShape, color = Color(0xFF303540)) {
                                                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                                        Text(titleFor(entry.id).take(1), color = Color.White,
                                                            style = MaterialTheme.typography.titleMedium)
                                                    }
                                                }
                                                Text(titleFor(entry.id), color = Color.White,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    textAlign = TextAlign.Center, maxLines = 2)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val entry = page.first()
                            Column(Modifier.size(108.dp, 160.dp).padding(12.dp),
                                verticalArrangement = Arrangement.SpaceBetween) {
                                Text(titleFor(entry.id), color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium, maxLines = 2)
                                Text("${entry.order}", color = Color.White,
                                    style = MaterialTheme.typography.headlineLarge)
                                LinearProgressIndicator(progress = { .64f }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            Text("Изменить порядок можно кнопками ниже", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickMessagesEditor(
    current: List<String>,
    enabled: Boolean,
    onSet: (List<String>) -> Unit,
) {
    var text by remember(current) { mutableStateOf(
        (if (current.isEmpty()) listOf("Да", "Нет", "Ок", "Скоро буду", "Перезвоню позже")
            .map(AppStrings::translate) else current).joinToString("\n")
    ) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Один ответ на строку", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                minLines = 5, maxLines = 12)
            Button(onClick = { onSet(text.lines().map(String::trim).filter(String::isNotEmpty)) }, enabled = enabled) {
                Text("Отправить на часы")
            }
        }
    }
}

@Composable
private fun HomeDashboard(
    tiles: List<HomeTile>, editing: Boolean, snapshot: BleSnapshot, stepGoal: Int,
    officialStyle: OfficialFaceStyle?, catalog: OfficialFaceCatalog,
    onTilesChanged: (List<HomeTile>) -> Unit, onOpenBand: () -> Unit,
    onOpenDiscovery: () -> Unit, onOpenActivity: () -> Unit, onChooseCity: () -> Unit,
) {
    val placements = remember(tiles) { HomeDashboardLayout.place(tiles) }
    val rowCount = placements.maxOfOrNull { it.row + it.rows } ?: 0
    val gap = 12.dp
    val rowHeight = 156.dp
    val haptic = LocalHapticFeedback.current
    val dragElevationPx = with(LocalDensity.current) { 16.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth - gap) / 2
        Box(Modifier.fillMaxWidth().height(rowHeight * rowCount + gap * (rowCount - 1).coerceAtLeast(0))) {
            for (placement in placements) {
                val tile = placement.tile
                val x = (cellWidth + gap) * placement.column
                val y = (rowHeight + gap) * placement.row
                val width = cellWidth * placement.columns + gap * (placement.columns - 1)
                val height = rowHeight * placement.rows + gap * (placement.rows - 1)
                val animatedX by animateDpAsState(x, label = "tile-x-${tile.kind}")
                val animatedY by animateDpAsState(y, label = "tile-y-${tile.kind}")
                var drag by remember(tile.kind) { mutableStateOf(Offset.Zero) }
                var dragging by remember(tile.kind) { mutableStateOf(false) }
                val dragModifier = if (editing) Modifier.pointerInput(tiles, placement) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = {
                            if (drag.getDistance() > 18.dp.toPx()) {
                                val centerX = x.toPx() + width.toPx() / 2f + drag.x
                                val centerY = y.toPx() + height.toPx() / 2f + drag.y
                                val target = placements.minByOrNull { other ->
                                    val otherX = ((cellWidth + gap) * other.column).toPx() +
                                        (cellWidth * other.columns + gap * (other.columns - 1)).toPx() / 2f
                                    val otherY = ((rowHeight + gap) * other.row).toPx() +
                                        (rowHeight * other.rows + gap * (other.rows - 1)).toPx() / 2f
                                    (otherX - centerX) * (otherX - centerX) +
                                        (otherY - centerY) * (otherY - centerY)
                                }
                                if (target != null && target.tile.kind != tile.kind)
                                    onTilesChanged(HomeDashboardLayout.swap(tiles, tile.kind, target.tile.kind))
                            }
                            drag = Offset.Zero
                            dragging = false
                        },
                        onDragCancel = { drag = Offset.Zero; dragging = false },
                        onDrag = { change, amount -> change.consume(); drag += amount },
                    )
                } else Modifier
                Box(Modifier.offset(animatedX, animatedY).size(width, height)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer(translationX = drag.x, translationY = drag.y,
                        shadowElevation = if (dragging) dragElevationPx else 0f)
                    .then(dragModifier)) {
                    HomeDashboardTile(tile, editing, snapshot, stepGoal, officialStyle, catalog,
                        onOpenBand, onOpenDiscovery, onOpenActivity, onChooseCity)
                    if (editing) {
                        var sizeMenu by remember(tile.kind) { mutableStateOf(false) }
                        Surface(Modifier.align(Alignment.BottomCenter).padding(6.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("⠿", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.weight(1f))
                                Box {
                                    TextButton(onClick = { sizeMenu = true }) { Text(tile.size.label) }
                                    DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                                        HomeTileSize.entries.forEach { size ->
                                            DropdownMenuItem(text = { Text(size.label) }, onClick = {
                                                sizeMenu = false
                                                onTilesChanged(HomeDashboardLayout.resize(tiles, tile.kind, size))
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDashboardTile(
    tile: HomeTile, editing: Boolean, snapshot: BleSnapshot, stepGoal: Int,
    officialStyle: OfficialFaceStyle?, catalog: OfficialFaceCatalog,
    onOpenBand: () -> Unit, onOpenDiscovery: () -> Unit,
    onOpenActivity: () -> Unit, onChooseCity: () -> Unit,
) {
    val isSmall = tile.size == HomeTileSize.SMALL
    val isLarge = tile.size == HomeTileSize.LARGE
    val click = when (tile.kind) {
        HomeTileKind.WATCH -> if (snapshot.connected) onOpenBand else onOpenDiscovery
        HomeTileKind.WEATHER -> onChooseCity
        else -> onOpenActivity
    }
    val background = when (tile.kind) {
        HomeTileKind.WATCH -> MaterialTheme.colorScheme.secondaryContainer
        HomeTileKind.WEATHER -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(modifier = Modifier.fillMaxSize().clickable(enabled = !editing, onClick = click),
        colors = CardDefaults.cardColors(containerColor = background)) {
        when (tile.kind) {
            HomeTileKind.WATCH -> {
                if (isSmall) Column(Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_nav_faces), contentDescription = null,
                        modifier = Modifier.size(25.dp))
                    Text("Galaxy Fit3", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(snapshot.batteryPercent?.let { "$it% заряд" } ?:
                        if (snapshot.connected) "Заряд —" else "Не подключено",
                        style = MaterialTheme.typography.bodySmall)
                } else Row(Modifier.fillMaxSize().padding(if (isLarge) 20.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isLarge) 18.dp else 12.dp)) {
                    if (isLarge) StockWatchFacePreview(snapshot.currentFaceId,
                        officialStyle = officialStyle, catalog = catalog)
                    else DashboardMiniFace(snapshot.currentFaceId, officialStyle, catalog)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Galaxy Fit3", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (snapshot.connected) "Подключено" else "Не подключено",
                            style = MaterialTheme.typography.bodySmall)
                        Text(snapshot.batteryPercent?.let { "Заряд $it%" } ?: "Заряд —",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HomeTileKind.WEATHER -> {
                val weather = snapshot.weather
                Column(Modifier.fillMaxSize().padding(14.dp),
                    verticalArrangement = if (isLarge) Arrangement.spacedBy(4.dp)
                        else Arrangement.Center) {
                    if (weather == null) {
                        Text("Погода", style = MaterialTheme.typography.titleMedium)
                        Text("Выбрать город ›", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${weather.temperature.roundToInt()}°",
                                    style = if (isSmall) MaterialTheme.typography.headlineMedium
                                    else MaterialTheme.typography.displaySmall)
                                Text(weather.city.name, localize = false, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                WeatherOutlineIcon(weather.code, weather.isDay,
                                    if (isSmall) 30.dp else 36.dp)
                                if (!isSmall) Text("↑ ${weather.high.roundToInt()}°  ↓ ${weather.low.roundToInt()}°",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (!isSmall && !editing) {
                            val now = System.currentTimeMillis() / 1000
                            val upcoming = weather.hours.filter { it.time > now + 300 }
                                .take(if (isLarge) 5 else 3)
                            if (isLarge) Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                WeatherHourCell(LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:mm")),
                                    weather.code, weather.isDay, weather.temperature.roundToInt(), Modifier.weight(1f))
                                upcoming.forEach { hour ->
                                    val time = Instant.ofEpochSecond(hour.time).atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("H:mm"))
                                    WeatherHourCell(time, hour.code, hour.isDay,
                                    hour.temperature.roundToInt(), Modifier.weight(1f))
                                }
                            }
                            if (isLarge) Text("по данным Open-Meteo", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> DashboardMetricTile(tile, snapshot, stepGoal, editing)
        }
    }
}

@Composable
private fun DashboardMetricTile(tile: HomeTile, snapshot: BleSnapshot, stepGoal: Int, editing: Boolean) {
    val health = snapshot.health
    val todaySteps = health.steps.takeIf { health.stepDay == LocalDate.now().toEpochDay() }
    val (value, detail) = when (tile.kind) {
        HomeTileKind.STEPS -> (todaySteps?.toString() ?: "—") to
            (todaySteps?.let { "Цель $stepGoal · ${(it * 100L / stepGoal).coerceAtMost(999)}%" } ?: "За сегодня")
        HomeTileKind.SLEEP -> (health.sleepMinutes?.let(::formatMinutes) ?: "—") to "Сегодня"
        HomeTileKind.PULSE -> (health.heartRate?.let { "$it" } ?: "—") to "уд/мин"
        HomeTileKind.STRESS -> (health.stress?.toString() ?: "—") to
            (health.stressAt?.let { "Измерено ${dashboardTime(it)}" } ?: "Нет замера")
        HomeTileKind.SPO2 -> (health.spo2?.let { "$it%" } ?: "—") to
            (health.spo2At?.let { "Измерено ${dashboardTime(it)}" } ?: "Нет замера")
        HomeTileKind.DISTANCE -> (health.distanceMeters?.let(::formatDistance) ?: "—") to "За сегодня"
        else -> "—" to ""
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tile.kind.title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = if (tile.size == HomeTileSize.LARGE)
            MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tile.kind == HomeTileKind.STRESS && health.stress != null) {
            val marker = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxWidth().height(15.dp)) {
                val lineY = size.height / 2f
                drawLine(Brush.horizontalGradient(listOf(Color(0xFF53B9DF), Color(0xFF56CEA9),
                    Color(0xFFD8D56C), Color(0xFFF09A5E))),
                    Offset(0f, lineY), Offset(size.width, lineY), 7.dp.toPx(), StrokeCap.Round)
                drawCircle(marker, 6.dp.toPx(), Offset((size.width * health.stress / 100f)
                    .coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), lineY))
            }
        }
        if (!editing || tile.size != HomeTileSize.SMALL) {
            Spacer(Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun dashboardTime(timestamp: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp))

@Composable
private fun DashboardMiniFace(faceId: Int?, officialStyle: OfficialFaceStyle?,
    catalog: OfficialFaceCatalog) {
    val custom = installedPreview(faceId, LocalCurrentFaceSampler.current)
    val bitmap by produceState<Bitmap?>(initialValue = null, officialStyle?.previewUrl) {
        value = if (officialStyle?.previewUrl.isNullOrBlank()) null else
            withContext(Dispatchers.IO) {
                runCatching { catalog.preview(officialStyle!!.previewUrl) }.getOrNull()
            }
    }
    Fit3PreviewFrame(Modifier.width(58.dp)) {
        val shown = if (custom.custom) custom.bitmap else bitmap
        if (shown != null) Image(shown.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else (if (custom.custom) null else stockFaceDrawable(faceId))?.let { drawable ->
            Image(painterResource(drawable), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
        } ?: Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_nav_faces), contentDescription = null,
                modifier = Modifier.size(26.dp), tint = Color.White)
        }
    }
}

@Composable
private fun HomeWatchCard(snapshot: BleSnapshot, officialStyle: OfficialFaceStyle?,
    catalog: OfficialFaceCatalog, onOpenBand: () -> Unit, onOpenDiscovery: () -> Unit) {
    Card(onClick = if (snapshot.connected) onOpenBand else onOpenDiscovery,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().heightIn(min = MainHeroHeight).padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            StockWatchFacePreview(if (snapshot.connected) snapshot.currentFaceId else null,
                officialStyle = officialStyle, catalog = catalog)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Galaxy Fit3", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(if (snapshot.connected) "Подключено" else snapshot.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.connected) {
                    Text(snapshot.batteryPercent?.let { "Заряд $it%${if (snapshot.charging) " · зарядка" else ""}" }
                        ?: "Заряд пока не получен", style = MaterialTheme.typography.bodyMedium)
                    Text("Циферблат и настройки →", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Найти браслет →", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun HomeHealthSummary(snapshot: BleSnapshot, onOpenActivity: () -> Unit) {
    Card(onClick = onOpenActivity) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Активность и здоровье", style = MaterialTheme.typography.titleLarge)
            val health = snapshot.health
            val todaySteps = health.steps.takeIf { health.stepDay == LocalDate.now().toEpochDay() }
            Text("Шаги  ${todaySteps?.toString() ?: "—"}", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Сон  ${health.sleepMinutes?.let(::formatMinutes) ?: "—"}")
                Text("Пульс  ${health.heartRate?.let { "$it уд/мин" } ?: "—"}")
            }
            val updatedAt = listOfNotNull(health.lastSuccessfulSyncMillis,
                snapshot.lastRefreshMillis, health.lastSyncMillis).maxOrNull()
            val timeFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            Text(updatedAt?.let { "Данные обновлены: ${timeFormat.format(java.util.Date(it))}" }
                ?: "Измерения ещё не получены с браслета",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val popularWeatherCities = listOf(
    WeatherCity(-1, "Москва", "", "Россия", 55.7558, 37.6173, "Europe/Moscow"),
    WeatherCity(-2, "Санкт-Петербург", "", "Россия", 59.9343, 30.3351, "Europe/Moscow"),
    WeatherCity(-3, "Казань", "Татарстан", "Россия", 55.7963, 49.1088, "Europe/Moscow"),
    WeatherCity(-4, "Новосибирск", "", "Россия", 55.0302, 82.9204, "Asia/Novosibirsk"),
    WeatherCity(-5, "Екатеринбург", "", "Россия", 56.8389, 60.6057, "Asia/Yekaterinburg"),
    WeatherCity(-6, "Ростов-на-Дону", "", "Россия", 47.2357, 39.7015, "Europe/Moscow"),
)

@Composable
private fun HomeWeatherCard(snapshot: BleSnapshot, onChooseCity: () -> Unit) {
    val weather = snapshot.weather
    Card(modifier = Modifier.clickable(onClick = onChooseCity),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (weather != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                    Text("${weather.temperature.roundToInt()}°",
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
                        Text("${weather.city.name}  ›", localize = false, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        WeatherOutlineIcon(weather.code, weather.isDay)
                        Text("↑ ${weather.high.roundToInt()}°   ↓ ${weather.low.roundToInt()}°",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                val now = System.currentTimeMillis() / 1000
                val upcoming = weather.hours.filter { it.time > now + 300 }.take(5)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WeatherHourCell(LocalDateTime.now().format(DateTimeFormatter.ofPattern("H:mm")),
                        weather.code, weather.isDay, weather.temperature.roundToInt(), Modifier.weight(1f))
                    upcoming.forEach { hour ->
                        val time = Instant.ofEpochSecond(hour.time).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("H:mm"))
                        WeatherHourCell(time, hour.code, hour.isDay,
                            hour.temperature.roundToInt(), Modifier.weight(1f))
                    }
                }
                Text("по данным Open-Meteo", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Погода", style = MaterialTheme.typography.titleMedium)
                Text("Выбрать город  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(snapshot.weatherStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeatherHourCell(time: String, code: Int, isDay: Boolean, temperature: Int,
    modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(time, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        WeatherOutlineIcon(code, isDay, 23.dp)
        Text("${temperature}°", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold)
    }
}

private fun weatherDescription(code: Int): String = when (code) {
    0 -> "Ясно"
    1, 2 -> "Переменная облачность"
    3 -> "Облачно"
    45, 48 -> "Туман"
    in 51..67, in 80..82 -> "Дождь"
    in 71..77, 85, 86 -> "Снег"
    in 95..99 -> "Гроза"
    else -> "Прогноз"
}

@Composable
private fun WeatherOutlineIcon(code: Int?, isDay: Boolean, iconSize: Dp = 48.dp) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(iconSize)) {
        val stroke = (iconSize.value / 20f).dp.toPx()
        val cx = size.width * .5f
        val cy = size.height * .48f
        if (code == null || code <= 2) {
            drawCircle(color, size.minDimension * .19f, Offset(cx, cy), style = Stroke(stroke))
            if (isDay) repeat(8) { i ->
                val angle = Math.PI * i / 4
                val a = size.minDimension * .31f
                val b = size.minDimension * .43f
                drawLine(color,
                    Offset(cx + (kotlin.math.cos(angle) * a).toFloat(),
                        cy + (kotlin.math.sin(angle) * a).toFloat()),
                    Offset(cx + (kotlin.math.cos(angle) * b).toFloat(),
                        cy + (kotlin.math.sin(angle) * b).toFloat()),
                    stroke, cap = StrokeCap.Round)
            }
        } else {
            val cloud = Path().apply {
                moveTo(size.width * .2f, size.height * .65f)
                cubicTo(size.width * .04f, size.height * .64f, size.width * .07f, size.height * .44f,
                    size.width * .28f, size.height * .43f)
                cubicTo(size.width * .35f, size.height * .13f, size.width * .72f, size.height * .17f,
                    size.width * .76f, size.height * .43f)
                cubicTo(size.width * .99f, size.height * .45f, size.width * .98f, size.height * .66f,
                    size.width * .8f, size.height * .66f)
                lineTo(size.width * .2f, size.height * .65f)
            }
            drawPath(cloud, color, style = Stroke(stroke, cap = StrokeCap.Round))
            if (code in 51..99 && code !in 71..77 && code != 85 && code != 86) {
                repeat(3) { i ->
                    val x = size.width * (.33f + i * .17f)
                    drawLine(color, Offset(x, size.height * .75f),
                        Offset(x - size.width * .05f, size.height * .88f), stroke,
                        cap = StrokeCap.Round)
                }
            } else if (code in 71..77 || code == 85 || code == 86) {
                repeat(3) { i -> drawCircle(color, stroke * .65f,
                    Offset(size.width * (.33f + i * .17f), size.height * .82f)) }
            }
        }
    }
}

@Composable
private fun HomeQuickActions(onPage: (Page) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { onPage(Page.FACES) }, modifier = Modifier.weight(1f)) {
            Text("Браслет")
        }
        FilledTonalButton(onClick = { onPage(Page.ACTIVITY) }, modifier = Modifier.weight(1f)) {
            Text("Активность")
        }
    }
}

@Composable
private fun DiscoveryIntro(snapshot: BleSnapshot) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Найдите свой Fit3", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Text("Поднесите браслет к телефону и выберите его ниже. Если Android запросит подтверждение сопряжения, проверьте экран браслета.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.status.startsWith("Ошибка") || snapshot.status.startsWith("Включите") ||
            snapshot.status.startsWith("Разрешите")) Text(snapshot.status,
                color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StockWatchFacePreview(faceId: Int?, large: Boolean = false,
    officialStyle: OfficialFaceStyle? = null, catalog: OfficialFaceCatalog? = null) {
    val custom = installedPreview(faceId, LocalCurrentFaceSampler.current)
    val preview = stockFaceDrawable(faceId)
    val officialBitmap by produceState<Bitmap?>(initialValue = null, officialStyle?.previewUrl) {
        value = if (officialStyle != null && catalog != null)
            withContext(Dispatchers.IO) { runCatching { catalog.preview(officialStyle.previewUrl) }.getOrNull() }
        else null
    }
    val width = if (large) 135.dp else 108.dp
    Fit3PreviewFrame(Modifier.width(width)) {
            val shown = if (custom.custom) custom.bitmap else officialBitmap
            if (shown != null) {
                Image(bitmap = shown.asImageBitmap(), contentDescription = AppStrings.translate("Текущий циферблат"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
            } else if (!custom.custom && preview != null) {
                Image(
                    painter = painterResource(preview),
                    contentDescription = AppStrings.translate("Превью циферблата $faceId"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.ic_nav_faces),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (large) 38.dp else 30.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "—",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    )
                }
            }
    }
}

private fun stockFaceDrawable(faceId: Int?): Int? = when (faceId) {
    22 -> R.drawable.face_22
    28 -> R.drawable.face_28
    46 -> R.drawable.face_46
    79 -> R.drawable.face_79
    106 -> R.drawable.face_106
    108 -> R.drawable.face_108
    254 -> R.drawable.face_254
    else -> null
}

@Composable
private fun CandidateCard(device: WatchCandidate, onConnect: (String) -> Unit) {
    Card(onClick = { onConnect(device.address) }) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, localize = false, fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium)
                Text(if (device.paired) "Уже сопряжён" else "Рядом · требуется подключение",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Выбрать →", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CurrentFaceCard(snapshot: BleSnapshot, officialStyle: OfficialFaceStyle?,
    officialName: String?,
    catalog: OfficialFaceCatalog, onClick: () -> Unit) {
    Card(onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MainHeroHeight).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StockWatchFacePreview(snapshot.currentFaceId, officialStyle = officialStyle, catalog = catalog)
            Column(Modifier.weight(1f)) {
                Text("Текущий циферблат", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (snapshot.currentFaceId != null) "Открыть циферблаты →" else "Подключите браслет",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val installed = snapshot.installedFaces.firstOrNull {
                    it.id == snapshot.currentFaceId && it.sampler == snapshot.currentFaceSampler
                }
                val key = snapshot.customFaceKeys[snapshot.currentFaceId to snapshot.currentFaceSampler]
                val customName = installedFaceName(key)
                val faceName = customName ?: if (key != null) stringResource(R.string.watch_faces_generic) else officialName ?: snapshot.currentFaceId?.let(::knownFaceName)
                    ?: installed?.name?.takeIf(String::isNotBlank)
                if (!faceName.isNullOrBlank()) {
                    Text(
                        faceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogFaceImage(style: OfficialFaceStyle?, catalog: OfficialFaceCatalog,
    faceId: Int, modifier: Modifier = Modifier, installedSampler: Int? = null) {
    val custom = if (installedSampler != null) installedPreview(faceId, installedSampler) else InstalledPreview(false, null)
    val bitmap by produceState<Bitmap?>(initialValue = null, style?.previewUrl, custom.custom) {
        value = if (!custom.custom && style != null && style.previewUrl.isNotBlank())
            withContext(Dispatchers.IO) { runCatching { catalog.preview(style.previewUrl) }.getOrNull() }
        else null
    }
    Fit3PreviewFrame(modifier.width(96.dp)) {
        val shown = if (custom.custom) custom.bitmap else bitmap
        if (shown != null) Image(shown.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else (if (custom.custom) null else stockFaceDrawable(faceId))?.let { drawable ->
            Image(painterResource(drawable), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_nav_faces), contentDescription = null,
                modifier = Modifier.size(30.dp), tint = Color.White)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun InstalledWatchFaceCell(face: OfficialFace, installed: Fit3SapCodec.InstalledFace,
    catalog: OfficialFaceCatalog, modifier: Modifier, selected: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, menuOpen: Boolean = false) {
    Card(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = when {
            menuOpen -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else -> null
        }) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CatalogFaceImage(face.styles.firstOrNull { it.id == installed.sampler }, catalog, face.id,
                installedSampler = installed.sampler)
            Text(face.name, localize = false, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium)
            Text(stringResource(if (selected) R.string.watch_faces_selected else R.string.custom_faces_variant,
                installed.sampler + 1), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FaceFamilyCell(face: OfficialFace, catalog: OfficialFaceCatalog, modifier: Modifier,
    installedSampler: Int? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CatalogFaceImage(face.styles.firstOrNull { it.id == installedSampler } ?: face.styles.firstOrNull(),
                catalog, face.id, installedSampler = installedSampler)
            Text(face.name, localize = false, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FaceStyleCell(face: OfficialFace, style: OfficialFaceStyle,
    catalog: OfficialFaceCatalog, modifier: Modifier, installed: Boolean,
    selected: Boolean, busy: Boolean, onSelect: () -> Unit) {
    Card(onClick = { if (!busy && !selected) onSelect() }, modifier = modifier,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CatalogFaceImage(style, catalog, face.id, installedSampler = if (installed) style.id else null)
            Text("Вариант ${style.id + 1}", style = MaterialTheme.typography.labelMedium,
                maxLines = 1)
            if (selected) Text("Выбран", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            else if (!installed) Text("Не установлен", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsChoice(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageSelector(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "system" to stringResource(R.string.language_system),
        "ru" to stringResource(R.string.language_russian),
        "en" to stringResource(R.string.language_english),
    )
    SettingsChoice(stringResource(R.string.language_title),
        options.firstOrNull { it.first == value }?.second ?: options.first().second,
        { expanded = true })
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.language_title)) },
        text = { Column(Modifier.fillMaxWidth()) {
            options.forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                    expanded = false
                    if (key != value) onChange(key)
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = key == value, onClick = {
                        expanded = false
                        if (key != value) onChange(key)
                    })
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("Закрыть") } },
    )
}

@Composable
private fun BandLanguageSelector(onSync: () -> Unit) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(BandLanguage.selection(context)) }
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(R.string.band_language_title)
    val followApp = stringResource(R.string.band_language_app)
    val options = listOf(io.github.yuriyurin.fit3companion.protocol.Fit3Languages.APP to followApp) +
        io.github.yuriyurin.fit3companion.protocol.Fit3Languages.ids.keys
            .map { it to io.github.yuriyurin.fit3companion.protocol.Fit3Languages.nativeName(it) }
            .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
    SettingsChoice(title, options.firstOrNull { it.first == value }?.second ?: followApp, { expanded = true })
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(options, key = { it.first }) { (key, label) ->
                    fun select() {
                        BandLanguage.set(context, key)
                        value = key
                        expanded = false
                        onSync()
                    }
                    Row(Modifier.fillMaxWidth().clickable { select() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = key == value, onClick = { select() })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false }) {
            Text(stringResource(R.string.band_language_close))
        } },
    )
}

@Composable
private fun ThemeSelector(value: String, onChange: (String, Offset) -> Unit) {
    val centers = remember { mutableStateMapOf<String, Offset>() }
    listOf("system" to "Как в системе", "dark" to "Тёмная", "light" to "Светлая",
        "amoled" to "AMOLED · чёрная")
        .forEach { (key, label) ->
            Card(onClick = { onChange(key, centers[key] ?: Offset.Zero) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).onGloballyPositioned {
                    val topLeft = it.positionInWindow()
                    centers[key] = Offset(topLeft.x + it.size.width / 2f,
                        topLeft.y + it.size.height / 2f)
                },
                colors = CardDefaults.cardColors(containerColor = if (value == key)
                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label)
                    if (value == key) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
}

@Composable
private fun AccentSelector(value: String, onChange: (String) -> Unit) {
    val adaptiveColor = if (Build.VERSION.SDK_INT >= 31) {
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(LocalContext.current).primary
        else dynamicLightColorScheme(LocalContext.current).primary
    } else Color(0xFF69BFA8)
    val accents = listOf(
        Triple("adaptive", adaptiveColor, "A"),
        Triple("blue", Color(0xFF4B81D8), ""),
        Triple("green", Color(0xFF32A773), ""),
        Triple("purple", Color(0xFF9267CF), ""),
        Triple("orange", Color(0xFFD5823E), ""),
        Triple("pink", Color(0xFFD46A91), ""),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        accents.forEach { (key, color, letter) ->
            Surface(
                onClick = { onChange(key) },
                shape = CircleShape,
                color = color,
                border = if (value == key) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                    else null,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (value == key && letter.isEmpty()) "✓" else letter,
                        color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DataCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun TodayHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Сегодня", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM", AppStrings.locale)),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The three default tab heroes share their footprint and content origin.
// A minimum rather than a fixed height preserves accessibility with enlarged text.
private val MainHeroHeight = 228.dp

@Composable
private fun StepsHeroCard(snapshot: BleSnapshot, localGoal: Int, dailySteps: Map<Long, Int>,
                          onActivityGoals: (ActivityGoals) -> Unit) {
    val steps = snapshot.health.steps.takeIf {
        snapshot.health.stepDay == LocalDate.now().toEpochDay()
    }
    val goal = localGoal.coerceAtLeast(1)
    var showGoalDialog by remember { mutableStateOf(false) }
    val rawProgress = if (steps != null) (steps / goal.toFloat()).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(rawProgress, animationSpec = tween(700), label = "stepProgress")
    Card(modifier = Modifier.clickable { showGoalDialog = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
      Column(Modifier.fillMaxWidth().heightIn(min = MainHeroHeight).padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                val track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .14f)
                val primary = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (steps != null) {
                        drawArc(primary, -90f, progress * 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(targetState = steps, label = "stepsValue") { value ->
                        Text(value?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                    }
                    Text("шагов", style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Активность", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val headline = when {
                    steps != null -> "Цель $goal · ${(progress * 100).roundToInt()}%"
                    else -> "Цель $goal шагов · ждём данные"
                }
                Text(headline, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
                if (steps != null && (snapshot.health.walkSteps != null || snapshot.health.runSteps != null)) {
                    Text("Ходьба ${snapshot.health.walkSteps ?: 0} · бег ${snapshot.health.runSteps ?: 0}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Изменить цель →", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.activity_goal_summary, snapshot.activityGoals.minutes,
                    snapshot.activityGoals.calories), style = MaterialTheme.typography.bodySmall)
            }
        }
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEachIndexed { index, label ->
                val day = monday.plusDays(index.toLong())
                val count = dailySteps[day.toEpochDay()]
                val reached = count != null && count >= goal
                val weekend = index >= 5
                val color = when {
                    reached -> Color(0xFF49C985)
                    weekend -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = CircleShape, border = BorderStroke(1.5.dp, color),
                        color = if (reached) color else Color.Transparent) {
                        Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = if (reached) Color.Black else color)
                        }
                    }
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                }
            }
        }
      }
    }
    if (showGoalDialog) ActivityGoalsDialog(snapshot.activityGoals,
        onDismiss = { showGoalDialog = false }, onSave = { goals ->
            onActivityGoals(goals); showGoalDialog = false
        })
}

@Composable
private fun ActivityGoalsDialog(goals: ActivityGoals, onDismiss: () -> Unit, onSave: (ActivityGoals) -> Unit) {
    var steps by remember { mutableStateOf(goals.steps.toString()) }
    var minutes by remember { mutableStateOf(goals.minutes.toString()) }
    var calories by remember { mutableStateOf(goals.calories.toString()) }
    val edited = ActivityGoals(steps.toIntOrNull() ?: 0, minutes.toIntOrNull() ?: 0, calories.toIntOrNull() ?: 0)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.activity_goals_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.activity_goals_description), style = MaterialTheme.typography.bodySmall)
            GoalInput(steps, { steps = it }, R.string.activity_goals_steps, "1000–50 000", 1000..50_000)
            GoalInput(minutes, { minutes = it }, R.string.activity_goals_minutes, "30–360", 30..360)
            GoalInput(calories, { calories = it }, R.string.activity_goals_calories, "100–5000", 100..5000)
        } },
        confirmButton = { TextButton(onClick = { onSave(edited) }, enabled = edited.valid()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun GoalInput(value: String, onChange: (String) -> Unit, label: Int, rangeLabel: String, range: IntRange) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.filter { c -> c in '0'..'9' }.take(5)) },
        label = { Text(stringResource(label)) }, supportingText = { Text(rangeLabel) },
        isError = value.toIntOrNull() !in range, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

@Composable
private fun MetricCard(label: String, value: String, subtitle: String) {
    Card(Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(targetState = value, label = "metric-$label") { shown ->
                Text(shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeeklyStepsCard(dailySteps: Map<Long, Int>, goal: Int) {
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val week = (0..6).map { index ->
        val day = monday.plusDays(index.toLong())
        day to dailySteps[day.toEpochDay()]?.takeIf { !day.isAfter(today) }
    }
    var selectedIndex by remember(monday) { mutableStateOf(today.dayOfWeek.value - 1) }
    val observed = week.mapNotNull { it.second }
    val ceiling = maxOf(observed.maxOrNull()?.let { (it * 1.15f).roundToInt() } ?: 0, 100).toFloat()
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Шаги за неделю", style = MaterialTheme.typography.titleMedium)
                Text("Цель $goal", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val primary = MaterialTheme.colorScheme.primary
            val guide = MaterialTheme.colorScheme.outlineVariant
            val surface = MaterialTheme.colorScheme.surface
            Canvas(Modifier.fillMaxWidth().height(142.dp).pointerInput(monday) {
                detectTapGestures { offset ->
                    selectedIndex = (offset.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                }
            }) {
                val top = 12.dp.toPx()
                val bottom = size.height - 10.dp.toPx()
                val height = bottom - top
                for (line in 0..2) {
                    val y = top + height * line / 2f
                    drawLine(guide, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val points = week.mapIndexed { index, (_, count) ->
                    count?.let {
                        Offset(size.width * (index + .5f) / 7f,
                            bottom - (it / ceiling).coerceIn(0f, 1f) * height)
                    }
                }
                for (index in 0 until 6) {
                    val start = points[index]
                    val end = points[index + 1]
                    if (start != null && end != null)
                        drawLine(primary, start, end, 3.dp.toPx(), StrokeCap.Round)
                }
                points.forEachIndexed { index, point ->
                    if (point != null) {
                        if (index == selectedIndex) drawCircle(surface, 9.dp.toPx(), point)
                        drawCircle(primary, if (index == selectedIndex) 6.dp.toPx() else 4.dp.toPx(), point)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val labels = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
                week.forEachIndexed { index, (day, _) ->
                    val selected = index == selectedIndex
                    Column(Modifier.weight(1f).clickable { selectedIndex = index },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(labels[index], style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                selected -> primary
                                index >= 5 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            })
                        Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val (selectedDay, selectedCount) = week[selectedIndex]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(selectedDay.format(DateTimeFormatter.ofPattern("d MMMM", AppStrings.locale)),
                    style = MaterialTheme.typography.bodyMedium)
                Text(when {
                    selectedDay.isAfter(today) -> "Впереди"
                    selectedCount == null -> "Нет данных"
                    else -> "$selectedCount шагов"
                }, style = MaterialTheme.typography.titleSmall,
                    color = if (selectedCount != null) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HealthVitalsCard(snapshot: BleSnapshot) {
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VitalCell("Пульс", snapshot.health.heartRate?.let { "$it уд/мин" } ?: "—")
            HorizontalDivider()
            StressGauge(snapshot.health.stress, snapshot.health.stressAt)
            HorizontalDivider()
            VitalCell("SpO₂", snapshot.health.spo2?.let { "$it%" } ?: "—")
            if (snapshot.health.heartRateMin != null || snapshot.health.heartRateMax != null) {
                Text("Пульс min ${snapshot.health.heartRateMin ?: "—"} · max ${snapshot.health.heartRateMax ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StressGauge(score: Int?, measuredAt: Long?) {
    val markerColor = MaterialTheme.colorScheme.onSurface
    val markerOutline = MaterialTheme.colorScheme.surface
    val colors = listOf(
        Color(0xFF53B9DF), Color(0xFF56CEA9), Color(0xFFD8D56C), Color(0xFFF09A5E),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom) {
            Text("Стресс", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(score?.let { "$it из 100" } ?: "—", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(25.dp)) {
            val barHeight = 12.dp.toPx()
            val barY = (size.height - barHeight) / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(colors),
                topLeft = Offset(0f, barY),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f),
            )
            score?.takeIf { it in 0..100 }?.let { value ->
                val radius = 9.dp.toPx()
                val center = Offset((size.width * value / 100f)
                    .coerceIn(radius, size.width - radius), size.height / 2f)
                drawCircle(markerOutline, radius + 2.dp.toPx(), center)
                drawCircle(markerColor, radius, center)
            }
        }
        Text(measuredAt?.let { timestamp ->
            val measuredDay = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val format = if (measuredDay == LocalDate.now())
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) else
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            "Измерено ${format.format(java.util.Date(timestamp))}"
        } ?: "Измерений пока нет", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VitalCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(targetState = value, label = "vital-$label") { shown ->
            Text(shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HealthSyncStatusCard(snapshot: BleSnapshot) {
    val health = snapshot.health
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Синхронизация Fit3 Health", style = MaterialTheme.typography.titleMedium)
            Text(snapshot.healthStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (health.sessionReady) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ) {
                    Text(if (health.sessionReady) "Capability OK" else "Handshake…",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                health.healthProtocolVersion?.let { version ->
                    Text("protocol $version", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (health.lastDataTypes.isNotEmpty()) {
                Text("Последние типы: ${health.lastDataTypes.joinToString { Fit3HealthCodec.dataTypeName(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            health.lastSyncMillis?.let { lastSync ->
                Text("Последние разобранные данные: ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(lastSync))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (health.lastDataTypes.isNotEmpty())
                "Пакеты часов получены. Показаны только подтверждённые показатели; полный обмен Health ещё исследуется."
                else "Fit3 App ответил на запрос возможностей и ждёт данные здоровья от браслета.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(AppStrings.locale, AppStrings.translate("%.2f км"), meters / 1000.0)
    else -> "${meters.roundToInt()} м"
}

private fun formatCompact(value: Double): String = if (value % 1.0 == 0.0) value.roundToInt().toString()
else String.format(AppStrings.locale, "%.1f", value)

private fun formatMinutes(minutes: Int): String = "${minutes / 60}ч ${minutes % 60}м"

@Composable
private fun NotificationToggleRow(label: String, enabled: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled, onCheckedChange = { onToggle() })
    }
}
