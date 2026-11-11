package com.commodityrisk.ledger.book;

import com.commodityrisk.ledger.ledger.LedgerRepository;
import com.commodityrisk.ledger.ledger.LedgerRepository.Posting;
import com.commodityrisk.ledger.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Read-side views of a book: positions, cash, daily P&L, journal; plus cash deposits. */
@RestController
@RequestMapping("/api/books/{book}")
public class BookController {

  private final JdbcClient jdbc;
  private final LedgerRepository ledger;

  public BookController(JdbcClient jdbc, LedgerRepository ledger) {
    this.jdbc = jdbc;
    this.ledger = ledger;
  }

  public record Position(
      String symbol, long quantity, long multiplier, BigDecimal lastSettle, LocalDate lastSettleDate, Long notionalCents) {}

  public record Summary(String book, long cashCents, List<Position> positions) {}

  @Operation(summary = "Cash balance and open positions, with the latest settlement price of each")
  @GetMapping
  public Summary summary(@PathVariable String book) {
    long cashAccount = account(book, LedgerRepository.cashCode(book));
    long positionAccount = account(book, LedgerRepository.positionCode(book));
    List<Position> positions =
        jdbc.sql(
                """
                select b.asset as symbol, b.amount as quantity, i.multiplier,
                       s.price as last_settle, s.business_date as last_settle_date
                  from balance b
                  join instrument i on i.symbol = b.asset
                  left join lateral (select price, business_date from settlement_price sp
                                      where sp.symbol = b.asset order by business_date desc limit 1) s on true
                 where b.account_id = ? and b.amount <> 0
                 order by i.root, i.contract_month
                """)
            .param(positionAccount)
            .query(
                (rs, n) -> {
                  BigDecimal settle = rs.getBigDecimal("last_settle");
                  long qty = rs.getLong("quantity");
                  long mult = rs.getLong("multiplier");
                  Long notional =
                      settle == null
                          ? null
                          : settle.multiply(BigDecimal.valueOf(qty * mult * 100)).longValue();
                  return new Position(
                      rs.getString("symbol"),
                      qty,
                      mult,
                      settle,
                      settle == null ? null : rs.getObject("last_settle_date", LocalDate.class),
                      notional);
                })
            .list();
    return new Summary(book, ledger.balance(cashAccount, LedgerRepository.USD), positions);
  }

  public record DailyPnl(LocalDate date, long variationMarginCents, long feesCents, long netCents, long cumulativeCents) {}

  @Operation(
      summary = "Daily P&L",
      description =
          "Variation margin received or paid at each end of day, minus fees on fills traded that day."
              + " The cumulative column is the book's total futures P&L.")
  @GetMapping("/pnl")
  public List<DailyPnl> pnl(@PathVariable String book) {
    long cashAccount = account(book, LedgerRepository.cashCode(book));
    List<Object[]> rows =
        jdbc.sql(
                """
                select e.business_date,
                       coalesce(sum(p.amount) filter (where e.entry_type = 'VARIATION_MARGIN'), 0) as vm,
                       coalesce(-sum(p.amount) filter (where e.entry_type = 'FILL'), 0) as fees
                  from journal_entry e join posting p on p.entry_id = e.id
                 where p.account_id = ? and p.asset = 'USD' and e.entry_type in ('VARIATION_MARGIN', 'FILL')
                 group by e.business_date order by e.business_date
                """)
            .param(cashAccount)
            .query((rs, n) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3)})
            .list();
    List<DailyPnl> out = new ArrayList<>();
    long cumulative = 0;
    for (Object[] r : rows) {
      long vm = (Long) r[1], fees = (Long) r[2];
      cumulative += vm - fees;
      out.add(new DailyPnl((LocalDate) r[0], vm, fees, vm - fees, cumulative));
    }
    return out;
  }

  @GetMapping("/journal")
  public List<LedgerRepository.JournalLine> journal(@PathVariable String book, @RequestParam(defaultValue = "50") int limit) {
    account(book, LedgerRepository.cashCode(book));
    return ledger.journal(book, Math.min(Math.max(limit, 1), 500));
  }

  public record Deposit(@NotNull @Positive Long amountCents, @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {}

  @Operation(summary = "Deposit cash into a book (creates the book if new)")
  @PostMapping("/deposits")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Summary deposit(@PathVariable String book, @Valid @RequestBody Deposit d) {
    if (!book.matches("[A-Za-z0-9_-]{1,40}")) throw ApiException.badRequest("invalid book name");
    LedgerRepository.BookAccounts acc = ledger.ensureBook(book);
    ledger.lockBook(book);
    ledger.postEntry(
        "DEPOSIT",
        d.date(),
        "deposit to " + book,
        null,
        List.of(
            new Posting(acc.cash(), LedgerRepository.USD, d.amountCents()),
            new Posting(ledger.house().funding(), LedgerRepository.USD, -d.amountCents())));
    return summary(book);
  }

  private long account(String book, String code) {
    return ledger.accountId(code).orElseThrow(() -> ApiException.notFound("no book " + book));
  }
}
