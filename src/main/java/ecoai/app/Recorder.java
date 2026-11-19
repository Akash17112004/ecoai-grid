package ecoai.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Saves a run as gzip-compressed JSON lines, one dashboard state frame every few simulated seconds, so a run can be
 * replayed later with the same dashboard ("replay" mode). Frames are flushed as they are written, so a crash loses
 * at most the last frame.
 */
public final class Recorder implements AutoCloseable {
    public static final double FRAME_S = 10;

    private final Path file;
    private final GZIPOutputStream out;
    private double nextAt = FRAME_S;
    private int frames;

    public Recorder(Path dir, String label) throws IOException {
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        file = dir.resolve("run-" + stamp + "-" + label.replaceAll("[^A-Za-z0-9_-]", "") + ".jsonl.gz");
        OutputStream raw = Files.newOutputStream(file);
        out = new GZIPOutputStream(raw, 1 << 16, true);       // syncFlush: every frame is readable immediately
    }

    public Path file() { return file; }
    public int frames() { return frames; }

    /** True when the simulated clock has passed the next frame time. */
    public boolean due(double simT) { return simT >= nextAt; }

    public synchronized void write(double simT, String stateJson) {
        try {
            out.write(stateJson.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            frames++;
            nextAt = simT + FRAME_S;
        } catch (IOException e) {
            System.err.println("recorder: " + e.getMessage());
        }
    }

    @Override public synchronized void close() {
        try { out.close(); } catch (IOException e) { System.err.println("recorder: " + e.getMessage()); }
    }
}
