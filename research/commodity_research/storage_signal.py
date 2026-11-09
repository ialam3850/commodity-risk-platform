"""Weekly natural gas storage "surprise" signal.

Every Thursday at 10:30 ET the EIA reports how much gas went into (or came
out of) underground storage in the week ending the previous Friday. Traders
compare the change with what they expected; a bigger injection than expected
means more supply than thought, which is bearish for price.

The real benchmark is the analysts' consensus forecast, which isn't public.
This module uses a public stand-in, the seasonal norm: the average change for
the same week of the year over the previous five years (the same comparison
EIA prints in its report). Only data available before each report is used.
"""

from __future__ import annotations

import numpy as np
import pandas as pd


def _checked_series(series: pd.Series) -> pd.Series:
    if not isinstance(series.index, pd.DatetimeIndex) or series.index.hasnans or series.index.has_duplicates:
        raise ValueError("storage data need unique, valid dates")
    series = series.sort_index().astype(float)
    if not np.isfinite(series.to_numpy()).all():
        raise ValueError("storage observations must be finite")
    return series


def weekly_changes(storage: pd.Series) -> pd.Series:
    """Change in working gas (Bcf) per report week; index = week-ending date."""
    s = _checked_series(storage)
    change = s.diff().dropna()
    change.name = "change_bcf"
    return change


def five_year_norm(changes: pd.Series, years: int = 5) -> pd.Series:
    """For each week, the mean change for the same ISO week number over the prior `years` years.

    NaN until `years` years of history exist. Week 53 is matched with week 52.
    """
    if not isinstance(years, (int, np.integer)) or isinstance(years, bool) or years < 1:
        raise ValueError("years must be a positive integer")
    changes = _checked_series(changes)
    df = changes.to_frame("chg")
    df["year"] = df.index.isocalendar().year.astype(int)
    df["week"] = df.index.isocalendar().week.clip(upper=52).astype(int)
    norm = pd.Series(np.nan, index=df.index, name="norm_bcf")
    by_week = {w: g for w, g in df.groupby("week")}
    for t, row in df.iterrows():
        g = by_week[row["week"]]
        prior = g[(g["year"] < row["year"]) & (g["year"] >= row["year"] - years)]
        if prior["year"].nunique() == years:
            norm.loc[t] = prior["chg"].mean()
    return norm


def release_dates(week_ending: pd.DatetimeIndex, trading_days: pd.DatetimeIndex) -> pd.DatetimeIndex:
    """Report day for each week: the Thursday after the week-ending Friday.

    When that Thursday is not a trading day (Thanksgiving, Christmas, ...) EIA
    usually publishes a day early or late; here the next trading day is used.
    """
    if (not trading_days.is_monotonic_increasing or trading_days.has_duplicates or trading_days.hasnans):
        raise ValueError("trading days must be sorted, unique, and valid")
    thursday = week_ending + pd.Timedelta(days=6)
    idx = trading_days.searchsorted(thursday)
    out = [trading_days[i] if i < len(trading_days) else pd.NaT for i in idx]
    return pd.DatetimeIndex(out)  # NaT when the report is after the end of the price data


def surprise_signal(storage: pd.Series, trading_days: pd.DatetimeIndex, z_window: int = 104) -> pd.DataFrame:
    """One row per report: release date, change, norm, surprise and its z-score.

    z = surprise / std(previous `z_window` surprises), so it is comparable across seasons and years
    and uses no future data.
    """
    if not isinstance(z_window, (int, np.integer)) or isinstance(z_window, bool) or z_window < 52:
        raise ValueError("z_window must be an integer of at least 52 weeks")
    changes = weekly_changes(storage)
    norm = five_year_norm(changes)
    df = pd.DataFrame({"change_bcf": changes, "norm_bcf": norm}).dropna()
    df["surprise_bcf"] = df["change_bcf"] - df["norm_bcf"]
    past_sd = df["surprise_bcf"].shift(1).rolling(z_window, min_periods=52).std()
    df["z"] = df["surprise_bcf"] / past_sd.replace(0.0, np.nan)
    df["release"] = release_dates(df.index, trading_days)
    return df.dropna(subset=["z", "release"])
