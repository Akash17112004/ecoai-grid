package ecoai.bus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** In-process broker with deterministic WiFi packet loss, used for the all-in-one demo and batch runs. */
public final class InMemoryBus implements MessageBus {
    private record Sub(String filter, BiConsumer<String, String> cb) {}

    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final long seed;
    private final String logFilter;
    private double now;

    public volatile double loss = 0.01;
    public long sent, dropped;

    /** @param logFilter keep a rolling log of messages whose topic contains this text (null = none) */
    public InMemoryBus(long seed, String logFilter) { this.seed = seed; this.logFilter = logFilter; }

    @Override public void setTime(double t) { now = t; }

    @Override public void subscribe(String filter, BiConsumer<String, String> cb) { subs.add(new Sub(filter, cb)); }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private boolean lost(String topic) {
        long h = mix(seed ^ mix(topic.hashCode()) ^ mix((long) now * 1315423911L));
        return (h >>> 11) * 0x1.0p-53 < loss;
    }

    @Override public void publish(String topic, String payload) {
        sent++;
        if (lost(topic)) { dropped++; return; }
        if (logFilter != null && topic.contains(logFilter)) {
            log.addLast(String.format(Locale.ROOT, "[%6.0fs] %s %s", now, topic, payload));
            while (log.size() > 8) log.removeFirst();
        }
        for (Sub s : subs) if (MessageBus.match(s.filter(), topic)) s.cb().accept(topic, payload);
    }

    public List<String> recentLog() { return new ArrayList<>(log); }
}
