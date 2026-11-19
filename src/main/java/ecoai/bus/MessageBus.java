package ecoai.bus;

import java.util.function.BiConsumer;

/** Publish/subscribe transport. Implemented in-memory (demo) and over a real MQTT broker (hardware). */
public interface MessageBus extends AutoCloseable {

    void publish(String topic, String payload);

    /** filter supports MQTT wildcards '+' (one level) and '#' (rest). Handler receives (topic, payload). */
    void subscribe(String filter, BiConsumer<String, String> handler);

    /** Simulated-time hook (only the in-memory bus uses it, for deterministic packet loss). */
    default void setTime(double t) {}

    default boolean isConnected() { return true; }

    @Override
    default void close() {}

    static boolean match(String filter, String topic) {
        String[] a = filter.split("/", -1), b = topic.split("/", -1);
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("#")) return true;
            if (i >= b.length) return false;
            if (!a[i].equals("+") && !a[i].equals(b[i])) return false;
        }
        return a.length == b.length;
    }
}
