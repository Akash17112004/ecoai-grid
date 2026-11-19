package ecoai.app;

import ecoai.Config;
import ecoai.control.Backend;
import ecoai.control.EventLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Self-contained, printable HTML report (Save as PDF from the browser) and CSV results for one run. */
public final class ReportBuilder {
    private ReportBuilder() {}

    /** One controller's totals. groundTruth rows come from the rig's own meters; otherwise they are backend estimates. */
    public record Row(String name, Backend backend, double whCool, double whFan, double whPump, double water,
                      double avgT, double maxT, double violS, double shedS) {}

    public record Data(String title, String mode, double simSeconds, String profile, boolean groundTruth,
                       List<Row> rows, Backend ai) {}

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    private static String nz(double v) { return Double.isNaN(v) ? "n/a" : f("%.1f", v); }

    static String clock(double s) {
        long t = (long) s;
        return f("%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60);
    }

    public static String csv(Data d) {
        StringBuilder sb = new StringBuilder("controller,cooling_wh,fan_wh,pump_wh,water_l,avg_temp_c,max_temp_c,sla_breach_s,load_shed_s\n");
        for (Row r : d.rows()) {
            sb.append(f("%s,%.3f,%.3f,%.3f,%.4f,%.2f,%.2f,%.0f,%.0f%n", r.name(), r.whCool(), r.whFan(), r.whPump(), r.water(),
                    r.avgT(), r.maxT(), r.violS(), r.shedS()));
        }
        return sb.toString();
    }

    public static String html(Data d) {
        Row ai = d.rows().get(d.rows().size() - 1);
        StringBuilder h = new StringBuilder(32768);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>EcoAI Grid run report</title><style>").append(CSS).append("</style></head><body><main>");
        h.append("<header><h1>EcoAI Grid: run report</h1><p class=\"sub\">").append(esc(d.title())).append("</p>")
         .append("<p class=\"meta\">Generated ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")))
         .append(" &middot; mode ").append(esc(d.mode())).append(" &middot; simulated time ").append(clock(d.simSeconds()))
         .append(" &middot; workload ").append(esc(d.profile())).append("</p></header>");

        // ---- headline
        h.append("<section><h2>Result</h2>");
        Row thermo = d.rows().size() > 1 ? d.rows().get(1) : null;
        if (thermo != null && thermo.whCool() > 0.05 && d.simSeconds() > 300) {
            double dE = 100 * (ai.whCool() / thermo.whCool() - 1);
            h.append("<p class=\"lead\">The AI controller used <b>").append(f("%.0f%%", Math.abs(dE))).append(dE <= 0 ? " less" : " more")
             .append("</b> cooling energy than the thermostat and recorded <b>").append(f("%.0f", ai.violS()))
             .append(" rack-seconds</b> above the ").append(f("%.0f", Config.SLA)).append(" &deg;C limit (thermostat: ")
             .append(f("%.0f", thermo.violS())).append(").</p>");
        } else if (thermo == null) {
            h.append("<p class=\"lead\">The AI controller used <b>").append(f("%.2f Wh", ai.whCool())).append("</b> of cooling energy and recorded <b>")
             .append(f("%.0f rack-seconds", ai.violS())).append("</b> above the ").append(f("%.0f", Config.SLA)).append(" &deg;C limit.</p>");
        } else {
            h.append("<p class=\"lead\">Run too short for a meaningful comparison yet (need a few simulated minutes).</p>");
        }
        h.append("<p class=\"note\">").append(d.groundTruth()
                ? "Totals come from the simulated rig's own meters (ground truth)."
                : "Totals are the backend's estimates from telemetry (no ground-truth meters in live mode).")
         .append("</p>");

        h.append("<table><thead><tr><th>Controller</th><th>Cooling Wh</th><th>Fan Wh</th><th>Pump Wh</th><th>Water L</th><th>Avg &deg;C</th><th>Max &deg;C</th><th>Over limit (rack-s)</th><th>Load shed (s)</th></tr></thead><tbody>");
        for (Row r : d.rows()) {
            h.append(r == ai ? "<tr class=\"ai\">" : "<tr>")
             .append("<th>").append(esc(r.name())).append("</th>")
             .append(f("<td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.3f</td><td>%s</td><td>%s</td><td>%.0f</td><td>%.0f</td></tr>",
                     r.whCool(), r.whFan(), r.whPump(), r.water(), nz(r.avgT()), nz(r.maxT()), r.violS(), r.shedS()));
        }
        h.append("</tbody></table>");
        if (d.rows().size() > 1 && d.simSeconds() > 300) {
            h.append("<h3>AI compared with each baseline</h3><table><thead><tr><th>Baseline</th><th>Cooling energy</th><th>Water</th><th>Baseline over-limit rack-s</th><th>AI over-limit rack-s</th></tr></thead><tbody>");
            for (int i = 0; i < d.rows().size() - 1; i++) {
                Row b = d.rows().get(i);
                String en = b.whCool() > 0.01 ? f("%+.0f%%", 100 * (ai.whCool() / b.whCool() - 1)) : "n/a";
                String wa = b.water() > 1e-6 ? f("%+.0f%%", 100 * (ai.water() / b.water() - 1)) : "n/a";
                h.append("<tr><th>").append(esc(b.name())).append("</th><td>").append(en).append("</td><td>").append(wa)
                 .append(f("</td><td>%.0f</td><td>%.0f</td></tr>", b.violS(), ai.violS()));
            }
            h.append("</tbody></table><p class=\"note\">Negative energy means the AI used less. Water is a trade-off: the optimiser uses the liquid loop because it is cheap in electricity; the water-priority setting moves this.</p>");
        }
        h.append("</section>");

