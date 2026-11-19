package ecoai.control;

import ecoai.Config;

/**
 * Predictive optimiser. Every second, per rack: roll the learned thermal model forward 30 s for 21 fan x 5 pump
 * candidates, and pick the cheapest (fan energy + pump energy + water cost + actuator change + predicted limit excess).
 * Safety layers around it: uncertainty-aware margin, hard guard near the SLA, sensor-fault and fan-fault handling,
 * and a thermostat fallback if the learned model is physically implausible.
 */
public final class AiPolicy implements Policy {
    private final Policy fallback = new ThermostatPolicy(0);

    @Override public boolean learns() { return true; }

    @Override public double[] decide(RackState s, double now) {
        s.fallback = false; s.guard = false; s.pred = Double.NaN; s.why = "";
        double fanMax = s.fanFault ? 0 : 1;
        if (s.sensorFault) { s.why = "Temperature sensor unreliable: holding full cooling"; return new double[]{1, 1}; }
        if (s.fanFault && ((long) (now - s.faultT)) % 300 < 10) {
            s.why = "Fan fault: briefly testing whether the fan has recovered";
            return new double[]{0.6, 1};
        }
        if (s.Tm >= Config.SLA - 1.0) {
            s.guard = true;
            s.why = String.format(java.util.Locale.ROOT, "Within 1 \u00b0C of the %.0f \u00b0C limit: maximum cooling", Config.SLA);
            return new double[]{fanMax, 1};
        }

        double[] th = s.rls.th;
        if (!(th[1] < 0 && th[2] > 0)) {
            s.fallback = true;
            s.why = "Thermal model not trusted yet: using plain thermostat rules";
            return fallback.decide(s, now);
        }

        double limit = Config.setpoint - ThermostatPolicy.clamp(1.5 * s.modelErr, 0, 2.5);   // uncertainty margin
        double ph = Math.max(s.Pf, s.P) + 2.0;                                     // forecast load + headroom
        double a0 = th[0] + th[2] * ph / 50;
        double ww = Config.waterWeight;
        double bestJ = Double.MAX_VALUE, bf = fanMax, bp = 1, bEnd = Double.NaN;
        int nf = s.fanFault ? 1 : 21;
        for (int fi = 0; fi < nf; fi++) {
            double f = s.fanFault ? 0 : fi * 0.05;
            for (int pi = 0; pi < 5; pi++) {
                double p = pi * 0.25;
                double a1 = (th[1] + th[3] * f + th[4] * p) / 10;
                double x = s.Tm - s.Ts, exc = 0;
                for (int k = 0; k < Config.HORIZON; k++) {
                    x += a0 + a1 * x;
                    double ex = Math.max(0, x + s.Ts - limit);
                    exc += ex * ex;
                }
                double j = Config.FAN_W * f * f * f + Config.PUMP_W * p * p + ww * p
                        + 0.3 * (Math.abs(f - s.cmdFan) + Math.abs(p - s.cmdPump)) + 40.0 * exc / Config.HORIZON;
                if (j < bestJ) { bestJ = j; bf = f; bp = p; bEnd = x + s.Ts; }
            }
        }
        s.pred = bEnd;
        s.why = String.format(java.util.Locale.ROOT,
                "Fan %.0f %%, pump %.0f %%: cheapest setting forecast to stay under %.1f \u00b0C%s",
                bf * 100, bp * 100, limit, s.fanFault ? " (fan out of service)" : "");
        return new double[]{bf, bp};
    }
}
