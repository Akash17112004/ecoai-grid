package ecoai.boot;

import ecoai.Config;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API. The /api/... routes are the ones the bundled dashboard uses (identical to the plain-JDK server);
 * the /api/v1/... routes are the documented, validated public API (see docs/openapi.yaml).
 */
@RestController
public class ApiController {
    private static final MediaType JSON_UTF8 = new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8);
    private final EngineService engine;

    public ApiController(EngineService engine) { this.engine = engine; }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(JSON_UTF8).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    // ---------------------------------------------------------------- routes used by the bundled dashboard
    @GetMapping("/api/state")
    public ResponseEntity<String> state() { return json(engine.state()); }

    @GetMapping("/api/ctl")
    public ResponseEntity<String> ctl(@RequestParam Map<String, String> query) {
        Map<String, String> q = new HashMap<>(query);
        q.remove("token");
        engine.control(q);
        return json("{\"ok\":true}");
    }

    @GetMapping("/api/events")
    public ResponseEntity<String> events() { return json(engine.events()); }

    @GetMapping("/api/runs")
    public ResponseEntity<String> runs() { return json(engine.runs()); }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() { return json(engine.health()); }

    @GetMapping("/api/report.html")
    public ResponseEntity<String> reportHtml() {
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(engine.report("html"));
    }

    @GetMapping("/api/report.csv")
    public ResponseEntity<String> reportCsv() {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ecoai-results.csv\"")
                .body(engine.report("csv"));
    }

    // ---------------------------------------------------------------- documented v1 API
    @GetMapping("/api/v1/state")
    public ResponseEntity<String> stateV1() { return json(engine.state()); }

    @GetMapping("/api/v1/events")
    public ResponseEntity<String> eventsV1() { return json(engine.events()); }

    @GetMapping("/api/v1/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("setpoint", Config.setpoint);
        m.put("waterWeight", Config.waterWeight);
        m.put("serviceLimit", Config.SLA);
        m.put("racks", Config.RACKS);
        m.put("mode", engine.mode());
        return m;
    }

    @PutMapping("/api/v1/config")
    public Map<String, Object> updateConfig(@Valid @RequestBody ConfigRequest body) {
        if (body.setpoint() != null) engine.control(Map.of("cmd", "setpoint", "val", String.valueOf(body.setpoint())));
        if (body.waterWeight() != null) engine.control(Map.of("cmd", "waterweight", "val", String.valueOf(body.waterWeight())));
        return config();
    }

    @PostMapping("/api/v1/commands")
    public ResponseEntity<Map<String, Object>> command(@Valid @RequestBody CommandRequest body) {
        if (body.cmd().equals("profile") && body.name() == null)
            throw new IllegalArgumentException("command 'profile' needs a 'name'");
        Map<String, String> q = new HashMap<>();
        q.put("cmd", body.cmd());
        if (body.rack() != null) q.put("rack", String.valueOf(body.rack()));
        if (body.name() != null) q.put("name", body.name());
        engine.control(q);
        Map<String, Object> ok = new HashMap<>();
        ok.put("accepted", true);
        ok.put("cmd", body.cmd());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ok);
    }
}
