package ecoai.boot;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-sent events: GET /api/v1/stream pushes the full dashboard state once a second. */
@RestController
public class StreamController {
    private final EngineService engine;
    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public StreamController(EngineService engine) { this.engine = engine; }

    @GetMapping(value = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter e = new SseEmitter(0L);          // no timeout: the client closes the stream
        clients.add(e);
        e.onCompletion(() -> clients.remove(e));
        e.onTimeout(() -> clients.remove(e));
        e.onError(t -> clients.remove(e));
        return e;
    }

    @Scheduled(fixedRate = 1000)
    public void push() {
        if (clients.isEmpty()) return;
        String state = engine.state();
        for (SseEmitter e : clients) {
            try { e.send(SseEmitter.event().name("state").data(state)); }
            catch (IOException | IllegalStateException ex) { clients.remove(e); }
        }
    }
}
