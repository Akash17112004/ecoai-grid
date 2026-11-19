package ecoai.control;

import ecoai.Config;
import ecoai.bus.MessageBus;
import ecoai.model.Command;
import ecoai.model.Telemetry;
import ecoai.util.Json;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;

/**
 * The control service (the "Spring Boot role"): ingests telemetry from the bus, validates it, detects sensor / fan
 * faults, learns the thermal model, asks the Policy for actuator targets, publishes commands, and keeps KPIs,
 * history and an optional CSV dataset (the training data for the offline ML track).
 *
 * It uses only what arrives over the bus, so it behaves identically against the simulator and real ESP32 nodes.
 */
public final class Backend {
    public final String name;
    private final MessageBus bus;
    private final Policy policy;
    private final DoubleSupplier clock;
    private final RackState[] racks = new RackState[Config.RACKS];
    private final ArrayDeque<double[]> hist = new ArrayDeque<>();
    public final EventLog events = new EventLog(500);
    private PrintWriter csv;
    private long seq;
    private int tickN;

    // KPIs estimated from telemetry (duty -> power through the fan / pump laws)
    private double whFan, whPump, whIT, water, violS, maxT = Double.NaN, shedS;

    public Backend(String name, MessageBus bus, Policy policy, DoubleSupplier clock) {
        this.name = name; this.bus = bus; this.policy = policy; this.clock = clock;
        for (int i = 0; i < Config.RACKS; i++) racks[i] = new RackState();
        bus.subscribe(Config.TELEMETRY_FILTER, (topic, payload) -> onTelemetry(payload));
    }

    /** Append every telemetry sample + decision to a CSV (dataset for ML training / the report). */
    public synchronized void openCsv(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        csv = new PrintWriter(Files.newBufferedWriter(file));
        csv.println("clock_s,esp_t,rack,temp_c,supply_c,power_w,fan,pump,rpm,cmd_fan,cmd_pump,pred_c,model_err_k,flags");
    }

    public synchronized void closeCsv() { if (csv != null) { csv.close(); csv = null; } }

    /** Call about once per second: marks silent racks STALE and records chart history. */
    public synchronized void tick(double now) {
        for (int i = 0; i < Config.RACKS; i++) {
            racks[i].stale = (now - racks[i].lastSeen) > Config.STALE_S;
            watch(i, racks[i], now);
        }
        if (++tickN % 5 == 0) {
            hist.addLast(new double[]{now, racks[0].Tm, racks[1].Tm, racks[2].Tm, coolW(), itW(),
                    racks[0].predPast, racks[1].predPast, racks[2].predPast});
            while (hist.size() > 360) hist.removeFirst();
        }
    }