        // ---- charts
        List<double[]> hist = d.ai().history();
        h.append("<section><h2>Temperatures (last ").append(hist.isEmpty() ? 0 : (int) Math.round((hist.get(hist.size() - 1)[0] - hist.get(0)[0]) / 60))
         .append(" simulated minutes, AI controller)</h2>");
        h.append(tempChart(hist));
        h.append("<h2>Cooling power by controller</h2>").append(powerChart(d.rows())).append("</section>");

        // ---- model + forecast
        h.append("<section><h2>Thermal model and 30 s forecast</h2><table><thead><tr><th>Rack</th><th>Model error (K)</th><th>Samples learned</th>")
         .append("<th>Forecast error, all (K)</th><th>Forecast bias (K)</th><th>Forecast error while temperature moves (K)</th><th>&ldquo;Stays the same&rdquo; error while moving (K)</th></tr></thead><tbody>");
        for (int i = 0; i < Config.RACKS; i++) {
            double[] s = d.ai().fcStats(i);
            h.append(f("<tr><th>Rack %d</th><td>%.2f</td><td>%d</td><td>%.2f</td><td>%+.2f</td><td>%.2f</td><td>%.2f</td></tr>",
                    i, d.ai().modelErr(i), d.ai().learned(i), s[0], s[2], s[4], s[5]));
        }
        h.append("</tbody></table><p class=\"note\">Each forecast is stored and scored against the temperature that actually arrived 30 s later. ")
         .append("While the controller holds a steady temperature a &ldquo;stays the same&rdquo; guess is already very accurate, so the model is judged ")
         .append("while the temperature is changing, where it is the better predictor. The small positive bias is deliberate: the optimiser plans for slightly more load than measured.</p></section>");

        // ---- events
        h.append("<section><h2>Safety and fault events</h2>");
        List<EventLog.Event> ev = d.ai().events.latest(60);
        if (ev.isEmpty()) h.append("<p class=\"note\">No events recorded.</p>");
        else {
            h.append("<table class=\"ev\"><thead><tr><th>Time</th><th>Level</th><th>Event</th></tr></thead><tbody>");
            for (EventLog.Event e : ev) {
                h.append("<tr class=\"").append(e.sev().name().toLowerCase(Locale.ROOT)).append("\"><td>").append(clock(e.t()))
                 .append("</td><td>").append(e.sev().name().toLowerCase(Locale.ROOT)).append("</td><td>").append(esc(e.text())).append("</td></tr>");
            }
            h.append("</tbody></table><p class=\"note\">Showing the most recent ").append(ev.size()).append(" of ").append(d.ai().events.total()).append(" events.</p>");
        }
        h.append("</section>");

