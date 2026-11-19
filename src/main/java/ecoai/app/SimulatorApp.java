package ecoai.app;

import ecoai.Config;
import ecoai.bus.MqttBus;
import ecoai.sim.HardwareRig;
import ecoai.util.Json;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Hardware stand-in for real-deployment mode: publishes the same MQTT telemetry the ESP32 nodes will, listens for
 * the same actuator commands. Stop this process and power the real ESP32s to switch to hardware - nothing else changes.
 */
public final class SimulatorApp {
    private SimulatorApp() {}

    public static void run(String host, int port, double speed, long seed) {
        MqttBus bus = new MqttBus(host, port, "ecoai-simulator-" + (System.nanoTime() & 0xffff));
        HardwareRig rig = new HardwareRig(bus, seed);
        bus.subscribe(Config.SIM_CONTROL, (topic, payload) -> {
            String cmd = Json.str(payload, "cmd", "");
            if (cmd.equals("profile")) { rig.setProfile(Json.str(payload, "name", "")); return; }
            int rack = (int) Json.num(payload, "rack", 0);
            System.out.println("[sim] fault injection: " + cmd + (cmd.contains("all") ? "" : " rack " + rack));
            rig.fault(cmd, rack);
        });
        long periodMs = Math.max(5, (long) (1000.0 / Math.max(0.1, speed)));
        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
        loop.scheduleAtFixedRate(() -> {
            try {
                rig.step();
                if (((long) rig.t) % 10 == 0) {
                    System.out.printf("[sim] t=%5.0fs  rack temps %.1f / %.1f / %.1f C  fan %.0f%% / %.0f%% / %.0f%%  broker %s%n",
                            rig.t, rig.racks[0].T, rig.racks[1].T, rig.racks[2].T,
                            rig.racks[0].fan * 100, rig.racks[1].fan * 100, rig.racks[2].fan * 100,
                            bus.isConnected() ? "up" : "DOWN");
                }
            } catch (Throwable t) { t.printStackTrace(); }
        }, 0, periodMs, TimeUnit.MILLISECONDS);
        System.out.println("EcoAI Grid HARDWARE SIMULATOR publishing to MQTT " + host + ":" + port + " at " + speed + "x");
    }
}
