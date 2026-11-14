package com.commodityrisk.ledger.risk;

import com.commodityrisk.ledger.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RiskController {

  private final VarService var;

  public RiskController(VarService var) {
    this.var = var;
  }

  public record OptionRequest(
      @NotNull Black76.Type type,
      @NotNull @Positive Double forward,
      @NotNull @Positive Double strike,
      @NotNull @PositiveOrZero Double expiry,
      @NotNull Double rate,
      @NotNull @PositiveOrZero Double vol) {}

  @Operation(
      summary = "Black-76 price and Greeks for an option on a futures contract",
      description = "Prices per unit of the underlying ($/MMBtu for NG). expiry in years, rate and vol annualised.")
  @PostMapping("/pricing/black76")
  public Black76.Greeks black76(@Valid @RequestBody OptionRequest o) {
    try {
      return Black76.greeks(o.type(), o.forward(), o.strike(), o.expiry(), o.rate(), o.vol());
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(e.getMessage());
    }
  }

  public record ImpliedVolRequest(
      @NotNull Black76.Type type, @NotNull @Positive Double price, @NotNull @Positive Double forward,
      @NotNull @Positive Double strike, @NotNull @Positive Double expiry, @NotNull Double rate) {}

  public record ImpliedVolResponse(Double impliedVol) {}

  @Operation(summary = "Implied volatility from an option price (null if outside no-arbitrage bounds)")
  @PostMapping("/pricing/implied-vol")
  public ImpliedVolResponse impliedVol(@Valid @RequestBody ImpliedVolRequest r) {
    if (!Double.isFinite(r.price()) || !Double.isFinite(r.forward()) || !Double.isFinite(r.strike())
        || !Double.isFinite(r.expiry()) || !Double.isFinite(r.rate())) {
      throw ApiException.badRequest("pricing inputs must be finite");
    }
    double v = Black76.impliedVol(r.type(), r.price(), r.forward(), r.strike(), r.expiry(), r.rate());
    return new ImpliedVolResponse(Double.isNaN(v) ? null : v);
  }

  @Operation(summary = "Historical-simulation VaR and expected shortfall for a book's futures positions")
  @GetMapping("/books/{book}/var")
  public VarService.Result historicalVar(
      @PathVariable String book,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(defaultValue = "0.99") double confidence,
      @RequestParam(defaultValue = "500") int lookbackDays,
      @RequestParam(defaultValue = "1") int horizonDays) {
    return var.historicalVar(book, asOf, confidence, lookbackDays, horizonDays);
  }
}
