package com.commodityrisk.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RiskIT extends IntegrationTest {

  @Test
  void black76EndpointPricesAnOption() {
    Map<?, ?> g = http.postForObject(
        "/api/pricing/black76",
        Map.of("type", "CALL", "forward", 19, "strike", 19, "expiry", 0.75, "rate", 0.10, "vol", 0.28),
        Map.class);
    assertThat(((Number) g.get("price")).doubleValue()).isCloseTo(1.7011, within(5e-5));
    Map<?, ?> iv = http.postForObject(
        "/api/pricing/implied-vol",
        Map.of("type", "CALL", "price", g.get("price"), "forward", 19, "strike", 19, "expiry", 0.75, "rate", 0.10),
        Map.class);
    assertThat(((Number) iv.get("impliedVol")).doubleValue()).isCloseTo(0.28, within(1e-8));
  }

  /**
   * Long 10 NGF27 at a 3.000 settle; 100 days of NG:C3 returns -50 bp .. +49 bp (and one -10% day).
   * 99% VaR over 100 scenarios = the worst one: 10 x 10,000 x 3.000 x (e^-0.10 - 1) = -$28,549.
   */
  @Test
  void historicalVarMatchesHandCalculation() {
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 10, "3.000", 0, "2026-10-20"), String.class);
    settle("NGF27", "2026-10-20", "3.000");

    List<Map<String, Object>> returns = new ArrayList<>();
    LocalDate d = LocalDate.of(2026, 6, 1);
    for (int i = 0; i < 100; i++) {
      double r = i == 37 ? -0.10 : (i - 50) / 10_000.0;
      for (String f : List.of("NG:C1", "NG:C2", "NG:C3")) {
        returns.add(Map.of("factor", f, "date", d.plusDays(i).toString(), "logReturn", f.equals("NG:C3") ? r : 0.5));
      }
    }
    http.postForEntity("/api/factor-returns", returns, String.class);

    // On 2026-10-20 the front NG month is Nov 2026, so Jan 2027 is the third nearby: NG:C3.
    Map<?, ?> v = http.getForObject("/api/books/MACRO/var?asOf=2026-10-20&confidence=0.99", Map.class);
    Map<?, ?> exposure = (Map<?, ?>) ((List<?>) v.get("exposures")).get(0);
    assertThat(exposure.get("factor")).isEqualTo("NG:C3");
    assertThat(((Number) v.get("scenarios")).intValue()).isEqualTo(100);
    assertThat(((Number) v.get("var")).doubleValue()).isCloseTo(-10 * 10_000 * 3.0 * Math.expm1(-0.10), within(1e-6));
    Map<?, ?> worst = (Map<?, ?>) ((List<?>) v.get("worstDays")).get(0);
    assertThat(worst.get("date")).isEqualTo("2026-07-08");
  }

  @Test
  void varNeedsPositionsAndData() {
    assertThat(http.getForEntity("/api/books/EMPTY/var?asOf=2026-10-20", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    instrument("NGF27");
    postFill(newKey(), fill("MACRO", "NGF27", "BUY", 1, "3.000", 0, "2026-10-20"), String.class);
    settle("NGF27", "2026-10-20", "3.000");
    assertThat(http.getForEntity("/api/books/MACRO/var?asOf=2026-10-20", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT); // no factor returns loaded
  }
}
