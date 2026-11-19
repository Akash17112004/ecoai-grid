package ecoai.app;

import ecoai.Config;
import ecoai.sim.Workload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluation report for the project write-up: several seeds and every workload preset, same faults for all
 * controllers, rendered as one printable HTML page. Everything here is regenerated from the code, never typed in.
 */
public final class BatchReport {
    private BatchReport() {}

    private record Result(String label, List<Scenario> scs) {}

    private static List<Scenario> run(long seed, double hours, boolean faults, Workload.Profile p) {
        List<Scenario> scs = DemoApp.scenariosForBatch(seed);
        for (Scenario s : scs) s.rig.setProfile(p.id());
        int n = (int) (hours * 3600);
        for (int k = 1; k <= n; k++) {
            if (faults) HeadlessRun.inject(scs, k);
            for (Scenario s : scs) s.step();
        }
        return scs;
    }

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }
    private static String pct(double ai, double base) { return base > 1e-9 ? f("%+.0f%%", 100 * (ai / base - 1)) : "n/a"; }

    public static void write(Path out, int seeds, double hours) throws IOException {
        System.out.println("Running " + seeds + " seeds and " + Workload.Profile.values().length + " workload presets, " + hours + " h each ...");
        List<Result> bySeed = new ArrayList<>();
        for (int s = 1; s <= seeds; s++) { bySeed.add(new Result("seed " + s, run(s, hours, true, Workload.Profile.MIXED))); System.out.print("."); }
        List<Result> byProfile = new ArrayList<>();
        for (Workload.Profile p : Workload.Profile.values()) { byProfile.add(new Result(p.label, run(1, hours, true, p))); System.out.print("."); }
        System.out.println();

        StringBuilder h = new StringBuilder(16384);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>EcoAI Grid evaluation</title><style>").append(CSS).append("</style></head><body><main>")
         .append("<h1>EcoAI Grid: evaluation</h1><p class=\"meta\">Generated ").append(LocalDate.now())
         .append(" by <code>java -jar ecoai-grid.jar report</code> &middot; ").append(f("%.1f", hours)).append(" simulated hours per run &middot; ")
         .append(Config.RACKS).append(" racks &middot; limit ").append(f("%.0f", Config.SLA)).append(" &deg;C &middot; AI target ")
         .append(f("%.0f", Config.setpoint)).append(" &deg;C &middot; water priority ").append(f("%.0f", Config.waterWeight)).append("</p>");

        h.append("<h2>1. Repeatability across random seeds</h2><p class=\"note\">Mixed workload, the same fault schedule for every controller (fan failure, frozen sensor, unplugged sensor, load bursts, network outage). Energy is the AI controller's cooling energy relative to each baseline; negative means less.</p>");
        h.append(table(bySeed));
        double[] sums = new double[3];
        int breachRuns = 0, runs = 0;
        for (Result r : bySeed) {
            Scenario ai = r.scs().get(3);
            for (int b = 0; b < 3; b++) sums[b] += 100 * (ai.rig.whCool() / r.scs().get(b).rig.whCool() - 1);
            if (ai.rig.violS > 0) breachRuns++;
            runs++;
        }
        h.append("<p class=\"lead\">Mean cooling energy of the AI: <b>").append(f("%+.0f%%", sums[0] / runs)).append("</b> vs fixed speed, <b>")
         .append(f("%+.0f%%", sums[1] / runs)).append("</b> vs thermostat, <b>").append(f("%+.0f%%", sums[2] / runs))
         .append("</b> vs raised thermostat. AI runs with any time above the limit: <b>").append(breachRuns).append(" of ").append(runs).append("</b>.</p>");

        h.append("<h2>2. Workload presets</h2><p class=\"note\">Seed 1, same fault schedule. Heavy loads leave less to save: when the racks are busy the AI can use more energy than the thermostat while keeping them under the limit.</p>");
        h.append(table(byProfile));

        h.append("<h2>3. Forecast accuracy (AI, mixed workload, all seeds)</h2><table><thead><tr><th>Run</th><th>Forecast error, all (K)</th><th>Bias (K)</th><th>Forecast error while temperature moves (K)</th><th>&ldquo;Stays the same&rdquo; error while moving (K)</th></tr></thead><tbody>");
        for (Result r : bySeed) {
            double a = 0, bias = 0, m = 0, n = 0;
            for (int i = 0; i < Config.RACKS; i++) {
                double[] fs = r.scs().get(3).backend.fcStats(i);
                a += fs[0]; bias += fs[2]; m += fs[4]; n += fs[5];
            }
            h.append("<tr><th>").append(r.label()).append("</th>").append(f("<td>%.2f</td><td>%+.2f</td><td>%.2f</td><td>%.2f</td></tr>",
                    a / Config.RACKS, bias / Config.RACKS, m / Config.RACKS, n / Config.RACKS));
        }
        h.append("</tbody></table><p class=\"note\">While the temperature is held steady a &ldquo;stays the same&rdquo; guess is already accurate, so the learned model is judged while the temperature moves.</p>");

        h.append("<h2>Limits of this evaluation</h2><ul><li>All hardware is simulated; the simulator shares the controller's model structure, so the forecast accuracy is optimistic until the real rig is logged.</li>")
         .append("<li>Results describe this scale model and these fault schedules only.</li>")
         .append("<li>Water is a trade-off, not a saving: the optimiser prefers the liquid loop because it is cheap in electricity.</li></ul></main></body></html>");
        Files.writeString(out, h.toString());
        System.out.println("wrote " + out.toAbsolutePath());
    }

    private static String table(List<Result> results) {
        StringBuilder h = new StringBuilder("<table><thead><tr><th>Run</th><th>Fixed speed Wh</th><th>Thermostat Wh</th><th>Raised thermostat Wh</th><th>AI Wh</th>")
                .append("<th>AI vs fixed</th><th>AI vs thermostat</th><th>AI vs raised</th><th>AI water vs thermostat</th>")
                .append("<th>Over-limit rack-s: fixed / thermo / raised / AI</th><th>AI max &deg;C</th></tr></thead><tbody>");
        for (Result r : results) {
            List<Scenario> s = r.scs();
            Scenario ai = s.get(3);
            h.append("<tr><th>").append(r.label()).append("</th>")
             .append(f("<td>%.1f</td><td>%.1f</td><td>%.1f</td><td>%.1f</td>", s.get(0).rig.whCool(), s.get(1).rig.whCool(), s.get(2).rig.whCool(), ai.rig.whCool()))
             .append("<td>").append(pct(ai.rig.whCool(), s.get(0).rig.whCool())).append("</td><td>").append(pct(ai.rig.whCool(), s.get(1).rig.whCool()))
             .append("</td><td>").append(pct(ai.rig.whCool(), s.get(2).rig.whCool())).append("</td><td>").append(pct(ai.rig.water, s.get(1).rig.water))
             .append(f("</td><td>%.0f / %.0f / %.0f / <b>%.0f</b></td><td>%.1f</td></tr>", s.get(0).rig.violS, s.get(1).rig.violS, s.get(2).rig.violS, ai.rig.violS, ai.rig.maxT));
        }
        return h.append("</tbody></table>").toString();
    }

    private static final String CSS = """
        :root{color-scheme:light}*{box-sizing:border-box}
        body{margin:0;background:#fff;color:#16222b;font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        main{max-width:1040px;margin:0 auto;padding:32px 20px 60px}
        h1{font-size:28px;margin:0 0 4px}h2{font-size:18px;margin:30px 0 6px}
        .meta,.note{color:#5b6b76;font-size:13px;margin:6px 0}.lead{font-size:16px;margin:10px 0}
        table{border-collapse:collapse;width:100%;font-size:12.5px;margin:10px 0}
        th,td{border-bottom:1px solid #dde4e8;padding:6px 7px;text-align:right}th:first-child{text-align:left}
        thead th{color:#46575f;font-weight:600;border-bottom:2px solid #c3ced4;vertical-align:bottom}tbody th{text-align:left;font-weight:600}
        code{background:#eef2f4;padding:1px 5px;border-radius:4px}ul{padding-left:20px}
        @media print{main{padding:0}table{break-inside:avoid}}
        """;
}
