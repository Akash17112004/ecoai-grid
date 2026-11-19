package ecoai.app;

import ecoai.Config;
import ecoai.bus.MqttBus;
import ecoai.control.AiPolicy;
import ecoai.control.Backend;
import ecoai.util.Json;
import ecoai.web.DashboardServer;
import ecoai.web.StateJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * Real deployment engine: the AI backend on an MQTT broker. Telemetry can come from the hardware simulator or from
 * real ESP32 nodes; the backend cannot tell the difference. Used by the plain-JDK server and by Spring Boot.
 */
public final class LiveEngine implements DashboardServer.Api, AutoCloseable {
    private final MqttBus bus;
    private final Backend backend;
    private final DoubleSupplier clock;
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ecoai-live-tick"); t.setDaemon(true); return t; });
    private final Recorder rec;

    public LiveEngine(String host, int mqttPort, Path csv, Path recordDir) throws IOException {
        bus = new MqttBus(host, mqttPort, "ecoai-backend-" + (System.nanoTime() & 0xffff));
        long t0 = System.nanoTime();
        clock = () -> (System.nanoTime() - t0) / 1e9;
        backend = new Backend("AI", bus, new AiPolicy(), clock);
        if (csv != null) backend.openCsv(csv);
        rec = recordDir == null ? null : new Recorder(recordDir, "live");
        bus.subscribe("ecoai/rack0/+", (topic, payload) -> {
            synchronized (log) {
                log.addLast(String.format(Locale.ROOT, "[%6.0fs] %s %s", clock.getAsDouble(), topic, payload));
                while (log.size() > 8) log.removeFirst();
            }
        });
        tick.scheduleAtFixedRate(() -> {
            try {
                double now = clock.getAsDouble();
                backend.tick(now);
                if (rec != null && rec.due(now)) rec.write(now, state());
            } catch (Throwable t) { t.printStackTrace(); }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override public String state() {
        StringBuilder extra = new StringBuilder();
        extra.append(",\"sent\":").append(bus.sent()).append(",\"dropped\":").append(bus.dropped())
             .append(",\"received\":").append(bus.received()).append(",\"log\":[");
        synchronized (log) {
            boolean first = true;
            for (String l : log) { if (!first) extra.append(','); first = false; extra.append(Json.quote(l)); }
        }
        extra.append(']');
        return StateJson.build("live", clock.getAsDouble(), 1, false, bus.isConnected() ? "up" : "down", true,
                List.of(new StateJson.View(backend, extra.toString(), null)));
    }

    @Override public void control(Map<String, String> q) {
        String cmd = q.getOrDefault("cmd", "");
        double val = DashboardServer.num(q.get("val"), 0);
        switch (cmd) {
            case "setpoint" -> Config.setpoint = Math.max(34, Math.min(39, val));
            case "waterweight" -> Config.waterWeight = Math.max(0, Math.min(60, val));
            case "speed", "pause", "reset" -> { }
            case "profile" -> bus.publish(Config.SIM_CONTROL, "{\"cmd\":\"profile\",\"name\":" + Json.quote(q.getOrDefault("name", "")) + "}");
            default -> bus.publish(Config.SIM_CONTROL, "{\"cmd\":" + Json.quote(cmd)
                    + ",\"rack\":" + (int) DashboardServer.num(q.get("rack"), 0) + "}");
        }
    }

    @Override public String events() {
        StringBuilder sb = new StringBuilder();
        backend.events.json(sb, 200);
        return sb.toString();
    }

    @Override public String report(String format) {
        var row = new ReportBuilder.Row("AI", backend, backend.whCool(), backend.whFan(), backend.whPump(), backend.water(),
                Double.NaN, backend.maxT(), backend.violS(), 0);
        var d = new ReportBuilder.Data("Live run on the MQTT broker (estimates from telemetry)", "live", clock.getAsDouble(),
                "real or simulated hardware", false, List.of(row), backend);
        return format.equals("csv") ? ReportBuilder.csv(d) : ReportBuilder.html(d);
    }

    @Override public String health() {
        return "{\"status\":\"" + (bus.isConnected() ? "UP" : "DEGRADED") + "\",\"mode\":\"live\",\"broker\":\""
                + (bus.isConnected() ? "up" : "down") + "\"}";
    }

    @Override public String runs() { return "[]"; }

    public boolean brokerUp() { return bus.isConnected(); }

    @Override public void close() {
        tick.shutdownNow();
        backend.closeCsv();
        if (rec != null) rec.close();
    }
}
