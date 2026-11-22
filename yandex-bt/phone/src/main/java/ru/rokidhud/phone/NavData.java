package ru.rokidhud.phone;

import ru.rokidhud.wire.HudMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NavData {
    private static final Pattern DISTANCE = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[,.]\\d+)?\\s*(?:км|метр(?:а|ов)?|м|km|m)(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LIMIT = Pattern.compile("(?:ограничение(?:\\s+скорости)?|лимит(?:\\s+скорости)?)\\s*[:—-]?\\s*(\\d{2,3})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public static final String YANDEX_MAPS = "ru.yandex.yandexmaps";
    public static final String YANDEX_NAVI = "ru.yandex.yandexnavi";
    public static volatile NavData current;
    public final long receivedMs;
    public final String raw;
    public final String distance;
    public final String maneuver;
    public final Integer limitKmh;
    public final String source;
    public final String street;

    private NavData(long receivedMs, String raw, String distance, String maneuver, Integer limitKmh, String source, String street) {
        this.receivedMs=receivedMs; this.raw=raw; this.distance=distance; this.maneuver=maneuver;
        this.limitKmh=limitKmh; this.source=source; this.street=street;
    }

    public static NavData parse(String pkg, String text) {
        if (!YANDEX_MAPS.equals(pkg) && !YANDEX_NAVI.equals(pkg)) return null;
        if (text == null || text.trim().isEmpty()) return null;
        String s = text.toLowerCase(java.util.Locale.ROOT);
        String m = "UNKNOWN";
        if (s.contains("разворот") || s.contains("развернитесь")) m="UTURN";
        else if (s.contains("кольцев") || s.contains("кругов") || s.contains("roundabout")) m="ROUNDABOUT";
        else if (s.contains("левее") || s.contains("плавно налево")) m="SLIGHT_LEFT";
        else if (s.contains("правее") || s.contains("плавно направо")) m="SLIGHT_RIGHT";
        else if (s.contains("налево") || s.contains("поверните влево") || s.contains("turn left")) m="LEFT";
        else if (s.contains("направо") || s.contains("поверните вправо") || s.contains("turn right")) m="RIGHT";
        else if (s.contains("прямо") || s.contains("straight")) m="STRAIGHT";
        else if (s.contains("прибыли") || s.contains("месте назначения")) m="ARRIVE";
        Matcher d = DISTANCE.matcher(text), l = LIMIT.matcher(text);
        String distance = d.find() ? d.group() : "";
        Integer limit = l.find() ? Integer.parseInt(l.group(1)) : null;
        if (limit != null && (limit < 5 || limit > 150)) limit = null;
        return new NavData(System.currentTimeMillis(), text.substring(0, Math.min(400, text.length())), distance, m, limit, pkg, "");
    }

    public NavData withGuidance(String imageManeuver, String routeStreet) {
        return new NavData(receivedMs, raw, distance,
            "UNKNOWN".equals(imageManeuver) ? maneuver : imageManeuver,
            limitKmh, source, routeStreet == null ? "" : routeStreet);
    }

    public HudMessage toWire(Integer speed) {
        HudMessage h = new HudMessage();
        h.state = distance.isEmpty() ? "idle" : "active";
        h.source = source; h.distance = distance; h.maneuver = maneuver; h.street = street;
        h.limitKmh = limitKmh; h.speedKmh = speed; h.text = raw;
        return h;
    }
}
