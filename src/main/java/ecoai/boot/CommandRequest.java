package ecoai.boot;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of POST /api/v1/commands: inject a fault or change the simulated workload. */
public record CommandRequest(
        @NotBlank
        @Pattern(regexp = "burst|burstall|fanfail|fanfix|stuck|unstuck|unplug|replug|partition|heal|clear|profile|pause|reset",
                 message = "unknown command")
        String cmd,
        @Min(0) @Max(2) Integer rack,
        @Pattern(regexp = "mixed|idle|office|batch|spiky|stress", message = "unknown workload profile") String name) {
}
