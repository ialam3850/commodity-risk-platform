package com.commodityrisk.ledger.fill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fills")
public class FillController {

  private final FillService fills;
  private final JdbcClient jdbc;

  public FillController(FillService fills, JdbcClient jdbc) {
    this.fills = fills;
    this.jdbc = jdbc;
  }

  @Operation(
      summary = "Record a fill",
      description =
          "Send a unique Idempotency-Key with each new fill. Retrying with the same key and body"
              + " returns the original response (header Idempotent-Replayed: true) without booking"
              + " the fill again; the same key with a different body is rejected with 422.")
  @PostMapping
  public ResponseEntity<FillResponse> create(
      @Parameter(description = "Client-generated unique key, e.g. a UUID") @RequestHeader("Idempotency-Key")
          String key,
      @Valid @RequestBody FillRequest request) {
    FillService.Result r = fills.record(key, request);
    return ResponseEntity.status(r.status())
        .header("Idempotent-Replayed", Boolean.toString(r.replayed()))
        .body(r.body());
  }

  public record FillRow(
      long id,
      String book,
      String symbol,
      String side,
      int quantity,
      BigDecimal price,
      long feeCents,
      LocalDate tradeDate,
      Instant executedAt) {}

  @GetMapping
  public List<FillRow> list(@RequestParam String book, @RequestParam(defaultValue = "100") int limit) {
    return jdbc.sql(
            """
            select id, book, symbol, side, quantity, price, fee_cents, trade_date, executed_at
              from fill where book = ? order by id desc limit ?
            """)
        .params(book, Math.min(Math.max(limit, 1), 1000))
        .query(FillRow.class)
        .list();
  }
}
