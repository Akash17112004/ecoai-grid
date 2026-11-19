package ecoai.control;

/**
 * Baseline 2/3: reactive proportional thermostat. Fan ramps from 25 % at (33+shift) C, pump engages above (37+shift) C.
 * shift=0 is the conservative thermostat; shift=2 raises it to roughly the AI controller's operating temperature,
 * which is the fair "why not just raise the thermostat?" comparison.
 */
public final class ThermostatPolicy implements Policy {
    private final double shift;

    public ThermostatPolicy(double shift) { this.shift = shift; }

    @Override public double[] decide(RackState s, double now) {
        if (Double.isNaN(s.Tm)) return new double[]{1, 1};         // no valid reading yet: full cooling
        double f = clamp(0.25 + 0.12 * (s.Tm - (33 + shift)), 0.2, 1.0);
        double p = s.Tm > 37 + shift ? clamp(0.25 * (s.Tm - (37 + shift)), 0, 1) : 0;
        return new double[]{f, p};
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
