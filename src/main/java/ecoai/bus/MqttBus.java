package ecoai.bus;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Minimal MQTT 3.1.1 client (QoS 0 publish/subscribe, clean session, keep-alive, auto-reconnect).
 * Works with Mosquitto and any standard broker; no external library needed.
 */
public final class MqttBus implements MessageBus {
    private record Sub(String filter, BiConsumer<String, String> cb) {}

    private static final int KEEP_ALIVE_S = 30;
    private static final String BROKER_FILTER = "ecoai/#";      // the project's whole topic namespace

    private final String host, clientId;
    private final int port;
    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private final AtomicLong sent = new AtomicLong(), dropped = new AtomicLong(), received = new AtomicLong();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-ping"); t.setDaemon(true); return t;
    });
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile boolean running = true, connected;
    private int packetId = 1;

    public MqttBus(String host, int port, String clientId) {
        this.host = host; this.port = port; this.clientId = clientId;
        Thread rx = new Thread(this::loop, "mqtt-rx");
        rx.setDaemon(true);
        rx.start();
        pinger.scheduleAtFixedRate(this::ping, KEEP_ALIVE_S / 2, KEEP_ALIVE_S / 2, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ public API
    @Override public boolean isConnected() { return connected; }
    public long sent() { return sent.get(); }
    public long dropped() { return dropped.get(); }
    public long received() { return received.get(); }

    /**
     * Handlers are routed locally. The broker only ever sees ONE subscription (ecoai/#): with several overlapping
     * broker-side subscriptions a broker may deliver the same message once per match, which would make a handler run twice.
     */
    @Override public void subscribe(String filter, BiConsumer<String, String> cb) {
        subs.add(new Sub(filter, cb));
    }

    @Override public void publish(String topic, String payload) {
        if (!connected) { dropped.incrementAndGet(); return; }
        try {
            byte[] t = topic.getBytes(StandardCharsets.UTF_8), p = payload.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream b = new ByteArrayOutputStream(t.length + p.length + 2);
            b.write(t.length >> 8); b.write(t.length & 0xff); b.write(t); b.write(p);
            send(0x30, b.toByteArray());
            sent.incrementAndGet();
        } catch (IOException e) {
            dropped.incrementAndGet();
            kill();
        }
    }

    @Override public void close() {
        running = false;
        try { if (connected) send(0xE0, new byte[0]); } catch (IOException ignored) { }
        kill();
        pinger.shutdownNow();
    }

    // ------------------------------------------------------------------ connection loop
    private void loop() {
        while (running) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 4000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(KEEP_ALIVE_S * 1500);
                InputStream in = s.getInputStream();
                sock = s; out = s.getOutputStream();
                sendConnect();
                int h = in.read();
                if (h != 0x20) throw new IOException("expected CONNACK, got " + h);
                byte[] ack = in.readNBytes(readLen(in));
                if (ack.length < 2 || ack[1] != 0) throw new IOException("broker refused connection, rc=" + (ack.length > 1 ? ack[1] : -1));
                connected = true;
                System.out.println("[mqtt] connected to " + host + ":" + port);
                sendSubscribe(BROKER_FILTER);
                readLoop(in);
            } catch (Exception e) {
                if (running) System.err.println("[mqtt] " + host + ":" + port + " - " + e.getMessage() + " (retrying)");
            }
            connected = false; out = null; sock = null;
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void readLoop(InputStream in) throws IOException {
        while (running) {
            int h = in.read();
            if (h < 0) throw new EOFException("broker closed the connection");
            byte[] body = in.readNBytes(readLen(in));
            if ((h >> 4) == 3) deliver(h, body);          // PUBLISH; SUBACK / PINGRESP need no action
        }
    }

    private void deliver(int header, byte[] b) throws IOException {
        int qos = (header >> 1) & 3;
        int tl = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        String topic = new String(b, 2, tl, StandardCharsets.UTF_8);
        int off = 2 + tl;
        if (qos > 0) {
            int id = ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
            off += 2;
            if (qos == 1) send(0x40, new byte[]{(byte) (id >> 8), (byte) id});
        }
        String payload = new String(b, off, b.length - off, StandardCharsets.UTF_8);
        received.incrementAndGet();
        for (Sub s : subs) {
            if (!MessageBus.match(s.filter(), topic)) continue;
            try { s.cb().accept(topic, payload); }
            catch (RuntimeException e) { System.err.println("[mqtt] handler error on " + topic + ": " + e); }
        }
    }

    // ------------------------------------------------------------------ packets
    private void sendConnect() throws IOException {
        byte[] id = clientId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); b.write(4); b.write('M'); b.write('Q'); b.write('T'); b.write('T');
        b.write(4);                         // protocol level 3.1.1
        b.write(0x02);                      // clean session
        b.write(KEEP_ALIVE_S >> 8); b.write(KEEP_ALIVE_S & 0xff);
        b.write(id.length >> 8); b.write(id.length & 0xff); b.write(id);
        send(0x10, b.toByteArray());
    }

    private void sendSubscribe(String filter) throws IOException {
        byte[] f = filter.getBytes(StandardCharsets.UTF_8);
        int id;
        synchronized (this) { id = packetId++; if (packetId > 65535) packetId = 1; }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id >> 8); b.write(id & 0xff);
        b.write(f.length >> 8); b.write(f.length & 0xff); b.write(f);
        b.write(0);                         // requested QoS 0
        send(0x82, b.toByteArray());
    }

    private void ping() {
        if (!connected) return;
        try { send(0xC0, new byte[0]); } catch (IOException e) { kill(); }
    }

    private void send(int header, byte[] body) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        ByteArrayOutputStream p = new ByteArrayOutputStream(body.length + 5);
        p.write(header);
        int len = body.length;
        do { int d = len % 128; len /= 128; if (len > 0) d |= 0x80; p.write(d); } while (len > 0);
        p.write(body);
        synchronized (writeLock) { o.write(p.toByteArray()); o.flush(); }
    }

    private static int readLen(InputStream in) throws IOException {
        int mul = 1, val = 0, d;
        do {
            d = in.read();
            if (d < 0) throw new EOFException();
            val += (d & 127) * mul;
            mul *= 128;
        } while ((d & 128) != 0 && mul <= 128 * 128 * 128 * 128);
        return val;
    }

    private void kill() {
        connected = false;
        Socket s = sock;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
