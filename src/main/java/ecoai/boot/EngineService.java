package ecoai.boot;

import ecoai.app.DemoApp;
import ecoai.app.LiveEngine;
import ecoai.web.DashboardServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Owns the running engine (simulated demo or live MQTT backend) and exposes it to the controllers. */
@Service
public class EngineService {
    private final EcoAiProperties props;
    private DashboardServer.Api api;
    private AutoCloseable closer;

    public EngineService(EcoAiProperties props) { this.props = props; }

    @PostConstruct
    void start() throws IOException {
        Path record = props.getRecordDir().isBlank() ? null : Path.of(props.getRecordDir());
        Path csv = props.getCsvLog().isBlank() ? null : Path.of(props.getCsvLog());
        if ("live".equalsIgnoreCase(props.getMode())) {
            LiveEngine live = new LiveEngine(props.getMqttHost(), props.getMqttPort(), csv, record);
            api = live;
            closer = live;
        } else if ("demo".equalsIgnoreCase(props.getMode())) {
            DemoApp demo = new DemoApp(props.getSeed(), props.getSpeed(), csv, record);
            demo.start();
            api = demo;
            closer = demo;
        } else {
            throw new IllegalStateException("ecoai.mode must be 'demo' or 'live', got '" + props.getMode() + "'");
        }
    }

    @PreDestroy
    void stop() throws Exception {
        if (closer != null) closer.close();
    }

    public String mode() { return props.getMode().toLowerCase(); }
    public String state() { return api.state(); }
    public void control(Map<String, String> q) { api.control(q); }
    public String events() { return api.events(); }
    public String report(String format) { return api.report(format); }
    public String runs() { return api.runs(); }
    public String health() { return api.health(); }
    public boolean up() { return health().contains("\"UP\""); }
}
