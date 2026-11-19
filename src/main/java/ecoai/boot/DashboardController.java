package ecoai.boot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Serves the single-page dashboard (the same page the plain-JDK server serves). */
@RestController
public class DashboardController {
    private volatile String page;

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> index() throws IOException {
        if (page == null) {
            try (InputStream in = new ClassPathResource("web/index.html").getInputStream()) {
                page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(page);
    }
}
