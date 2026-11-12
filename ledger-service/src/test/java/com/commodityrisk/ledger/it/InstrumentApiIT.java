package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InstrumentApiIT extends IntegrationTest {

  @Test
  void creatingAnInstrumentPersistsItsTermsAndCanBeRepeated() {
    var body = Map.of("symbol", "CLF27");
    var first = http.postForEntity("/api/instruments", body, Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getBody()).containsEntry("multiplier", 1000);
    assertThat(http.getForObject("/api/instruments/CLF27", Map.class))
        .containsEntry("symbol", "CLF27")
        .containsEntry("lastTradeDate", "2026-12-21");
    assertThat(http.postForEntity("/api/instruments", body, Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(jdbc.sql("select count(*) from instrument").query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void anUnknownProductIsRejectedWithoutWritingAnInstrument() {
    var response = http.postForEntity("/api/instruments", Map.of("symbol", "ZZF27"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(jdbc.sql("select count(*) from instrument").query(Long.class).single()).isZero();
  }
}