        // ---- settings + honesty
        h.append("<section><h2>Settings</h2><table class=\"ev\"><tbody>")
         .append(f("<tr><th>Racks</th><td>%d</td></tr><tr><th>Service limit</th><td>%.0f &deg;C</td></tr><tr><th>AI target ceiling</th><td>%.1f &deg;C</td></tr>",
                 Config.RACKS, Config.SLA, Config.setpoint))
         .append(f("<tr><th>Water priority</th><td>%.0f</td></tr><tr><th>Forecast horizon</th><td>%d s</td></tr>", Config.waterWeight, Config.HORIZON))
         .append(f("<tr><th>Node trip / thermal fuse / watchdog</th><td>%.0f &deg;C / %.0f &deg;C / %.0f s</td></tr>", Config.TRIP, Config.FUSE_ON, Config.WATCHDOG_S))
         .append("</tbody></table></section>");
        h.append("<section><h2>How to read these numbers</h2><ul>")
         .append("<li>The hardware is simulated. The simulator shares the controller's model structure, so accuracy here is optimistic until the real rig is logged.</li>")
         .append("<li>Percentages describe this scale model only. They are not claims about full-size data centres.</li>")
         .append("<li>Short runs and runs right after injected faults can look worse than the long-run averages in the project README.</li></ul></section>");
        h.append("</main></body></html>");
        return h.toString();
    }

    // ------------------------------------------------------------------ SVG charts
    private static final String[] RACK_COL = {"#0e7c86", "#2f6fdd", "#c9731a"};
    private static final String[] CTRL_COL = {"#8a94a0", "#b2564b", "#c9731a", "#0e7c86"};

    /** Spreads end-of-line labels so they never overlap: sorted by y, at least gap px apart. */
    private static double[] spread(double[] ys, double gap) {
        Integer[] idx = new Integer[ys.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(ys[a], ys[b]));
        double[] out = ys.clone();
        for (int k = 1; k < idx.length; k++) if (out[idx[k]] - out[idx[k - 1]] < gap) out[idx[k]] = out[idx[k - 1]] + gap;
        return out;
    }

    private static String tempChart(List<double[]> hist) {
        if (hist.size() < 2) return "<p class=\"note\">Not enough history yet.</p>";
        double w = 760, hh = 260, l = 44, r = 70, t = 10, b = 26;
        double t0 = hist.get(0)[0], t1 = hist.get(hist.size() - 1)[0];
        double dmin = 1e9;
        for (double[] row : hist) for (int k = 0; k < 3; k++) if (!Double.isNaN(row[1 + k])) dmin = Math.min(dmin, row[1 + k]);
        double lo = Math.max(20, Math.floor(Math.min(dmin, Config.setpoint) - 1)), hi = Math.ceil(Config.SLA + 1);
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "<svg viewBox=\"0 0 %.0f %.0f\" role=\"img\" aria-label=\"Rack temperatures over time\">", w, hh));
        int step = (hi - lo) > 10 ? 4 : 2;
        for (double g = Math.ceil(lo / step) * step; g <= hi; g += step) {
            double y = t + (hh - t - b) * (1 - (g - lo) / (hi - lo));
            s.append(String.format(Locale.ROOT, "<line x1=\"%.0f\" x2=\"%.0f\" y1=\"%.1f\" y2=\"%.1f\" class=\"grid\"/><text x=\"%.0f\" y=\"%.1f\" class=\"tick\" text-anchor=\"end\">%.0f</text>", l, w - r, y, y, l - 6, y + 4, g));
        }
        for (double[] ref : new double[][]{{Config.SLA, 1}, {Config.setpoint, 0}}) {
            double y = t + (hh - t - b) * (1 - (ref[0] - lo) / (hi - lo));
            String c = ref[1] == 1 ? "limit" : "target";
            s.append(String.format(Locale.ROOT, "<line x1=\"%.0f\" x2=\"%.0f\" y1=\"%.1f\" y2=\"%.1f\" class=\"%s\"/><text x=\"%.0f\" y=\"%.1f\" class=\"lbl %s\">%s %.0f&deg;C</text>",
                    l, w - r, y, y, c, l + 6, y - 5, c, ref[1] == 1 ? "limit" : "AI target", ref[0]));
        }
        double[] endY = new double[3];
        for (int k = 0; k < 3; k++) {
            StringBuilder p = new StringBuilder();
            for (double[] row : hist) {
                if (Double.isNaN(row[1 + k])) continue;
                double x = l + (w - l - r) * (row[0] - t0) / Math.max(1, t1 - t0);
                double y = t + (hh - t - b) * (1 - (Math.max(lo, Math.min(hi, row[1 + k])) - lo) / (hi - lo));
                p.append(p.length() == 0 ? "M" : "L").append(String.format(Locale.ROOT, "%.1f %.1f", x, y));
                endY[k] = y;
            }
            if (p.length() > 0) s.append("<path d=\"").append(p).append("\" fill=\"none\" stroke=\"").append(RACK_COL[k]).append("\" stroke-width=\"1.4\"/>");
        }
        double[] ly = spread(endY, 13);
        for (int k = 0; k < 3; k++)
            s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"%.1f\" fill=\"%s\" class=\"lbl\">Rack %d</text>", w - r + 6, ly[k] + 4, RACK_COL[k], k));
        s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"%.0f\" class=\"tick\">%s</text><text x=\"%.0f\" y=\"%.0f\" class=\"tick\" text-anchor=\"end\">%s</text>",
                l, hh - 6, clock(t0), w - r, hh - 6, clock(t1)));
        return s.append("</svg>").toString();
    }

    private static String powerChart(List<Row> rows) {
        double w = 760, hh = 220, l = 44, r = 90, t = 10, b = 26;
        double t0 = Double.MAX_VALUE, t1 = 0, mx = 1;
        for (Row row : rows) for (double[] p : row.backend().history()) { t0 = Math.min(t0, p[0]); t1 = Math.max(t1, p[0]); mx = Math.max(mx, p[4]); }
        if (t1 <= t0) return "<p class=\"note\">Not enough history yet.</p>";
        mx = Math.ceil(mx / 5) * 5;
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "<svg viewBox=\"0 0 %.0f %.0f\" role=\"img\" aria-label=\"Cooling power per controller\">", w, hh));
        for (int g = 0; g <= 4; g++) {
            double v = mx * g / 4, y = t + (hh - t - b) * (1 - g / 4.0);
            s.append(String.format(Locale.ROOT, "<line x1=\"%.0f\" x2=\"%.0f\" y1=\"%.1f\" y2=\"%.1f\" class=\"grid\"/><text x=\"%.0f\" y=\"%.1f\" class=\"tick\" text-anchor=\"end\">%.0f W</text>", l, w - r, y, y, l - 6, y + 4, v));
        }
        int k = 0;
        double[] endY = new double[rows.size()];
        for (Row row : rows) {
            for (double[] pt : row.backend().history()) endY[k] = t + (hh - t - b) * (1 - pt[4] / mx);
            k++;
        }
        double[] ly = spread(endY, 13);
        k = 0;
        for (Row row : rows) {
            StringBuilder p = new StringBuilder();
            double lastY = ly[k] - 4;
            for (double[] pt : row.backend().history()) {
                double x = l + (w - l - r) * (pt[0] - t0) / (t1 - t0), y = t + (hh - t - b) * (1 - pt[4] / mx);
                p.append(p.length() == 0 ? "M" : "L").append(String.format(Locale.ROOT, "%.1f %.1f", x, y));
            }
            String col = CTRL_COL[Math.min(k, CTRL_COL.length - 1)];
            boolean aiRow = k == rows.size() - 1;
            if (p.length() > 0) s.append("<path d=\"").append(p).append("\" fill=\"none\" stroke=\"").append(col).append("\" stroke-width=\"")
                    .append(aiRow ? "2.2" : "1.3").append("\"/><text x=\"").append(String.format(Locale.ROOT, "%.0f\" y=\"%.1f", w - r + 6, lastY + 4))
                    .append("\" fill=\"").append(col).append("\" class=\"lbl\">").append(esc(row.name())).append("</text>");
            k++;
        }
        s.append(String.format(Locale.ROOT, "<text x=\"%.0f\" y=\"%.0f\" class=\"tick\">%s</text><text x=\"%.0f\" y=\"%.0f\" class=\"tick\" text-anchor=\"end\">%s</text>",
                l, hh - 6, clock(t0), w - r, hh - 6, clock(t1)));
        return s.append("</svg>").toString();
    }

    private static final String CSS = """
        :root{color-scheme:light}*{box-sizing:border-box}
        body{margin:0;background:#fff;color:#16222b;font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        main{max-width:860px;margin:0 auto;padding:32px 20px 60px}
        h1{font-size:28px;margin:0 0 4px;letter-spacing:-.01em}h2{font-size:18px;margin:30px 0 10px}h3{font-size:15px;margin:22px 0 8px}
        .sub{margin:0;font-size:16px;color:#34444f}.meta{color:#5b6b76;font-size:13px;margin:6px 0 0}
        .lead{font-size:17px;margin:0 0 6px}.note{color:#5b6b76;font-size:13px;margin:8px 0 0}
        table{border-collapse:collapse;width:100%;font-size:13.5px;margin:10px 0}
        th,td{border-bottom:1px solid #dde4e8;padding:6px 8px;text-align:right}th:first-child,td:first-child{text-align:left;white-space:nowrap}
        thead th{color:#46575f;font-weight:600;border-bottom:2px solid #c3ced4;vertical-align:bottom}tbody th{text-align:left;font-weight:600}
        tr.ai{background:#e8f4f5}tr.crit td{color:#9b2c2c}tr.warn td{color:#8a5a12}table.ev td{text-align:left}
        svg{width:100%;height:auto;display:block}.grid{stroke:#e3e9ec;stroke-width:1}.tick{font-size:10.5px;fill:#5b6b76}
        .lbl{font-size:11px;font-weight:600}.limit{stroke:#b2564b;stroke-dasharray:5 4;fill:#b2564b}.target{stroke:#0e7c86;stroke-dasharray:2 4;fill:#0e7c86}
        text.limit,text.target{stroke:none}
        ul{padding-left:20px}
        @media print{main{padding:0}section{break-inside:avoid}}
        """;
}