    // ------------------------------------------------------------------ ingest -> decide -> command
    private synchronized void onTelemetry(String payload) {
        Telemetry tel;
        try { tel = Telemetry.parse(payload); } catch (RuntimeException e) { return; }
        if (tel.rack() < 0 || tel.rack() >= Config.RACKS) return;
        RackState s = racks[tel.rack()];
        double now = clock.getAsDouble();

        double dt = Double.isNaN(s.lastTelT) ? 0
                : (tel.t() > s.lastTelT && tel.t() - s.lastTelT <= 5) ? tel.t() - s.lastTelT : 1;
        s.lastTelT = tel.t();
        s.lastSeen = now;
        s.Ts = tel.supply(); s.P = tel.power(); s.fan = tel.fan(); s.pump = tel.pump(); s.rpm = tel.rpm();
        s.trip = tel.trip(); s.fs = tel.failsafe();
        s.stale = false;
        s.Pf = Double.isNaN(s.Pf) ? s.P : 0.5 * s.Pf + 0.5 * s.P;

        s.invalid = !tel.tempValid();
        if (!s.invalid) {
            s.Tm = tel.temp();
            s.stuckCnt = (!Double.isNaN(s.lastT) && Math.abs(tel.temp() - s.lastT) < 1e-4) ? s.stuckCnt + 1 : 0;
            s.lastT = tel.temp();
        }
        s.sensorFault = s.invalid || s.stuckCnt >= 40;

        // fan fault: commanded hard, duty applied, but tachometer shows (almost) no rotation. Latched.
        if (!s.fanFault) {
            s.fanFaultCnt = (s.cmdFan > 0.5 && tel.fan() > 0.4 && tel.rpm() < 300) ? s.fanFaultCnt + 1 : 0;
            if (s.fanFaultCnt >= 5) { s.fanFault = true; s.faultT = now; }
        } else if (tel.rpm() >= 300 && tel.fan() > 0.2) {
            s.fanFault = false; s.fanFaultCnt = 0;
        }

        // KPIs
        boolean fanStalled = tel.fan() > 0.4 && tel.rpm() < 300;       // duty applied but not rotating: draws no real power
        double fw = fanStalled ? 0 : Config.FAN_W * Math.pow(tel.fan(), 3), pw = Config.PUMP_W * tel.pump() * tel.pump();
        s.coolW = fw + pw;
        whFan += fw * dt / 3600; whPump += pw * dt / 3600;
        whIT += Math.max(0, tel.power()) * dt / 3600;
        water += tel.pump() * Config.WATER_LPM / 60.0 * dt;
        if (!s.invalid) {
            if (tel.temp() > Config.SLA) violS += dt;
            maxT = Double.isNaN(maxT) ? tel.temp() : Math.max(maxT, tel.temp());
        }
        if (tel.trip()) shedS += dt;

        if (policy.learns()) learn(s, tel);

        double[] c = policy.decide(s, now);
        s.cmdFan = c[0]; s.cmdPump = c[1];
        trackForecast(s, now);
        watch(tel.rack(), s, now);
        bus.publish(Config.commandTopic(tel.rack()), new Command(c[0], c[1], ++seq).toJson());

        if (csv != null) {
            csv.printf(Locale.ROOT, "%.1f,%.0f,%d,%.4f,%.4f,%.3f,%.3f,%.3f,%.0f,%.3f,%.3f,%s,%.3f,%s%n",
                    now, tel.t(), tel.rack(), tel.temp(), tel.supply(), tel.power(), tel.fan(), tel.pump(), tel.rpm(),
                    c[0], c[1], Double.isNaN(s.pred) ? "" : String.format(Locale.ROOT, "%.3f", s.pred),
                    s.modelErr, flags(s));
        }
    }

    /**
     * Forecast scorecard: each 30 s forecast is stored and compared with the temperature that actually arrived 30 s
     * later, next to the naive "it will stay where it is now" forecast. Lets anyone check that the model adds value.
     */
    private void trackForecast(RackState s, double now) {
        while (!s.fcQueue.isEmpty() && s.fcQueue.peekFirst()[0] <= now) {
            double[] f = s.fcQueue.pollFirst();
            if (s.invalid || s.sensorFault || now - f[0] > 3) continue;         // no trustworthy actual to score against
            double eF = Math.abs(f[1] - s.Tm), eN = Math.abs(f[2] - s.Tm);
            s.sumF += eF; s.sumN += eN; s.sumBias += f[1] - s.Tm; s.nAll++;
            if (f[3] > 0) { s.sumFT += eF; s.sumNT += eN; s.nT++; }
            s.fcErr = Double.isNaN(s.fcErr) ? eF : 0.98 * s.fcErr + 0.02 * eF;
            s.naiveErr = Double.isNaN(s.naiveErr) ? eN : 0.98 * s.naiveErr + 0.02 * eN;
            s.predPast = f[1];
            s.fcN++;
        }
        if (!Double.isNaN(s.pred) && !s.invalid && s.fcQueue.size() < 64) s.fcQueue.addLast(new double[]{now + Config.HORIZON, s.pred, s.Tm, moving(s) ? 1 : 0});
    }

    /** True if the rack temperature moved more than 0.3 K over the last learning window (known at forecast time). */
    private static boolean moving(RackState s) {
        if (s.win.size() < 2) return false;
        return Math.abs(s.win.getLast()[1] - s.win.getFirst()[1]) > 0.3;
    }

    // ---- event log: edge detection on the rack flags (rate-limited so a flapping flag cannot flood the log)
    private static final int F_STALE = 0, F_SENSOR = 1, F_FAN = 2, F_FALLBACK = 3, F_GUARD = 4, F_FS = 5, F_TRIP = 6, F_SLA = 7;

