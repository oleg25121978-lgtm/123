package ru.rokidhud.phone;
import org.junit.Test;
import static org.junit.Assert.*;
public class NavDataTest {
    @Test public void rightTurn() {
        NavData d=NavData.parse(NavData.YANDEX_NAVI, "Через 200 м поверните направо");
        assertEquals("RIGHT", d.maneuver); assertEquals("200 м",d.distance);
    }
    @Test public void noFakeLaneOrLimit() {
        NavData d=NavData.parse(NavData.YANDEX_MAPS, "Через 1,2 км поворот налево");
        assertNull(d.limitKmh); assertEquals("LEFT", d.maneuver);
    }
    @Test public void rejectForeign() { assertNull(NavData.parse("com.other", "200 м направо")); }
}
