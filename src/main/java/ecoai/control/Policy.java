package ecoai.control;

/** A cooling control strategy: given the latest rack state, return {fan duty, pump duty} in 0..1. */
public interface Policy {
    double[] decide(RackState s, double now);

    /** True if the backend should feed telemetry into the per-rack thermal model for this policy. */
    default boolean learns() { return false; }
}
