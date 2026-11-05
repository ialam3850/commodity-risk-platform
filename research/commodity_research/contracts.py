"""NYMEX Henry Hub natural gas (NG) contract calendar.

NG trading terminates three business days before the first calendar day of
the delivery month. "Business day" here means a day on which the futures
traded, taken from the price history itself, so exchange holidays (Good
Friday, Juneteenth, ...) are handled without a holiday table. For dates past
the end of the data, weekdays are used as an approximation.

EIA's "Contract 1" series is the contract with the earliest delivery month
that is still trading, "Contract 2" the next, and so on. On the day after a
contract's last trading day, every series steps forward by one month (the
roll).
"""

from __future__ import annotations

import pandas as pd

MONTH_CODES = "FGHJKMNQUVXZ"


def symbol(root: str, delivery: pd.Period) -> str:
    """NG + month code + 2-digit year, e.g. NGF27 for January 2027."""
    return f"{root}{MONTH_CODES[delivery.month - 1]}{delivery.year % 100:02d}"


def last_trade_date(delivery: pd.Period, trading_days: pd.DatetimeIndex) -> pd.Timestamp:
    """Third trading day before the first calendar day of `delivery` (a monthly Period)."""
    first = delivery.start_time.normalize()
    prior = trading_days[trading_days < first]
    if len(prior) >= 3 and prior[-1] >= first - pd.Timedelta(days=10):
        return prior[-3]
    # Beyond (or before) the data: fall back to weekdays.
    return pd.bdate_range(end=first - pd.Timedelta(days=1), periods=3)[0]


def front_month(date: pd.Timestamp, trading_days: pd.DatetimeIndex) -> pd.Period:
    """Delivery month of the nearest contract still trading on `date`."""
    m = pd.Period(date, freq="M")
    while last_trade_date(m, trading_days) < date:
        m += 1
    return m


def nearby_months(dates: pd.DatetimeIndex, trading_days: pd.DatetimeIndex, n: int = 4) -> pd.DataFrame:
    """For each date, the delivery month of nearby contracts 1..n (columns C1..Cn)."""
    cache: dict[pd.Period, pd.Timestamp] = {}

    def ltd(m: pd.Period) -> pd.Timestamp:
        if m not in cache:
            cache[m] = last_trade_date(m, trading_days)
        return cache[m]

    rows = []
    for d in dates:
        m = pd.Period(d, freq="M")
        while ltd(m) < d:
            m += 1
        rows.append([m + k for k in range(n)])
    return pd.DataFrame(rows, index=dates, columns=[f"C{k + 1}" for k in range(n)])


def roll_dates(trading_days: pd.DatetimeIndex) -> pd.DatetimeIndex:
    """Trading days on which the front month changed versus the previous trading day."""
    if trading_days.empty:
        return trading_days[:0]
    fronts = nearby_months(trading_days, trading_days, n=1)["C1"]
    changed = fronts != fronts.shift(1)
    changed.iloc[0] = False
    return trading_days[changed.to_numpy()]
