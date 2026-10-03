package io.github.yuriyurin.fit3companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import io.github.yuriyurin.fit3companion.protocol.Fit3OobeCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3BatteryCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3SapCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3FacePolicy
import io.github.yuriyurin.fit3companion.protocol.FaceDeleteState
import io.github.yuriyurin.fit3companion.protocol.Fit3NotificationCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3NotificationIconSender
import io.github.yuriyurin.fit3companion.protocol.Fit3SettingsCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import io.github.yuriyurin.fit3companion.protocol.ActivityGoals
import io.github.yuriyurin.fit3companion.protocol.Fit3ActivityGoalCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthAcceptance
import io.github.yuriyurin.fit3companion.protocol.Fit3RefreshSession
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthLargeDataReceiver
import io.github.yuriyurin.fit3companion.protocol.Fit3PedometerBackSync
import io.github.yuriyurin.fit3companion.protocol.Fit3BackSyncSession
import io.github.yuriyurin.fit3companion.protocol.Fit3MediaCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3MediaFileCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3WeatherCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3WidgetsCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3QuickPanelCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3AppsCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3QuickMessagesCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3OrderedItem
import io.github.yuriyurin.fit3companion.protocol.SaMessageCodec
import io.github.yuriyurin.fit3companion.protocol.SapReassembler
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.ArrayDeque

data class WatchCandidate(
    val name: String,
    val address: String,
    val paired: Boolean = false,
    val rssi: Int? = null,
)

enum class SetupStage { IDLE, WAIT_INFO, WAIT_DEVICE, WAIT_SETTINGS, WAIT_AGREEMENT, COMPLETE }
enum class GoalSyncState { LOCAL, PENDING, SENDING, SYNCED, FAILED }

data class BleSnapshot(
    val status: String = "Не подключено",
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<WatchCandidate> = emptyList(),
    val softwareVersion: String? = null,
    val currentFaceId: Int? = null,
    val currentFaceSampler: Int = 0,
    val installedFaces: List<Fit3SapCodec.InstalledFace> = emptyList(),
    val customFaceKeys: Map<Pair<Int, Int>, String> = emptyMap(),
    val facesLoading: Boolean = false,
    val facesReady: Boolean = false,
    val faceMaximum: Int? = null,
    val faceDeleteState: FaceDeleteState = FaceDeleteState.IDLE,
    val faceChangePending: Boolean = false,
    val faceInstallPending: Boolean = false,
    val faceInstallStatus: String = "",
    val sapReady: Boolean = false,
    val notificationCapabilityReady: Boolean = false,
    val setupStage: SetupStage = SetupStage.IDLE,
    val healthStatus: String = "Данные здоровья ещё не синхронизированы",
    val health: Fit3HealthCodec.State = Fit3HealthCodec.State(),
    val activityGoals: ActivityGoals = ActivityGoals(),
    val goalSyncState: GoalSyncState = GoalSyncState.LOCAL,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val refreshing: Boolean = false,
    val lastRefreshMillis: Long? = null,
    val notificationStatus: String = "Уведомления ещё не отправлялись",
    val weather: WeatherForecast? = null,
    val weatherStatus: String = "Выберите город",
    val weatherLocationRequested: Boolean = false,
    val fullSettings: Fit3SettingsCodec.State = Fit3SettingsCodec.State(),
    val widgets: List<Fit3OrderedItem> = emptyList(),
    val apps: List<Fit3OrderedItem> = emptyList(),
    val quickPanel: List<Fit3OrderedItem> = emptyList(),
    val quickMessages: List<String> = emptyList(),
    val protocolLog: List<String> = emptyList(),
    val featureStatus: String = "",
)

