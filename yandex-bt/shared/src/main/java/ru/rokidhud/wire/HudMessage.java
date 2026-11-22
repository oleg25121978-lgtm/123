package ru.rokidhud.wire;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** One UTF-8 JSON object per line, no image/screen data. Versioned protocol. */
public final class HudMessage {
    public static final UUID SERVICE_UUID = UUID.fromString("5a6d41c7-8aa2-4cc4-bf87-d6c362182404");
    public String state = "idle";
    public String maneuver = "UNKNOWN";
    public String distance = "";
    public String street = "";
    public Integer speedKmh = null;
    public Integer limitKmh = null;
    public long timestampMs = System.currentTimeMillis();
    public String source = "yandex";
    public String text = "";

    public String encode() {
        JSONObject j = new JSONObject();
        try {
            j.put("v", 1); j.put("state", state); j.put("maneuver", maneuver);
            j.put("distance", distance); j.put("street", street);
            if (speedKmh != null) j.put("speedKmh", speedKmh);
            if (limitKmh != null) j.put("limitKmh", limitKmh);
            j.put("timestampMs", timestampMs); j.put("source", source);
            j.put("text", text);
            return j.toString();
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
    }

    public static HudMessage decode(String line) throws JSONException {
        JSONObject j = new JSONObject(line);
        if (j.optInt("v", -1) != 1) throw new JSONException("Unsupported HUD version");
        HudMessage m = new HudMessage();
        m.state = j.optString("state", "idle");
        m.maneuver = j.optString("maneuver", "UNKNOWN");
        m.distance = j.optString("distance", "");
        m.street = j.optString("street", "");
        m.speedKmh = j.has("speedKmh") && !j.isNull("speedKmh") ? j.getInt("speedKmh") : null;
        m.limitKmh = j.has("limitKmh") && !j.isNull("limitKmh") ? j.getInt("limitKmh") : null;
        m.timestampMs = j.optLong("timestampMs", 0L);
        m.source = j.optString("source", "");
        m.text = j.optString("text", "");
        return m;
    }
}
