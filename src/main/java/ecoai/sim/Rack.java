package ecoai.sim;

/** Physical state of one rack enclosure (ground truth, invisible to the backend). */
public final class Rack {
    public final int id;
    public double T = 28.0;                 // true temperature, C
    public double fan = 0.3, pump = 0.0;    // applied duty 0..1
    public double loadW, heaterW;           // requested / actually dissipated IT load
    public boolean fanFailed, relayOpen, fuseOpen;

    public Rack(int id) { this.id = id; }
}
