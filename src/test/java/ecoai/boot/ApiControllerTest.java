package ecoai.boot;

import ecoai.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API contract tests against the running Spring context (demo mode, nothing external needed). */
@SpringBootTest(properties = {"ecoai.mode=demo", "ecoai.record-dir=", "ecoai.speed=200"})
@AutoConfigureMockMvc
class ApiControllerTest {
    @Autowired MockMvc mvc;

    /** Config is intentionally live-tunable and static in the shared control engine. */
    @AfterEach
    void restoreLiveTunables() {
        Config.setpoint = 38.0;
        Config.waterWeight = 15.0;
    }

    @Test void dashboardPageIsServed() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("EcoAI Grid")));
    }

    @Test void stateHasFourControllersAndHistory() throws Exception {
        mvc.perform(get("/api/v1/state")).andExpect(status().isOk())
           .andExpect(jsonPath("$.sc.length()").value(4))
           .andExpect(jsonPath("$.sla").value(40.0));
    }

    @Test void configCanBeTunedWithinBounds() throws Exception {
        mvc.perform(put("/api/v1/config").contentType(MediaType.APPLICATION_JSON).content("{\"setpoint\":37.5}"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.setpoint").value(37.5));
    }

    @Test void configRejectsOutOfRangeValues() throws Exception {
        mvc.perform(put("/api/v1/config").contentType(MediaType.APPLICATION_JSON).content("{\"setpoint\":55}"))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.setpoint").exists());
    }

    @Test void unknownCommandIsRejected() throws Exception {
        mvc.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"cmd\":\"selfdestruct\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test void faultCommandIsAccepted() throws Exception {
        mvc.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"cmd\":\"fanfail\",\"rack\":1}"))
           .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"cmd\":\"clear\"}"))
           .andExpect(status().isAccepted());
    }

    @Test void profileCommandNeedsAName() throws Exception {
        mvc.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"cmd\":\"profile\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test void reportsAreAvailable() throws Exception {
        mvc.perform(get("/api/report.csv")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.startsWith("controller,")));
        mvc.perform(get("/api/report.html")).andExpect(status().isOk());
    }

    @Test void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
