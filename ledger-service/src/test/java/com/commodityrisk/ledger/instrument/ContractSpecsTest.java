package com.commodityrisk.ledger.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ContractSpecsTest {

  // Published CME last trading days.
  @ParameterizedTest
  @CsvSource({
    "NG, 2024-07, 2024-06-26",
    "NG, 2024-12, 2024-11-26", // Thanksgiving (Nov 28) is skipped
    "NG, 2025-01, 2024-12-27",
    "NG, 2025-02, 2025-01-29",
    "NG, 2025-05, 2025-04-28",
    "CL, 2024-05, 2024-04-22",
    "CL, 2025-01, 2024-12-19", // Dec 25 is a holiday: four business days before it
    "CL, 2025-02, 2025-01-21", // Jan 25 is a Saturday; MLK Day (Jan 20) is skipped
  })
  void lastTradeDateMatchesExchangeCalendar(String root, String month, String expected) {
    assertThat(ContractSpecs.lastTradeDate(root, YearMonth.parse(month))).isEqualTo(LocalDate.parse(expected));
  }

  @Test
  void parsesAndFormatsSymbols() {
    var p = ContractSpecs.parse("NGF27").orElseThrow();
    assertThat(p.root()).isEqualTo("NG");
    assertThat(p.month()).isEqualTo(YearMonth.of(2027, 1));
    assertThat(ContractSpecs.symbol("CL", YearMonth.of(2026, 12))).isEqualTo("CLZ26");
    assertThat(ContractSpecs.parse("NGA27")).isEmpty();
    assertThat(ContractSpecs.parse("ng f27")).isEmpty();
  }

  @Test
  void nearbyIndexCountsFromTheFrontMonth() {
    // On 2024-12-20 the front NG contract is Jan 2025 (expires Dec 27).
    LocalDate d = LocalDate.of(2024, 12, 20);
    assertThat(ContractSpecs.frontMonth("NG", d)).isEqualTo(YearMonth.of(2025, 1));
    assertThat(ContractSpecs.nearbyIndex("NG", YearMonth.of(2025, 1), d)).isEqualTo(1);
    assertThat(ContractSpecs.nearbyIndex("NG", YearMonth.of(2025, 3), d)).isEqualTo(3);
    // On its last trading day a contract is still the front month; the day after, it is gone.
    assertThat(ContractSpecs.nearbyIndex("NG", YearMonth.of(2025, 1), LocalDate.of(2024, 12, 27))).isEqualTo(1);
    assertThat(ContractSpecs.nearbyIndex("NG", YearMonth.of(2025, 1), LocalDate.of(2024, 12, 30))).isEqualTo(0);
  }

  @Test
  void holidaysIncludeGoodFridayAndObservedDays() {
    assertThat(ExchangeCalendar.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
    assertThat(ExchangeCalendar.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
    assertThat(ExchangeCalendar.isBusinessDay(LocalDate.of(2024, 3, 29))).isFalse(); // Good Friday
    assertThat(ExchangeCalendar.isBusinessDay(LocalDate.of(2026, 7, 3))).isFalse(); // July 4 is a Saturday
    assertThat(ExchangeCalendar.isBusinessDay(LocalDate.of(2027, 12, 31))).isFalse(); // Jan 1 2028 is a Saturday
    assertThat(ExchangeCalendar.isBusinessDay(LocalDate.of(2024, 10, 14))).isTrue(); // Columbus Day: markets open
  }

  @Test
  void valueChangeIsExactCents() {
    Instrument ng =
        new Instrument("NGF27", "NG", LocalDate.of(2027, 1, 1), 10_000, new BigDecimal("0.001"), LocalDate.of(2026, 12, 29));
    // 3 contracts x 0.001 x 10,000 MMBtu = $30 = 3,000 cents
    assertThat(ng.valueChangeCents(3, new BigDecimal("3.500"), new BigDecimal("3.501"))).isEqualTo(3_000);
    assertThat(ng.valueChangeCents(-2, new BigDecimal("3.500"), new BigDecimal("3.250"))).isEqualTo(500_000);
    assertThat(ng.onTick(new BigDecimal("3.5005"))).isFalse();
    assertThat(ng.onTick(new BigDecimal("3.50"))).isTrue();
    assertThatThrownBy(() -> ng.valueChangeCents(1, new BigDecimal("3.5"), new BigDecimal("3.5000001")))
        .isInstanceOf(ArithmeticException.class);
  }
}
