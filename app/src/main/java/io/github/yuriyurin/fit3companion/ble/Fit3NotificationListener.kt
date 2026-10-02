package io.github.yuriyurin.fit3companion.ble

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.yuriyurin.fit3companion.protocol.Fit3NotificationCodec
import java.io.ByteArrayOutputStream

/** Opt-in two-way notification bridge. Optional Protocol Monitor diagnostics may contain private data. */
class Fit3NotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var active: Fit3NotificationListener? = null
            private set
        private const val WATCH_ICON_SIZE = 112
    }

    private val recent = mutableMapOf<String, Long>()
    private val known = mutableMapOf<String, StatusBarNotification>()

    override fun onCreate() {
        super.onCreate()
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (isForwardingEnabled()) resyncToWatch()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        known[sbn.key] = sbn
        if (!isForwardingEnabled() || !shouldForward(sbn)) return
        val now = System.currentTimeMillis()
        if (now - (recent[sbn.key] ?: 0L) < 1_000L) return
        recent[sbn.key] = now
        if (recent.size > 300) recent.clear()
        forward(sbn, popup = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        known.remove(sbn.key)
        recent.remove(sbn.key)
        if (isForwardingEnabled()) Fit3ConnectionService.active?.removeForwardedNotification(sbn.key)
    }

    private fun isForwardingEnabled(): Boolean =
        getSharedPreferences("fit3_notifications", MODE_PRIVATE).let {
            it.getBoolean("enabled", false) && it.getBoolean("read_enabled", true)
        }

    private fun shouldForward(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && !sbn.isOngoing &&
            !isMediaTransportNotification(sbn.notification) &&
            sbn.notification.visibility != Notification.VISIBILITY_SECRET &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    /**
     * Playback controls are ongoing media state, not user notifications. Forwarding them made every
     * Spotify/YouTube track change pop up on Fit3 as if a new message had arrived.
     */
    private fun isMediaTransportNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras
        if (extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        val template = extras?.getString("android.template").orEmpty()
        if (template.contains("MediaStyle", ignoreCase = true)) return true
        return false
    }

    private fun forward(sbn: StatusBarNotification, popup: Boolean) {
        val notification = sbn.notification
        val extras = notification.extras ?: return
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }

        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val regular = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }?.filter { it.isNotBlank() }
        val body = when {
            !big.isNullOrBlank() -> big
            !lines.isNullOrEmpty() -> lines.takeLast(3).joinToString("\n")
            !regular.isNullOrBlank() -> regular
            else -> ""
        }
        val heading = when {
            !conversation.isNullOrBlank() && title.isNotBlank() -> "$conversation · $title"
            title.isNotBlank() -> title
            !conversation.isNullOrBlank() -> conversation
            else -> appName
        }
        if (heading.isBlank() && body.isBlank()) return
        val canReply = notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
        // Notification.largeIcon is the legacy Bitmap field on some Android SDK stubs,
        // while getLargeIcon() returns Icon. Using property syntax here is ambiguous in Kotlin.
        // For the watch identity we prefer the installed application icon anyway; if that
        // cannot be loaded, fall back to Notification.getLargeIcon()/smallIcon explicitly.
        val icon = applicationIconPng(sbn.packageName)
            ?: runCatching { notification.getLargeIcon()?.loadDrawable(this)?.let(::drawableToPng) }.getOrNull()
            ?: runCatching { notification.smallIcon?.loadDrawable(this)?.let(::drawableToPng) }.getOrNull()
        Fit3ConnectionService.active?.forwardNotification(
            appName = appName,
            title = heading,
            body = body,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            popup = popup,
            category = notification.category,
            canReply = canReply,
            appIconPng = icon,
        )
    }

    private fun applicationIconPng(packageName: String): ByteArray? = try {
        val drawable = packageManager.getApplicationIcon(packageName)
        drawableToPng(drawable)
    } catch (_: Exception) { null }

    private fun drawableToPng(drawable: Drawable): ByteArray? = try {
        val bitmap = Bitmap.createBitmap(WATCH_ICON_SIZE, WATCH_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, WATCH_ICON_SIZE, WATCH_ICON_SIZE)
        drawable.draw(canvas)
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) null else stream.toByteArray()
    } catch (_: Exception) { null }

    /** Clear the watch-side mirror and silently repopulate it after reconnect. */
    fun resyncToWatch() {
        if (!isForwardingEnabled()) return
        Fit3ConnectionService.active?.clearForwardedNotifications()
        val source = try { activeNotifications?.toList().orEmpty() } catch (_: Exception) { known.values.toList() }
        source.filter(::shouldForward).forEach { sbn ->
            known[sbn.key] = sbn
            forward(sbn, popup = false)
        }
    }

    fun handleWatchCommand(key: String?, command: Fit3NotificationCodec.BandCommand) {
        when (command) {
            Fit3NotificationCodec.BandCommand.ClearAll -> {
                try { cancelAllNotifications() } catch (_: Exception) { }
                known.clear()
            }
            is Fit3NotificationCodec.BandCommand.Delete -> {
                if (key != null) try { cancelNotification(key) } catch (_: Exception) { }
            }
            is Fit3NotificationCodec.BandCommand.ShowOnPhone -> {
                val sbn = key?.let { known[it] ?: activeNotifications?.firstOrNull { n -> n.key == it } }
                try { sbn?.notification?.contentIntent?.send() } catch (_: Exception) { }
            }
            is Fit3NotificationCodec.BandCommand.Reply -> {
                val sbn = key?.let { known[it] ?: activeNotifications?.firstOrNull { n -> n.key == it } }
                if (sbn != null) sendRemoteInputReply(sbn.notification, command.text)
            }
        }
    }

    private fun sendRemoteInputReply(notification: Notification, text: String): Boolean {
        val action = notification.actions?.firstOrNull { a -> !a.remoteInputs.isNullOrEmpty() } ?: return false
        val remoteInputs = action.remoteInputs ?: return false
        return try {
            val fillInIntent = Intent()
            val results = Bundle()
            remoteInputs.forEach { input -> results.putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
            action.actionIntent.send(this, 0, fillInIntent)
            true
        } catch (_: Exception) { false }
    }
}
