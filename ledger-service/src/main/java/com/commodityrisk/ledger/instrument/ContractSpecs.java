package com.commodityrisk.ledger.instrument;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract terms for the futures this service knows how to set up from a symbol alone.
 *
 * <p>Symbols follow the exchange convention ROOT + month code + 2-digit year: NGF27 is Henry Hub
 * natural gas for January 2027 delivery. Month codes: F G H J K M N Q U V X Z = Jan ... Dec.
 */
public final class ContractSpecs {

  /** Terms of one futures product. */
  public record Spec(String root, String name, long multiplier, BigDecimal tickSize) {}

  private static final Map<String, Spec> SPECS =
      Map.of(
          "NG", new Spec("NG", "Henry Hub Natural Gas", 10_000, new BigDecimal("0.001")),
          "CL", new Spec("CL", "WTI Crude Oil", 1_000, new BigDecimal("0.01")));

  private static final String MONTH_CODES = "FGHJKMNQUVXZ";
  private static final Pattern SYMBOL = Pattern.compile("^([A-Z]{2})([FGHJKMNQUVXZ])(\\d{2})$");

  private ContractSpecs() {}

  public static Optional<Spec> spec(String root) {
    return Optional.ofNullable(SPECS.get(root));
  }

  /** Parsed symbol: root and delivery month. */
  public record ParsedSymbol(String root, YearMonth month) {}

  public static Optional<ParsedSymbol> parse(String symbol) {
    Matcher m = SYMBOL.matcher(symbol);
    if (!m.matches()) return Optional.empty();
    int month = MONTH_CODES.indexOf(m.group(2).charAt(0)) + 1;
    int year = 2000 + Integer.parseInt(m.group(3));
    return Optional.of(new ParsedSymbol(m.group(1), YearMonth.of(year, month)));
  }

  public static String symbol(String root, YearMonth month) {
    return root + MONTH_CODES.charAt(month.getMonthValue() - 1) + String.format("%02d", month.getYear() % 100);
  }

  /**
   * Last trading day under the exchange rules.
   *
   * <ul>
   *   <li>NG: three business days before the first calendar day of the delivery month.
   *   <li>CL: three business days before the 25th calendar day of the month before delivery; if the
   *       25th is not a business day, four business days before it.
   * </ul>
   */
  public static LocalDate lastTradeDate(String root, YearMonth deliveryMonth) {
    return switch (root) {
      case "NG" -> ExchangeCalendar.businessDaysBefore(deliveryMonth.atDay(1), 3);
      case "CL" -> {
        LocalDate the25th = deliveryMonth.minusMonths(1).atDay(25);
        int n = ExchangeCalendar.isBusinessDay(the25th) ? 3 : 4;
        yield ExchangeCalendar.businessDaysBefore(the25th, n);
      }
      default -> throw new IllegalArgumentException("no expiry rule for " + root);
    };
  }

  /**
   * The contract month that is "nearby number 1" (the front month) on {@code date}: the first
   * delivery month whose last trading day is on or after {@code date}.
   */
  public static YearMonth frontMonth(String root, LocalDate date) {
    YearMonth m = YearMonth.from(date);
    while (lastTradeDate(root, m).isBefore(date)) m = m.plusMonths(1);
    return m;
  }

  /** 1 for the front month, 2 for the next, and so on; 0 or less if already expired. */
  public static int nearbyIndex(String root, YearMonth contractMonth, LocalDate date) {
    YearMonth front = frontMonth(root, date);
    return (contractMonth.getYear() - front.getYear()) * 12
        + contractMonth.getMonthValue()
        - front.getMonthValue()
        + 1;
  }
}