    private void watch(int i, RackState s, double now) {
        boolean[] cur = {s.stale && s.lastSeen > 0, s.sensorFault, s.fanFault, s.fallback, s.guard, s.fs, s.trip,
                !s.invalid && !Double.isNaN(s.Tm) && s.Tm > Config.SLA};
        for (int f = 0; f < cur.length; f++) {
            boolean was = (s.prevFlags & (1 << f)) != 0;
            if (cur[f] == was) continue;
            if (cur[f]) s.prevFlags |= 1 << f; else s.prevFlags &= ~(1 << f);
            if (cur[f] && now - s.lastRaised[f] < 20 && (f == F_GUARD || f == F_FALLBACK || f == F_SLA)) continue;
            if (cur[f]) s.lastRaised[f] = now;
            describe(i, s, f, cur[f], now);
        }
    }

    private void describe(int i, RackState s, int f, boolean on, double now) {
        String r = "Rack " + i + ": ";
        EventLog.Severity w = EventLog.Severity.WARN, c = EventLog.Severity.CRIT, n = EventLog.Severity.INFO;
        switch (f) {
            case F_STALE -> events.add(now, on ? w : n, i, r + (on ? "no data for " + (int) Config.STALE_S + " s, control is blind" : "data flowing again"));
            case F_SENSOR -> events.add(now, on ? c : n, i, r + (on ? "temperature sensor fault (invalid or frozen reading), running full cooling"
                    : "temperature sensor healthy again"));
            case F_FAN -> events.add(now, on ? c : n, i, r + (on ? String.format(Locale.ROOT, "fan fault: %.0f %% commanded, %.0f rpm measured, shifting load to the pump", s.cmdFan * 100, s.rpm)
                    : "fan running again"));
            case F_FALLBACK -> { if (on) events.add(now, w, i, r + "learned model not trusted, thermostat fallback active"); }
            case F_GUARD -> { if (on) events.add(now, w, i, String.format(Locale.ROOT, r + "within 1 \u00b0C of the limit (%.1f \u00b0C), maximum cooling", s.Tm)); }
            case F_FS -> events.add(now, on ? c : n, i, r + (on ? "node lost the backend and entered its own fail-safe (cooling to maximum)" : "node fail-safe cleared, backend in control"));
            case F_TRIP -> events.add(now, on ? c : n, i, r + (on ? "over-temperature trip, IT load shed by the node" : "trip reset, load restored"));
            case F_SLA -> events.add(now, on ? c : n, i, String.format(Locale.ROOT, r + (on ? "above the %.0f \u00b0C limit (%.1f \u00b0C)" : "back under the %.0f \u00b0C limit"), Config.SLA, s.Tm));
            default -> { }
        }
    }

    private void learn(RackState s, Telemetry tel) {
        if (s.invalid) return;
        double x = tel.temp() - tel.supply();
        s.win.addLast(new double[]{tel.t(), tel.temp(), 1, x / 10, tel.power() / 50, tel.fan() * x / 10, tel.pump() * x / 10});
        while (s.win.size() > Config.WIN + 1) s.win.removeFirst();
        if (s.win.size() < Config.WIN + 1 || s.fanFault || s.sensorFault) return;
        double[] a = s.win.getFirst(), z = s.win.getLast();
        double dt = z[0] - a[0];
        if (Math.abs(dt - Config.WIN) > 0.5) return;                    // gap from lost packets: skip this window
        double y = (z[1] - a[1]) / dt;                                  // measured dT/dt over the window
        double[] m = new double[Rls.N];
        int c = 0;
        for (double[] e : s.win) {
            if (c == Config.WIN) break;
            for (int j = 0; j < Rls.N; j++) m[j] += e[2 + j];
            c++;
        }
        for (int j = 0; j < Rls.N; j++) m[j] /= c;
        double res = Math.abs(y - s.rls.predict(m));
        s.modelErr = 0.995 * s.modelErr + 0.005 * res * Config.HORIZON; // running 30 s prediction error (K)
        s.rls.update(m, y);
        s.learned++;
    }

    // ------------------------------------------------------------------ read side
    private double coolW() { double w = 0; for (RackState s : racks) w += s.coolW; return w; }
    private double itW() { double w = 0; for (RackState s : racks) w += Math.max(0, s.P); return w; }

    public synchronized double whCool() { return whFan + whPump; }
    public synchronized double whFan() { return whFan; }
    public synchronized double whPump() { return whPump; }
    public synchronized double whIT() { return whIT; }
    public synchronized double water() { return water; }
    public synchronized double violS() { return violS; }
    public synchronized double maxT() { return maxT; }
    public synchronized double modelErr(int rack) { return racks[rack].modelErr; }
    public synchronized long learned(int rack) { return racks[rack].learned; }

