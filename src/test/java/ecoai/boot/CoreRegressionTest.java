package ecoai.boot;

import ecoai.app.HeadlessRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same eight regression checks as "java -jar ecoai-grid.jar selftest", run by "mvn test". */
class CoreRegressionTest {
    @Test void aiControllerStillMeetsItsClaims() {
        assertTrue(HeadlessRun.selfTest(42), "self-test failed: see console output for the failing check");
    }
}
