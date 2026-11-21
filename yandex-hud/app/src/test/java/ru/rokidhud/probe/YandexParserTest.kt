package ru.rokidhud.probe

import org.junit.Assert.*
import org.junit.Test

class YandexParserTest {
    @Test fun acceptsRealYandexPackage() {
        val n = YandexNotificationParser.parse("ru.yandex.yandexmaps", "Через 350 м поверните направо")
        assertNotNull(n)
        assertEquals(Maneuver.RIGHT, n!!.maneuver)
        assertEquals("350 м", n.distance)
    }
    @Test fun rejectsOtherApps() {
        assertNull(YandexNotificationParser.parse("com.example.app", "350 м направо"))
    }
    @Test fun doesNotGuessArrowFromDistanceOnly() {
        val n = YandexNotificationParser.parse("ru.yandex.yandexmaps", "350 м")
        assertEquals(Maneuver.UNKNOWN, n?.maneuver)
    }
    @Test fun handlesRussianUturn() {
        val n = YandexNotificationParser.parse("ru.yandex.yandexnavi", "Через 1,2 км разворот")
        assertEquals(Maneuver.UTURN, n?.maneuver)
        assertEquals("1,2 км", n?.distance)
    }
    @Test fun ignoresEmptyMessages() {
        assertNull(YandexNotificationParser.parse("ru.yandex.yandexmaps", ""))
    }
}