    public synchronized List<double[]> history() { return new ArrayList<>(hist); }
    public synchronized double fcErr(int rack) { return racks[rack].fcErr; }
    public synchronized double naiveErr(int rack) { return racks[rack].naiveErr; }
    public synchronized long fcCount(int rack) { return racks[rack].fcN; }
    /** {mean |forecast err|, mean |naive err|, mean signed bias, n, same two means while temperature was moving, n}. */
    public synchronized double[] fcStats(int rack) {
        RackState s = racks[rack];
        return new double[]{s.sumF / Math.max(1, s.nAll), s.sumN / Math.max(1, s.nAll), s.sumBias / Math.max(1, s.nAll), s.nAll,
                s.sumFT / Math.max(1, s.nT), s.sumNT / Math.max(1, s.nT), s.nT};
    }
    public synchronized String why(int rack) { return racks[rack].why; }
    public synchronized boolean fanFault(int rack) { return racks[rack].fanFault; }

    private static String flags(RackState s) {
        List<String> l = new ArrayList<>();
        if (s.stale) l.add("STALE");
        if (s.sensorFault) l.add("SENSOR");
        if (s.fanFault) l.add("FAN");
        if (s.fallback) l.add("FALLBACK");
        if (s.guard) l.add("GUARD");
        if (s.fs) l.add("FAILSAFE");
        if (s.trip) l.add("TRIP");
        return String.join(",", l);
    }

    /** JSON snapshot for the dashboard. topExtra / rackExtra are raw fragments that start with a comma (or are empty). */
    public synchronized void json(StringBuilder sb, String topExtra, IntFunction<String> rackExtra) {
        json(sb, topExtra, rackExtra, 0);
    }

    public synchronized void json(StringBuilder sb, String topExtra, IntFunction<String> rackExtra, int maxEvents) {
        sb.append("{\"name\":").append(Json.quote(name))
          .append(",\"coolW\":").append(Json.fmt(coolW()))
          .append(",\"whCool\":").append(Json.fmt(whCool()))
          .append(",\"whFan\":").append(Json.fmt(whFan))
          .append(",\"whPump\":").append(Json.fmt(whPump))
          .append(",\"whIT\":").append(Json.fmt(whIT))
          .append(",\"water\":").append(Json.fmt(water))
          .append(",\"viol\":").append(Json.fmt(violS))
          .append(",\"maxT\":").append(Json.fmt(maxT))
          .append(",\"shed\":").append(Json.fmt(shedS))
          .append(topExtra == null ? "" : topExtra)
          .append(",\"racks\":[");
        for (int i = 0; i < Config.RACKS; i++) {
            RackState s = racks[i];
            if (i > 0) sb.append(',');
            sb.append("{\"Tm\":").append(Json.fmt(s.Tm))
              .append(",\"pred\":").append(Json.fmt(s.pred))
              .append(",\"P\":").append(Json.fmt(s.P))
              .append(",\"fan\":").append(Json.fmt(s.fan))
              .append(",\"pump\":").append(Json.fmt(s.pump))
              .append(",\"rpm\":").append(Json.fmt(s.rpm))
              .append(",\"cmdFan\":").append(Json.fmt(s.cmdFan))
              .append(",\"cmdPump\":").append(Json.fmt(s.cmdPump))
              .append(",\"err\":").append(Json.fmt(s.modelErr))
              .append(",\"learned\":").append(s.learned)
              .append(",\"flags\":").append(Json.quote(flags(s)))
              .append(",\"why\":").append(Json.quote(s.why))
              .append(",\"fcErr\":").append(Json.fmt(s.fcErr))
              .append(",\"naiveErr\":").append(Json.fmt(s.naiveErr))
              .append(",\"fcN\":").append(s.fcN)
              .append(",\"fcBias\":").append(Json.fmt(s.nAll > 0 ? s.sumBias / s.nAll : Double.NaN))
              .append(",\"fcMov\":").append(Json.fmt(s.nT >= 20 ? s.sumFT / s.nT : Double.NaN))
              .append(",\"naiveMov\":").append(Json.fmt(s.nT >= 20 ? s.sumNT / s.nT : Double.NaN))
              .append(rackExtra == null ? "" : rackExtra.apply(i))
              .append('}');
        }
        sb.append(']');
        if (maxEvents > 0) { sb.append(",\"events\":"); events.json(sb, maxEvents); }
        sb.append(",\"nEvents\":").append(events.total()).append('}');
    }
}
