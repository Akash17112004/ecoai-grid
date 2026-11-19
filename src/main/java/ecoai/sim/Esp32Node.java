package ecoai.sim;

import ecoai.Config;
import ecoai.bus.MessageBus;
import ecoai.model.Command;
import ecoai.model.Telemetry;

import java.util.Random;

/**
 * Software model of the ESP32 firmware on one rack (firmware/esp32_node/esp32_node.ino does the same on real hardware):
 * DS18B20 / INA219 / tach sampling, EMA filtering, over-temperature load shedding,
 * PWM slew limiting and the watchdog fail-safe when the backend goes silent.
 */
public final class Esp32Node {
    private final Rack r;
    private final MessageBus bus;
    private final Random rng;
    private double ema = Double.NaN, emaS = Double.NaN, stuckRaw;
    private volatile double now, fanTgt = 0.3, pumpTgt = 0, lastCmd;
    private boolean stuck, unplugged, failsafe, trip;
    private volatile boolean netUp = true;

    public Esp32Node(Rack r, MessageBus bus, long seed) {
        this.r = r; this.bus = bus;
        this.rng = new Random(seed + r.id * 7919L);
        bus.subscribe(Config.commandTopic(r.id), (topic, payload) -> onCommand(payload));
    }

    private void onCommand(String payload) {
        if (!netUp) return;
        Command c = Command.parse(payload);
        if (!Double.isNaN(c.fan())) fanTgt = clamp(c.fan(), 0, 1);
        if (!Double.isNaN(c.pump())) pumpTgt = clamp(c.pump(), 0, 1);
        lastCmd = now;
    }

    public void setStuck(boolean on) {
        stuck = on;
        if (on) stuckRaw = Double.isNaN(ema) ? r.T : ema;
    }
    public boolean isStuck() { return stuck; }
    public void setUnplugged(boolean on) { unplugged = on; }
    public boolean isUnplugged() { return unplugged; }
    public boolean isFailsafe() { return failsafe; }
    public void setNetwork(boolean up) { netUp = up; }

    public void tick(double t, double supply) {
        now = t;
        double raw = Math.round((r.T + rng.nextGaussian() * 0.15) / 0.0625) * 0.0625;     // DS18B20, 12-bit
        double rawS = Math.round((supply + rng.nextGaussian() * 0.10) / 0.0625) * 0.0625;  // intake sensor
        double pw = r.heaterW + rng.nextGaussian() * 0.25;                                 // INA219
        double tach = Math.max(0, (r.fanFailed ? 0 : r.fan * Config.FAN_RPM) + rng.nextGaussian() * 20);
        if (stuck) raw = stuckRaw;
        if (!unplugged) ema = Double.isNaN(ema) ? raw : ema + 0.3 * (raw - ema);
        emaS = Double.isNaN(emaS) ? rawS : emaS + 0.3 * (rawS - emaS);
        double reported = unplugged ? -127.0 : ema;                                        // DS18B20 error code

        if (!trip && !Double.isNaN(ema) && ema >= Config.TRIP) trip = true;
        else if (trip && ema < Config.TRIP_RESET) trip = false;
        r.relayOpen = trip;                                                                // shed IT load

        if (netUp) {
            bus.publish(Config.telemetryTopic(r.id), new Telemetry(r.id, t, reported, emaS, pw, r.fan, r.pump,
                    tach, trip, failsafe).toJson());
        }

        failsafe = (t - lastCmd) > Config.WATCHDOG_S;                                      // backend silent
        double ft = fanTgt, pt = pumpTgt;
        if (failsafe) { ft = 1.0; pt = Math.max(pt, 0.5); }
        if (unplugged) { ft = 1.0; pt = 1.0; }                                             // no sensor: full cooling
        if (trip) { ft = 1.0; pt = 1.0; }
        r.fan += clamp(ft - r.fan, -0.25, 0.25);                                           // PWM slew limiter
        r.pump += clamp(pt - r.pump, -0.15, 0.15);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
