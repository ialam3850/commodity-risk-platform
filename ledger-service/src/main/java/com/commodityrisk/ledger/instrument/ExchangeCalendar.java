package com.commodityrisk.ledger.instrument;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.Set;

/**
 * NYMEX business days: weekdays that aren't exchange holidays.
 *
 * <p>Holidays follow the standard U.S. exchange schedule: New Year's Day, Martin Luther King Jr.
 * Day, Presidents' Day, Good Friday, Memorial Day, Juneteenth (from 2022), Independence Day, Labor
 * Day, Thanksgiving and Christmas, with Saturday holidays observed on Friday and Sunday holidays on
 * Monday. One-off closures (e.g. national days of mourning) are not included, so treat dates near
 * those as approximate.
 */
public final class ExchangeCalendar {

  private ExchangeCalendar() {}

  public static boolean isBusinessDay(LocalDate d) {
    DayOfWeek w = d.getDayOfWeek();
    return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays(d.getYear()).contains(d);
  }

  /** The business day {@code n} business days before {@code d} (d itself not counted). */
  public static LocalDate businessDaysBefore(LocalDate d, int n) {
    LocalDate x = d;
    int counted = 0;
    while (counted < n) {
      x = x.minusDays(1);
      if (isBusinessDay(x)) counted++;
    }
    return x;
  }

  public static LocalDate previousBusinessDay(LocalDate d) {
    return businessDaysBefore(d, 1);
  }

  static Set<LocalDate> holidays(int year) {
    Set<LocalDate> h = new HashSet<>();
    h.add(observed(LocalDate.of(year, Month.JANUARY, 1)));
    h.add(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3)); // MLK Day
    h.add(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3)); // Presidents' Day
    h.add(easterSunday(year).minusDays(2)); // Good Friday
    h.add(LocalDate.of(year, Month.MAY, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    if (year >= 2022) h.add(observed(LocalDate.of(year, Month.JUNE, 19)));
    h.add(observed(LocalDate.of(year, Month.JULY, 4)));
    h.add(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)); // Labor Day
    h.add(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)); // Thanksgiving
    h.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));
    // New Year's Day of next year observed on Friday Dec 31 falls in this year.
    LocalDate nextNewYear = observed(LocalDate.of(year + 1, Month.JANUARY, 1));
    if (nextNewYear.getYear() == year) h.add(nextNewYear);
    return h;
  }

  private static LocalDate observed(LocalDate d) {
    return switch (d.getDayOfWeek()) {
      case SATURDAY -> d.minusDays(1);
      case SUNDAY -> d.plusDays(1);
      default -> d;
    };
  }

  private static LocalDate nthWeekday(int year, Month month, DayOfWeek day, int n) {
    return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day));
  }

  /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
  static LocalDate easterSunday(int y) {
    int a = y % 19;
    int b = y / 100;
    int c = y % 100;
    int d = b / 4;
    int e = b % 4;
    int f = (b + 8) / 25;
    int g = (b - f + 1) / 3;
    int h = (19 * a + b - d - g + 15) % 30;
    int i = c / 4;
    int k = c % 4;
    int l = (32 + 2 * e + 2 * i - h - k) % 7;
    int m = (a + 11 * h + 22 * l) / 451;
    int month = (h + l - 7 * m + 114) / 31;
    int day = ((h + l - 7 * m + 114) % 31) + 1;
    return LocalDate.of(y, month, day);
  }
}
