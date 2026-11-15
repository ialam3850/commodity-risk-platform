package com.commodityrisk.ledger.risk;

import com.commodityrisk.ledger.instrument.ContractSpecs;
import com.commodityrisk.ledger.instrument.Instrument;
import com.commodityrisk.ledger.instrument.InstrumentRepository;
import com.commodityrisk.ledger.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historical-simulation VaR for a book of futures.
 *
 * <p>Risk factors are nearby contracts: "NG:C1" is whichever NG contract is closest to expiry on a
 * given day, "NG:C2" the next, and so on, with returns that skip the jump when the front contract
 * rolls (computed in research/commodity_research/returns.py). A position in NGF27 is mapped to the
 * factor matching its distance from expiry today, e.g. NG:C3 if two contracts expire before it.
 * Contracts further out than the furthest factor loaded use that furthest factor - a proxy, since
 * back months usually move less than front months.
 *
 * <p>Each historical day's factor returns are applied to today's settlement prices and the book is
 * revalued: P&amp;L = quantity x multiplier x settle x (e^{sqrt(h) r} - 1). h is the horizon in days
 * (square-root-of-time scaling).
 */
@Service
public class VarService {

  public record Exposure(String symbol, long quantity, BigDecimal settle, String factor, long notionalCents) {}

  public record WorstDay(LocalDate date, double pnl) {}

  public record Result(
      String book,
      LocalDate asOf,
      double confidence,
      int horizonDays,
      double var,
      double expectedShortfall,
      int scenarios,
      int tail,
      LocalDate firstScenario,
      LocalDate lastScenario,
      List<Exposure> exposures,
      List<WorstDay> worstDays) {}

  private final JdbcClient jdbc;
  private final InstrumentRepository instruments;

  public VarService(JdbcClient jdbc, InstrumentRepository instruments) {
    this.jdbc = jdbc;
    this.instruments = instruments;
  }

  @Transactional(readOnly = true)
  public Result historicalVar(String book, LocalDate asOf, double confidence, int lookbackDays, int horizonDays) {
    if (!(confidence > 0.5 && confidence < 1)) throw ApiException.badRequest("confidence must be in (0.5, 1)");
    if (lookbackDays < 20 || lookbackDays > 5000) throw ApiException.badRequest("lookbackDays must be 20-5000");
    if (horizonDays < 1 || horizonDays > 60) throw ApiException.badRequest("horizonDays must be 1-60");

    Map<String, Long> positions = new TreeMap<>();
    jdbc.sql(
            """
            select symbol, sum(case when side = 'BUY' then quantity else -quantity end) as qty
              from fill where book = ? and trade_date <= ? group by symbol
            """)
        .params(book, asOf)
        .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
        .list()
        .forEach(e -> {
          if (e.getValue() != 0) positions.put(e.getKey(), e.getValue());
        });
    if (positions.isEmpty()) throw ApiException.unprocessable("book " + book + " has no open positions on " + asOf);

    // Map each position to a factor and look up today's settle.
    List<Exposure> exposures = new ArrayList<>();
    List<Instrument> insts = new ArrayList<>();
    for (var e : positions.entrySet()) {
      Instrument inst = instruments.find(e.getKey()).orElseThrow();
      BigDecimal settle =
          jdbc.sql(
                  """
                  select price from settlement_price where symbol = ? and business_date <= ?
                   order by business_date desc limit 1
                  """)
              .params(inst.symbol(), asOf)
              .query(BigDecimal.class)
              .optional()
              .orElseThrow(() -> ApiException.conflict("no settlement price for " + inst.symbol() + " on or before " + asOf));
      int nearby = ContractSpecs.nearbyIndex(inst.root(), YearMonth.from(inst.contractMonth()), asOf);
      if (nearby < 1) throw ApiException.conflict(inst.symbol() + " has expired by " + asOf);
      Integer maxLoaded =
          jdbc.sql(
                  """
                  select max(cast(substring(factor from ':C([0-9]+)$') as int))
                    from (select distinct factor from factor_return where factor like ?) f
                  """)
              .param(inst.root() + ":C%")
              .query(Integer.class)
              .optional()
              .orElse(null);
      if (maxLoaded == null) throw ApiException.conflict("no factor returns loaded for " + inst.root());
      String factor = inst.root() + ":C" + Math.min(nearby, maxLoaded);
      long notional = settle.multiply(BigDecimal.valueOf(e.getValue() * inst.multiplier() * 100)).longValue();
      exposures.add(new Exposure(inst.symbol(), e.getValue(), settle, factor, notional));
      insts.add(inst);
    }

    // Scenario dates: the last `lookbackDays` days (up to asOf) on which every needed factor has a return.
    List<String> factors = exposures.stream().map(Exposure::factor).distinct().sorted().toList();
    List<LocalDate> dates =
        jdbc.sql(
                """
                select business_date from factor_return
                 where factor in (:factors) and business_date <= :asOf
                 group by business_date having count(distinct factor) = :n
                 order by business_date desc limit :lookback
                """)
            .param("factors", factors)
            .param("asOf", asOf)
            .param("n", factors.size())
            .param("lookback", lookbackDays)
            .query(LocalDate.class)
            .list();
    if (dates.size() < 20) throw ApiException.conflict("only " + dates.size() + " days of factor returns available");
    LocalDate first = dates.get(dates.size() - 1), last = dates.get(0);

    Map<LocalDate, Map<String, Double>> returns = new LinkedHashMap<>();
    jdbc.sql(
            """
            select business_date, factor, log_return from factor_return
             where factor in (:factors) and business_date between :first and :last
            """)
        .param("factors", factors)
        .param("first", first)
        .param("last", last)
        .query((rs, n) -> {
          returns
              .computeIfAbsent(rs.getObject(1, LocalDate.class), d -> new TreeMap<>())
              .put(rs.getString(2), rs.getDouble(3));
          return null;
        })
        .list();

    double scale = Math.sqrt(horizonDays);
    double[] pnl = new double[dates.size()];
    List<WorstDay> byDay = new ArrayList<>();
    for (int s = 0; s < dates.size(); s++) {
      Map<String, Double> r = returns.get(dates.get(s));
      double total = 0;
      for (int i = 0; i < exposures.size(); i++) {
        Exposure x = exposures.get(i);
        double units = (double) x.quantity() * insts.get(i).multiplier();
        total += units * x.settle().doubleValue() * Math.expm1(scale * r.get(x.factor()));
      }
      pnl[s] = total;
      byDay.add(new WorstDay(dates.get(s), total));
    }
    HistoricalVar.Result v = HistoricalVar.fromPnl(pnl, confidence);
    List<WorstDay> worst = byDay.stream().sorted(Comparator.comparingDouble(WorstDay::pnl)).limit(5).toList();
    return new Result(
        book, asOf, confidence, horizonDays, v.var(), v.es(), v.scenarios(), v.tail(), first, last, exposures, worst);
  }
}
