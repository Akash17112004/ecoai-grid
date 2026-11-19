package ecoai;

/** All tunables and the MQTT topic contract in one place. */
public final class Config {
    private Config() {}

    public static final int RACKS = 3;
    public static final double DT = 1.0;                       // simulation step (s) = ESP32 publish period

    // ---- rig physics (simulator only; on real hardware these come from calibration)
    public static final double C_TH = 300.0;                   // J/K thermal mass of one rack enclosure
    public static final double G0 = 0.6, GF = 3.2, GP = 4.0;   // W/K: passive, fan @100 %, liquid loop @100 %
    public static final double FAN_W = 6.0, PUMP_W = 8.0;      // electrical W @100 % duty (fan ~ f^3, pump ~ p^2)
    public static final double WATER_LPM = 0.05;               // coolant make-up per rack @100 % pump (L/min)
    public static final double FAN_RPM = 3000, MAX_LOAD = 52.0;

    // ---- safety limits
    public static final double SLA = 40.0;                     // service limit, C (a breach is counted above this)
    public static final double TRIP = 45.0, TRIP_RESET = 41.0; // ESP32 over-temperature load shedding
    public static final double FUSE_ON = 50.0, FUSE_OFF = 42.0;// independent hardware thermal fuse (true temp)
    public static final double WATCHDOG_S = 10.0;              // ESP32 fail-safe if the backend is silent this long
    public static final double STALE_S = 5.0;                  // backend marks a rack STALE after this silence

    // ---- controller
    public static final int HORIZON = 30, WIN = 10;            // prediction horizon / learning window (s)
    public static volatile double setpoint = 38.0;             // AI target ceiling, C (live-tunable)
    public static volatile double waterWeight = 15.0;          // AI: W-equivalent cost per unit pump duty (live-tunable)

    // ---- MQTT topic contract (see docs/MQTT_CONTRACT.md)
    public static final String TELEMETRY_FILTER = "ecoai/+/telemetry";
    public static final String SIM_CONTROL = "ecoai/sim/control";
    public static String telemetryTopic(int rack) { return "ecoai/rack" + rack + "/telemetry"; }
    public static String commandTopic(int rack) { return "ecoai/rack" + rack + "/cmd"; }
}
