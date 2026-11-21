package app.fluxa

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps a light copy of the active notifications so the in-game drawer can show them (messages,
 * music players and so on) without leaving the game. Needs "notification access" granted once in
 * Settings; nothing is stored or sent anywhere, the list only lives in memory.
 */
class FluxaNotificationListener : NotificationListenerService() {

    class Action(val title: String, val intent: PendingIntent?)

    class Item(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val media: Boolean,
        val clearable: Boolean,
        val time: Long,
        val actions: List<Action>,
    )

    override fun onListenerConnected() {
        instance = this
        notifyChanged()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        notifyChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = notifyChanged()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = notifyChanged()

    private fun toItem(sbn: StatusBarNotification): Item? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val media = n.category == Notification.CATEGORY_TRANSPORT || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        // persistent system / service notifications are noise; keep media players though
        if (!media && sbn.isOngoing) return null
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val actions = if (media) {
            (n.actions ?: emptyArray()).filter { !it.title.isNullOrBlank() }.take(4)
                .map { Action(it.title.toString(), it.actionIntent) }
        } else emptyList()
        return Item(sbn.key, app, title, text, media, sbn.isClearable, sbn.postTime, actions)
    }

    private fun snapshotInternal(): List<Item> =
        runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .mapNotNull { toItem(it) }
            .sortedWith(compareByDescending<Item> { it.media }.thenByDescending { it.time })

    companion object {
        @Volatile private var instance: FluxaNotificationListener? = null
        private val ui = Handler(Looper.getMainLooper())

        /** Called on the main thread whenever the notification list changes (set by the drawer, cleared on close). */
        @Volatile var onChange: (() -> Unit)? = null

        private fun notifyChanged() {
            val cb = onChange ?: return
            ui.post { cb() }
        }

        fun enabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun snapshot(): List<Item> = instance?.snapshotInternal().orEmpty()

        fun dismiss(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }
    }
}
