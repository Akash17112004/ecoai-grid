package ecoai.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under the "ecoai." prefix in application.yml (or environment variables such as ECOAI_MODE). */
@ConfigurationProperties(prefix = "ecoai")
public class EcoAiProperties {
    /** demo = four simulated controllers side by side; live = AI backend on an MQTT broker. */
    private String mode = "demo";
    private long seed = 42;
    /** Simulation speed for demo mode (simulated seconds per wall second). */
    private double speed = 20;
    /** Directory for recorded runs (replayable). Empty = no recording. */
    private String recordDir = "";
    /** Optional CSV dataset of every telemetry sample and decision. Empty = off. */
    private String csvLog = "";
    /** If set, every request must carry this token (query ?token=, header X-Token or the cookie set on first visit). */
    private String token = "";
    private String mqttHost = "localhost";
    private int mqttPort = 1883;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }
    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }
    public String getRecordDir() { return recordDir; }
    public void setRecordDir(String recordDir) { this.recordDir = recordDir; }
    public String getCsvLog() { return csvLog; }
    public void setCsvLog(String csvLog) { this.csvLog = csvLog; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getMqttHost() { return mqttHost; }
    public void setMqttHost(String mqttHost) { this.mqttHost = mqttHost; }
    public int getMqttPort() { return mqttPort; }
    public void setMqttPort(int mqttPort) { this.mqttPort = mqttPort; }
}
