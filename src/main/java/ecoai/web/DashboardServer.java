package ecoai.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves the dashboard page plus the JSON API. Localhost-only unless lan=true; with a token set, every request must
 * carry it (query ?token=, header X-Token, or the cookie set on the first visit to /?token=...).
 */
public final class DashboardServer {
    public interface Api {
        String state();
        void control(Map<String, String> query);
        /** Newest-first operator events as a JSON array, or null if the mode has none. */
        default String events() { return null; }
        /** "html" (printable report) or "csv" (results table); null if unsupported. */
        default String report(String format) { return null; }
        /** Saved runs as a JSON array, or null. */
        default String runs() { return null; }
        default String health() { return "{\"status\":\"UP\"}"; }
    }

    private final HttpServer server;
    private final String token;

    public DashboardServer(int port, boolean lan, Api api) throws IOException { this(port, lan, null, api); }

    public DashboardServer(int port, boolean lan, String token, Api api) throws IOException {
        this.token = (token == null || token.isBlank()) ? null : token;
        server = HttpServer.create(lan ? new InetSocketAddress(port) : new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", guard(ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/") || p.equals("/index.html")) {
                try (InputStream in = DashboardServer.class.getResourceAsStream("/web/index.html")) {
                    if (in == null) respond(ex, 500, "text/plain", "dashboard resource missing (build copies src/main/resources)");
                    else respond(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } else respond(ex, 404, "text/plain", "not found");
        }));
        server.createContext("/fonts/", guard(ex -> {
            String name = ex.getRequestURI().getPath().substring("/fonts/".length());
            if (!name.matches("[A-Za-z0-9._-]+\\.woff2")) { respond(ex, 404, "text/plain", "not found"); return; }   // no path traversal
            try (InputStream in = DashboardServer.class.getResourceAsStream("/web/fonts/" + name)) {
                if (in == null) { respond(ex, 404, "text/plain", "not found"); return; }
                byte[] b = in.readAllBytes();
                ex.getResponseHeaders().set("Content-Type", "font/woff2");
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
        }));
        server.createContext("/api/state", guard(ex -> respond(ex, 200, "application/json; charset=utf-8", api.state())));
        server.createContext("/api/health", guard(ex -> respond(ex, 200, "application/json", api.health())));
        server.createContext("/api/events", guard(ex -> {
            String j = api.events();
            if (j == null) respond(ex, 404, "text/plain", "not available in this mode");
            else respond(ex, 200, "application/json; charset=utf-8", j);
        }));
        server.createContext("/api/runs", guard(ex -> {
            String j = api.runs();
            if (j == null) respond(ex, 404, "text/plain", "not available in this mode");
            else respond(ex, 200, "application/json; charset=utf-8", j);
        }));
        server.createContext("/api/report.html", guard(ex -> report(ex, api, "html", "text/html; charset=utf-8")));
        server.createContext("/api/report.csv", guard(ex -> report(ex, api, "csv", "text/csv; charset=utf-8")));
        server.createContext("/api/ctl", guard(ex -> {
            Map<String, String> q = query(ex);
            q.remove("token");
            api.control(q);
            respond(ex, 200, "application/json", "{\"ok\":true}");
        }));
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    private static void report(HttpExchange ex, Api api, String fmt, String type) throws IOException {
        String body = api.report(fmt);
        if (body == null) { respond(ex, 404, "text/plain", "not available in this mode"); return; }
        if (fmt.equals("csv")) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"ecoai-results.csv\"");
        respond(ex, 200, type, body);
    }

    private HttpHandler guard(HttpHandler inner) {
        return ex -> {
            try {
                if (token != null && !authorised(ex)) {
                    respond(ex, 401, "text/plain; charset=utf-8", "Access token required. Open the page as /?token=<token> once.");
                    return;
                }
                inner.handle(ex);
            } catch (RuntimeException e) {
                respond(ex, 500, "text/plain", "internal error");
                e.printStackTrace();
            }
        };
    }

    private boolean authorised(HttpExchange ex) {
        String got = query(ex).get("token");
        if (got == null) got = ex.getRequestHeaders().getFirst("X-Token");
        if (got == null) {
            String c = ex.getRequestHeaders().getFirst("Cookie");
            if (c != null) for (String part : c.split(";")) {
                String s = part.trim();
                if (s.startsWith("eco_token=")) got = URLDecoder.decode(s.substring(10), StandardCharsets.UTF_8);
            }
        }
        if (got == null || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) return false;
        if (query(ex).get("token") != null) {
            ex.getResponseHeaders().add("Set-Cookie", "eco_token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "; Path=/; HttpOnly; SameSite=Strict");
        }
        return true;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) q.put(URLDecoder.decode(kv.substring(0, e), StandardCharsets.UTF_8),
                             URLDecoder.decode(kv.substring(e + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    public void start() { server.start(); }
    public void stop() { server.stop(0); }

    private static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    public static double num(String s, double def) {
        try { return s == null ? def : Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }
}
