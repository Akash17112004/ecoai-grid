package ecoai.web;

import ecoai.Config;
import ecoai.control.Backend;
import ecoai.util.Json;

import java.util.List;
import java.util.function.IntFunction;

/** Builds the single JSON document the dashboard polls. Works for 1..N controllers (demo has 4, live has 1). */
public final class StateJson {
    private StateJson() {}

    /** One controller to show. The last view is the "main" one (charts); topExtra/rackExtra are raw JSON fragments. */
    public record View(Backend backend, String topExtra, IntFunction<String> rackExtra) {}

    public static String build(String mode, double t, double speed, boolean paused, String broker,
                               boolean simControls, List<View> views) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("{\"mode\":").append(Json.quote(mode))
          .append(",\"t\":").append(Json.fmt(t))
          .append(",\"speed\":").append(Json.fmt(speed))
          .append(",\"paused\":").append(paused)
          .append(",\"sla\":").append(Config.SLA)
          .append(",\"setpoint\":").append(Json.fmt(Config.setpoint))
          .append(",\"waterWeight\":").append(Json.fmt(Config.waterWeight))
          .append(",\"broker\":").append(broker == null ? "null" : Json.quote(broker))
          .append(",\"simControls\":").append(simControls)
          .append(",\"sc\":[");
        for (int k = 0; k < views.size(); k++) {
            if (k > 0) sb.append(',');
            View v = views.get(k);
            v.backend().json(sb, v.topExtra(), v.rackExtra(), k == views.size() - 1 ? 40 : 0);
        }
        sb.append("],\"hist\":{");
        List<double[]> main = views.get(views.size() - 1).backend().history();
        sb.append("\"t\":");
        series(sb, main, 0);
        for (int r = 0; r < Config.RACKS; r++) { sb.append(",\"T").append(r).append("\":"); series(sb, main, 1 + r); }
        for (int r = 0; r < Config.RACKS; r++) { sb.append(",\"F").append(r).append("\":"); series(sb, main, 6 + r); }
        for (int k = 0; k < views.size(); k++) {
            sb.append(",\"cool").append(k).append("\":");
            series(sb, views.get(k).backend().history(), 4);
        }
        sb.append("}}");
        return sb.toString();
    }

    private static void series(StringBuilder sb, List<double[]> h, int col) {
        sb.append('[');
        for (int i = 0; i < h.size(); i++) { if (i > 0) sb.append(','); sb.append(Json.fmt(h.get(i)[col])); }
        sb.append(']');
    }
}
