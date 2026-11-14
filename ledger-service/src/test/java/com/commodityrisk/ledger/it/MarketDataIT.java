package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MarketDataIT extends IntegrationTest {
  @Test
  void emptyUploadsAreSuccessful() {
    for (String route : List.of("/api/settlements", "/api/factor-returns")) {
      var response = http.postForEntity(route, List.of(), Map.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody().get("upserted")).isEqualTo(0);
    }
  }

  @Test
  void nullRowsAreClientErrors() {
    for (String route : List.of("/api/settlements", "/api/factor-returns")) {
      var response = http.postForEntity(route, Arrays.asList((Object) null), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test
  void missingReturnsDoNotBecomeZeros() {
    var response = http.postForEntity("/api/factor-returns",
        List.of(Map.of("factor", "NG:C1", "date", "2026-10-20")), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(jdbc.sql("select count(*) from factor_return").query(Long.class).single()).isZero();
  }

  @Test
  void aBadRowRollsBackTheWholeBatch() {
    var response = http.postForEntity("/api/factor-returns", List.of(
        Map.of("factor", "NG:C1", "date", "2026-10-20", "logReturn", 0.01),
        Map.of("factor", "NG:C2", "date", "2026-10-20", "logReturn", "NaN")), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(jdbc.sql("select count(*) from factor_return").query(Long.class).single()).isZero();
  }

  @Test
  void repeatedUploadsReplaceValuesWithoutAddingRows() {
    for (double value : new double[] {0.01, 0.02}) {
      var response = http.postForEntity("/api/factor-returns", List.of(
          Map.of("factor", "NG:C1", "date", "2026-10-20", "logReturn", value)), String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(jdbc.sql("select count(*) from factor_return").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("select log_return from factor_return").query(Double.class).single()).isEqualTo(0.02);
  }
}
