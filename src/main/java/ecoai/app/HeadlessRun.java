package ecoai.app;

import ecoai.Config;

import java.util.List;
import java.util.Locale;

/** Batch evaluation (no UI): same workload + same fault schedule through every controller, then a results table. */
public final class HeadlessRun {
    private HeadlessRun() {}

    static void inject(List<Scenario> scs, int k) {
        String cmd = null; int rack = 0;
        switch (k) {
            case 1800 -> cmd = "burstall";                      // synchronized load spike on all racks
            case 3600 -> { cmd = "fanfail"; rack = 1; }         // fan 1 dies
            case 4200 -> { cmd = "fanfix"; rack = 1; }
            case 7500 -> { cmd = "stuck"; rack = 2; }           // rack 2 temp sensor freezes
            case 7800 -> { cmd = "unstuck"; rack = 2; }
            case 7900 -> { cmd = "burst"; rack = 2; }           // burst right after the sensor recovers
            case 9000 -> { cmd = "unplug"; rack = 0; }          // rack 0 sensor cable pulled
            case 9200 -> { cmd = "replug"; rack = 0; }
            case 10800 -> cmd = "partition";                    // WiFi / broker outage (all nodes)
            case 10890 -> cmd = "heal";
            default -> { }
        }
        if (cmd != null) for (Scenario s : scs) s.rig.fault(cmd, rack);
    }

    public static List<Scenario> run(long seed, double hours, boolean faults) {
        return run(seed, hours, faults, ecoai.sim.Workload.Profile.MIXED);
    }

    public static List<Scenario> run(long seed, double hours, boolean faults, ecoai.sim.Workload.Profile profile) {
        List<Scenario> scs = DemoApp.scenariosForBatch(seed);
        for (Scenario s : scs) s.rig.setProfile(profile.id());
        int n = (int) (hours * 3600);
        for (int k = 1; k <= n; k++) {
            if (faults) inject(scs, k);
            for (Scenario s : scs) s.step();
        }
        return scs;
    }

    public static void print(List<Scenario> scs, long seed, double hours, boolean faults) {
        System.out.printf(Locale.ROOT, "%nEcoAI Grid batch run: %.1f h simulated, %d racks, seed %d, fault schedule %s%n%n",
                hours, Config.RACKS, seed, faults ? "ON" : "off");
        System.out.printf(Locale.ROOT, "%-12s %10s %8s %8s %8s %7s %8s %9s %8s%n",
                "controller", "cooling Wh", "fan Wh", "pump Wh", "water L", "avg C", "max C", "SLA-s", "shed-s");
        for (Scenario s : scs) {
            var r = s.rig;
            System.out.printf(Locale.ROOT, "%-12s %10.2f %8.2f %8.2f %8.3f %7.1f %8.1f %9.0f %8.0f%n",
                    s.name, r.whCool(), r.whFan, r.whPump, r.water, r.sumT / (r.steps * Config.RACKS), r.maxT, r.violS, r.shedS);
        }
        Scenario ai = scs.get(scs.size() - 1);
        System.out.printf(Locale.ROOT, "%nAI cooling-energy saving / water change, relative to each baseline (IT load %.0f Wh):%n", ai.rig.whIT);
        for (int i = 0; i < scs.size() - 1; i++) {
            Scenario b = scs.get(i);
            double dw = b.rig.water > 1e-9 ? 100 * (ai.rig.water / b.rig.water - 1) : Double.NaN;
            System.out.printf(Locale.ROOT, "  vs %-12s energy %+.0f%%   water %+.0f%%   (SLA breach rack-s: baseline %.0f, AI %.0f)%n",
                    b.name, 100 * (ai.rig.whCool() / b.rig.whCool() - 1), dw, b.rig.violS, ai.rig.violS);
        }
        StringBuilder m = new StringBuilder("Learned thermal model, 30 s prediction error per rack (K): ");
        for (int i = 0; i < Config.RACKS; i++) m.append(String.format(Locale.ROOT, "%.2f ", ai.backend.modelErr(i)));
        System.out.println(m);
        System.out.println("Backend energy estimate vs rig meter (Wh):" + (faults
                ? "   [estimates miss the network-outage window: no telemetry arrives while the link is down]" : ""));
        for (Scenario s : scs) {
            System.out.printf(Locale.ROOT, "  %-12s estimate %.2f   meter %.2f   (%+.1f%%)%n",
                    s.name, s.backend.whCool(), s.rig.whCool(), 100 * (s.backend.whCool() / s.rig.whCool() - 1));
        }
        System.out.printf(Locale.ROOT, "MQTT (AI rig): %d msgs, %d dropped (%.1f%%)%n", ai.bus.sent, ai.bus.dropped,
                100.0 * ai.bus.dropped / ai.bus.sent);
    }

