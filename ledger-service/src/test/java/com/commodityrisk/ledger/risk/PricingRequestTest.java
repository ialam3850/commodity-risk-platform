package com.commodityrisk.ledger.risk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RiskController.class)
@Import(PricingRequestTest.Config.class)
class PricingRequestTest {
  @TestConfiguration
  static class Config {
    @Bean VarService varService() { return new VarService(null, null); }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private Map<String, Object> option() {
    return new HashMap<>(Map.of("type", "CALL", "forward", 3., "strike", 3.2,
        "expiry", 0.5, "rate", 0.04, "vol", 0.6));
  }

  @Test
  void requiredValuesCannotDefaultToZero() throws Exception {
    for (String field : List.of("type", "forward", "strike", "expiry", "rate", "vol")) {
      var body = option();
      body.remove(field);
      mvc.perform(post("/api/pricing/black76").contentType("application/json").content(json.writeValueAsString(body)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void explicitZeroTimeAndVolatilityAreAccepted() throws Exception {
    var body = option();
    body.put("expiry", 0.);
    body.put("vol", 0.);
    mvc.perform(post("/api/pricing/black76").contentType("application/json").content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  @Test
  void nonFiniteRateIsAClientError() throws Exception {
    var body = option();
    body.put("rate", "NaN");
    mvc.perform(post("/api/pricing/black76").contentType("application/json").content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }
}
