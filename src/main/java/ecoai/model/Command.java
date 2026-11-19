package ecoai.model;

import ecoai.util.Json;

import java.util.Locale;

/** Actuator command from the backend to a rack node: {"fan":0.55,"pump":0.25,"seq":42}. NaN = field absent. */
public record Command(double fan, double pump, long seq) {

    public static Command parse(String s) {
        return new Command(Json.num(s, "fan", Double.NaN), Json.num(s, "pump", Double.NaN), (long) Json.num(s, "seq", 0));
    }

    public String toJson() {
        return String.format(Locale.ROOT, "{\"fan\":%.3f,\"pump\":%.3f,\"seq\":%d}", fan, pump, seq);
    }
}
