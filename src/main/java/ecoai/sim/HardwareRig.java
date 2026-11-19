package ecoai.sim;

import ecoai.Config;
import ecoai.bus.MessageBus;

/**
 * The whole simulated test rig: 3 racks (heater = server load, fan, pump, relay, thermal fuse) plus their ESP32 nodes.
 * It only talks to the outside world through the MessageBus, exactly like the real hardware will.
 */
public final class HardwareRig {
    public final MessageBus bus;
    public final Rack[] racks = new Rack[Config.RACKS];
    public final Esp32Node[] nodes = new Esp32Node[Config.RACKS];
    private final Workload wl;
    public double t;

    // ground-truth instrumentation (a wattmeter / thermocouple on the real rig)
    public double whFan, whPump, whIT, water, violS, maxT, shedS, sumT, coolW;
    public long steps;

    public HardwareRig(MessageBus bus, long seed) {
        this.bus = bus;
        wl = new Workload(seed);
        for (int i = 0; i < Config.RACKS; i++) {
            racks[i] = new Rack(i);
            nodes[i] = new Esp32Node(racks[i], bus, seed);
        }
    }

    /** Ambient / cold-aisle supply temperature: 24 C with a slow +-1.5 C swing. */
    public static double supplyT(double t) { return 24.0 + 1.5 * Math.sin(2 * Math.PI * t / 5400.0); }

    public synchronized void step() {
        t += Config.DT; steps++;
        bus.setTime(t);
        double sup = supplyT(t);
        wl.update(t);
        for (int i = 0; i < Config.RACKS; i++) racks[i].loadW = wl.load[i];
        for (Esp32Node n : nodes) n.tick(t, sup);

        double fw = 0, pw = 0;
        for (Rack r : racks) {
            if (!r.fuseOpen && r.T >= Config.FUSE_ON) r.fuseOpen = true;
            else if (r.fuseOpen && r.T < Config.FUSE_OFF) r.fuseOpen = false;
            r.heaterW = (r.relayOpen || r.fuseOpen) ? 0 : r.loadW;
            double fanEff = r.fanFailed ? 0 : r.fan;
            double g = Config.G0 + Config.GF * fanEff + Config.GP * r.pump;
            r.T += Config.DT * (r.heaterW - g * (r.T - sup)) / Config.C_TH;
            double f = r.fanFailed ? 0 : Config.FAN_W * r.fan * r.fan * r.fan;
            double p = Config.PUMP_W * r.pump * r.pump;
            fw += f; pw += p;
            whIT += r.heaterW * Config.DT / 3600;
            water += r.pump * Config.WATER_LPM / 60.0 * Config.DT;
            if (r.T > Config.SLA) violS += Config.DT;
            if (r.relayOpen || r.fuseOpen) shedS += Config.DT;
            maxT = Math.max(maxT, r.T);
            sumT += r.T;
        }
        coolW = fw + pw;
        whFan += fw * Config.DT / 3600;
        whPump += pw * Config.DT / 3600;
    }

    public double whCool() { return whFan + whPump; }

    public Workload.Profile profile() { return wl.profile(); }
    public boolean setProfile(String id) {
        Workload.Profile p = Workload.Profile.parse(id);
        if (p == null) return false;
        wl.setProfile(p);
        return true;
    }

    /** Fault / load injection (what a person does to the real rig: unplug a fan, add load, kill WiFi...). */
    public synchronized void fault(String cmd, int rack) {
        int i = Math.max(0, Math.min(Config.RACKS - 1, rack));
        switch (cmd) {
            case "burst" -> wl.forceBurst(i, 150);
            case "burstall" -> { for (int k = 0; k < Config.RACKS; k++) wl.forceBurst(k, 150); }
            case "fanfail" -> racks[i].fanFailed = true;
            case "fanfix" -> racks[i].fanFailed = false;
            case "stuck" -> nodes[i].setStuck(true);
            case "unstuck" -> nodes[i].setStuck(false);
            case "unplug" -> nodes[i].setUnplugged(true);
            case "replug" -> nodes[i].setUnplugged(false);
            case "partition" -> { for (Esp32Node n : nodes) n.setNetwork(false); }
            case "heal" -> { for (Esp32Node n : nodes) n.setNetwork(true); }
            case "clear" -> {
                for (int k = 0; k < Config.RACKS; k++) {
                    racks[k].fanFailed = false;
                    nodes[k].setStuck(false);
                    nodes[k].setUnplugged(false);
                    nodes[k].setNetwork(true);
                }
            }
            default -> { }
        }
    }
}
