package com.commodityrisk.ledger.eod;

import com.commodityrisk.ledger.instrument.Instrument;
import com.commodityrisk.ledger.instrument.InstrumentRepository;
import com.commodityrisk.ledger.ledger.LedgerRepository;
import com.commodityrisk.ledger.ledger.LedgerRepository.Posting;
import com.commodityrisk.ledger.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End of day: marks every position in a book to the day's settlement price and moves the
 * difference as cash (variation margin), the way a clearing house does.
 *
 * <p>For each contract the day's variation margin is
 *
 * <pre>
 *   carried quantity x (settle today - settle at last EOD) x multiplier
 * + sum over today's fills of signed quantity x (settle today - fill price) x multiplier
 * </pre>
 *
 * <p>Summed over the life of a position this equals its total P&amp;L (realised plus unrealised), so
 * cumulative variation margin minus fees is the book's P&amp;L, with no separate cost-basis
 * bookkeeping.
 *
 * <p>Rules that keep it consistent: EOD runs once per book and date (a second call returns the
 * first result); dates must be run in order (a date with fills can't be skipped); fills can't be
 * booked on a date that is already settled; every contract with a position or a fill needs a
 * settlement price for the date; positions must be closed by the contract's last trading day
 * (delivery is not modelled).
 */
@Service
public class EodService {

  public record Line(
      String symbol,
      long carriedQuantity,
      BigDecimal previousSettle,
      long fillQuantity,
      BigDecimal settle,
      long endQuantity,
      long variationMarginCents) {}

  public record Result(String book, LocalDate businessDate, Long journalEntryId, List<Line> lines, long totalCents, boolean replayed) {}

  private final JdbcClient jdbc;
  private final LedgerRepository ledger;
  private final InstrumentRepository instruments;

  public EodService(JdbcClient jdbc, LedgerRepository ledger, InstrumentRepository instruments) {
    this.jdbc = jdbc;
    this.ledger = ledger;
    this.instruments = instruments;
  }

  private record FillRow(String symbol, String side, int quantity, BigDecimal price) {
    long signed() {
      return "BUY".equals(side) ? quantity : -quantity;
    }
  }

  @Transactional
  public Result run(String book, LocalDate date) {
    if (!ledger.bookExists(book)) throw ApiException.notFound("no book " + book);
    LedgerRepository.BookAccounts accounts = ledger.ensureBook(book);
    ledger.lockBook(book);

    Optional<Long> existing =
        jdbc.sql("select coalesce(entry_id, -1) from eod_run where book = ? and business_date = ?")
            .params(book, date)
            .query(Long.class)
            .optional();
    if (existing.isPresent()) {
      // Already settled: recompute the breakdown for display, but post nothing.
      Result r = compute(book, date, true);
      return new Result(book, date, existing.get() < 0 ? null : existing.get(), r.lines(), r.totalCents(), true);
    }
    LocalDate lastRun = lastRun(book, date).orElse(null);
    if (jdbc.sql("select exists (select 1 from eod_run where book = ? and business_date > ?)")
        .params(book, date)
        .query(Boolean.class)
        .single()) {
      throw ApiException.conflict("book " + book + " is already settled after " + date);
    }
    List<LocalDate> skipped =
        jdbc.sql(
                """
                select distinct trade_date from fill
                 where book = ? and trade_date < ? and (? :: date is null or trade_date > ?) order by trade_date
                """)
            .params(book, date, lastRun, lastRun)
            .query(LocalDate.class)
            .list();
    if (!skipped.isEmpty()) {
      throw ApiException.conflict("run end-of-day for " + skipped.get(0) + " first (fills on " + skipped + ")");
    }

    Result r = compute(book, date, false);
    Long entryId = null;
    if (r.totalCents() != 0) {
      LedgerRepository.HouseAccounts house = ledger.house();
      entryId =
          ledger.postEntry(
              "VARIATION_MARGIN",
              date,
              "variation margin " + book + " " + date,
              null,
              List.of(
                  new Posting(accounts.cash(), LedgerRepository.USD, r.totalCents()),
                  new Posting(house.clearingCash(), LedgerRepository.USD, -r.totalCents())));
    }
    jdbc.sql("insert into eod_run (book, business_date, entry_id) values (?, ?, ?)")
        .params(book, date, entryId)
        .update();
    return new Result(book, date, entryId, r.lines(), r.totalCents(), false);
  }

  private Optional<LocalDate> lastRun(String book, LocalDate before) {
    return jdbc.sql("select max(business_date) from eod_run where book = ? and business_date < ?")
        .params(book, before)
        .query(LocalDate.class)
        .optional();
  }

  private Result compute(String book, LocalDate date, boolean replay) {
    LocalDate lastRun = lastRun(book, date).orElse(null);
    // Position carried into today: everything traded on or before the last settled date.
    Map<String, Long> carried = new TreeMap<>();
    if (lastRun != null) {
      jdbc.sql(
              """
              select symbol, sum(case when side = 'BUY' then quantity else -quantity end) as qty
                from fill where book = ? and trade_date <= ? group by symbol
              """)
          .params(book, lastRun)
          .query((rs, i) -> Map.entry(rs.getString("symbol"), rs.getLong("qty")))
          .list()
          .forEach(e -> carried.put(e.getKey(), e.getValue()));
    }
    List<FillRow> today =
        jdbc.sql("select symbol, side, quantity, price from fill where book = ? and trade_date = ? order by id")
            .params(book, date)
            .query(FillRow.class)
            .list();

    TreeSet<String> symbols = new TreeSet<>();
    carried.forEach((s, q) -> {
      if (q != 0) symbols.add(s);
    });
    today.forEach(f -> symbols.add(f.symbol()));

    List<String> missing = new ArrayList<>();
    List<String> expired = new ArrayList<>();
    List<Line> lines = new ArrayList<>();
    long total = 0;
    for (String symbol : symbols) {
      Instrument inst = instruments.find(symbol).orElseThrow();
      long carriedQty = carried.getOrDefault(symbol, 0L);
      long fillQty = today.stream().filter(f -> f.symbol().equals(symbol)).mapToLong(FillRow::signed).sum();
      long endQty = carriedQty + fillQty;
      if (!replay && endQty != 0 && date.isAfter(inst.lastTradeDate())) {
        expired.add(symbol);
        continue;
      }
      Optional<BigDecimal> settle = settlement(symbol, date);
      Optional<BigDecimal> prevSettle = carriedQty != 0 ? settlement(symbol, lastRun) : Optional.empty();
      if (settle.isEmpty()) {
        missing.add(symbol + "@" + date);
        continue;
      }
      if (carriedQty != 0 && prevSettle.isEmpty()) {
        missing.add(symbol + "@" + lastRun);
        continue;
      }
      long vm = 0;
      if (carriedQty != 0) vm += inst.valueChangeCents(carriedQty, prevSettle.get(), settle.get());
      for (FillRow f : today) {
        if (f.symbol().equals(symbol)) vm += inst.valueChangeCents(f.signed(), f.price(), settle.get());
      }
      total += vm;
      lines.add(new Line(symbol, carriedQty, prevSettle.orElse(null), fillQty, settle.get(), endQty, vm));
    }
    if (!expired.isEmpty()) {
      throw ApiException.conflict("open positions past their last trading day (delivery not supported): " + expired);
    }
    if (!missing.isEmpty()) throw ApiException.conflict("missing settlement prices: " + missing);
    return new Result(book, date, null, lines, total, replay);
  }

  private Optional<BigDecimal> settlement(String symbol, LocalDate date) {
    return jdbc.sql("select price from settlement_price where symbol = ? and business_date = ?")
        .params(symbol, date)
        .query(BigDecimal.class)
        .optional();
  }
}
