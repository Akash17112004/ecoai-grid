package ecoai.boot;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Body of PUT /api/v1/config: live-tune the optimiser. Missing fields are left unchanged. */
public record ConfigRequest(
        @DecimalMin("34.0") @DecimalMax("39.0") Double setpoint,
        @DecimalMin("0.0") @DecimalMax("60.0") Double waterWeight) {
}
