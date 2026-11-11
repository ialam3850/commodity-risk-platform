package com.commodityrisk.ledger.fill;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An execution reported by the broker: "book NG-MACRO bought 3 NGF27 at 3.512".
 *
 * @param tradeDate the business date the fill belongs to (it is settled at that day's close)
 * @param executedAt optional execution timestamp; defaults to the time the service receives it
 */
public record FillRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}", message = "must be 1-40 letters, digits, _ or -") String book,
    @NotBlank String symbol,
    @NotNull Side side,
    @Min(1) @Max(10_000) int quantity,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price,
    @PositiveOrZero long feeCents,
    @NotNull LocalDate tradeDate,
    Instant executedAt) {

  public enum Side {
    BUY,
    SELL
  }

  /** +quantity for a buy, -quantity for a sell. */
  public long signedQuantity() {
    return side == Side.BUY ? quantity : -quantity;
  }
}
