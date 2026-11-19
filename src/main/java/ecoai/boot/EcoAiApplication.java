package ecoai.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot front end for EcoAI Grid. The control algorithms, simulator, MQTT client and dashboard page are the
 * same classes the plain-JDK build uses (package ecoai.*); this layer adds REST/SSE, validation, security filter,
 * health checks and configuration properties.
 */
@SpringBootApplication
@EnableConfigurationProperties(EcoAiProperties.class)
@EnableScheduling
public class EcoAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcoAiApplication.class, args);
    }
}
