package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.commodityrisk.ledger.fill.FillResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FillApiIT extends IntegrationTest {

  @Test
  void bookingAFillMovesContractsAndFees() {
    instrument("NGF27");
    ResponseEntity<FillResponse> r = postFill(newKey(), fill("MACRO", "NGF27", "BUY", 3, "3.512", 750, "2026-10-20"), FillResponse.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
    assertThat(r.getBody().positionAfter()).isEqualTo(3);

    Map<?, ?> book = http.getForObject("/api/books/MACRO", Map.class);
    assertThat(((Number) book.get("cashCents")).longValue()).isEqualTo(-750);
    // The clearing house holds the other side of the position, so contracts net to zero.
    Long net = jdbc.sql("select sum(amount) from posting where asset = 'NGF27'").query(Long.class).single();
    assertThat(net).isZero();
  }

  @Test
  void retryWithTheSameKeyReturnsTheOriginalResponse() {
    instrument("NGF27");
    String key = newKey();
    var body = fill("MACRO", "NGF27", "SELL", 2, "3.500", 0, "2026-10-20");
    FillResponse first = postFill(key, body, FillResponse.class).getBody();
    ResponseEntity<FillResponse> again = postFill(key, body, FillResponse.class);

    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.getBody()).isEqualTo(first);
    assertThat(jdbc.sql("select count(*) from fill").query(Long.class).single()).isEqualTo(1);
    // "3.5" is the same price as "3.500": still a replay, not a conflict.
    var sameMeaning = fill("MACRO", "ngf27", "SELL", 2, "3.5", 0, "2026-10-20");
    assertThat(postFill(key, sameMeaning, FillResponse.class).getHeaders().getFirst("Idempotent-Replayed"))
        .isEqualTo("true");
  }

  @Test
  void reusingAKeyForADifferentFillIsRejected() {
    instrument("NGF27");
    String key = newKey();
    postFill(key, fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-20"), String.class);
    ResponseEntity<Map> r = postFill(key, fill("MACRO", "NGF27", "BUY", 2, "3.500", 0, "2026-10-20"), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat((String) r.getBody().get("detail")).contains("different request");
  }

  @Test
  void rejectsBadRequests() {
    instrument("NGF27");
    var ok = fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-20");
    assertThat(postFill(null, ok, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.5005", 0, "2026-10-20"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST); // off tick
    assertThat(postFill(newKey(), fill("MACRO", "NGF27", "BUY", 0, "3.500", 0, "2026-10-20"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST); // zero quantity
    assertThat(postFill(newKey(), fill("MACRO", "XXF27", "BUY", 1, "3.500", 0, "2026-10-20"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); // unknown instrument
    assertThat(postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2027-01-15"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY); // after last trading day
    assertThat(jdbc.sql("select count(*) from fill").query(Long.class).single()).isZero();
    // A rejected request doesn't burn its key: the client can fix the cause and retry.
    String key = newKey();
    postFill(key, fill("MACRO", "NGH27", "BUY", 1, "3.500", 0, "2026-10-20"), Map.class);
    instrument("NGH27");
    assertThat(postFill(key, fill("MACRO", "NGH27", "BUY", 1, "3.500", 0, "2026-10-20"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void instrumentsComeFromTheContractRules() {
    ResponseEntity<Map> r = http.postForEntity("/api/instruments", Map.of("symbol", "CLF27"), Map.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(r.getBody()).containsEntry("multiplier", 1000).containsEntry("lastTradeDate", "2026-12-21");
    assertThat(http.postForEntity("/api/instruments", Map.of("symbol", "CLF27"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(http.postForEntity("/api/instruments", Map.of("symbol", "ZZF27"), Map.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
