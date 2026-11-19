package ecoai.control;

import java.util.ArrayDeque;

/** What the backend knows about one rack: only what arrived over the bus, plus derived estimates and flags. */
public final class RackState {
    public double Tm = Double.NaN, Ts = 24, P, Pf = Double.NaN, fan, pump, rpm;
    public double lastSeen, lastT = Double.NaN, lastTelT = Double.NaN;
    public double cmdFan = 0.3, cmdPump = 0, pred = Double.NaN, modelErr = 1.0, faultT, coolW;
    public int stuckCnt, fanFaultCnt;
    public boolean sensorFault, invalid, fanFault, stale = true, fallback, guard, fs, trip;
    public long learned;
    public String why = "";                                   // one-line plain-language reason for the last decision
    public double fcErr = Double.NaN, naiveErr = Double.NaN, predPast = Double.NaN;   // 30 s forecast accuracy (K)
    public long fcN, nAll, nT;
    public double sumF, sumN, sumBias, sumFT, sumNT;
    public int prevFlags;                                     // event-log edge detection
    public final double[] lastRaised = new double[8];
    public final ArrayDeque<double[]> fcQueue = new ArrayDeque<>();   // {due time, forecast, temp when issued}
    public final Rls rls = new Rls();
    public final ArrayDeque<double[]> win = new ArrayDeque<>();
}
