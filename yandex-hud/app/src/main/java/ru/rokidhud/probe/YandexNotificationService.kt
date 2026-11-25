package ru.rokidhud.probe

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

data class NavSnapshot(
    val source: String,
    val maneuver: Maneuver,
    val distance: String?,
    val street: String?,
    val rawText: String,
    val imageIds: List<String> = emptyList(),
    val samples: List<ArrowCapture.Sample> = emptyList()
)
enum class Maneuver { LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, STRAIGHT, ROUNDABOUT, ARRIVE, UNKNOWN }

object NavStore {
    @Volatile var current: NavSnapshot? = null
        private set
    val listeners = CopyOnWriteArraySet<(NavSnapshot?) -> Unit>()
    fun publish(value: NavSnapshot?) { current = value; listeners.forEach { it(value) } }
}

object YandexNotificationParser {
    // Notification format varies between Yandex Maps/Nav versions and may contain bitmap-only arrows.
    private val allowed = setOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")
    private val distance = Regex("""\b\d+(?:[.,]\d+)?\s*(?:км|м|km|m)\b""", RegexOption.IGNORE_CASE)
    fun parse(packageName: String, text: String): NavSnapshot? {
        if (packageName !in allowed || text.isBlank()) return null
        val lower = text.lowercase()
        val maneuver = when {
            Regex("""разворот|развернитесь|u.turn""").containsMatchIn(lower) -> Maneuver.UTURN
            Regex("""кругов|кольцев|roundabout""").containsMatchIn(lower) -> Maneuver.ROUNDABOUT
            Regex("""плавно налево|держитесь левее""").containsMatchIn(lower) -> Maneuver.SLIGHT_LEFT
            Regex("""плавно направо|держитесь правее""").containsMatchIn(lower) -> Maneuver.SLIGHT_RIGHT
            Regex("""налево|левый поворот|turn left""").containsMatchIn(lower) -> Maneuver.LEFT
            Regex("""направо|правый поворот|turn right""").containsMatchIn(lower) -> Maneuver.RIGHT
            Regex("""прямо|straight""").containsMatchIn(lower) -> Maneuver.STRAIGHT
            Regex("""прибыли|пункт назначения|destination""").containsMatchIn(lower) -> Maneuver.ARRIVE
            else -> Maneuver.UNKNOWN
        }
        return NavSnapshot(packageName, maneuver, distance.find(text)?.value, null, text.take(500))
    }
}

class YandexNotificationService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var unreadable: Runnable? = null
    private var activeRouteKey: String? = null
    private fun clearRoute() {
        unreadable?.let(handler::removeCallbacks)
        unreadable = null
        activeRouteKey = null
        NavStore.publish(null)
        HudBusBinding.release(this)
    }
    private fun markUnreadable(key: String) {
        if (activeRouteKey != key || unreadable != null) return
        val task = Runnable { clearRoute() }
        unreadable = task
        handler.postDelayed(task, 10000L)
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { isYandex(it.packageName) }?.lastOrNull()?.let(::onNotificationPosted)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !isYandex(sbn.packageName)) return
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT == 0) { markUnreadable(sbn.key); return }
        if (sbn.notification.category != "navigation") { markUnreadable(sbn.key); return }
        val layout = NotificationLayoutInspector.inspect(this, sbn.notification)
        val e = sbn.notification.extras
        val parts = mutableListOf<String>()
        for (key in arrayOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)) {
            e.getCharSequence(key)?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts.add(it.toString()) }
        parts.addAll(layout.texts)
        val snapshot = YandexNotificationParser.parse(sbn.packageName, parts.distinct().joinToString(" | "))
            ?.copy(imageIds = layout.imageResourceNames, samples = layout.samples)
        // Exact hash recognition only. An unlabeled picture never produces a turn.
        if (snapshot != null) {
            val fromImage = ArrowLabels.resolve(this, snapshot.samples)
            val textManeuver = snapshot.maneuver
            val chosen = if (fromImage != Maneuver.UNKNOWN) fromImage else textManeuver
            val destination = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            val streetName = destination?.takeIf { it.isNotBlank() && !it.contains("Navigator is running", ignoreCase = true) }
            val next = snapshot.copy(maneuver = chosen, street = streetName)
            if (next.distance != null) {
                unreadable?.let(handler::removeCallbacks)
                unreadable = null
                activeRouteKey = sbn.key
                HudBusBinding.hold(this)
                NavStore.publish(next)
            } else markUnreadable(sbn.key)
        } else markUnreadable(sbn.key)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.key == activeRouteKey) clearRoute()
    }
    override fun onListenerDisconnected() {
        clearRoute()
        super.onListenerDisconnected()
    }
    override fun onDestroy() {
        clearRoute()
        super.onDestroy()
    }
    private fun isYandex(pkg: String) = pkg == "ru.yandex.yandexmaps" || pkg == "ru.yandex.yandexnavi"
}
