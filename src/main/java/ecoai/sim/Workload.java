package ecoai.sim;

import ecoai.Config;

import java.util.Locale;
import java.util.Random;

/**
 * Server-load generator (stands in for real IT load on the heater). The default MIXED profile is a daily-style sine
 * plus noise and random bursts; the others are presets for the demo (idle night, office day, batch jobs, spiky, stress).
 */
public final class Workload {
    public enum Profile {
        MIXED("Mixed", "Slow daily swing with occasional bursts (default; used for the README results)"),
        IDLE("Idle", "Quiet night: low steady load, no bursts"),
        OFFICE("Office day", "Load climbs through the morning and falls in the evening"),
        BATCH("Batch jobs", "Racks switch between heavy jobs and idle in staggered cycles"),
        SPIKY("Spiky", "Frequent short bursts on top of a moderate base"),
        STRESS("Stress", "High sustained load with bursts: tests the cooling headroom");

        public final String label, blurb;
        Profile(String label, String blurb) { this.label = label; this.blurb = blurb; }

        public static Profile parse(String s) {
            if (s != null) for (Profile p : values()) if (p.name().equalsIgnoreCase(s.trim())) return p;
            return null;
        }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Random rng;
    final double[] load = new double[Config.RACKS];
    private final double[] burst = new double[Config.RACKS], noise = new double[Config.RACKS];
    private volatile Profile profile = Profile.MIXED;

    public Workload(long seed) { rng = new Random(seed); }

    public Profile profile() { return profile; }
    public void setProfile(Profile p) { if (p != null) profile = p; }

    void update(double t) {
        Profile pr = profile;
        double prob = switch (pr) { case MIXED -> 1.0 / 500; case OFFICE -> 1.0 / 900; case SPIKY -> 1.0 / 120;
            case STRESS -> 1.0 / 300; default -> 0; };
        double amp = switch (pr) { case SPIKY -> 22; case STRESS -> 12; default -> 18; };
        for (int i = 0; i < Config.RACKS; i++) {
            if (burst[i] > 0) burst[i] -= Config.DT;
            else if (rng.nextDouble() < prob) {
                burst[i] = pr == Profile.SPIKY ? 25 + rng.nextDouble() * 55 : 60 + rng.nextDouble() * 120;
            }
            noise[i] = 0.9 * noise[i] + 0.4 * rng.nextGaussian();
            double base = switch (pr) {
                case MIXED -> 22 + 7 * Math.sin(2 * Math.PI * t / 1800.0 + i * 2.1);
                case IDLE -> 10 + 2 * Math.sin(2 * Math.PI * t / 1200.0 + i * 2.1);
                case OFFICE -> 12 + 24 * (0.5 - 0.5 * Math.cos(2 * Math.PI * (t + i * 60) / 3600.0));
                case BATCH -> (((t + i * 300) % 900) < 360) ? 42 : 12;
                case SPIKY -> 18 + 3 * Math.sin(2 * Math.PI * t / 900.0 + i * 2.1);
                case STRESS -> 38 + 6 * Math.sin(2 * Math.PI * t / 1200.0 + i * 2.1);
            };
            double v = base + noise[i] + (burst[i] > 0 ? amp : 0);
            load[i] = Math.max(5, Math.min(Config.MAX_LOAD, v));
        }
    }

    void forceBurst(int rack, double seconds) { burst[rack] = Math.max(burst[rack], seconds); }
}
