package ecoai.app;

import ecoai.web.DashboardServer;

import java.io.IOException;
import java.nio.file.Path;

/** Real deployment mode: the AI backend + dashboard on an MQTT broker (plain-JDK HTTP server). */
public final class BackendApp {
    private BackendApp() {}

    public static void run(String host, int mqttPort, int httpPort, boolean lan, Path csv) throws IOException {
        run(host, mqttPort, httpPort, lan, csv, null, null);
    }

    public static void run(String host, int mqttPort, int httpPort, boolean lan, Path csv, String token, Path recordDir) throws IOException {
        LiveEngine engine = new LiveEngine(host, mqttPort, csv, recordDir);
        Runtime.getRuntime().addShutdownHook(new Thread(engine::close));
        new DashboardServer(httpPort, lan, token, engine).start();
        System.out.println("EcoAI Grid BACKEND on MQTT " + host + ":" + mqttPort + ", dashboard http://localhost:" + httpPort
                + (csv != null ? ", logging to " + csv : ""));
    }
}