/** Foreground BLE client; verified OOBE and read-only refreshes run automatically. */
@SuppressLint("MissingPermission")
class Fit3BleClient(
    private val context: Context,
    private val onUpdate: (BleSnapshot) -> Unit,
    private val onWatchNotificationCommand: (String?, Fit3NotificationCodec.BandCommand) -> Unit = { _, _ -> },
    private val onWeatherRequest: (Int) -> Unit = {},
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("00001a1a-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
        val WRITE: UUID = UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val discovered = linkedMapOf<String, WatchCandidate>()
    private val assembler = SapReassembler()
    private val healthLargeDataReceiver = Fit3HealthLargeDataReceiver()
    private val mediaBridge = Fit3MediaBridge(context)
    private val initialHealth = Fit3HealthStore.load(context)
    private val initialGoals = Fit3ActivityGoalStore.load(context)
    private var snapshot = BleSnapshot(
        health = initialHealth,
        activityGoals = initialGoals.goals,
        goalSyncState = if (initialGoals.pending) GoalSyncState.PENDING else GoalSyncState.LOCAL,
        lastRefreshMillis = initialHealth.lastSuccessfulSyncMillis ?: context.getSharedPreferences("fit3_health_sync_meta", Context.MODE_PRIVATE)
            .getLong("last_refresh", 0L).takeIf { it > 0L },
    )
    private var lastPersistedHealth = snapshot.health
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var actualMtu = 23
    private var transportMtu = 500
    private var scanActive = false
    private var generation = 0
    private var faceRequestSent = false
    private var gotSapReply = false
    private var setupStage = SetupStage.IDLE
    private var healthProbeSent = false
    private var healthRequestSequence = 10000
    private var refreshSerial = 0
    private var healthCapabilityReady = false
    private var healthCapabilityProbePending = false
    private val backSyncSession = Fit3BackSyncSession()
    private var backSyncChunkCount = 0
    private var backSyncSequence = 0
    private var backSyncWireToken = 0
    private var pendingHealthRequest: Int? = null
    private var pendingHealthRequestAt = 0L
    private val refreshSession = Fit3RefreshSession()
    private var goalSequence: Int? = null
    private var goalRevision = 0L
    private var goalAttempts = 0
    private fun nextHealthSequence(): Int {
        healthRequestSequence = if (healthRequestSequence >= 0xffff) 1 else healthRequestSequence + 1
        return healthRequestSequence
    }
    private var lastAddress: String? = null
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var pendingFace: Pair<Int, Int>? = null
    private var pendingDeleteFace: Pair<Int, Int>? = null
    private var faceDeleteToken = 0
    private var faceListRequestToken = 0
    private var pendingInstallFace: Pair<Int, Int>? = null
    private val faceSources = InstalledFaceSources(context)
    private var pendingInstallCustomKey: String? = null
    private var pendingInstallAcknowledged = false
    private var pendingInstallCommandSent = false
    private var pendingExtendedInstall: io.github.yuriyurin.fit3companion.protocol.ExtendedFaceInstall? = null
    @Volatile private var faceInstallToken = 0
    private var faceInstallDeadline: io.github.yuriyurin.fit3companion.protocol.FaceInstallDeadline? = null
    private var faceTransferCloseSent = true
    @Volatile private var faceSocket: BluetoothSocket? = null
    private data class MediaFile(val name: String, val bytes: ByteArray, val attempts: Int = 0)
    private val pendingMediaFiles = ArrayDeque<MediaFile>()
    private val sentMediaFiles = mutableSetOf<String>()
    private var mediaFileInFlight: MediaFile? = null
    private var mediaTransferToken = 0
    @Volatile private var mediaSocket: BluetoothSocket? = null
    private var mediaSocketOpening = false
    private var mediaBtOpenPending = false
    private var lastMediaIdentity: String? = null
    private var lastArtworkName: String? = null
    private var currentArtworkName: String? = null
    private var lastMediaAppId: String? = null
    private var lastPlaybackState: Int? = null
    private var lastQueueIdentity: String? = null
    private var lastMediaVolume: Int? = null
    private var cachedQueueIdentity: String? = null
    private var cachedQueueFile: MediaFile? = null
    private var notificationSequence = 1
    private var pendingNotificationSequence: Int? = null
    private val notificationKeyBySequence = mutableMapOf<Int, String>()
    private val notificationSequenceByKey = mutableMapOf<String, Int>()
    private val syncedAppIcons = mutableSetOf<String>()
    private val notificationIcons = mutableMapOf<String, ByteArray>()
    private val notificationIconSender = Fit3NotificationIconSender()
    private var notificationIconFormat = 0
    private val notificationAppIdPrefs = context.getSharedPreferences("fit3_notification_app_ids", Context.MODE_PRIVATE)
    private data class QueuedWrite(val bytes: ByteArray, val onSent: (() -> Unit)? = null)
    private val writeQueue = ArrayDeque<QueuedWrite>()
    private var writeBusy = false
    private var acknowledgedWrites = false
    private var currentWrite: QueuedWrite? = null
    private var writeToken = 0

    private fun publish(status: String = snapshot.status) {
        snapshot = snapshot.copy(status = status, devices = discovered.values.toList(), scanning = scanActive)
        onUpdate(snapshot)
    }

    private fun hex(bytes: ByteArray, limit: Int = 96): String {
        val shown = bytes.take(limit).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        return if (bytes.size > limit) "$shown …(+${bytes.size - limit})" else shown
    }

    private fun protocolLog(direction: String, serviceId: Int?, bytes: ByteArray) {
        val serviceName = when (serviceId) {
            Fit3SapCodec.SERVICE_OOBE -> "OOBE"
            Fit3SapCodec.SERVICE_LOCATION -> "Location"
            Fit3SapCodec.SERVICE_WEATHER -> "Weather"
            Fit3SapCodec.SERVICE_WATCHFACE -> "WatchFace"
            Fit3SapCodec.SERVICE_OTA_TRANSFER -> "FileTransfer"
            Fit3SapCodec.SERVICE_NOTIFICATIONS -> "Notifications"
            Fit3SapCodec.SERVICE_CALENDAR -> "Calendar"
            Fit3SapCodec.SERVICE_MEDIA -> "Media"
            Fit3SapCodec.SERVICE_HEALTH -> "Health"
            Fit3SapCodec.SERVICE_SETTINGS -> "Settings"
            Fit3SapCodec.SERVICE_WIDGETS -> "Widgets"
            Fit3SapCodec.SERVICE_QUICK_MESSAGES -> "QuickMessages"
            Fit3SapCodec.SERVICE_QUICK_PANEL -> "QuickPanel"
            Fit3SapCodec.SERVICE_APPS -> "Apps"
            null -> null
            else -> "Unknown"
        }
        val decoded = when {
            serviceId == Fit3SapCodec.SERVICE_SETTINGS && direction == "RX" -> Fit3SettingsCodec.describe(bytes)
            serviceId == Fit3SapCodec.SERVICE_NOTIFICATIONS -> Fit3NotificationCodec.describe(bytes)
            serviceId == Fit3SapCodec.SERVICE_MEDIA -> Fit3MediaCodec.describe(bytes)
            serviceId == Fit3SapCodec.SERVICE_HEALTH -> Fit3HealthCodec.describe(bytes)
            serviceId == Fit3SapCodec.SERVICE_WATCHFACE && direction == "RX" -> Fit3SapCodec.describeWatchFace(bytes)
            else -> null
        }
        val tag = if (serviceId == null) direction else buildString {
            append(direction).append(" S").append(serviceId)
            if (serviceName != null) append("/").append(serviceName)
            if (!decoded.isNullOrBlank()) append(" ").append(decoded)
        }
        // Keep the complete Health payload in memory for an explicit diagnostic export.
        // The on-screen list still shows only the latest 40 entries.
        val line = "$tag  ${hex(bytes, if (serviceId == Fit3SapCodec.SERVICE_HEALTH) 4096 else 96)}"
        // Narrow transfer diagnostics contain opaque file/icon names, not notification
        // contents or health measurements. Available through ADB without a full packet log.
        if (direction in setOf("MEDIA-ART", "MEDIA-FILE-OK", "MEDIA-FILE-FAILED",
                "NOTI-ICON-ACK", "NOTI-ICON-FAILED", "HEALTH-SAVE-FAILED")) {
            android.util.Log.i("Fit3Transfer", "$direction ${bytes.toString(Charsets.UTF_8)}")
        }
        snapshot = snapshot.copy(protocolLog = (listOf(line) + snapshot.protocolLog).take(300))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return@post
                if (!isFit3Name(name)) return@post
                discovered[result.device.address] = WatchCandidate(
                    name, result.device.address,
                    result.device.bondState == BluetoothDevice.BOND_BONDED,
                    result.rssi,
                )
                publish()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanActive = false
                publish("Ошибка поиска Bluetooth: $errorCode")
            }
        }
    }

    private fun isFit3Name(name: String): Boolean =
        name.contains("Fit3", ignoreCase = true) ||
            name.contains("R390", ignoreCase = true)

    fun startScan() {
        val bluetooth = adapter ?: run { publish("Bluetooth недоступен"); return }
        if (!bluetooth.isEnabled) { publish("Включите Bluetooth"); return }
        stopScan()
        discovered.clear()
        try {
            bluetooth.bondedDevices.orEmpty().forEach { device ->
                val name = device.name ?: return@forEach
                if (isFit3Name(name)) {
                    discovered[device.address] = WatchCandidate(name, device.address, paired = true)
                }
            }
            val scanner = bluetooth.bluetoothLeScanner ?: run {
                publish("BLE-сканер недоступен")
                return
            }
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanActive = true
            publish("Поиск часов…")
            main.postDelayed({ if (scanActive) stopScan() }, 12_000)
        } catch (error: SecurityException) {
            publish("Разрешите доступ к устройствам рядом")
        }
    }

    fun stopScan() {
        if (!scanActive) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanActive = false
        publish("Выберите часы")
    }

    fun connect(address: String) {
        stopScan()
        disconnect(updateStatus = false)
        lastAddress = address
        val bluetooth = adapter ?: run { publish("Bluetooth недоступен"); return }
        val device = try { bluetooth.getRemoteDevice(address) } catch (_: IllegalArgumentException) {
            publish("Неверный адрес устройства")
            return
        }
        snapshot = snapshot.copy(softwareVersion = null, currentFaceId = null,
            currentFaceSampler = 0, installedFaces = emptyList(), customFaceKeys = emptyMap(), facesLoading = false,
            facesReady = false, faceMaximum = null, faceDeleteState = FaceDeleteState.IDLE,
            faceChangePending = false, sapReady = false, notificationCapabilityReady = false,
            setupStage = SetupStage.IDLE,
            faceInstallPending = false, faceInstallStatus = "",
            healthStatus = "Проверяем данные браслета", health = Fit3HealthStore.load(context), batteryPercent = null,
            charging = false, refreshing = false, fullSettings = Fit3SettingsCodec.State(),
            widgets = emptyList(), apps = emptyList(), quickPanel = emptyList(),
            quickMessages = emptyList(), protocolLog = emptyList(), featureStatus = "")
        lastPersistedHealth = snapshot.health
        faceRequestSent = false
        gotSapReply = false
        healthProbeSent = false
        setupStage = SetupStage.IDLE
        pendingFace = null
        syncedAppIcons.clear()
        notificationIcons.clear()
        notificationIconFormat = 0
        val thisGeneration = ++generation
        publish("Подключение к ${device.name ?: "Galaxy Fit3"}…")
        try {
            gatt = device.connectGatt(context, false, callback(thisGeneration), BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            publish("Для подключения требуется разрешение Bluetooth")
        }
    }

    private fun callback(thisGeneration: Int) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (thisGeneration != generation) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    reconnectScheduled = false
                    snapshot = snapshot.copy(connected = true)
                    publish("Согласование Bluetooth…")
                    if (!link.requestMtu(517)) fail("Не удалось запросить MTU")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    generation++
                    healthLargeDataReceiver.reset()
                    backSyncSession.reset()
                    backSyncWireToken++
                    notificationIconSender.reset()
                    refreshSession.reset()
                    pendingHealthRequest = null
                    healthCapabilityReady = false
                    goalSequence = null
                    goalAttempts = 0
                    healthCapabilityProbePending = false
                    cancelFaceInstall("Связь прервана во время установки")
                    writeCharacteristic = null
                    gatt = null
                    link.close()
                    snapshot = snapshot.copy(connected = false, sapReady = false, refreshing = false)
                    publish(if (status == BluetoothGatt.GATT_SUCCESS) "Связь прервана" else "Ошибка связи: $status")
                    scheduleReconnect()
                } else if (status != BluetoothGatt.GATT_SUCCESS) fail("Ошибка подключения: $status")
            }
        }

        override fun onMtuChanged(link: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (thisGeneration != generation) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || mtu < 108) {
                    fail("Недостаточный BLE MTU: $mtu")
                } else {
                    actualMtu = mtu
                    publish("Поиск сервиса часов…")
                    if (!link.discoverServices()) fail("Сервис часов не найден")
                }
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            main.post {
                if (thisGeneration != generation) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Ошибка поиска сервисов: $status"); return@post }
                val service = link.getService(SERVICE)
                val notify = service?.getCharacteristic(NOTIFY)
                val write = service?.getCharacteristic(WRITE)
                val descriptor = notify?.getDescriptor(CCCD)
                if (notify == null || write == null || descriptor == null) {
                    fail("SAP-сервис не найден на часах")
                    return@post
                }
                writeCharacteristic = write
                acknowledgedWrites = (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                protocolLog("GATT-WRITE-MODE", null,
                    (if (acknowledgedWrites) "acknowledged" else "paced-no-response").toByteArray())
                if (!link.setCharacteristicNotification(notify, true)) {
                    fail("Не удалось включить уведомления")
                    return@post
                }
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    link.writeDescriptor(descriptor)
                }
                if (!ok) fail("Не удалось подписаться на ответы часов")
            }
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (thisGeneration != generation) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    publish("Ожидание ответа часов…")
                    main.postDelayed({
                        if (thisGeneration == generation && !gotSapReply && gatt != null) {
                            fail("Нет ответа часов при согласовании")
                        }
                    }, 15_000)
                } else fail("Ошибка подписки: $status")
            }
        }

        override fun onCharacteristicWrite(
            link: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (thisGeneration != generation) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Ошибка записи Bluetooth: $status")
                } else if (acknowledgedWrites && writeBusy) {
                    val completed = currentWrite
                    currentWrite = null
                    writeBusy = false
                    completed?.onSent?.invoke()
                    pumpWrites()
                }
            }
        }

        @Deprecated("For Android 12 and earlier")
        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            received(thisGeneration, characteristic.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(
            link: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) { received(thisGeneration, value.copyOf()) }
    }

    private fun received(thisGeneration: Int, data: ByteArray) {
        main.post {
            if (thisGeneration != generation) return@post
            if (data.size == 105 && (data[1].toInt() and 0xff) == Fit3SapCodec.CAPABILITY_REQUEST) {
                try {
                    transportMtu = Fit3SapCodec.negotiatedTransportMtu(data)
                    require(transportMtu in 15..500)
                    writeRaw(Fit3SapCodec.capabilityResponse(data))
                    snapshot = snapshot.copy(sapReady = true)
                    lastMediaIdentity = null
                    lastMediaAppId = null
                    lastPlaybackState = null
                    lastQueueIdentity = null
                    lastMediaVolume = null
                    reconnectAttempt = 0
                    publish("Канал часов готов")
                    main.postDelayed({
                        if (thisGeneration == generation) startSetup()
                    }, 220)
                    main.postDelayed({
                        if (thisGeneration == generation) requestNotificationCapability()
                    }, 650)
                    main.postDelayed({
                        if (thisGeneration == generation && setupStage == SetupStage.IDLE &&
                            !faceRequestSent && gatt != null) {
                            requestCurrentFace()
                        }
                    }, 4_000)
                } catch (error: Exception) { fail("Ошибка согласования: ${error.message}") }
                return@post
            }
            try {
                val reply = assembler.push(Fit3SapCodec.decodeFrame(data)) ?: return@post
                gotSapReply = true
                val serviceId = reply.first
                val message = reply.second
                protocolLog("RX", serviceId, message)
                if (serviceId == Fit3SapCodec.SERVICE_OOBE && handleSetupReply(message)) return@post
                if (serviceId == Fit3SapCodec.SERVICE_OOBE && message.isNotEmpty() &&
                    (message[0].toInt() and 0xff) == 0x41) {
                    snapshot = snapshot.copy(softwareVersion = Fit3SapCodec.findSoftwareVersion(message))
                    publish("Часы отвечают")
                    main.postDelayed({
                        if (thisGeneration == generation && setupStage == SetupStage.IDLE &&
                            !faceRequestSent) requestCurrentFace()
                    }, 220)
                } else if (serviceId == Fit3SapCodec.SERVICE_WATCHFACE) {
                    val watchFaceMessageId = SaMessageCodec.parseHeader(message)?.id
                    val allFacesInfo = Fit3SapCodec.parseAllFacesInfo(message)
                    val parsedFaces = allFacesInfo?.faces ?: Fit3SapCodec.parseInstalledFaces(message)
                    if (parsedFaces != null) {
                        val current = parsedFaces.firstOrNull { it.current } ?: parsedFaces.firstOrNull {
                            it.id == snapshot.currentFaceId && it.sampler == snapshot.currentFaceSampler
                        }
                        snapshot = snapshot.copy(
                            installedFaces = parsedFaces,
                            facesLoading = false, facesReady = true,
                            faceMaximum = allFacesInfo?.maximum ?: snapshot.faceMaximum,
                            currentFaceId = current?.id ?: snapshot.currentFaceId,
                            currentFaceSampler = current?.sampler ?: snapshot.currentFaceSampler,
                            featureStatus = "Получено циферблатов: ${parsedFaces.size}",
                        )
                        if (pendingDeleteFace != null &&
                            Fit3FacePolicy.deletionConfirmed(pendingDeleteFace!!, snapshot.faceDeleteState, parsedFaces)) {
                            pendingDeleteFace = null
                            snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.DELETED)
                        }
                        if (pendingDeleteFace != null && allFacesInfo != null &&
                            snapshot.faceDeleteState == FaceDeleteState.CHECKING) {
                            val target = pendingDeleteFace!!
                            if (Fit3FacePolicy.canDelete(parsedFaces, target.first, target.second,
                                    snapshot.currentFaceId, snapshot.currentFaceSampler, snapshot.facesReady)) {
                                snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.SENDING)
                                sendReadOnly(Fit3SapCodec.SERVICE_WATCHFACE,
                                    Fit3SapCodec.deleteFaceRequest(target.first, target.second))
                            } else {
                                pendingDeleteFace = null
                                snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.FAILED)
                            }
                        }
                        if (pendingInstallAcknowledged && pendingInstallFace != null && parsedFaces.any {
                                it.id == pendingInstallFace!!.first && it.sampler == pendingInstallFace!!.second
                            }) {
                            val confirmed = parsedFaces.first {
                                it.id == pendingInstallFace!!.first && it.sampler == pendingInstallFace!!.second
                            }
                            runCatching { faceSources.confirmed(lastAddress.orEmpty(), confirmed, pendingInstallCustomKey) }
                                .onFailure { protocolLog("FACE-SOURCE-FAILED", null,
                                    "Watchface preview source could not be saved: ${it.message}".toByteArray()) }
                            cancelFaceInstall("Циферблат установлен")
                        }
                        snapshot = snapshot.copy(customFaceKeys = runCatching {
                            faceSources.reconcile(lastAddress.orEmpty(), parsedFaces)
                        }.getOrDefault(emptyMap()))
                        publish()
                    } else if (watchFaceMessageId == 0 || watchFaceMessageId == 1) {
                        snapshot = snapshot.copy(facesLoading = false, facesReady = false,
                            featureStatus = "Часы ответили списком циферблатов, но формат ещё неизвестен; смотрите Protocol Monitor")
                        publish()
                    }
                    Fit3SapCodec.parseDeleteFaceResponse(message)?.let { result ->
                        if (pendingDeleteFace == (result.id to result.sampler) &&
                            snapshot.faceDeleteState == FaceDeleteState.SENDING) {
                            if (result.success) {
                                snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.VERIFYING, facesReady = false)
                            } else {
                                pendingDeleteFace = null
                                snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.FAILED)
                            }
                            publish()
                            syncWatchFaces() // Do not remove a cell until a fresh watch list confirms absence.
                        }
                    }
                    Fit3SapCodec.parseSetCurrentFaceResponse(message)?.let { selected ->
                        if (selected == pendingFace) {
                            pendingFace = null
                            snapshot = snapshot.copy(faceChangePending = false)
                            main.postDelayed({ if (thisGeneration == generation) syncWatchFaces() }, 350)
                        }
                    }
                    Fit3SapCodec.parseInstallFaceResponse(message)?.let { result ->
                        pendingExtendedInstall?.response(result)
                        finishExtendedFaceInstallResponse()
                        if (pendingInstallCommandSent && pendingInstallFace == (result.id to result.sampler)) {
                            if (result.status == 1) {
                                pendingInstallAcknowledged = true
                                snapshot = snapshot.copy(faceInstallStatus = "Проверяем циферблат на часах…")
                                publish()
                                main.postDelayed({ if (thisGeneration == generation) requestInstalledFaces() }, 500)
                            } else {
                                cancelFaceInstall(when (result.status) {
                                        2 -> "Недостаточно заряда часов"
                                        3 -> "Часы не нашли файл циферблата"
                                        4 -> "Часы отклонили формат циферблата"
                                        5, 6 -> "Память или список циферблатов заполнены"
                                        else -> "Часы отклонили установку (${result.status})"
                                    })
                                publish()
                            }
                        }
                    }
                    Fit3SapCodec.parseCurrentFaceInfo(message)?.let { info ->
                        val resolved = Fit3SapCodec.resolveCurrentFace(info, snapshot.installedFaces)
                        if (resolved == null) requestInstalledFaces()
                        else snapshot = snapshot.copy(currentFaceId = resolved.id, currentFaceSampler = resolved.sampler)
                        if (setupStage == SetupStage.IDLE || setupStage == SetupStage.COMPLETE) publish("Подключено")
                        if (!healthProbeSent && (setupStage == SetupStage.IDLE || setupStage == SetupStage.COMPLETE)) {
                            healthProbeSent = true
                            main.postDelayed({
                                if (thisGeneration == generation && gatt != null) requestHealthSnapshot()
                            }, 400)
                        }
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_WEATHER) {
                    Fit3WeatherCodec.requestKind(message)?.let(onWeatherRequest)
                } else if (serviceId == Fit3SapCodec.SERVICE_MEDIA) {
                    val remote = Fit3MediaCodec.parseRemoteControl(message)
                    val volume = Fit3MediaCodec.parseVolumeControl(message)
                    val skipItem = Fit3MediaCodec.skipToItemRequest(message)
                    when {
                        remote != null -> {
                            // Fit3 sends a RELEASED and PRESSED packet for one button gesture.
                            // Execute only PRESSED; beta8 executed both and could double-skip tracks.
                            if (remote.pressed) {
                                val ok = mediaBridge.handleRemote(remote.action)
                                snapshot = snapshot.copy(featureStatus = if (ok)
                                    "Команда музыки с часов: ${remote.action.name}" else
                                    "Не найден активный Android MediaSession")
                                publish()
                                main.postDelayed({ if (thisGeneration == generation) sendMediaPlaybackState() }, 120)
                                main.postDelayed({
                                    if (thisGeneration == generation) {
                                        sendMediaPlaybackState()
                                        if (remote.action == Fit3MediaCodec.RemoteAction.NEXT ||
                                            remote.action == Fit3MediaCodec.RemoteAction.PREVIOUS) sendMediaMetadata()
                                    }
                                }, 450)
                            }
                        }
                        volume != null -> {
                            val ok = mediaBridge.handleVolume(volume)
                            snapshot = snapshot.copy(featureStatus = if (ok)
                                "Громкость изменена с часов" else
                                "Не удалось изменить громкость")
                            publish()
                            main.postDelayed({ if (thisGeneration == generation) sendMediaVolumeInfo() }, 100)
                        }
                        skipItem != null -> {
                            mediaBridge.skipToItem(skipItem.first, skipItem.second)
                            main.postDelayed({ if (thisGeneration == generation) {
                                sendMediaMetadata()
                                sendMediaPlaybackState()
                            } }, 250)
                        }
                        else -> handleMediaRequest(message)
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_OTA_TRANSFER) {
                    val accepted = Fit3MediaFileCodec.acceptedFileInfo(message)
                    if (accepted != null && mediaFileInFlight != null) {
                        if (accepted) requestMediaBluetoothOpen() else finishMediaFileTransfer(false)
                    }
                    val bluetoothOpen = Fit3MediaFileCodec.acceptedBluetoothOpen(message)
                    if (bluetoothOpen != null && mediaFileInFlight != null && mediaBtOpenPending) {
                        mediaBtOpenPending = false
                        if (bluetoothOpen) startMediaSocketTransfer() else finishMediaFileTransfer(false)
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_HEALTH) {
                    if (backSyncChunkCount > 0 && Fit3PedometerBackSync.largeDataAccepted(message, backSyncChunkCount)) {
                        protocolLog("HEALTH-BACK-SYNC-TRANSPORT-ACK", null, byteArrayOf())
                        backSyncSession.delivered(backSyncSequence)
                        backSyncChunkCount = 0
                        return@post
                    }
                    if (backSyncSession.receive(message)) {
                        protocolLog("HEALTH-BACK-SYNC-RESPONSE", null, message)
                    }
                    val largeData = healthLargeDataReceiver.accept(message)
                    if (largeData != null && largeData.payload == null) {
                        largeData.reply?.let { sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, it) }
                        return@post
                    }
                    val payload = largeData?.payload ?: message
                    val now = System.currentTimeMillis()
                    goalSequence?.let { seq ->
                        Fit3ActivityGoalCodec.result(payload, seq)?.let { accepted ->
                            goalSequence = null
                            val currentRevision = goalRevision
                            val persisted = runCatching {
                                accepted && Fit3ActivityGoalStore.confirm(context, currentRevision)
                            }.getOrElse {
                                protocolLog("ACTIVITY-GOALS-SAVE-FAILED", null, it.javaClass.simpleName.toByteArray())
                                false
                            }
                            if (persisted) {
                                snapshot = snapshot.copy(goalSyncState = GoalSyncState.SYNCED)
                                protocolLog("ACTIVITY-GOALS-ACK", null, payload)
                            } else {
                                snapshot = snapshot.copy(goalSyncState = GoalSyncState.FAILED)
                            }
                            main.postDelayed({ if (thisGeneration == generation) sendPendingActivityGoals() }, 500)
                        }
                    }
                    val incoming = try {
                        Fit3HealthAcceptance.accept(snapshot.health, payload, now, lastAddress) { state, packet ->
                            Fit3HealthStore.accept(context, lastAddress.orEmpty(), state, packet, now)
                        }
                    } catch (error: Exception) {
                        // Do not announce acceptance of records that failed to reach durable storage.
                        // The watch can retry; the UI retains the last successfully committed state.
                        protocolLog("HEALTH-SAVE-FAILED", null, error.javaClass.simpleName.toByteArray())
                        refreshSession.reset()
                        pendingHealthRequest = null
                        snapshot = snapshot.copy(refreshing = false, healthStatus = "Не удалось сохранить данные. Повторите обновление.")
                        publish()
                        return@post
                    }
                    snapshot = snapshot.copy(health = incoming.state, healthStatus = incoming.status,
                        lastRefreshMillis = incoming.state.lastSuccessfulSyncMillis ?: snapshot.lastRefreshMillis)
                    lastPersistedHealth = incoming.state
                    try {
                        Fit3ActivityGoalStore.observe(context, Fit3ActivityGoalCodec.observations(payload))
                    } catch (error: Exception) {
                        protocolLog("ACTIVITY-GOALS-SAVE-FAILED", null, error.javaClass.simpleName.toByteArray())
                        snapshot = snapshot.copy(goalSyncState = GoalSyncState.FAILED)
                        publish()
                        return@post // No acceptance of goals that failed to reach storage.
                    }
                    snapshot = snapshot.copy(activityGoals = Fit3ActivityGoalStore.load(context).goals)
                    if (incoming.state.lastSyncMillis != null) {
                        incoming.state.steps?.let { count ->
                            val day = incoming.state.stepDay?.toString() ?: return@let
                            val prefs = context.getSharedPreferences("fit3_daily_steps", Context.MODE_PRIVATE)
                            if (count != prefs.getInt(day, -1)) prefs.edit().putInt(day, count).apply()
                        }
                    }
                    publish()
                    // Health data exchange is request/ack driven.  In particular AZA3 first sends
                    // GM_REQUEST_CAPABILITY (A2 ...); old Companion versions ignored it, so the
                    // watch never progressed to SYNC_DATA.  Reply immediately before doing any
                    // follow-up polling.
                    // Samsung's receiver acknowledges the completed chunk transfer separately
                    // from the inner Health request. The inner sequence-numbered result is sent
                    // after the payload is parsed; both acknowledgements are needed.
                    largeData?.reply?.let { sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, it) }
                    incoming.reply?.let { sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, it) }
                    if (incoming.event == Fit3HealthCodec.Event.SYNC_DATA && incoming.reply != null ||
                        pendingHealthRequest?.let { Fit3HealthCodec.isRequestDataSuccess(payload, it) } == true) {
                        refreshSession.confirm(now)
                    }
                    if (incoming.event == Fit3HealthCodec.Event.WEARABLE_CAPABILITY_REQUEST && incoming.reply != null) {
                        healthCapabilityReady = true
                        healthCapabilityProbePending = false
                        main.postDelayed({
                            if (thisGeneration == generation && snapshot.sapReady) beginHealthDataRequest()
                        }, 250)
                    }
                    if (incoming.event == Fit3HealthCodec.Event.GM_CAPABILITY_REQUEST) {
                        // GM capability is an earlier handshake stage. After acknowledging it, one
                        // host capability probe leads into the supported REQUEST_DATA exchange.
                        // AZA3 explicitly rejects CHECK_STATUS; do not restart its handshake with it.
                        val currentGeneration = generation
                        main.postDelayed({
                            if (currentGeneration == generation && snapshot.sapReady) {
                                requestHealthSnapshot()
                            }
                        }, 650)
                    }
                    if (incoming.event == Fit3HealthCodec.Event.REQUEST_DATA && incoming.reply != null &&
                        healthCapabilityReady) {
                        // The watch explicitly asked the phone for data. Answer its request first.
                        sendPedometerBackSync()
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_SETTINGS) {
                    var changed = false
                    Fit3BatteryCodec.parse(message)?.let { battery ->
                        snapshot = snapshot.copy(batteryPercent = battery.percent,
                            charging = battery.charging)
                        changed = true
                    }
                    val merged = Fit3SettingsCodec.merge(snapshot.fullSettings, message)
                    if (merged != snapshot.fullSettings) {
                        snapshot = snapshot.copy(fullSettings = merged)
                        Fit3BackupStore.rememberWatchState(context, snapshot)
                        changed = true
                    }
                    if (changed) publish()
                } else if (serviceId == Fit3SapCodec.SERVICE_WIDGETS) {
                    Fit3WidgetsCodec.parse(message)?.let {
                        snapshot = snapshot.copy(widgets = it, featureStatus = "Плитки синхронизированы")
                        Fit3BackupStore.rememberWatchState(context, snapshot)
                        publish()
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_QUICK_PANEL) {
                    Fit3QuickPanelCodec.parse(message)?.let {
                        snapshot = snapshot.copy(quickPanel = it, featureStatus = "Quick Panel синхронизирован")
                        Fit3BackupStore.rememberWatchState(context, snapshot)
                        publish()
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_APPS) {
                    Fit3AppsCodec.parse(message)?.let {
                        snapshot = snapshot.copy(apps = it, featureStatus = "Порядок приложений синхронизирован")
                        Fit3BackupStore.rememberWatchState(context, snapshot)
                        publish()
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_QUICK_MESSAGES) {
                    Fit3QuickMessagesCodec.parse(message)?.let {
                        snapshot = snapshot.copy(quickMessages = it, featureStatus = "Быстрые ответы синхронизированы")
                        Fit3BackupStore.rememberWatchState(context, snapshot)
                        publish()
                    }
                } else if (serviceId == Fit3SapCodec.SERVICE_NOTIFICATIONS) {
                    if (message.firstOrNull()?.toInt()?.and(0xff) == 62) {
                        dispatchNotificationIcon(notificationIconSender.acknowledge(message))
                        return@post
                    }
                    Fit3NotificationCodec.parseIconCapability(message)?.let {
                        notificationIconFormat = it.format
                        snapshot = snapshot.copy(notificationCapabilityReady = true,
                            notificationStatus = "Формат иконок часов: ${it.format}")
                        publish()
                    }
                    Fit3NotificationCodec.parseIconRequest(message)?.let { (url, size) ->
                        val raw = notificationIcons[url]?.let { png ->
                            encodeNotificationIcon(png, size, notificationIconFormat)
                        } ?: byteArrayOf()
                        val response = Fit3NotificationCodec.iconResponse(url, raw, size)
                        if (raw.isNotEmpty() && response.size > 980) {
                            dispatchNotificationIcon(notificationIconSender.enqueue(url, response))
                        } else {
                            sendReadOnly(Fit3SapCodec.SERVICE_NOTIFICATIONS, response)
                        }
                        publish()
                    }
                    Fit3NotificationCodec.parseBandCommand(message)?.let { command ->
                        val sequence = when (command) {
                            is Fit3NotificationCodec.BandCommand.Delete -> command.sequence
                            is Fit3NotificationCodec.BandCommand.Reply -> command.sequence
                            is Fit3NotificationCodec.BandCommand.ShowOnPhone -> command.sequence
                            Fit3NotificationCodec.BandCommand.ClearAll -> null
                        }
                        val key = sequence?.let(notificationKeyBySequence::get)
                        onWatchNotificationCommand(key, command)
                        if (command is Fit3NotificationCodec.BandCommand.Delete && key != null) {
                            notificationKeyBySequence.remove(command.sequence)
                            notificationSequenceByKey.remove(key)
                        } else if (command is Fit3NotificationCodec.BandCommand.ClearAll) {
                            notificationKeyBySequence.clear(); notificationSequenceByKey.clear()
                        }
                    }
                    Fit3NotificationCodec.parseAck(message)?.let { (sequence, accepted) ->
                        if (sequence == pendingNotificationSequence) {
                            pendingNotificationSequence = null
                            snapshot = snapshot.copy(notificationStatus = if (accepted)
                                "Часы подтвердили тестовое уведомление" else
                                "Часы отклонили уведомление")
                            publish()
                        }
                    }
                }
            } catch (_: Exception) {
                // Control/unknown frames are ignored; only validated SAP replies change UI state.
            }
        }
    }

    private fun sendReadOnly(serviceId: Int, message: ByteArray) {
        try {
            protocolLog("TX", serviceId, message)
            writeMessage(serviceId, message)
        } catch (error: Exception) { fail("Ошибка запроса: ${error.message}") }
    }

    fun sendWeather(forecast: WeatherForecast) {
        if (!snapshot.sapReady) return
        sendReadOnly(Fit3SapCodec.SERVICE_WEATHER, Fit3WeatherCodec.response(forecast))
    }

    private fun writeMessage(serviceId: Int, message: ByteArray, onSent: (() -> Unit)? = null) {
        val frames = Fit3SapCodec.encodeMessage(serviceId, message, transportMtu)
        frames.forEachIndexed { index, bytes ->
            writeRaw(bytes, onSent.takeIf { index == frames.lastIndex })
        }
    }

    private fun sendMediaPlaybackState() {
        if (!snapshot.sapReady) return
        sendReadOnly(Fit3SapCodec.SERVICE_MEDIA, Fit3MediaCodec.playbackStateResponse(mediaBridge.currentState()))
    }

    /** Keep the watch in step with Android players without requiring the music screen to be reopened. */
    fun syncMediaIfChanged() {
        if (!snapshot.sapReady || setupStage !in listOf(SetupStage.IDLE, SetupStage.COMPLETE)) return
        val (state, artwork) = mediaBridge.currentMedia()
        currentArtworkName = artwork?.name
        if (state.appId != lastMediaAppId) {
            lastMediaAppId = state.appId
            sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
                Fit3MediaCodec.appCountChangedResponse(state.appId))
        }
        val identity = "${state.appId}|${state.title}|${state.artist}|${state.durationMs}|${state.activeItemId}"
        if (identity != lastMediaIdentity || artwork?.name.orEmpty() != lastArtworkName) {
            lastMediaIdentity = identity
            lastArtworkName = artwork?.name.orEmpty()
            sendMediaMetadata(state, artwork)
            sendMediaPlaybackState()
        } else if (state.playbackState != lastPlaybackState) sendMediaPlaybackState()
        lastPlaybackState = state.playbackState
        val queue = mediaBridge.currentQueue()
        val queueIdentity = "${queue.appId}|${queue.title}|${queue.items.joinToString { "${it.id}:${it.title}" }}"
        if (queueIdentity != lastQueueIdentity) {
            lastQueueIdentity = queueIdentity
            sendMediaQueueInfo()
        }
        val volume = mediaBridge.currentVolume()
        if (volume != lastMediaVolume) {
            lastMediaVolume = volume
            sendMediaVolumeInfo()
        }
    }

    private fun sendMediaMetadata() {
        if (!snapshot.sapReady) return
        val (state, artwork) = mediaBridge.currentMedia()
        currentArtworkName = artwork?.name
        sendMediaMetadata(state, artwork)
    }

    private fun sendMediaMetadata(state: Fit3MediaCodec.MediaState, artwork: Fit3MediaBridge.Artwork?) {
        sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
            Fit3MediaCodec.metadataResponse(state, artwork?.name.orEmpty()))
        if (artwork != null) protocolLog("MEDIA-ART", null,
            "${artwork.name}: ${artwork.bytes.size} bytes".toByteArray())
        artwork?.let { enqueueMediaFile(MediaFile(it.name, it.bytes)) }
    }

    private fun sendMediaVolumeInfo() {
        if (!snapshot.sapReady) return
        sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
            Fit3MediaCodec.volumeInfoResponse(mediaBridge.currentVolume(), mediaBridge.headsetConnected()))
    }

    private fun sendMediaQueueInfo() {
        if (!snapshot.sapReady) return
        sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
            Fit3MediaCodec.queueInfoResponse(mediaBridge.currentQueue()))
    }

    private fun sendMediaQueue(appId: String?) {
        if (!snapshot.sapReady) return
        val queue = mediaBridge.currentQueue()
        if (appId == null || appId != queue.appId || queue.appId.isEmpty()) return
        val identity = "${queue.appId}|${queue.title}|${queue.items.joinToString { "${it.id}:${it.title}:${it.subtitle}" }}"
        if (identity != cachedQueueIdentity) {
            cachedQueueIdentity = identity
            cachedQueueFile = if (queue.items.isEmpty()) null else {
                val name = Fit3MediaCodec.mediaFileName(queue.appId, queue.title, null,
                    "que", System.currentTimeMillis())
                MediaFile(name, Fit3MediaCodec.queueFile(queue, name))
            }
            pendingMediaFiles.removeAll { it.name.endsWith(".que") }
        }
        val name = cachedQueueFile?.name.orEmpty()
        sendReadOnly(Fit3SapCodec.SERVICE_MEDIA, Fit3MediaCodec.queueResponse(queue, name))
        cachedQueueFile?.let(::enqueueMediaFile)
    }

    private fun handleMediaRequest(message: ByteArray) {
        when (Fit3MediaCodec.requestId(message)) {
            Fit3MediaCodec.REQ_MEDIA_CHANGED -> {
                sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
                    Fit3MediaCodec.mediaChangedResponse(
                        Fit3MediaCodec.mediaChangedRequestState(message) ?: 1))
                val appId = mediaBridge.currentState().appId
                // The stock provider answers this request with the watch state only.
                // Re-announcing the app and all metadata on every AOD/visible transition
                // repeatedly resets the watch's player and can leave a stale track on screen.
                if (appId != lastMediaAppId) {
                    lastMediaAppId = appId
                    sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
                        Fit3MediaCodec.appCountChangedResponse(appId))
                }
                syncMediaIfChanged()
            }
            Fit3MediaCodec.REQ_METADATA -> sendMediaMetadata()
            Fit3MediaCodec.REQ_PLAYBACK_STATE -> sendMediaPlaybackState()
            Fit3MediaCodec.REQ_CAPABILITY -> sendReadOnly(
                Fit3SapCodec.SERVICE_MEDIA,
                Fit3MediaCodec.capabilityResponse(mediaBridge.maxVolume(), mediaBridge.warningVolume()),
            )
            Fit3MediaCodec.REQ_VOLUME_INFO -> sendMediaVolumeInfo()
            Fit3MediaCodec.REQ_APP_INFO -> {
                val appId = Fit3MediaCodec.requestAppId(message, Fit3MediaCodec.REQ_APP_INFO)
                if (appId != null && appId == mediaBridge.currentState().appId) {
                    val icon = mediaBridge.currentAppIcon(appId)
                    sendReadOnly(Fit3SapCodec.SERVICE_MEDIA,
                        Fit3MediaCodec.appInfoResponse(appId, mediaBridge.currentAppTitle(appId),
                            icon?.name.orEmpty()))
                    icon?.let { enqueueMediaFile(MediaFile(it.name, it.bytes)) }
                }
            }
            Fit3MediaCodec.REQ_QUEUE ->
                sendMediaQueue(Fit3MediaCodec.requestAppId(message, Fit3MediaCodec.REQ_QUEUE))
        }
    }

    private fun enqueueMediaFile(file: MediaFile) {
        if (!snapshot.sapReady || file.name in sentMediaFiles ||
            pendingMediaFiles.any { it.name == file.name } || mediaFileInFlight?.name == file.name) return
        val extension = file.name.substringAfterLast('.')
        // A new song supersedes pending artwork and queue files from the previous one.
        pendingMediaFiles.removeAll { it.name.endsWith(".$extension") }
        // The watch opens the queue screen immediately after requesting its file. An icon
        // transfer can take seconds or fail, so it must not hold the queue behind it.
        val priority = when (extension) { "que" -> 2; "art" -> 1; else -> 0 }
        val inFlightExtension = mediaFileInFlight?.name?.substringAfterLast('.')
        val inFlightPriority = when (inFlightExtension) { "que" -> 2; "art" -> 1; else -> 0 }
        val replacingTransfer = inFlightExtension == extension ||
            (mediaFileInFlight != null && priority > inFlightPriority)
        if (replacingTransfer) finishMediaFileTransfer(false)
        if (pendingMediaFiles.size >= 6) pendingMediaFiles.removeFirst()
        if (priority > 0) pendingMediaFiles.addFirst(file) else pendingMediaFiles.addLast(file)
        if (!replacingTransfer) startNextMediaFile()
    }

    private fun startNextMediaFile() {
        if (mediaFileInFlight != null || pendingMediaFiles.isEmpty() || !snapshot.sapReady ||
            snapshot.faceInstallPending || faceSocket != null) return
        val file = pendingMediaFiles.removeFirst()
        mediaFileInFlight = file
        val token = ++mediaTransferToken
        try {
            val request = Fit3MediaFileCodec.fileInfoRequest(file.name, file.bytes.size)
            protocolLog("TX", Fit3SapCodec.SERVICE_OTA_TRANSFER, request)
            writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, request)
        } catch (_: Exception) { finishMediaFileTransfer(false); return }
        main.postDelayed({
            if (token == mediaTransferToken && mediaFileInFlight?.name == file.name && !mediaSocketOpening)
                finishMediaFileTransfer(false)
        }, 6_000)
        main.postDelayed({
            if (token == mediaTransferToken && mediaFileInFlight?.name == file.name)
                finishMediaFileTransfer(false)
        }, 45_000)
    }

    private fun requestMediaBluetoothOpen() {
        if (mediaFileInFlight == null || mediaBtOpenPending || mediaSocketOpening) return
        mediaBtOpenPending = true
        try {
            val request = byteArrayOf(2)
            protocolLog("TX", Fit3SapCodec.SERVICE_OTA_TRANSFER, request)
            writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, request)
        } catch (_: Exception) { finishMediaFileTransfer(false) }
    }

    private fun startMediaSocketTransfer() {
        val file = mediaFileInFlight ?: return
        if (mediaSocketOpening) return
        mediaSocketOpening = true
        val device = try {
            adapter?.bondedDevices?.firstOrNull { it.address == lastAddress }
        } catch (_: SecurityException) { null }
        if (device == null) { finishMediaFileTransfer(false); return }
        val token = mediaTransferToken
        val currentGeneration = generation
        // The watch has confirmed Agent-30 BT_OPEN, not merely FILE_INFO/GATT write.
        Thread({
            var socket: BluetoothSocket? = null
            var completed = false
            var failure: String? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3"))
                mediaSocket = socket
                socket.connect()
                MediaFileTransport.transfer(socket.inputStream, socket.outputStream, file.name, file.bytes) {
                    token != mediaTransferToken || currentGeneration != generation
                }
                completed = true
            } catch (error: Exception) { failure = error.message ?: error.javaClass.simpleName }
            finally {
                runCatching { socket?.close() }
                if (mediaSocket === socket) mediaSocket = null
                main.post {
                    if (token == mediaTransferToken && currentGeneration == generation) {
                        protocolLog(if (completed) "MEDIA-FILE-OK" else "MEDIA-FILE-FAILED",
                            null, "${file.name}: ${failure.orEmpty()}".toByteArray(Charsets.UTF_8))
                        publish()
                        finishMediaFileTransfer(completed)
                    }
                }
            }
        }, "Fit3-media-transfer").start()
    }

    private fun finishMediaFileTransfer(completed: Boolean) {
        val file = mediaFileInFlight ?: return
        if (completed) sentMediaFiles.add(file.name)
        mediaFileInFlight = null
        mediaSocketOpening = false
        mediaBtOpenPending = false
        mediaTransferToken++
        runCatching { mediaSocket?.close() }
        mediaSocket = null
        runCatching { writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, byteArrayOf(3)) }
        // A queue request can interrupt an art file. Retry that art after the queue,
        // but never resurrect an old track or loop indefinitely on a failed transfer.
        if (!completed && snapshot.sapReady && file.name.endsWith(".art") &&
            file.name == currentArtworkName && file.attempts < 2 &&
            pendingMediaFiles.none { it.name == file.name }) {
            pendingMediaFiles.addLast(file.copy(attempts = file.attempts + 1))
        }
        // Stock provider sends the Agent 9 response before the file and does not replay it
        // after SPP completion. Replaying it can reset the watch's open queue/music view.
        main.postDelayed({ startNextMediaFile() }, 500)
    }

    private fun dispatchNotificationIcon(update: Fit3NotificationIconSender.Update) {
        update.acceptedKey?.let {
            snapshot = snapshot.copy(notificationStatus = "Иконка приложения получена браслетом")
            protocolLog("NOTI-ICON-ACK", null, it.toByteArray())
        }
        update.failedKey?.let {
            snapshot = snapshot.copy(notificationStatus = "Не удалось передать иконку приложения")
            protocolLog("NOTI-ICON-FAILED", null, it.toByteArray())
        }
        update.batch?.let { batch ->
            val thisGeneration = generation
            // 112px RGBA is >50KB: enqueueing all SAP frames overflows the 64-frame
            // GATT queue, and starting the ACK timer before transmission retries too early.
            fun sendChunk(index: Int) {
                if (thisGeneration != generation || !snapshot.sapReady ||
                    !notificationIconSender.isCurrent(batch.version)) return
                if (index == batch.packets.size) {
                    main.postDelayed({
                        if (generation == thisGeneration && snapshot.sapReady)
                            dispatchNotificationIcon(notificationIconSender.timeout(batch.version))
                    }, 15_000)
                    return
                }
                try {
                    protocolLog("TX", Fit3SapCodec.SERVICE_NOTIFICATIONS, batch.packets[index])
                    writeMessage(Fit3SapCodec.SERVICE_NOTIFICATIONS, batch.packets[index]) {
                        sendChunk(index + 1)
                    }
                } catch (error: Exception) { fail("Ошибка передачи иконки: ${error.message}") }
            }
            sendChunk(0)
        }
        if (update.acceptedKey != null || update.failedKey != null) publish()
    }

    private fun cancelMediaTransfers() {
        mediaTransferToken++
        pendingMediaFiles.clear()
        mediaFileInFlight = null
        mediaSocketOpening = false
        mediaBtOpenPending = false
        sentMediaFiles.clear()
        lastArtworkName = null
        currentArtworkName = null
        runCatching { mediaSocket?.close() }
        mediaSocket = null
    }

    private fun requestCurrentFace() {
        faceRequestSent = true
        sendReadOnly(Fit3SapCodec.SERVICE_WATCHFACE, Fit3SapCodec.currentFaceRequest)
    }

    fun syncWatchFaces() {
        if (!snapshot.connected || !snapshot.sapReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            publish("Дождитесь подключения часов")
            return
        }
        // Official BandFace flow uses the dedicated Agent 6 builders for
        // current face and installed list.  Keep these as separate messages;
        // do not derive any watch-face appearance from phone time/Health data.
        requestCurrentFace()
        val currentGeneration = generation
        main.postDelayed({
            if (currentGeneration == generation && snapshot.sapReady) requestInstalledFaces()
        }, 120)
    }

    fun requestInstalledFaces() {
        if (!snapshot.connected || !snapshot.sapReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            publish("Дождитесь подключения часов")
            return
        }
        snapshot = snapshot.copy(facesLoading = true, facesReady = false)
        publish()
        sendReadOnly(Fit3SapCodec.SERVICE_WATCHFACE, Fit3SapCodec.allFacesInfoRequest)
        val currentGeneration = generation
        val requestToken = ++faceListRequestToken
        main.postDelayed({
            if (currentGeneration == generation && requestToken == faceListRequestToken && snapshot.facesLoading) {
                snapshot = snapshot.copy(facesLoading = false)
                publish("Не удалось получить список циферблатов")
            }
        }, 8_000)
    }

    fun selectInstalledFace(id: Int, sampler: Int) {
        if (id !in 1..255) {
            publish("Выберите этот циферблат в меню Fit3")
            return
        }
        if (!snapshot.connected || !snapshot.sapReady || snapshot.faceChangePending ||
            !snapshot.facesReady || pendingDeleteFace != null || snapshot.faceInstallPending ||
            snapshot.installedFaces.none { it.id == id && it.sampler == sampler }) {
            publish("Сначала получите список установленных циферблатов")
            return
        }
        pendingFace = id to sampler
        snapshot = snapshot.copy(faceChangePending = true)
        publish("Смена циферблата…")
        sendReadOnly(Fit3SapCodec.SERVICE_WATCHFACE, Fit3SapCodec.setCurrentFaceRequest(id, sampler))
        val currentGeneration = generation
        main.postDelayed({
            if (currentGeneration == generation && pendingFace == (id to sampler)) {
                pendingFace = null
                snapshot = snapshot.copy(faceChangePending = false)
                publish("Часы не подтвердили смену циферблата")
            }
        }, 8_000)
    }

    fun deleteInstalledFace(id: Int, sampler: Int) {
        if (pendingDeleteFace != null) return
        if (!snapshot.sapReady || snapshot.facesLoading ||
            snapshot.faceChangePending || snapshot.faceInstallPending ||
            !Fit3FacePolicy.canDelete(snapshot.installedFaces, id, sampler,
                snapshot.currentFaceId, snapshot.currentFaceSampler, snapshot.facesReady)) {
            snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.FAILED)
            publish(); return
        }
        val target = id to sampler
        val token = ++faceDeleteToken
        val currentGeneration = generation
        pendingDeleteFace = target
        snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.CHECKING)
        publish()
        // Re-read first: the wearer may have selected a different face since the dialog opened.
        syncWatchFaces()
        main.postDelayed({
            if (currentGeneration == generation && token == faceDeleteToken && pendingDeleteFace == target) {
                pendingDeleteFace = null
                snapshot = snapshot.copy(faceDeleteState = FaceDeleteState.TIMEOUT)
                publish()
                syncWatchFaces()
            }
        }, 12_000)
    }

    fun reportFaceDownload(status: String) {
        snapshot = snapshot.copy(faceInstallStatus = status)
        publish()
    }

    internal fun installFace(payload: FacePackageRepository.Payload) {
        // Final strict gate for ALL callers before changing transfer state or contacting Fit3.
        val verified = runCatching {
            val report = FaceBinValidator.validate(payload.binary, payload.fileName, payload.faceId)
            require(payload.samplerId in report.styles) { "Выбранный вариант отсутствует внутри BIN" }
        }
        if (verified.isFailure) {
            protocolLog("FACE-BIN-REJECTED", null, verified.exceptionOrNull()?.message.orEmpty().toByteArray())
            reportFaceDownload("BIN отклонён проверкой: ${verified.exceptionOrNull()?.message}")
            return
        }
        if (payload.faceId !in 1..99999 || payload.samplerId !in 0..9 ||
            (payload.faceId > 255 && (payload.customKey == null || payload.samplerId != 0))) {
            reportFaceDownload("Этот вариант пока нельзя установить из приложения")
            return
        }
        if (!snapshot.sapReady || snapshot.faceInstallPending || pendingInstallFace != null ||
            pendingDeleteFace != null || snapshot.faceChangePending) {
            reportFaceDownload("Подключите часы и дождитесь окончания предыдущей передачи")
            return
        }
        if (!snapshot.facesReady || snapshot.faceMaximum == null) {
            requestInstalledFaces()
            reportFaceDownload("Получаем список циферблатов. Повторите выбор после обновления")
            return
        }
        if (!Fit3FacePolicy.hasRoom(snapshot.installedFaces, snapshot.faceMaximum, payload.faceId, payload.samplerId)) {
            reportFaceDownload("Список циферблатов заполнен. Удалите один в разделе «Установленные»")
            return
        }
        if (snapshot.batteryPercent != null && snapshot.batteryPercent!! < 30) {
            reportFaceDownload("Для установки зарядите часы хотя бы до 30%")
            return
        }
        // Face installation has priority over best-effort album art / queue delivery.
        if (payload.repairedVariantCount) protocolLog("FACE-BIN-VARIANTS-REPAIRED", null,
            "id=${payload.faceId} sampler=${payload.samplerId}".toByteArray())
        cancelMediaTransfers()
        val bluetooth = adapter ?: run { reportFaceDownload("Bluetooth недоступен"); return }
        val bonded = try { bluetooth.bondedDevices.orEmpty().filter {
            it.name?.contains("Fit3", ignoreCase = true) == true ||
                it.name?.contains("R390", ignoreCase = true) == true
        } } catch (_: SecurityException) {
            reportFaceDownload("Разрешите доступ к устройствам рядом"); return
        }
        val device = bonded.firstOrNull { it.address == lastAddress } ?: bonded.singleOrNull()
        if (device == null) { reportFaceDownload("Не найден сопряжённый Galaxy Fit3"); return }
        val token = ++faceInstallToken
        val currentGeneration = generation
        pendingInstallFace = payload.faceId to payload.samplerId
        pendingInstallCustomKey = payload.customKey
        pendingInstallAcknowledged = false
        pendingInstallCommandSent = false
        pendingExtendedInstall = if (payload.faceId > 255)
            io.github.yuriyurin.fit3companion.protocol.ExtendedFaceInstall(payload.faceId, payload.samplerId) else null
        faceInstallDeadline = io.github.yuriyurin.fit3companion.protocol.FaceInstallDeadline(
            android.os.SystemClock.elapsedRealtime())
        faceTransferCloseSent = false
        snapshot = snapshot.copy(faceInstallPending = true,
            faceInstallStatus = "Подготавливаем передачу циферблата…")
        publish()
        scheduleFaceInstallDeadline(token, currentGeneration)
        val name = payload.fileName.toByteArray(Charsets.UTF_8)
        val size = payload.binary.size
        val metadata = byteArrayOf(1, 3, 6, 4, name.size.toByte()) + name +
            byteArrayOf(5, size.toByte(), (size ushr 8).toByte(),
                (size ushr 16).toByte(), (size ushr 24).toByte())
        try {
            protocolLog("TX", Fit3SapCodec.SERVICE_OTA_TRANSFER, metadata)
            writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, metadata)
        } catch (error: Exception) {
            cancelFaceInstall("Не удалось подготовить часы: ${error.message}")
            publish()
            return
        }
        main.postDelayed({
            if (currentGeneration != generation || token != faceInstallToken) return@postDelayed
            try {
                protocolLog("TX", Fit3SapCodec.SERVICE_OTA_TRANSFER, byteArrayOf(2))
                writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, byteArrayOf(2))
            } catch (error: Exception) {
                cancelFaceInstall("Передача не завершена: ${error.message}")
                publish()
                return@postDelayed
            }
            Thread({
                var socket: BluetoothSocket? = null
                var transferred = false
                try {
                    socket = device.createRfcommSocketToServiceRecord(
                        UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3"))
                    faceSocket = socket
                    if (token != faceInstallToken) {
                        socket.close()
                        if (faceSocket === socket) faceSocket = null
                        return@Thread
                    }
                    socket.connect()
                    main.post { if (token == faceInstallToken) reportFaceDownload("Передаём циферблат…") }
                    FaceOtaTransport.transfer(socket.inputStream, socket.outputStream,
                        payload.fileName, payload.binary, cancelled = { token != faceInstallToken },
                        progress = { sent, total -> main.post {
                            if (token == faceInstallToken && currentGeneration == generation) {
                                faceInstallDeadline?.progress(android.os.SystemClock.elapsedRealtime())
                                reportFaceDownload("Передача: ${sent * 100 / total}%")
                            }
                        } })
                    transferred = true
                } catch (error: Exception) {
                    main.post {
                        if (token == faceInstallToken) {
                            cancelFaceInstall("Передача не завершена: ${error.message}")
                            publish()
                        }
                    }
                } finally {
                    main.post {
                        if (currentGeneration == generation && token == faceInstallToken) {
                            closeFaceTransferChannel()
                        }
                    }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { }
                    runCatching { socket?.close() }
                    if (faceSocket === socket) faceSocket = null
                    if (transferred) main.postDelayed({
                        if (currentGeneration != generation || token != faceInstallToken) return@postDelayed
                        try {
                            reportFaceDownload("Устанавливаем циферблат…")
                            faceInstallDeadline?.awaitConfirmation(android.os.SystemClock.elapsedRealtime())
                            if (pendingExtendedInstall != null) {
                                pendingExtendedInstall?.transferred()
                                finishExtendedFaceInstallResponse()
                                // S30's receiver auto-installs the full filename. SAP INSTALL
                                // cannot encode this ID and must never address its low-byte alias.
                                if (pendingInstallFace != null) requestInstalledFaces()
                            } else {
                                val request = Fit3SapCodec.installFaceRequest(payload.faceId, payload.samplerId)
                                pendingInstallCommandSent = true
                                protocolLog("TX", Fit3SapCodec.SERVICE_WATCHFACE, request)
                                writeMessage(Fit3SapCodec.SERVICE_WATCHFACE, request)
                            }
                        } catch (error: Exception) {
                            cancelFaceInstall("Не удалось отправить установку: ${error.message}")
                            publish()
                        }
                    }, 1_000)
                }
            }, "Fit3-face-transfer").start()
        }, 150)
    }

    private fun finishExtendedFaceInstallResponse() {
        val extended = pendingExtendedInstall ?: return
        if (pendingInstallFace != (extended.id to extended.sampler)) return
        val status = extended.statusAfterTransfer ?: return
        if (status == 1) {
            if (!pendingInstallAcknowledged) {
                pendingInstallAcknowledged = true
                snapshot = snapshot.copy(faceInstallStatus = "Проверяем циферблат на часах…")
                publish()
                requestInstalledFaces()
            }
        } else {
            protocolLog("FACE-EXTENDED-FAILED", null, "Automatic install status=$status id=${extended.id}".toByteArray())
            cancelFaceInstall("Часы не подтвердили установку пользовательского циферблата")
            publish()
        }
    }

    private fun scheduleFaceInstallDeadline(token: Int, currentGeneration: Int) {
        main.postDelayed({
            if (token != faceInstallToken || currentGeneration != generation || pendingInstallFace == null)
                return@postDelayed
            if (faceInstallDeadline?.expired(android.os.SystemClock.elapsedRealtime()) == true) {
                protocolLog("FACE-INSTALL-TIMEOUT", null,
                    "id=${pendingInstallFace?.first} sampler=${pendingInstallFace?.second}".toByteArray())
                cancelFaceInstall("Нет подтверждения установки от часов")
                publish()
                if (snapshot.sapReady) requestInstalledFaces()
            } else scheduleFaceInstallDeadline(token, currentGeneration)
        }, 1_000)
    }

    private fun closeFaceTransferChannel() {
        if (faceTransferCloseSent || !snapshot.connected || !snapshot.sapReady) return
        // Mark before enqueueing: write failure can synchronously disconnect and
        // re-enter cancellation. Never recursively enqueue another close request.
        faceTransferCloseSent = true
        runCatching {
            protocolLog("TX", Fit3SapCodec.SERVICE_OTA_TRANSFER, byteArrayOf(3))
            writeMessage(Fit3SapCodec.SERVICE_OTA_TRANSFER, byteArrayOf(3))
        }.onFailure { protocolLog("FACE-CLOSE-FAILED", null, it.message.orEmpty().toByteArray()) }
    }

    fun requestHealthSnapshot() {
        if (!snapshot.connected || !snapshot.sapReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            snapshot = snapshot.copy(healthStatus = "Дождитесь подключения часов")
            publish(); return
        }
        if (!refreshSession.active) {
            refreshSession.begin(System.currentTimeMillis())
            scheduleRefreshCompletion()
        }
        snapshot = snapshot.copy(healthStatus = "Запрашиваем данные браслета…")
        publish()
        if (healthCapabilityReady) beginHealthDataRequest()
        else if (!healthCapabilityProbePending) {
            healthCapabilityProbePending = true
            sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, Fit3HealthCodec.mobileCapabilityRequest)
        }
    }

    private fun beginHealthDataRequest() {
        if (!snapshot.sapReady || !healthCapabilityReady) return
        sendPendingActivityGoals()
        val now = System.currentTimeMillis()
        // Coalesce concurrent automatic/manual requests rather than restart an active exchange.
        if (pendingHealthRequest != null && now - pendingHealthRequestAt < 45_000) return
        val sequence = nextHealthSequence()
        pendingHealthRequest = sequence
        pendingHealthRequestAt = now
        sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, Fit3HealthCodec.requestData(sequence, now))
    }

    private fun scheduleRefreshCompletion() {
        val thisGeneration = generation
        main.postDelayed({
            if (generation != thisGeneration || !refreshSession.active) return@postDelayed
            when (refreshSession.poll(System.currentTimeMillis())) {
                Fit3RefreshSession.Result.WAITING -> scheduleRefreshCompletion()
                Fit3RefreshSession.Result.SUCCESS -> {
                    pendingHealthRequest = null
                    val now = System.currentTimeMillis()
                    val state = snapshot.health.copy(lastSuccessfulSyncMillis = now)
                    try {
                        Fit3HealthStore.save(context, state)
                        snapshot = snapshot.copy(health = state, refreshing = false, lastRefreshMillis = now)
                        context.getSharedPreferences("fit3_health_sync_meta", Context.MODE_PRIVATE)
                            .edit().putLong("last_refresh", now).apply()
                        publish()
                        sendPedometerBackSync()
                    } catch (_: Exception) {
                        snapshot = snapshot.copy(refreshing = false, healthStatus = "Не удалось сохранить данные")
                        publish()
                    }
                }
                Fit3RefreshSession.Result.TIMEOUT -> {
                    pendingHealthRequest = null
                    healthCapabilityProbePending = false
                    snapshot = snapshot.copy(refreshing = false, healthStatus = "Браслет не ответил на обновление")
                    publish()
                }
            }
        }, 1_000)
    }

    /** One-shot diagnostic: the exact 0x34 host-capability probe used by the official plugin. */
    fun requestHealthCapabilityForDebug() {
        if (!snapshot.connected || !snapshot.sapReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            snapshot = snapshot.copy(healthStatus = "Дождитесь подключения часов")
            publish(); return
        }
        snapshot = snapshot.copy(healthStatus = "Проверяем обмен возможностями Health…")
        publish()
        sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, Fit3HealthCodec.mobileCapabilityRequest)
    }

    fun reloadHealthFromStore() {
        val restored = Fit3HealthStore.load(context)
        lastPersistedHealth = restored
        snapshot = snapshot.copy(health = restored,
            activityGoals = Fit3ActivityGoalStore.load(context).goals,
            lastRefreshMillis = restored.lastSuccessfulSyncMillis ?: snapshot.lastRefreshMillis)
        publish()
        sendPendingActivityGoals()
    }

    fun setActivityGoals(goals: ActivityGoals) {
        val saved = Fit3ActivityGoalStore.save(context, goals)
        snapshot = snapshot.copy(activityGoals = saved.goals, goalSyncState = GoalSyncState.PENDING)
        goalAttempts = 0
        publish()
        // Wait for an earlier revision's acknowledgement rather than mixing two requests.
        sendPendingActivityGoals()
        if (!healthCapabilityReady && snapshot.sapReady) requestHealthSnapshot()
    }

    private fun sendPendingActivityGoals() {
        val saved = Fit3ActivityGoalStore.load(context)
        if (!saved.pending || !snapshot.sapReady || !healthCapabilityReady ||
            snapshot.faceInstallPending || goalSequence != null || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) return
        if (saved.revision != goalRevision) { goalRevision = saved.revision; goalAttempts = 0 }
        if (goalAttempts >= 3) return
        val seq = nextHealthSequence()
        val thisGeneration = generation
        goalSequence = seq
        goalAttempts++
        val offset = java.util.TimeZone.getDefault().getOffset(saved.revision)
        val packet = Fit3ActivityGoalCodec.request(seq, saved.goals, saved.revision, offset, saved.uuid)
        snapshot = snapshot.copy(activityGoals = saved.goals, goalSyncState = GoalSyncState.SENDING)
        protocolLog("ACTIVITY-GOALS-SEND", null,
            "seq=$seq steps=${saved.goals.steps} minutes=${saved.goals.minutes} kcal=${saved.goals.calories}".toByteArray())
        try {
            sendReadOnly(Fit3SapCodec.SERVICE_HEALTH, packet)
        } catch (error: Exception) {
            goalSequence = null
            snapshot = snapshot.copy(goalSyncState = GoalSyncState.FAILED)
            protocolLog("ACTIVITY-GOALS-SEND-FAILED", null, error.javaClass.simpleName.toByteArray())
            publish()
            return
        }
        publish()
        main.postDelayed({
            if (generation == thisGeneration && goalSequence == seq) {
                goalSequence = null
                snapshot = snapshot.copy(goalSyncState = GoalSyncState.FAILED)
                protocolLog("ACTIVITY-GOALS-TIMEOUT", null, byteArrayOf())
                publish()
                sendPendingActivityGoals()
            }
        }, 45_000)
    }

    /** Restore today's persisted bins, also after watch reset. No synthetic totals or sleep. */
    private fun sendPedometerBackSync() {
        if (!snapshot.sapReady || !healthCapabilityReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE) || snapshot.faceInstallPending) return
        if (lastAddress == null || !lastAddress.equals(snapshot.health.stepSourceAddress, ignoreCase = true)) return
        val now = System.currentTimeMillis()
        val bins = runCatching { Fit3PedometerBackSync.bins(snapshot.health, now) }.getOrNull() ?: return
        val day = snapshot.health.stepDay ?: return
        val localNow = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        // AZA3 ignores the text date. Never finish yesterday's transfer after midnight.
        if (localNow.hour == 23 && localNow.minute == 59) return
        val sequence = nextHealthSequence()
        if (!backSyncSession.begin(bins, day, sequence, now)) return
        backSyncSequence = sequence
        val token = ++backSyncWireToken
        val currentGeneration = generation
        try {
            val payload = Fit3PedometerBackSync.request(sequence, bins)
            // Keep each Health chunk below both the stock-size limit and SAP's 16 fragments.
            val prefix = if (transportMtu < 256) 2 else 4
            val sapCapacity = 16 * (transportMtu - prefix - 4) - 2
            val messages = Fit3PedometerBackSync.chunks(payload, minOf(1024, sapCapacity))
            backSyncChunkCount = if (messages.size > 1) messages.size else 0
            protocolLog("HEALTH-BACK-SYNC-SEND", null,
                "seq=$sequence bins=${bins.size} steps=${bins.sumOf { it.record.count }}".toByteArray())
            fun sendChunk(index: Int) {
                if (currentGeneration != generation || token != backSyncWireToken) return
                if (Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() != day) {
                    backSyncSession.expire(sequence); backSyncChunkCount = 0; return
                }
                protocolLog("TX", Fit3SapCodec.SERVICE_HEALTH, messages[index])
                writeMessage(Fit3SapCodec.SERVICE_HEALTH, messages[index]) {
                    if (index + 1 < messages.size) sendChunk(index + 1)
                    else if (messages.size == 1) {
                        // Stock sender waits for SAP delivery, not a non-existent C7 result.
                        backSyncSession.delivered(sequence)
                        protocolLog("HEALTH-BACK-SYNC-SENT", null, byteArrayOf())
                    } else main.postDelayed({
                        if (currentGeneration == generation && token == backSyncWireToken) {
                            if (backSyncSession.pending) protocolLog("HEALTH-BACK-SYNC-NO-TRANSPORT-ACK", null, byteArrayOf())
                            backSyncSession.expire(sequence); backSyncChunkCount = 0
                        }
                    }, 30_000)
                }
            }
            sendChunk(0)
        } catch (error: Exception) {
            backSyncSession.expire(sequence); backSyncChunkCount = 0
            protocolLog("HEALTH-BACK-SYNC-FAILED", null, error.javaClass.simpleName.toByteArray())
        }
    }

    fun requestFullSettings() {
        if (!snapshot.sapReady) { publish("Дождитесь подключения часов"); return }
        // The Fit3 full-settings response already contains display/vibration/advanced fields.
        // Sleep/theatre current state and battery are separate messages in Samsung's plugin.
        sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3SettingsCodec.fullSettingsRequest)
        main.postDelayed({ if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3SettingsCodec.sleepModeRequest) }, 160)
        main.postDelayed({ if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3SettingsCodec.theatreModeRequest) }, 320)
        main.postDelayed({ if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3SettingsCodec.batteryRequest) }, 480)
    }

    fun setBrightness(value: Int) = sendSetting(
        Fit3SettingsCodec.setBrightness(value), "Яркость отправлена", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(brightness = value) }

    fun setAutoBrightness(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setAutoBrightness(enabled), "Автояркость отправлена", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(autoBrightness = enabled) }

    fun setAlwaysOnDisplay(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setAlwaysOnDisplay(enabled), "AOD отправлен", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(alwaysOnDisplay = enabled) }

    fun setRaiseToWake(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setRaiseToWake(enabled), "Поднятие запястья отправлено", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(raiseToWake = enabled) }

    fun setTouchToWake(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setTouchToWake(enabled), "Пробуждение касанием отправлено", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(touchToWake = enabled) }

    fun setRightWrist(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setRightWrist(enabled), "Ожидаем подтверждение запястья от браслета",
        Fit3SettingsCodec.fullSettingsRequest
    )

    fun setButtonOnRight(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setButtonOnRight(enabled), "Ожидаем подтверждение положения кнопки",
        Fit3SettingsCodec.fullSettingsRequest
    )

    fun setAutoMediaControl(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setAutoMediaControl(enabled), "Автопоказ управления музыкой отправлен", Fit3SettingsCodec.fullSettingsRequest
    ) { it.copy(autoMediaControl = enabled) }

    fun setSleepMode(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setSleepMode(enabled), "Режим сна отправлен", Fit3SettingsCodec.sleepModeRequest
    ) { it.copy(sleepMode = enabled) }

    fun setTheatreMode(enabled: Boolean) = sendSetting(
        Fit3SettingsCodec.setTheatreMode(enabled), "Режим Театр отправлен", Fit3SettingsCodec.theatreModeRequest
    ) { it.copy(theatreMode = enabled) }

    fun setScreenTimeout(seconds: Int) = sendSetting(
        Fit3SettingsCodec.setScreenTimeout(seconds), "Таймаут экрана отправлен", Fit3SettingsCodec.displayListRequest
    ) { it.copy(timeoutSeconds = seconds) }

    private fun sendSetting(
        packet: ByteArray,
        status: String,
        verifyRequest: ByteArray,
        optimistic: (Fit3SettingsCodec.State) -> Fit3SettingsCodec.State = { it },
    ) {
        if (!snapshot.sapReady) { publish("Дождитесь подключения часов"); return }
        // Material switches/sliders respond immediately.  The read-back below remains the source
        // of truth and will correct the UI if the watch rejects a value.
        snapshot = snapshot.copy(fullSettings = optimistic(snapshot.fullSettings), featureStatus = status)
        publish()
        sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, packet)
        main.postDelayed({
            if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, verifyRequest)
        }, 600)
    }

    fun requestWidgets() = sendFeatureRequest(Fit3SapCodec.SERVICE_WIDGETS, Fit3WidgetsCodec.request, "Запрос плиток…")
    fun setWidgets(ids: List<Int>) {
        if (ids.size > 12) { publish("Fit3 поддерживает максимум 12 активных плиток"); return }
        sendFeatureRequest(Fit3SapCodec.SERVICE_WIDGETS,
            Fit3WidgetsCodec.set(ids.mapIndexed { i, id -> Fit3OrderedItem(id, i + 1) }), "Порядок плиток отправлен")
        main.postDelayed({ if (snapshot.sapReady) requestWidgets() }, 350)
    }

    fun requestApps() = sendFeatureRequest(Fit3SapCodec.SERVICE_APPS, Fit3AppsCodec.request, "Запрос приложений…")
    fun setApps(ids: List<Int>) {
        sendFeatureRequest(Fit3SapCodec.SERVICE_APPS, Fit3AppsCodec.set(ids), "Порядок приложений отправлен")
        main.postDelayed({ if (snapshot.sapReady) requestApps() }, 350)
    }

    fun requestQuickPanel() = sendFeatureRequest(Fit3SapCodec.SERVICE_QUICK_PANEL, Fit3QuickPanelCodec.request, "Запрос Quick Panel…")
    fun setQuickPanel(ids: List<Int>) {
        sendFeatureRequest(Fit3SapCodec.SERVICE_QUICK_PANEL,
            Fit3QuickPanelCodec.set(ids.mapIndexed { i, id -> Fit3OrderedItem(id, i + 1) }), "Quick Panel отправлен")
        main.postDelayed({ if (snapshot.sapReady) requestQuickPanel() }, 350)
    }

    fun setQuickMessages(messages: List<String>) {
        val cleaned = messages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
        sendFeatureRequest(Fit3SapCodec.SERVICE_QUICK_MESSAGES, Fit3QuickMessagesCodec.set(cleaned), "Быстрые ответы отправлены")
        snapshot = snapshot.copy(quickMessages = cleaned)
        publish()
    }

    private fun sendFeatureRequest(serviceId: Int, packet: ByteArray, status: String) {
        if (!snapshot.sapReady) { publish("Дождитесь подключения часов"); return }
        sendReadOnly(serviceId, packet)
        snapshot = snapshot.copy(featureStatus = status)
        publish()
    }

    fun syncTime() {
        if (!snapshot.sapReady) { publish("Дождитесь подключения часов"); return }
        sendReadOnly(Fit3SapCodec.SERVICE_OOBE,
            Fit3OobeCodec.initSettingsRequest(Instant.now(), ZoneId.systemDefault(),
                localeId = io.github.yuriyurin.fit3companion.BandLanguage.localeId(context),
                hour24 = DateFormat.is24HourFormat(context)))
        snapshot = snapshot.copy(featureStatus = "Время и часовой пояс отправлены на часы")
        publish()
    }

    fun syncBandLanguage() {
        if (!snapshot.sapReady || setupStage !in listOf(SetupStage.IDLE, SetupStage.COMPLETE)) return
        sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS,
            io.github.yuriyurin.fit3companion.protocol.Fit3Languages.languagePacket(
                io.github.yuriyurin.fit3companion.BandLanguage.localeId(context)))
    }

    fun clearProtocolLog() {
        snapshot = snapshot.copy(protocolLog = emptyList())
        publish()
    }

    fun saveLocalBackup() {
        Fit3BackupStore.save(context, snapshot)
        snapshot = snapshot.copy(featureStatus = "Локальная резервная копия сохранена")
        publish()
    }

    fun restoreLocalBackup() {
        val backup = Fit3BackupStore.load(context) ?: run {
            snapshot = snapshot.copy(featureStatus = "Локальная резервная копия не найдена")
            publish(); return
        }
        if (!snapshot.sapReady) { publish("Дождитесь подключения часов"); return }
        backup.settings.brightness?.let(::setBrightness)
        backup.settings.autoBrightness?.let(::setAutoBrightness)
        backup.settings.alwaysOnDisplay?.let(::setAlwaysOnDisplay)
        backup.settings.raiseToWake?.let(::setRaiseToWake)
        backup.settings.touchToWake?.let(::setTouchToWake)
        backup.settings.rightWrist?.let(::setRightWrist)
        backup.settings.buttonOnRight?.let(::setButtonOnRight)
        backup.settings.autoMediaControl?.let(::setAutoMediaControl)
        backup.settings.sleepMode?.let(::setSleepMode)
        backup.settings.theatreMode?.let(::setTheatreMode)
        backup.settings.timeoutSeconds?.let(::setScreenTimeout)
        if (backup.widgets.isNotEmpty()) setWidgets(backup.widgets)
        if (backup.apps.isNotEmpty()) setApps(backup.apps)
        if (backup.quickPanel.isNotEmpty()) setQuickPanel(backup.quickPanel)
        if (backup.quickMessages.isNotEmpty()) setQuickMessages(backup.quickMessages)
        snapshot = snapshot.copy(featureStatus = "Восстановление локальной копии отправлено на часы")
        publish()
    }

    /** Samsung's notification DB allocates wearable app IDs from 20 upward.
     * Keep a stable package->ID mapping instead of sending a large Java hash as beta8 did. */
    private fun notificationAppId(packageName: String): Int {
        val key = "app:$packageName"
        notificationAppIdPrefs.getInt(key, -1).takeIf { it >= 20 }?.let { return it }
        val used = notificationAppIdPrefs.all
            .filterKeys { it.startsWith("app:") }
            .values.mapNotNull { (it as? Int)?.takeIf { id -> id >= 20 } }.toSet()
        var next = notificationAppIdPrefs.getInt("next", 20).coerceAtLeast(20)
        while (next in used) next++
        if (next > 32000) next = (20..32000).firstOrNull { it !in used } ?: 20
        notificationAppIdPrefs.edit().putInt(key, next).putInt("next", next + 1).apply()
        return next
    }

    fun sendTestNotification() {
        val png = runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 112, 112)
            drawable.draw(Canvas(bitmap))
            java.io.ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        }.getOrNull()
        forwardNotification("Fit3 App", io.github.yuriyurin.fit3companion.AppStrings.translate("Тест уведомлений"),
            io.github.yuriyurin.fit3companion.AppStrings.translate("Если вы видите это на часах, передача работает."),
            context.packageName, appIconPng = png)
    }

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
        if (!snapshot.connected || !snapshot.sapReady || !snapshot.notificationCapabilityReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            snapshot = snapshot.copy(notificationStatus = "Нет соединения с часами")
            publish()
            return
        }
        val sequence = notificationSequence++
        try {
            val appId = notificationAppId(packageName)
            // Samsung sends an APP_ICON_<id> URL with the notification; the watch requests
            // the image later with message 3. Command 19 is not an app-registration packet.
            val iconUrl = appIconPng?.takeIf { notificationIconFormat in listOf(2, 3, 5) }
                ?.let { "APP_ICON_$appId" }
            if (iconUrl != null) notificationIcons[iconUrl] = requireNotNull(appIconPng)
            val iconSentNow = iconUrl != null && syncedAppIcons.add(packageName)
            val monochrome = if (notificationIconFormat == 1 && appIconPng != null)
                encodeNotificationIcon(appIconPng, 35, 1) else null
            val payload = Fit3NotificationCodec.newNotification(
                sequence = sequence,
                title = title,
                text = body,
                appName = appName,
                packageName = packageName,
                whenMillis = System.currentTimeMillis(),
                appId = appId,
                popup = popup,
                category = category,
                canReply = canReply,
                iconUrl = iconUrl,
                iconBinary = monochrome,
                iconNewlyAdded = iconSentNow,
            )
            protocolLog("TX", Fit3SapCodec.SERVICE_NOTIFICATIONS, payload)
            writeMessage(Fit3SapCodec.SERVICE_NOTIFICATIONS, payload)
            if (notificationKey != null) {
                notificationSequenceByKey.put(notificationKey, sequence)?.let { notificationKeyBySequence.remove(it) }
                notificationKeyBySequence[sequence] = notificationKey
            }
            pendingNotificationSequence = sequence
            snapshot = snapshot.copy(notificationStatus = "${appName}: уведомление отправлено; ждём ответ часов…")
            publish()
            val sentGeneration = generation
            main.postDelayed({
                if (sentGeneration == generation && pendingNotificationSequence == sequence) {
                    pendingNotificationSequence = null
                    snapshot = snapshot.copy(notificationStatus =
                        "Нет подтверждения уведомления от часов; проверьте экран")
                    publish()
                }
            }, 8_000)
        } catch (error: Exception) {
            snapshot = snapshot.copy(notificationStatus = "Ошибка отправки ${appName}: ${error.message}")
            publish()
        }
    }

    private fun requestNotificationCapability(attempt: Int = 0) {
        if (!snapshot.sapReady || snapshot.notificationCapabilityReady) return
        sendReadOnly(Fit3SapCodec.SERVICE_NOTIFICATIONS, Fit3NotificationCodec.iconCapabilityRequest())
        val requestGeneration = generation
        if (attempt < 2) main.postDelayed({
            if (requestGeneration == generation) requestNotificationCapability(attempt + 1)
        }, 3_000)
    }

    private fun encodeNotificationIcon(png: ByteArray, size: Int, format: Int): ByteArray? {
        if (format !in listOf(1, 2, 3, 5)) return null
        val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(source, size, size, true)
        if (format == 1) {
            val rowBytes = (size + 7) / 8
            val bits = ByteArray(size * rowBytes)
            for (y in 0 until size) for (x in 0 until size) {
                if ((scaled.getPixel(x, y) ushr 24 and 0xff) > 128) {
                    val offset = y * rowBytes + x / 8
                    bits[offset] = (bits[offset].toInt() or (1 shl (7 - x % 8))).toByte()
                }
            }
            return bits
        }
        val out = ByteArray(size * size * if (format == 3) 4 else if (format == 5) 3 else 2)
        var index = 0
        for (y in 0 until size) for (x in 0 until size) {
            val pixel = scaled.getPixel(x, y)
            val alpha = pixel ushr 24 and 0xff
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            when (format) {
                2 -> {
                    val rgb565 = ((red and 0xf8) shl 8) or ((green and 0xfc) shl 3) or (blue ushr 3)
                    out[index++] = rgb565.toByte()
                    out[index++] = (rgb565 ushr 8).toByte()
                }
                3 -> {
                    out[index++] = red.toByte(); out[index++] = green.toByte()
                    out[index++] = blue.toByte(); out[index++] = (255 - alpha).toByte()
                }
                5 -> {
                    out[index++] = if (alpha > 127) 0.toByte() else 0xff.toByte()
                    out[index++] = ((red and 0xf8) or (green ushr 5)).toByte()
                    out[index++] = (((green shl 3) and 0xe0) or (blue ushr 3)).toByte()
                }
            }
        }
        return out
    }

    fun removeForwardedNotification(notificationKey: String) {
        val sequence = notificationSequenceByKey.remove(notificationKey) ?: return
        notificationKeyBySequence.remove(sequence)
        if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_NOTIFICATIONS,
            Fit3NotificationCodec.deleteFromMobile(sequence))
    }

    fun clearForwardedNotifications() {
        notificationKeyBySequence.clear()
        notificationSequenceByKey.clear()
        syncedAppIcons.clear()
        notificationIcons.clear()
        if (snapshot.sapReady) sendReadOnly(Fit3SapCodec.SERVICE_NOTIFICATIONS,
            Fit3NotificationCodec.clearAllFromMobile())
    }

    /** Refreshes all currently supported read-only data without resetting the watch. */
    fun refresh(manual: Boolean = true) {
        if (manual && goalSequence == null) goalAttempts = 0
        if (!snapshot.connected || !snapshot.sapReady || setupStage !in
            listOf(SetupStage.IDLE, SetupStage.COMPLETE)) {
            publish("Дождитесь подключения часов")
            return
        }
        val serial = ++refreshSerial
        val thisGeneration = generation
        snapshot = snapshot.copy(refreshing = manual || snapshot.refreshing, healthStatus = "Запрашиваем данные браслета…")
        publish()
        syncWatchFaces()
        main.postDelayed({
            if (serial == refreshSerial && thisGeneration == generation)
                sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3BatteryCodec.request)
        }, 300)
        main.postDelayed({
            if (serial == refreshSerial && thisGeneration == generation)
                sendReadOnly(Fit3SapCodec.SERVICE_SETTINGS, Fit3SettingsCodec.fullSettingsRequest)
        }, 480)
        main.postDelayed({
            if (serial == refreshSerial && thisGeneration == generation)
                requestHealthSnapshot()
        }, 900)
    }

    /** Starts the same WatchInfo -> DeviceStatus -> InitSettings sequence as the plugin. */
    fun startSetup() {
        if (!snapshot.connected || !snapshot.sapReady) {
            publish("Дождитесь готовности канала часов")
            return
        }
        if (setupStage !in listOf(SetupStage.IDLE, SetupStage.COMPLETE)) return
        advanceSetup(SetupStage.WAIT_INFO, "Настройка: сведения о часах…")
        sendReadOnly(Fit3SapCodec.SERVICE_OOBE, Fit3SapCodec.watchInfoRequest)
    }

    private fun advanceSetup(stage: SetupStage, status: String) {
        setupStage = stage
        snapshot = snapshot.copy(setupStage = stage)
        publish(status)
        if (stage.name.startsWith("WAIT_")) {
            val currentGeneration = generation
            main.postDelayed({
                if (generation == currentGeneration && setupStage == stage) {
                    setupStage = SetupStage.IDLE
                    snapshot = snapshot.copy(setupStage = SetupStage.IDLE)
                    publish("Нет ответа на шаг настройки: ${stage.name}")
                }
            }, 15_000)
        }
    }

    private fun handleSetupReply(message: ByteArray): Boolean {
        val id = Fit3OobeCodec.responseId(message) ?: return false
        when (setupStage) {
            SetupStage.WAIT_INFO -> if (id == 1) {
                snapshot = snapshot.copy(softwareVersion = Fit3SapCodec.findSoftwareVersion(message))
                advanceSetup(SetupStage.WAIT_DEVICE, "Настройка: состояние устройства…")
                sendReadOnly(Fit3SapCodec.SERVICE_OOBE, Fit3OobeCodec.deviceStatusRequest)
                return true
            }
            SetupStage.WAIT_DEVICE -> if (id == 2) {
                advanceSetup(SetupStage.WAIT_SETTINGS, "Настройка: дата и язык…")
                sendReadOnly(Fit3SapCodec.SERVICE_OOBE,
                    Fit3OobeCodec.initSettingsRequest(Instant.now(), ZoneId.systemDefault(),
                        localeId = io.github.yuriyurin.fit3companion.BandLanguage.localeId(context),
                        hour24 = DateFormat.is24HourFormat(context)))
                return true
            }
            SetupStage.WAIT_SETTINGS -> if (id == 3) {
                // The verified PC flow always sent step 4 after 0x43. The
                // WatchInfo flag is not a reliable substitute for 0x44:
                // previously paired watches can still show Setup Wizard.
                advanceSetup(SetupStage.WAIT_AGREEMENT, "Завершение настройки часов…")
                sendReadOnly(Fit3SapCodec.SERVICE_OOBE, Fit3OobeCodec.userAgreementRequest)
                return true
            }
            SetupStage.WAIT_AGREEMENT -> if (id == 4) {
                advanceSetup(SetupStage.COMPLETE, "Настройка завершена. Часы могут перезапуститься")
                syncBandLanguage()
                refresh(manual = false)
                return true
            }
            else -> Unit
        }
        return false
    }

    private fun writeRaw(bytes: ByteArray, onSent: (() -> Unit)? = null) {
        require(bytes.size <= actualMtu - 3) { "Пакет больше согласованного BLE MTU" }
        require(writeQueue.size < 64) { "Очередь Bluetooth переполнена" }
        writeQueue.addLast(QueuedWrite(bytes.copyOf(), onSent))
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writeBusy || writeQueue.isEmpty()) return
        val link = gatt ?: error("Нет соединения")
        val write = writeCharacteristic ?: error("Канал записи недоступен")
        val queued = writeQueue.removeFirst()
        val bytes = queued.bytes
        writeBusy = true
        currentWrite = queued
        val token = ++writeToken
        val writeType = if (acknowledgedWrites) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            link.writeCharacteristic(write, bytes, writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            write.writeType = writeType
            @Suppress("DEPRECATION")
            write.value = bytes
            @Suppress("DEPRECATION")
            link.writeCharacteristic(write)
        }
        if (!ok) {
            writeBusy = false
            fail("Android отклонил запись GATT")
            return
        }
        val thisGeneration = generation
        // A supported acknowledged characteristic supplies real flow control. Only
        // no-response-only peers need fixed pacing; their callbacks cannot release writes.
        main.postDelayed({
            if (thisGeneration == generation && writeBusy && writeToken == token) {
                if (acknowledgedWrites) fail("Нет подтверждения записи Bluetooth")
                else {
                    currentWrite = null
                    writeBusy = false
                    queued.onSent?.invoke()
                    pumpWrites()
                }
            }
        }, if (acknowledgedWrites) 8_000 else 250)
    }

    private fun fail(message: String) {
        setupStage = SetupStage.IDLE
        snapshot = snapshot.copy(connected = false, sapReady = false, setupStage = SetupStage.IDLE,
            refreshing = false)
        publish(message)
        disconnect(updateStatus = false)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val address = lastAddress ?: return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delay = (2_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(60_000L)
        reconnectAttempt++
        val scheduledGeneration = generation
        publish("Переподключение через ${delay / 1000} с…")
        main.postDelayed({
            if (scheduledGeneration == generation && lastAddress == address && gatt == null) {
                reconnectScheduled = false
                connect(address)
            }
        }, delay)
    }

    fun disconnect() = disconnect(updateStatus = true)

    private fun disconnect(updateStatus: Boolean) {
        generation++
        cancelMediaTransfers()
        healthLargeDataReceiver.reset()
        backSyncSession.reset()
        backSyncChunkCount = 0
        backSyncWireToken++
        notificationIconSender.reset()
        refreshSession.reset()
        pendingHealthRequest = null
        healthCapabilityReady = false
        healthCapabilityProbePending = false
        cancelFaceInstall(if (updateStatus) "Установка отменена" else "Связь прервана во время установки")
        if (updateStatus) {
            lastAddress = null
            reconnectScheduled = false
            reconnectAttempt = 0
        }
        writeCharacteristic = null
        writeQueue.clear()
        writeBusy = false
        currentWrite = null
        writeToken++
        pendingFace = null
        pendingDeleteFace = null
        faceDeleteToken++
        pendingNotificationSequence = null
        notificationKeyBySequence.clear()
        notificationSequenceByKey.clear()
        gatt?.let { link ->
            try { link.disconnect() } catch (_: SecurityException) { }
            link.close()
        }
        gatt = null
        setupStage = SetupStage.IDLE
        snapshot = snapshot.copy(connected = false, sapReady = false, setupStage = SetupStage.IDLE,
            refreshing = false, facesLoading = false, facesReady = false, faceChangePending = false,
            faceDeleteState = FaceDeleteState.IDLE,
            faceInstallPending = false)
        if (updateStatus) publish("Отключено")
    }

    private fun cancelFaceInstall(reason: String) {
        val wasPending = pendingInstallFace != null || snapshot.faceInstallPending
        if (wasPending) closeFaceTransferChannel()
        faceInstallDeadline = null
        pendingExtendedInstall = null
        pendingInstallCustomKey = null
        pendingInstallAcknowledged = false
        pendingInstallCommandSent = false
        faceInstallToken++
        runCatching { faceSocket?.close() }
        faceSocket = null
        pendingInstallFace = null
        if (wasPending) {
            snapshot = snapshot.copy(faceInstallPending = false, faceInstallStatus = reason)
        }
    }

    fun close() {
        stopScan()
        disconnect()
    }
}
