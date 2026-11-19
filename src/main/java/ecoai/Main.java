package ecoai;

import ecoai.app.BackendApp;
import ecoai.app.DemoApp;
import ecoai.app.BatchReport;
import ecoai.app.HeadlessRun;
import ecoai.app.ReplayApp;
import ecoai.app.SimulatorApp;

import java.nio.file.Path;

/**
 * EcoAI Grid entry point.
 *
 *   demo       all-in-one: simulated rig, 4 controllers side by side, dashboard            (default)
 *   replay     play back a saved run (--record dir) in the dashboard
 *   report     evaluation report (seeds x workload presets) as printable HTML   (--seeds N --hours H --out file)
 *   headless   batch evaluation, prints the results table
 *   selftest   regression checks (exit code 0 = pass)
 *   backend    AI backend + dashboard on a real MQTT broker                  (--mqtt host[:port])
 *   simulator  hardware stand-in publishing to a real MQTT broker            (--mqtt host[:port])
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        String mode = "demo";
        int i = 0;
        if (args.length > 0 && !args[0].startsWith("--")) { mode = args[0]; i = 1; }
        int port = 8080;
        double speed = 20, hours = 4;
        long seed = 42;
        boolean faults = false, lan = false;
        String mqtt = "localhost:1883";
        Path log = null, record = null, out = Path.of("report.html");
        String token = null, positional = null;
        int seeds = 6;
        for (; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--speed" -> speed = Double.parseDouble(args[++i]);
                case "--hours" -> hours = Double.parseDouble(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--faults" -> faults = true;
                case "--lan" -> lan = true;
                case "--mqtt" -> mqtt = args[++i];
                case "--log" -> log = Path.of(args[++i]);
                case "--record" -> record = Path.of(args[++i]);
                case "--token" -> token = args[++i];
                case "--seeds" -> seeds = Integer.parseInt(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--water-weight" -> Config.waterWeight = Double.parseDouble(args[++i]);
                case "--setpoint" -> Config.setpoint = Double.parseDouble(args[++i]);
                default -> {
                    if (!args[i].startsWith("--") && positional == null) positional = args[i];
                    else { System.err.println("unknown option " + args[i]); System.exit(2); }
                }
            }
        }
        String host = mqtt.contains(":") ? mqtt.substring(0, mqtt.lastIndexOf(':')) : mqtt;
        int mport = mqtt.contains(":") ? Integer.parseInt(mqtt.substring(mqtt.lastIndexOf(':') + 1)) : 1883;

        switch (mode) {
            case "demo" -> {
                if (lan && token == null) System.err.println("warning: --lan without --token lets anyone on the network inject faults; add --token <secret>");
                new DemoApp(seed, speed, log, record).run(port, lan, token);
            }
            case "replay" -> {
                if (positional == null) { System.err.println("usage: replay <run file or directory> [--port N]"); System.exit(2); }
                new ReplayApp(Path.of(positional)).run(port, lan, token);
            }
            case "report" -> BatchReport.write(out, seeds, hours == 4 ? 4 : hours);
            case "headless" -> HeadlessRun.print(HeadlessRun.run(seed, hours, faults), seed, hours, faults);
            case "selftest" -> {
                System.out.println("EcoAI Grid self-test (seed " + seed + ")");
                boolean ok = HeadlessRun.selfTest(seed);
                System.out.println(ok ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
                System.exit(ok ? 0 : 1);
            }
            case "backend" -> BackendApp.run(host, mport, port, lan, log, token, record);
            case "simulator" -> SimulatorApp.run(host, mport, speed == 20 ? 1 : speed, seed);
            default -> { System.err.println("unknown mode " + mode + " (demo|replay|report|headless|selftest|backend|simulator)"); System.exit(2); }
        }
    }
}
