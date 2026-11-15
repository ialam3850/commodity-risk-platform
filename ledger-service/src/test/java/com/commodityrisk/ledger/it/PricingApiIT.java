package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PricingApiIT extends IntegrationTest {
  private Map<String, Object> option() {
    return new HashMap<>(Map.of("type", "CALL", "forward", 3., "strike", 3.2,
        "expiry", 0.5, "rate", 0.04, "vol", 0.6));
  }

  @Test
  void omittedRequiredValuesAreClientErrors() {
    for (String field : List.of("type", "forward", "strike", "expiry", "rate", "vol")) {
      var body = option();
      body.remove(field);
      assertThat(http.postForEntity("/api/pricing/black76", body, String.class).getStatusCode())
          .as(field).isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void zeroExpiryAndZeroVolatilityAreStillAccepted() {
    var body = option();
    body.put("forward", 4.);
    body.put("strike", 3.);
    body.put("expiry", 0.);
    body.put("vol", 0.);
    var response = http.postForEntity("/api/pricing/black76", body, Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) response.getBody().get("price")).doubleValue()).isEqualTo(1);
  }

  @Test
  void nonFiniteRatesAndMissingIvInputsAreClientErrors() {
    var body = option();
    body.put("rate", "NaN");
    assertThat(http.postForEntity("/api/pricing/black76", body, String.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    body.remove("vol");
    body.put("price", 0.2);
    body.remove("expiry");
    assertThat(http.postForEntity("/api/pricing/implied-vol", body, String.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
