package ecoai.control;

/** Baseline 1: fixed fan 80 %, pump 40 % regardless of load (how an un-tuned cooling loop is often run). */
public final class StaticPolicy implements Policy {
    @Override public double[] decide(RackState s, double now) { return new double[]{0.8, 0.4}; }
}
