package ecoai.control;

/**
 * Recursive least squares for the per-rack thermal model (learned online from telemetry):
 *   dT/dt = th0 + th1*x/10 + th2*P/50 + th3*fan*x/10 + th4*pump*x/10,   x = T_rack - T_supply
 * i.e. passive leakage, IT heat input, and the fan / liquid-loop cooling gains.
 * The starting values are a datasheet-style guess, deliberately 10-30 % off the real rig.
 */
public final class Rls {
    public static final int N = 5;
    public final double[] th;
    private final double[][] P = new double[N][N];
    private static final double LAMBDA = 0.99995;

    public Rls() {
        double c = 340, g0 = 0.5, gf = 2.8, gp = 3.4;
        th = new double[]{0, -g0 * 10 / c, 50 / c, -gf * 10 / c, -gp * 10 / c};
        for (int i = 0; i < N; i++) P[i][i] = 2.0;
    }

    public double predict(double[] f) {
        double s = 0;
        for (int i = 0; i < N; i++) s += th[i] * f[i];
        return s;
    }

    public void update(double[] f, double y) {
        double[] pf = new double[N];
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) pf[i] += P[i][j] * f[j];
        double den = LAMBDA;
        for (int i = 0; i < N; i++) den += f[i] * pf[i];
        double e = y - predict(f);
        double[] k = new double[N];
        for (int i = 0; i < N; i++) { k[i] = pf[i] / den; th[i] += k[i] * e; }
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) P[i][j] = (P[i][j] - k[i] * pf[j]) / LAMBDA;
    }
}
