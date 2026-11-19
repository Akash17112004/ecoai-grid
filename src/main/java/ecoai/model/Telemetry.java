package ecoai.model;

import ecoai.util.Json;

import java.util.Locale;

/**
 * One telemetry sample from an ESP32 rack node. This record IS the wire contract:
 * {"rack":0,"t":123,"temp":34.5,"supply":24.1,"power":28.4,"fan":0.55,"pump":0.0,"rpm":1650,"trip":0,"fs":0}
 */
public record Telemetry(int rack, double t, double temp, double supply, double power,
                        double fan, double pump, double rpm, boolean trip, boolean failsafe) {

    public static Telemetry parse(String s) {
        return new Telemetry(
                (int) Json.num(s, "rack", -1),
                Json.num(s, "t", 0),
                Json.num(s, "temp", Double.NaN),
                Json.num(s, "supply", 24),
                Json.num(s, "power", 0),
                Json.num(s, "fan", 0),
                Json.num(s, "pump", 0),
                Json.num(s, "rpm", 0),
                Json.num(s, "trip", 0) > 0.5,
                Json.num(s, "fs", 0) > 0.5);
    }

    /** DS18B20 reports -127 when unplugged; NaN means the field was missing or unparsable. */
    public boolean tempValid() {
        return !Double.isNaN(temp) && temp > -20 && temp < 125;
    }

    public String toJson() {
        return String.format(Locale.ROOT,
                "{\"rack\":%d,\"t\":%.0f,\"temp\":%.4f,\"supply\":%.4f,\"power\":%.3f,\"fan\":%.3f,\"pump\":%.3f,\"rpm\":%.0f,\"trip\":%d,\"fs\":%d}",
                rack, t, temp, supply, power, fan, pump, rpm, trip ? 1 : 0, failsafe ? 1 : 0);
    }
}
