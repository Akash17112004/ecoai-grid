package ecoai.app;

import ecoai.util.Json;
import ecoai.web.DashboardServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Replays a saved run (see {@link Recorder}) through the normal dashboard: play, pause, speed and a scrub bar.
 * Point it at a file, or at a directory to pick the newest run and switch between runs from the page.
 */
public final class ReplayApp implements DashboardServer.Api {
    private final Path dir;
    private final Object lock = new Object();
    private List<String> frames = List.of();
    private String current = "";
    private double cursor;                       // frame index (fractional)
    private volatile double speed = 10;          // simulated seconds per wall second
    private volatile boolean paused;
    private long lastNs = System.nanoTime();

    public ReplayApp(Path fileOrDir) throws IOException {
        if (Files.isDirectory(fileOrDir)) {
            dir = fileOrDir;
            List<Path> runs = list();
            if (runs.isEmpty()) throw new IOException("no saved runs in " + fileOrDir + " (start the demo with --record " + fileOrDir + ")");
            load(runs.get(0));
        } else {
            dir = fileOrDir.toAbsolutePath().getParent();
            load(fileOrDir);
        }
    }

    private List<Path> list() throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
        }
    }

    private void load(Path file) throws IOException {
        List<String> l = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            try { while ((line = r.readLine()) != null) if (line.startsWith("{") && line.endsWith("}")) l.add(line); }
            catch (java.io.EOFException | java.util.zip.ZipException truncated) { /* run still being written or cut short: use what is there */ }
        }
        if (l.isEmpty()) throw new IOException(file.getFileName() + " has no frames");
        synchronized (lock) { frames = l; current = file.getFileName().toString(); cursor = 0; lastNs = System.nanoTime(); }
    }

    private void advance() {
        long n = System.nanoTime();
        double dt = (n - lastNs) / 1e9;
        lastNs = n;
        if (!paused) cursor = Math.min(frames.size() - 1, cursor + dt * speed / Recorder.FRAME_S);
    }

    @Override public String state() {
        synchronized (lock) {
            advance();
            String fr = frames.get((int) cursor);
            double t = Json.num(fr, "t", 0);
            String info = String.format(Locale.ROOT, "{\"mode\":\"replay\",\"replay\":{\"file\":%s,\"idx\":%d,\"n\":%d,\"frac\":%.4f,\"end\":%s},\"paused\":%s,\"speed\":%.0f,",
                    Json.quote(current), (int) cursor, frames.size(), frames.size() > 1 ? cursor / (frames.size() - 1) : 1.0,
                    cursor >= frames.size() - 1, paused, speed);
            // drop the frame's own mode / paused / speed / simControls fields; keep everything else untouched
            String rest = fr.substring(1)
                    .replaceFirst("\"mode\":\"[a-z]*\",", "")
                    .replaceFirst("\"t\":[-0-9.]+,", "\"t\":" + String.format(Locale.ROOT, "%.3f", t) + ",")
                    .replaceFirst("\"speed\":[-0-9.]+,", "")
                    .replaceFirst("\"paused\":(true|false),", "")
                    .replace("\"simControls\":true", "\"simControls\":false");
            return info + rest;
        }
    }

    @Override public void control(Map<String, String> q) {
        synchronized (lock) {
            advance();
            switch (q.getOrDefault("cmd", "")) {
                case "speed" -> speed = Math.max(1, Math.min(1000, DashboardServer.num(q.get("val"), 10)));
                case "pause" -> paused = !paused;
                case "seek" -> cursor = Math.max(0, Math.min(frames.size() - 1, DashboardServer.num(q.get("val"), 0) * (frames.size() - 1)));
                case "load" -> {
                    String name = q.getOrDefault("name", "");
                    try {
                        for (Path p : list()) if (p.getFileName().toString().equals(name)) { load(p); return; }
                    } catch (IOException e) { System.err.println("replay: " + e.getMessage()); }
                }
                default -> { }
            }
        }
    }

    @Override public String runs() {
        StringBuilder sb = new StringBuilder("[");
        try {
            boolean first = true;
            for (Path p : list()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(Json.quote(p.getFileName().toString())).append(",\"bytes\":").append(Files.size(p))
                  .append(",\"current\":").append(p.getFileName().toString().equals(current)).append('}');
            }
        } catch (IOException e) { /* return what we have */ }
        return sb.append(']').toString();
    }

    @Override public String events() {
        synchronized (lock) {
            String fr = frames.get((int) cursor);
            int i = fr.indexOf("\"events\":");
            if (i < 0) return "[]";
            int s = fr.indexOf('[', i), depth = 0, e = s;
            for (; e < fr.length(); e++) {
                char c = fr.charAt(e);
                if (c == '[') depth++; else if (c == ']' && --depth == 0) break;
            }
            return fr.substring(s, e + 1);
        }
    }

    public void run(int port, boolean lan, String token) throws IOException {
        new DashboardServer(port, lan, token, this).start();
        System.out.println("EcoAI Grid REPLAY of " + current + " (" + frames.size() + " frames) at http://localhost:" + port + "  - Ctrl+C to stop");
    }
}
