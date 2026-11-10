package com.commodityrisk.ledger.instrument;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A futures contract: one root (NG) and one delivery month (Jan 2027). */
public record Instrument(
    String symbol,
    String root,
    LocalDate contractMonth,
    long multiplier,
    BigDecimal tickSize,
    LocalDate lastTradeDate) {

  /** True if {@code price} is a whole number of ticks. */
  public boolean onTick(BigDecimal price) {
    return price.remainder(tickSize).signum() == 0;
  }

  /**
   * Dollar value, in cents, of {@code quantity} contracts moving from {@code from} to {@code to}:
   * quantity x (to - from) x multiplier x 100. Exact: tick-aligned prices times the multiplier are
   * always whole cents for the products here, and anything else throws rather than rounds.
   */
  public long valueChangeCents(long quantity, BigDecimal from, BigDecimal to) {
    BigDecimal cents =
        to.subtract(from)
            .multiply(BigDecimal.valueOf(quantity))
            .multiply(BigDecimal.valueOf(multiplier))
            .multiply(BigDecimal.valueOf(100));
    return cents.stripTrailingZeros().longValueExact(); // ArithmeticException if fractional cents
  }
}
