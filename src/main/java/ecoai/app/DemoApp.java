package ecoai.app;

import ecoai.Config;
import ecoai.control.AiPolicy;
import ecoai.control.StaticPolicy;
import ecoai.control.ThermostatPolicy;
import ecoai.sim.Workload;
import ecoai.util.Json;
import ecoai.web.DashboardServer;
import ecoai.web.StateJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * All-in-one demo: four identical simulated rigs run the same workload under four controllers, side by side.
 * Implements the dashboard API, so the plain-JDK server and the Spring Boot controllers drive exactly the same engine.
 */
public final class DemoApp implements DashboardServer.Api, AutoCloseable {
    private final Object lock = new Object();
    private final long seed;
    private final Path csv, recordDir;
    private List<Scenario> scs;
    private Recorder recorder;
    private volatile double speed;
    private volatile boolean paused;
    private ScheduledExecutorService loop;
    private DashboardServer web;

    public DemoApp(long seed, double speed, Path csv) { this(seed, speed, csv, null); }

    public DemoApp(long seed, double speed, Path csv, Path recordDir) {
        this.seed = seed; this.speed = speed; this.csv = csv; this.recordDir = recordDir;
        scs = newScenarios();
        startRecording();
    }

    private List<Scenario> newScenarios() {
        List<Scenario> l = new ArrayList<>(List.of(
                new Scenario("STATIC", new StaticPolicy(), seed),
                new Scenario("THERMOSTAT", new ThermostatPolicy(0), seed),
                new Scenario("THERMOSTAT+", new ThermostatPolicy(2.0), seed),
                new Scenario("AI", new AiPolicy(), seed)));
        if (csv != null) {
            try { l.get(3).backend.openCsv(csv); } catch (IOException e) { System.err.println("csv: " + e.getMessage()); }
        }
        return l;
    }

    private void startRecording() {
        if (recordDir == null) return;
        try { recorder = new Recorder(recordDir, "demo"); }
        catch (IOException e) { System.err.println("record: " + e.getMessage()); recorder = null; }
    }

    private void stopRecording() { if (recorder != null) { recorder.close(); recorder = null; } }

    public static List<Scenario> scenariosForBatch(long seed) {
        return new DemoApp(seed, 1, null).scs;
    }

    // ------------------------------------------------------------------ dashboard API
    private String stateLocked() {
        List<StateJson.View> views = new ArrayList<>();
        for (Scenario s : scs) views.add(new StateJson.View(s.backend, s.topExtra(), s::rackExtra));
        return StateJson.build("demo", scs.get(3).rig.t, speed, paused, null, true, views);
    }

    @Override public String state() { synchronized (lock) { return stateLocked(); } }

    @Override public void control(Map<String, String> q) { synchronized (lock) { handle(q); } }

    @Override public String events() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            scs.get(3).backend.events.json(sb, 200);
            return sb.toString();
        }
    }

    @Override public String report(String format) {
        synchronized (lock) {
            List<ReportBuilder.Row> rows = new ArrayList<>();
            for (Scenario s : scs) {
                var r = s.rig;
                rows.add(new ReportBuilder.Row(s.name, s.backend, r.whCool(), r.whFan, r.whPump, r.water,
                        r.steps == 0 ? Double.NaN : r.sumT / (r.steps * Config.RACKS), r.maxT, r.violS, r.shedS));
            }
            Scenario ai = scs.get(3);
            var d = new ReportBuilder.Data("Side-by-side demo: four controllers, identical workload and faults", "demo", ai.rig.t,
                    ai.rig.profile().label, true, rows, ai.backend);
            return format.equals("csv") ? ReportBuilder.csv(d) : ReportBuilder.html(d);
        }
    }

    @Override public String runs() {
        StringBuilder sb = new StringBuilder("[");
        if (recordDir != null && Files.isDirectory(recordDir)) {
            try (Stream<Path> st = Files.list(recordDir)) {
                boolean first = true;
                for (Path p : st.filter(x -> x.getFileName().toString().endsWith(".jsonl.gz")).sorted().toList()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"name\":").append(Json.quote(p.getFileName().toString())).append(",\"bytes\":").append(Files.size(p)).append('}');
                }
            } catch (IOException e) { /* partial list is fine */ }
        }
        return sb.append(']').toString();
    }

    @Override public String health() {
        synchronized (lock) {
            return "{\"status\":\"UP\",\"mode\":\"demo\",\"simSeconds\":" + Json.fmt(scs.get(3).rig.t) + ",\"paused\":" + paused + "}";
        }
    }

    // ------------------------------------------------------------------ lifecycle
    /** Starts the simulation loop (without any HTTP server); used by both the plain-JDK and the Spring Boot front ends. */
    public void start() {
        loop = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ecoai-sim"); t.setDaemon(true); return t; });
        long[] last = {System.nanoTime()};
        double[] acc = {0};
        loop.scheduleAtFixedRate(() -> {
            try {
                synchronized (lock) {
                    long n = System.nanoTime();
                    double dr = (n - last[0]) / 1e9;
                    last[0] = n;
                    if (paused) return;
                    acc[0] += speed * Math.min(dr, 0.25);
                    for (int steps = 0; acc[0] >= 1 && steps < 2000; steps++, acc[0] -= 1) {
                        for (Scenario s : scs) s.step();
                        if (recorder != null && recorder.due(scs.get(3).rig.t)) recorder.write(scs.get(3).rig.t, stateLocked());
                    }
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void run(int port, boolean lan) throws IOException { run(port, lan, null); }

    public void run(int port, boolean lan, String token) throws IOException {
        web = new DashboardServer(port, lan, token, this);
        web.start();
        start();
        System.out.println("EcoAI Grid DEMO running at http://localhost:" + port + (lan ? "  (LAN access on)" : "")
                + (token != null ? "  (token required)" : "")
                + (recorder != null ? "  recording to " + recorder.file() : "") + "  - Ctrl+C to stop");
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    @Override public void close() {
        if (loop != null) loop.shutdownNow();
        synchronized (lock) { stopRecording(); for (Scenario s : scs) s.backend.closeCsv(); }
        if (web != null) web.stop();
    }

    private void handle(Map<String, String> q) {
        String cmd = q.getOrDefault("cmd", "");
        int rack = (int) DashboardServer.num(q.get("rack"), 0);
        double val = DashboardServer.num(q.get("val"), 0);
        switch (cmd) {
            case "speed" -> speed = Math.max(1, Math.min(1000, val));
            case "pause" -> paused = !paused;
            case "reset" -> {
                for (Scenario s : scs) s.backend.closeCsv();
                Workload.Profile keep = scs.get(3).rig.profile();
                stopRecording();
                scs = newScenarios();
                for (Scenario s : scs) s.rig.setProfile(keep.id());
                startRecording();
            }
            case "setpoint" -> Config.setpoint = Math.max(34, Math.min(39, val));
            case "profile" -> { for (Scenario s : scs) s.rig.setProfile(q.get("name")); }
            case "waterweight" -> Config.waterWeight = Math.max(0, Math.min(60, val));
            default -> { for (Scenario s : scs) s.rig.fault(cmd, rack); }
        }
    }
}
