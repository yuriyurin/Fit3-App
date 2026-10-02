package io.github.yuriyurin.fit3companion.ble

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.AudioDeviceInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import io.github.yuriyurin.fit3companion.protocol.Fit3MediaCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3VolumePolicy
import java.io.ByteArrayOutputStream

/** Uses Android's public MediaSession/AudioManager APIs; no Samsung service dependency. */
class Fit3MediaBridge(private val context: Context) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(context, Fit3NotificationListener::class.java)
    data class Artwork(val name: String, val bytes: ByteArray)
    private var cachedArtworkKey: String? = null
    private var cachedArtwork: Artwork? = null
    private var cachedArtworkBitmap: Bitmap? = null
    private val cachedAppIcons = HashMap<String, Artwork?>()
    private var volumeBeforeMute = 0

    private fun controllers(): List<MediaController> = try {
        sessions?.getActiveSessions(listener).orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun activeController(): MediaController? {
        val all = controllers()
        return all.firstOrNull {
            it.playbackState?.state in setOf(
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
            )
        } ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: all.firstOrNull()
    }

    fun currentState(): Fit3MediaCodec.MediaState = stateOf(activeController())

    private fun stateOf(controller: MediaController?, metadata: MediaMetadata? = controller?.metadata): Fit3MediaCodec.MediaState {
        val playback = controller?.playbackState
        val state = when (playback?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> Fit3MediaCodec.PLAYBACK_PLAYING
            PlaybackState.STATE_PAUSED -> Fit3MediaCodec.PLAYBACK_PAUSED
            else -> Fit3MediaCodec.PLAYBACK_STOPPED
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString().orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.description?.subtitle?.toString().orEmpty()
        return Fit3MediaCodec.MediaState(
            playbackState = state,
            positionMs = playback?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            title = title,
            artist = artist,
            appId = controller?.packageName.orEmpty(),
            activeItemId = playback?.activeQueueItemId ?: -1L,
        )
    }

    fun currentQueue(): Fit3MediaCodec.QueueData {
        val controller = activeController()
        val items = controller?.queue.orEmpty().take(Fit3MediaCodec.MAX_QUEUE_ITEMS).map { item ->
            Fit3MediaCodec.QueueItem(
                item.queueId,
                item.description.title?.toString().orEmpty(),
                item.description.subtitle?.toString().orEmpty(),
            )
        }
        return Fit3MediaCodec.QueueData(
            controller?.packageName.orEmpty(),
            controller?.queueTitle?.toString().orEmpty(),
            items,
        )
    }

    fun currentAppTitle(appId: String): String {
        if (appId.isBlank()) return ""
        return try {
            val info = context.packageManager.getApplicationInfo(appId, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { appId.substringAfterLast('.') }
    }

    /** Match the stock media provider's circular 32-pixel app icon file. */
    fun currentAppIcon(appId: String): Artwork? {
        if (appId.isBlank()) return null
        if (cachedAppIcons.containsKey(appId)) return cachedAppIcons[appId]
        val icon = try {
            val drawable = context.packageManager.getApplicationIcon(appId)
            val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val sourceCanvas = Canvas(source)
            drawable.setBounds(0, 0, 32, 32)
            drawable.draw(sourceCanvas)
            val masked = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(masked)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawCircle(16f, 16f, 16f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(source, Rect(0, 0, 32, 32), Rect(0, 0, 32, 32), paint)
            paint.xfermode = null
            source.recycle()
            val bytes = ByteArrayOutputStream().use { stream ->
                masked.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            masked.recycle()
            Artwork("$appId.app", bytes)
        } catch (_: Exception) { null }
        cachedAppIcons[appId] = icon
        return icon
    }

    fun skipToItem(appId: String, queueId: Long): Boolean {
        val controller = activeController() ?: return false
        if (controller.packageName != appId || controller.queue.orEmpty().none { it.queueId == queueId }) return false
        return try {
            controller.transportControls.skipToQueueItem(queueId)
            true
        } catch (_: Exception) { false }
    }

    /** Metadata and art must describe the same controller/track, even during a skip. */
    fun currentMedia(): Pair<Fit3MediaCodec.MediaState, Artwork?> {
        val controller = activeController()
        val metadata = controller?.metadata
        return stateOf(controller, metadata) to artworkOf(controller, metadata)
    }

    fun currentArtwork(): Artwork? = artworkOf(activeController())

    private fun artworkOf(controller: MediaController?, metadata: MediaMetadata? = controller?.metadata): Artwork? {
        controller ?: return null
        metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty()
        val key = "${controller.packageName}|$title|$artist|${metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}|$artUri"
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: artworkUriBitmap(metadata)
        // Match the stock validator: late/replaced bitmaps matter even when all text and
        // the URI stay unchanged. Keep an owned copy; a player may reuse/mutate its bitmap.
        if (key == cachedArtworkKey && bitmap != null &&
            runCatching { cachedArtworkBitmap?.sameAs(bitmap) == true }.getOrDefault(false)) {
            return cachedArtwork
        }
        if (key == cachedArtworkKey && bitmap == null && cachedArtwork != null) return cachedArtwork
        val artwork = bitmap?.let { source ->
            try {
                val output = Bitmap.createBitmap(256, 402, Bitmap.Config.RGB_565)
                val canvas = Canvas(output)
                canvas.drawColor(Color.BLACK)
                val scale = minOf(256f / source.width, 402f / source.height)
                val width = source.width * scale
                val height = source.height * scale
                canvas.drawBitmap(source, null, RectF((256 - width) / 2, (402 - height) / 2,
                    (256 + width) / 2, (402 + height) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                val bytes = ByteArrayOutputStream().use { stream ->
                    output.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
                output.recycle()
                Artwork(Fit3MediaCodec.mediaFileName(controller.packageName, artist, title,
                    "art", System.currentTimeMillis()), bytes)
            } catch (_: Exception) { null }
        }
        cachedArtworkKey = key
        cachedArtwork = artwork
        cachedArtworkBitmap?.recycle()
        cachedArtworkBitmap = if (artwork != null) runCatching {
            bitmap?.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }.getOrNull() else null
        return artwork
    }

    private fun artworkUriBitmap(metadata: MediaMetadata): Bitmap? {
        val raw = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(raw))?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    /** Spotify's local session reports 0/0 on some phones; its actual scale is STREAM_MUSIC. */
    private fun remoteVolumeInfo(controller: MediaController?): MediaController.PlaybackInfo? =
        runCatching {
            controller?.playbackInfo?.takeIf {
                it.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && it.maxVolume > 0
            }
        }.getOrNull()

    fun currentVolume(): Int = try {
        remoteVolumeInfo(activeController())?.currentVolume
            ?: audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    } catch (_: Exception) { 0 }

    fun maxVolume(): Int = try {
        remoteVolumeInfo(activeController())?.maxVolume
            ?: audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    } catch (_: Exception) { 15 }

    fun headsetConnected(): Boolean = try {
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true
    } catch (_: Exception) { false }

    fun warningVolume(): Int = (maxVolume() * 2 / 3).coerceAtLeast(1)

    fun handleRemote(action: Fit3MediaCodec.RemoteAction): Boolean {
        val controller = activeController() ?: return false
        return try {
            when (action) {
                Fit3MediaCodec.RemoteAction.STOP -> controller.transportControls.stop()
                Fit3MediaCodec.RemoteAction.PLAY_PAUSE -> {
                    when (controller.playbackState?.state) {
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.STATE_BUFFERING,
                        PlaybackState.STATE_CONNECTING -> controller.transportControls.pause()
                        else -> controller.transportControls.play()
                    }
                }
                Fit3MediaCodec.RemoteAction.PREVIOUS -> controller.transportControls.skipToPrevious()
                Fit3MediaCodec.RemoteAction.NEXT -> controller.transportControls.skipToNext()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun handleVolume(command: Fit3MediaCodec.VolumeCommand): Boolean {
        val manager = audio ?: return false
        val controller = activeController()
        val remoteInfo = remoteVolumeInfo(controller)
        val remoteController = if (remoteInfo != null) controller else null
        return try {
            val max = remoteInfo?.maxVolume ?: manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = remoteInfo?.currentVolume ?: manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            fun adjust(direction: Int) {
                if (remoteController != null) remoteController.adjustVolume(direction, 0)
                else manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            }
            fun set(value: Int) {
                if (remoteController != null) remoteController.setVolumeTo(value, 0)
                else manager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            }
            when (command.action) {
                Fit3MediaCodec.VolumeAction.SET -> {
                    val value = command.value ?: return false
                    val step = Fit3VolumePolicy.stepToward(current, max, value) ?: return false
                    // The stock plugin changes one step per watch command on other OEMs.
                    val directSet = Build.MANUFACTURER.equals("samsung", true) ||
                        Build.MANUFACTURER.equals("google", true)
                    if (directSet) {
                        set(value)
                    } else if (step != 0) adjust(step)
                }
                Fit3MediaCodec.VolumeAction.UP -> adjust(AudioManager.ADJUST_RAISE)
                Fit3MediaCodec.VolumeAction.DOWN -> adjust(AudioManager.ADJUST_LOWER)
                Fit3MediaCodec.VolumeAction.MUTE_ON -> {
                    volumeBeforeMute = current
                    set(0)
                }
                Fit3MediaCodec.VolumeAction.MUTE_OFF -> {
                    val restored = volumeBeforeMute.coerceIn(1, max.coerceAtLeast(1))
                    set(restored)
                    volumeBeforeMute = 0
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
