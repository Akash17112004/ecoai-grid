package ecoai.boot;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Adds the control engine (and, in live mode, the MQTT broker link) to /actuator/health. */
@Component("controlEngine")
public class EngineHealthIndicator implements HealthIndicator {
    private final EngineService engine;

    public EngineHealthIndicator(EngineService engine) { this.engine = engine; }

    @Override
    public Health health() {
        Health.Builder b = engine.up() ? Health.up() : Health.down();
        return b.withDetail("mode", engine.mode()).withDetail("engine", engine.health()).build();
    }
}
