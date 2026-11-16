package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class EodIT extends IntegrationTest {

  private ResponseEntity<Map> eod(String book, String date) {
    return http.postForEntity("/api/books/" + book + "/eod/" + date, null, Map.class);
  }

  private static long cents(Map<?, ?> m, String key) {
    return ((Number) m.get(key)).longValue();
  }

  /**
   * A worked example, checkable by hand.
   *
   * <pre>
   * Day 1: buy 2 NGF27 at 3.500, settles 3.550  ->  2 x (3.550 - 3.500) x 10,000 = +$1,000
   * Day 2: sell 1 at 3.600,      settles 3.520  ->  carried 2 x (3.520 - 3.550) x 10,000 = -$600
   *                                                 sold   -1 x (3.520 - 3.600) x 10,000 = +$800
   *                                                 day 2 total                          = +$200
   * Total +$1,200 = realised (sold at 3.600, bought at 3.500: +$1,000)
   *               + unrealised (1 left, worth 3.520 vs 3.500: +$200).
   * </pre>
   */
  @Test
  void variationMarginAddsUpToRealisedPlusUnrealisedPnl() {
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 2, "3.500", 300, "2026-10-19"), String.class);
    settle("NGF27", "2026-10-19", "3.550");
    Map<?, ?> day1 = eod("MACRO", "2026-10-19").getBody();
    assertThat(cents(day1, "totalCents")).isEqualTo(100_000);

    postFill(newKey(), fill("MACRO", "NGF27", "SELL", 1, "3.600", 150, "2026-10-20"), String.class);
    settle("NGF27", "2026-10-20", "3.520");
    Map<?, ?> day2 = eod("MACRO", "2026-10-20").getBody();
    assertThat(cents(day2, "totalCents")).isEqualTo(20_000);
    Map<?, ?> line = (Map<?, ?>) ((List<?>) day2.get("lines")).get(0);
    assertThat(cents(line, "carriedQuantity")).isEqualTo(2);
    assertThat(cents(line, "endQuantity")).isEqualTo(1);

    List<Map<String, Object>> pnl = http.getForObject("/api/books/MACRO/pnl", List.class);
    assertThat(pnl).hasSize(2);
    assertThat(cents(pnl.get(0), "netCents")).isEqualTo(100_000 - 300);
    assertThat(cents(pnl.get(1), "cumulativeCents")).isEqualTo(120_000 - 450);
    // Cash = variation margin received minus fees paid.
    assertThat(cents(http.getForObject("/api/books/MACRO", Map.class), "cashCents")).isEqualTo(120_000 - 450);
  }

  @Test
  void runningTwiceReturnsTheFirstResult() {
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-19"), String.class);
    settle("NGF27", "2026-10-19", "3.510");
    Map<?, ?> first = eod("MACRO", "2026-10-19").getBody();
    Map<?, ?> second = eod("MACRO", "2026-10-19").getBody();
    assertThat(first.get("replayed")).isEqualTo(false);
    assertThat(second.get("replayed")).isEqualTo(true);
    assertThat(second.get("totalCents")).isEqualTo(first.get("totalCents"));
    assertThat(jdbc.sql("select count(*) from journal_entry where entry_type = 'VARIATION_MARGIN'").query(Long.class).single())
        .isEqualTo(1);
  }

  @Test
  void refusesToSettleWithoutPricesOrOutOfOrder() {
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-19"), String.class);
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-20"), String.class);

    ResponseEntity<Map> noPrice = eod("MACRO", "2026-10-19");
    assertThat(noPrice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat((String) noPrice.getBody().get("detail")).contains("missing settlement prices");

    settle("NGF27", "2026-10-20", "3.520");
    ResponseEntity<Map> skipped = eod("MACRO", "2026-10-20");
    assertThat(skipped.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat((String) skipped.getBody().get("detail")).contains("2026-10-19 first");
  }

  @Test
  void settledDatesAreClosedToNewFills() {
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-19"), String.class);
    settle("NGF27", "2026-10-19", "3.510");
    eod("MACRO", "2026-10-19");
    ResponseEntity<Map> late = postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.500", 0, "2026-10-19"), Map.class);
    assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void openPositionPastExpiryIsRefused() {
    instrument("NGX26"); // Nov 2026, last trade 2026-10-28
    postFill(newKey(), fill("MACRO", "NGX26", "BUY", 1, "3.000", 0, "2026-10-28"), String.class);
    settle("NGX26", "2026-10-28", "3.010");
    assertThat(eod("MACRO", "2026-10-28").getStatusCode()).isEqualTo(HttpStatus.OK);
    settle("NGX26", "2026-10-29", "3.010");
    ResponseEntity<Map> after = eod("MACRO", "2026-10-29");
    assertThat(after.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat((String) after.getBody().get("detail")).contains("last trading day");
  }
}
