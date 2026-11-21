package ru.rokidhud.probe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

data class NavSnapshot(
    val source: String,
    val maneuver: Maneuver,
    val distance: String?,
    val street: String?,
    val rawText: String,
    val imageIds: List<String> = emptyList()
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
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.filter { isYandex(it.packageName) }?.lastOrNull()?.let(::onNotificationPosted)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !isYandex(sbn.packageName)) return
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        if (sbn.notification.category != Notification.CATEGORY_NAVIGATION) return
        val layout = NotificationLayoutInspector.inspect(this, sbn.notification)
        val e = sbn.notification.extras
        val parts = mutableListOf<String>()
        for (key in arrayOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)) {
            e.getCharSequence(key)?.toString()?.takeIf(String::isNotBlank)?.let(parts::add)
        }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts.add(it.toString()) }
        parts.addAll(layout.texts)
        val snapshot = YandexNotificationParser.parse(sbn.packageName, parts.distinct().joinToString(" | "))
            ?.copy(imageIds = layout.imageResourceNames)
        // Ignore empty notification updates; a later screen/bitmap extractor may fill the missing fields.
        if (snapshot != null) NavStore.publish(snapshot)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isYandex(sbn.packageName)) {
            val active = activeNotifications?.any { isYandex(it.packageName) } == true
            if (!active) NavStore.publish(null)
        }
    }
    private fun isYandex(pkg: String) = pkg == "ru.yandex.yandexmaps" || pkg == "ru.yandex.yandexnavi"
}
