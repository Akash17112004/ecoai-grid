package ecoai.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny flat-JSON helpers (the MQTT payloads are flat objects, so no library is needed). */
public final class Json {
    private Json() {}

    private static final Map<String, Pattern> NUM = new ConcurrentHashMap<>();
    private static final Map<String, Pattern> STR = new ConcurrentHashMap<>();

    /** Reads a numeric field; returns def when missing, null, NaN, or malformed. */
    public static double num(String json, String key, double def) {
        Pattern p = NUM.computeIfAbsent(key, k ->
                Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)"));
        Matcher m = p.matcher(json);
        if (!m.find()) return def;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return def; }
    }

    /** Reads a string field; returns def when missing. */
    public static String str(String json, String key, String def) {
        Pattern p = STR.computeIfAbsent(key, k ->
                Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"([^\"]*)\""));
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : def;
    }

    /** Number to JSON (NaN / Infinity become null). */
    public static String fmt(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "null" : String.format(Locale.ROOT, "%.3f", v);
    }

    /** String to a quoted JSON string. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
