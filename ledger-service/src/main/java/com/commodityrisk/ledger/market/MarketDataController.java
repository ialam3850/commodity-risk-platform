package com.commodityrisk.ledger.market;

import com.commodityrisk.ledger.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement prices (for marking positions) and risk-factor returns (for historical VaR). Loaded in
 * bulk by research/scripts/load_ledger.py; uploads are upserts, so re-loading is harmless.
 */
@RestController
@RequestMapping("/api")
public class MarketDataController {

  private final JdbcClient jdbc;

  public MarketDataController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Settlement(
      @NotBlank String symbol, @NotNull LocalDate date, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price) {}

  public record FactorReturn(@NotBlank String factor, @NotNull LocalDate date, @NotNull Double logReturn) {}

  @Operation(summary = "Upsert daily settlement prices")
  @PostMapping("/settlements")
  @Transactional
  public Map<String, Integer> upsertSettlements(@RequestBody @Size(max = 50_000) List<@NotNull @Valid Settlement> rows) {
    if (rows.isEmpty()) return Map.of("upserted", 0);
    TreeSet<String> symbols = new TreeSet<>();
    rows.forEach(r -> symbols.add(r.symbol()));
    List<String> known =
        jdbc.sql("select symbol from instrument where symbol in (:s)").param("s", symbols).query(String.class).list();
    symbols.removeAll(known);
    if (!symbols.isEmpty()) throw ApiException.unprocessable("unknown instruments: " + symbols);
    for (Settlement r : rows) {
      jdbc.sql(
              """
              insert into settlement_price (symbol, business_date, price) values (?, ?, ?)
              on conflict (symbol, business_date) do update set price = excluded.price
              """)
          .params(r.symbol(), r.date(), r.price())
          .update();
    }
    return Map.of("upserted", rows.size());
  }

  public record SettlementRow(String symbol, LocalDate businessDate, BigDecimal price) {}

  @GetMapping("/settlements")
  public List<SettlementRow> settlements(
      @RequestParam String symbol,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return jdbc.sql(
            """
            select symbol, business_date, price from settlement_price
             where symbol = ? and business_date between ? and ? order by business_date
            """)
        .params(symbol, from != null ? from : LocalDate.of(1900, 1, 1), to != null ? to : LocalDate.of(9999, 1, 1))
        .query(SettlementRow.class)
        .list();
  }

  @Operation(summary = "Upsert daily risk-factor log returns (e.g. factor NG:C1)")
  @PostMapping("/factor-returns")
  @Transactional
  public Map<String, Integer> upsertFactorReturns(@RequestBody @Size(max = 100_000) List<@NotNull @Valid FactorReturn> rows) {
    for (FactorReturn r : rows) {
      if (!Double.isFinite(r.logReturn())) throw ApiException.badRequest("non-finite return for " + r.factor());
      jdbc.sql(
              """
              insert into factor_return (factor, business_date, log_return) values (?, ?, ?)
              on conflict (factor, business_date) do update set log_return = excluded.log_return
              """)
          .params(r.factor(), r.date(), r.logReturn())
          .update();
    }
    return Map.of("upserted", rows.size());
  }

  public record FactorSummary(String factor, long days, LocalDate first, LocalDate last) {}

  @GetMapping("/factor-returns")
  public List<FactorSummary> factorSummary() {
    return jdbc.sql(
            """
            select factor, count(*) as days, min(business_date) as first, max(business_date) as last
              from factor_return group by factor order by factor
            """)
        .query(FactorSummary.class)
        .list();
  }
}