    /** Regression check for the team: returns true if the AI controller still behaves. */
    public static boolean selfTest(long seed) {
        List<Scenario> scs = run(seed, 3.2, true);
        Scenario ai = scs.get(scs.size() - 1), thermo = scs.get(1), stat = scs.get(0);
        boolean ok = true;
        ok &= check("AI never breaches the 40 C SLA (true temperature)", ai.rig.violS == 0);
        ok &= check("AI peak true temperature stays below 41 C", ai.rig.maxT < 41.0);
        ok &= check("independent thermal fuse never needed (<50 C)", ai.rig.maxT < Config.FUSE_ON);
        ok &= check("AI cooling energy < THERMOSTAT cooling energy", ai.rig.whCool() < thermo.rig.whCool());
        ok &= check("AI cooling energy < STATIC cooling energy", ai.rig.whCool() < stat.rig.whCool());
        ok &= check("learned model error < 0.6 K on every rack",
                ai.backend.modelErr(0) < 0.6 && ai.backend.modelErr(1) < 0.6 && ai.backend.modelErr(2) < 0.6);
        // estimator accuracy is checked on a fault-free run: during a network outage no telemetry arrives to integrate
        Scenario clean = run(seed, 1.0, false).get(3);
        ok &= check("backend energy estimate within 3 % of rig meter (no outage)",
                Math.abs(clean.backend.whCool() / clean.rig.whCool() - 1) < 0.03);
        ok &= check("backend water estimate within 5 % (+0.005 L) of rig meter (no outage)",
                Math.abs(clean.backend.water() - clean.rig.water) < 0.005 + 0.05 * clean.rig.water);
        ok &= selfTestFeatures(seed, scs);
        return ok;
    }

    /** Checks for the operator-facing features: events, forecast scorecard, presets, report, record/replay, token. */
    private static boolean selfTestFeatures(long seed, List<Scenario> faultRun) {
        boolean ok = true;
        var ev = faultRun.get(3).backend.events.latest(500);
        ok &= check("event log records the fan fault, the sensor fault and the network outage", has(ev, "fan fault") && has(ev, "sensor fault") && has(ev, "no data"));
        ok &= check("event log records the node fail-safe", has(ev, "fail-safe"));
        double fm = 0, nm = 0;
        for (int i = 0; i < Config.RACKS; i++) { double[] f = faultRun.get(3).backend.fcStats(i); fm += f[4]; nm += f[5]; }
        ok &= check("30 s forecast beats \"stays the same\" while the temperature is moving", fm < nm);
        boolean presets = true;
        for (ecoai.sim.Workload.Profile p : ecoai.sim.Workload.Profile.values()) {
            Scenario ai = run(seed, 1.2, true, p).get(3);
            presets &= ai.rig.violS == 0;
        }
        ok &= check("AI stays under the limit in every workload preset", presets);
        try {
            DemoApp demo = new DemoApp(seed, 1, null);
            String html = demo.report("html"), csv = demo.report("csv");
            ok &= check("report exports (HTML and CSV)", html.contains("run report") && csv.startsWith("controller,") && csv.lines().count() == 5);
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ecoai-selftest");
            try (Recorder rec = new Recorder(dir, "test")) { rec.write(10, demo.state()); rec.write(20, demo.state()); }
            ReplayApp replay = new ReplayApp(dir);
            String st = replay.state();
            ok &= check("recorded run replays through the dashboard API", st.contains("\"mode\":\"replay\"") && st.contains("\"n\":2"));
            try (var files = java.nio.file.Files.list(dir)) { files.forEach(f -> f.toFile().delete()); }
            dir.toFile().delete();
            ok &= check("access token is enforced", tokenWorks(demo));
        } catch (Exception e) {
            ok &= check("report / replay / token checks ran (" + e + ")", false);
        }
        return ok;
    }

    private static boolean has(List<ecoai.control.EventLog.Event> ev, String text) {
        for (var e : ev) if (e.text().contains(text)) return true;
        return false;
    }

    private static boolean tokenWorks(DemoApp api) throws Exception {
        int port;
        try (var s = new java.net.ServerSocket(0)) { port = s.getLocalPort(); }
        var web = new ecoai.web.DashboardServer(port, false, "s3cret", api);
        web.start();
        try {
            var c = java.net.http.HttpClient.newHttpClient();
            int a = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/state")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            int b = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/state?token=s3cret")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            return a == 401 && b == 200;
        } finally { web.stop(); }
    }

    private static boolean check(String what, boolean pass) {
        System.out.println((pass ? "  PASS  " : "  FAIL  ") + what);
        return pass;
    }
}
