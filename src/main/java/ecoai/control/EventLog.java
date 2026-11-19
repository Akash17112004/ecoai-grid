package ecoai.control;

import ecoai.util.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Thread-safe ring buffer of operator-facing events (faults, safety actions, limit breaches) with severity. */
public final class EventLog {
    public enum Severity { INFO, WARN, CRIT }

    public record Event(long seq, double t, Severity sev, int rack, String text) {
        public String toJson() {
            return "{\"seq\":" + seq + ",\"t\":" + Json.fmt(t) + ",\"sev\":" + Json.quote(sev.name().toLowerCase(Locale.ROOT))
                    + ",\"rack\":" + rack + ",\"text\":" + Json.quote(text) + "}";
        }
    }

    private final int cap;
    private final ArrayDeque<Event> ring = new ArrayDeque<>();
    private long seq;

    public EventLog(int cap) { this.cap = cap; }

    public synchronized void add(double t, Severity sev, int rack, String text) {
        ring.addLast(new Event(++seq, t, sev, rack, text));
        while (ring.size() > cap) ring.removeFirst();
    }

    /** Newest first, at most max entries. */
    public synchronized List<Event> latest(int max) {
        List<Event> out = new ArrayList<>(Math.min(max, ring.size()));
        var it = ring.descendingIterator();
        while (it.hasNext() && out.size() < max) out.add(it.next());
        return out;
    }

    public synchronized long total() { return seq; }

    public synchronized long count(Severity s) {
        long n = 0;
        for (Event e : ring) if (e.sev == s) n++;
        return n;
    }

    public void json(StringBuilder sb, int max) {
        sb.append('[');
        boolean first = true;
        for (Event e : latest(max)) { if (!first) sb.append(','); first = false; sb.append(e.toJson()); }
        sb.append(']');
    }
}
