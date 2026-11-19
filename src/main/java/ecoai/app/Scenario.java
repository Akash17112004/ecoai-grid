package ecoai.app;

import ecoai.bus.InMemoryBus;
import ecoai.control.Backend;
import ecoai.control.Policy;
import ecoai.sim.HardwareRig;

/** One complete closed loop (simulated rig + in-memory bus + backend with one control policy). */
final class Scenario {
    final String name;
    final InMemoryBus bus;
    final HardwareRig rig;
    final Backend backend;

    Scenario(String name, Policy policy, long seed) {
        this.name = name;
        bus = new InMemoryBus(seed ^ 0x9E3779B97F4A7C15L, "rack0");
        rig = new HardwareRig(bus, seed);
        backend = new Backend(name, bus, policy, () -> rig.t);
    }

    void step() {
        rig.step();
        backend.tick(rig.t);
    }

    /** Ground-truth extras (only a simulator can know these) appended to the dashboard JSON. */
    String topExtra() {
        StringBuilder sb = new StringBuilder();
        sb.append(",\"trueMaxT\":").append(ecoai.util.Json.fmt(rig.maxT))
          .append(",\"trueViol\":").append(ecoai.util.Json.fmt(rig.violS))
          .append(",\"trueShed\":").append(ecoai.util.Json.fmt(rig.shedS))
          .append(",\"trueWhCool\":").append(ecoai.util.Json.fmt(rig.whCool()))
          .append(",\"trueWater\":").append(ecoai.util.Json.fmt(rig.water))
          .append(",\"profile\":").append(ecoai.util.Json.quote(rig.profile().id()))
          .append(",\"sent\":").append(bus.sent).append(",\"dropped\":").append(bus.dropped)
          .append(",\"log\":[");
        boolean first = true;
        for (String l : bus.recentLog()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(ecoai.util.Json.quote(l));
        }
        return sb.append(']').toString();
    }

    String rackExtra(int i) {
        var r = rig.racks[i];
        return ",\"T\":" + ecoai.util.Json.fmt(r.T) + ",\"load\":" + ecoai.util.Json.fmt(r.loadW)
                + ",\"heater\":" + ecoai.util.Json.fmt(r.heaterW)
                + ",\"fanT\":" + ecoai.util.Json.fmt(r.fan) + ",\"pumpT\":" + ecoai.util.Json.fmt(r.pump)
                + ",\"nodeFs\":" + rig.nodes[i].isFailsafe()
                + ",\"fanFailed\":" + r.fanFailed + ",\"stuck\":" + rig.nodes[i].isStuck()
                + ",\"unplugged\":" + rig.nodes[i].isUnplugged();
    }
}
