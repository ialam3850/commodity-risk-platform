"""Event backtest of the storage-surprise rule, with costs and walk-forward selection.

Rule: on each report day with |z| >= threshold, take a position of
-sign(z) front-month contracts (bigger build than normal -> short) at that
day's settlement price, and close it `hold` trading days later at settlement.
The report comes out at 10:30 ET and NYMEX settles NG at 14:30 ET, so entering
at the settle is achievable. Positions never overlap for hold <= 4.

P&L is per contract in dollars:
    side x price_entry x (exp(sum of roll-adjusted C1 log returns) - 1) x 10,000 - costs
Costs per round trip: 2 x (commission + 1 tick of slippage) = 2 x ($2.50 + $10).

Walk-forward: for each test year, choose (threshold, hold) by Sharpe on all
earlier years only (at least `min_train_years`), then trade the test year with
that choice. Concatenating the test years gives an out-of-sample record that
no parameter choice has seen.
"""

from __future__ import annotations

import itertools
from dataclasses import dataclass

import numpy as np
import pandas as pd

MULTIPLIER = 10_000  # MMBtu per NG contract
TICK_VALUE = 10.0  # $0.001 x 10,000
COMMISSION = 2.50  # per contract per side, a typical retail futures rate
ROUND_TRIP_COST = 2 * (COMMISSION + TICK_VALUE)

THRESHOLDS = (0.5, 1.0, 1.5)
HOLDS = (1, 2, 3, 4)


@dataclass(frozen=True)
class Params:
    threshold: float
    hold: int

    def __post_init__(self):
        if not np.isfinite(self.threshold) or self.threshold < 0:
            raise ValueError("threshold must be finite and nonnegative")
        if not isinstance(self.hold, (int, np.integer)) or isinstance(self.hold, bool) or not 1 <= self.hold <= 4:
            raise ValueError("hold must be an integer from 1 to 4 trading days")


def trades(signal: pd.DataFrame, c1_returns: pd.Series, c1_price: pd.Series, params: Params,
           cost: float = ROUND_TRIP_COST) -> pd.DataFrame:
    """One row per trade: entry date, side, z, gross and net P&L ($/contract)."""
    if not np.isfinite(cost) or cost < 0:
        raise ValueError("cost must be finite and nonnegative")
    days = c1_returns.index
    if not days.is_monotonic_increasing or days.has_duplicates or days.hasnans:
        raise ValueError("return dates must be sorted, unique, and valid")
    rows = []
    for _, s in signal.sort_values("release").iterrows():
        if not np.isfinite(s["z"]) or abs(s["z"]) < params.threshold:
            continue
        entry = s["release"]
        i = days.searchsorted(entry)
        if i >= len(days) or days[i] != entry or i + params.hold >= len(days):
            continue
        window = c1_returns.iloc[i + 1: i + 1 + params.hold]  # returns after the entry settle
        if not np.isfinite(window.to_numpy()).all():
            continue
        side = -np.sign(s["z"])
        entry_price = c1_price.get(entry, np.nan)
        if not np.isfinite(entry_price) or entry_price <= 0:
            continue
        gross = side * entry_price * np.expm1(window.sum()) * MULTIPLIER
        rows.append({"entry": entry, "exit": days[i + params.hold], "side": int(side), "z": s["z"],
                     "gross": gross, "net": gross - cost})
    result = pd.DataFrame(rows, columns=["entry", "exit", "side", "z", "gross", "net"])
    # Date columns need datetime dtype even when no parameter pair produces a trade.
    result["entry"] = pd.to_datetime(result["entry"])
    result["exit"] = pd.to_datetime(result["exit"])
    return result


def stats(pnl: pd.Series, weeks: int) -> dict:
    """Summary of a per-trade P&L series observed over `weeks` report weeks.

    Sharpe is annualised from weekly P&L including weeks with no trade (zero),
    so a rule that trades rarely isn't flattered.
    """
    pnl = pnl.astype(float)
    n = len(pnl)
    if not isinstance(weeks, (int, np.integer)) or isinstance(weeks, bool) or weeks < n:
        raise ValueError("weeks must be an integer at least as large as the trade count")
    if not np.isfinite(pnl.to_numpy()).all():
        raise ValueError("P&L observations must be finite")
    weekly = np.concatenate([pnl.to_numpy(), np.zeros(max(weeks - n, 0))])
    sd = weekly.std(ddof=1) if len(weekly) > 1 else np.nan
    sharpe = weekly.mean() / sd * np.sqrt(52) if np.isfinite(sd) and sd > 0 else np.nan
    equity = pnl.cumsum()
    drawdown = (equity.cummax().clip(lower=0) - equity).max() if n else 0.0
    t = pnl.mean() / (pnl.std(ddof=1) / np.sqrt(n)) if n > 2 and pnl.std(ddof=1) > 0 else np.nan
    return {"trades": n, "total": float(pnl.sum()), "mean": float(pnl.mean()) if n else np.nan,
            "hit_rate": float((pnl > 0).mean()) if n else np.nan, "sharpe": float(sharpe),
            "max_drawdown": float(drawdown), "t_stat": float(t)}


def grid(signal: pd.DataFrame, c1_returns: pd.Series, c1_price: pd.Series) -> pd.DataFrame:
    """In-sample results for every parameter pair (for the overfitting comparison)."""
    weeks = len(signal)
    out = []
    for th, h in itertools.product(THRESHOLDS, HOLDS):
        tr = trades(signal, c1_returns, c1_price, Params(th, h))
        out.append({"threshold": th, "hold": h, **stats(tr["net"], weeks)})
    return pd.DataFrame(out)


def walk_forward(signal: pd.DataFrame, c1_returns: pd.Series, c1_price: pd.Series,
                 min_train_years: int = 3) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Returns (out-of-sample trades, per-year parameter choices)."""
    if not isinstance(min_train_years, (int, np.integer)) or isinstance(min_train_years, bool) or min_train_years < 1:
        raise ValueError("min_train_years must be a positive integer")
    years = sorted(signal["release"].dt.year.unique())
    oos, choices = [], []
    for y in years[min_train_years:]:
        train = signal[signal["release"].dt.year < y]
        test = signal[signal["release"].dt.year == y]
        best, best_sharpe = None, -np.inf
        for th, h in itertools.product(THRESHOLDS, HOLDS):
            p = Params(th, h)
            # Trades whose exit falls in the test year are excluded from training.
            tr = trades(train, c1_returns, c1_price, p)
            tr = tr[tr["exit"].dt.year < y]
            s = stats(tr["net"], len(train))["sharpe"]
            if np.isfinite(s) and s > best_sharpe:
                best, best_sharpe = p, s
        if best is None:
            continue
        tr = trades(test, c1_returns, c1_price, best)
        oos.append(tr)
        choices.append({"year": y, "threshold": best.threshold, "hold": best.hold,
                        "train_sharpe": best_sharpe, "test_trades": len(tr), "test_net": float(tr["net"].sum())})
    oos_trades = pd.concat(oos, ignore_index=True) if oos else trades(signal.iloc[:0], c1_returns, c1_price, Params(1, 1))
    return oos_trades, pd.DataFrame(choices)


def reaction(signal: pd.DataFrame, c1_returns: pd.Series) -> pd.DataFrame:
    """Return on the report day itself (previous settle -> report-day settle) next to each z.

    Not tradable (the report lands mid-session), but it shows whether the
    surprise measure captures news the market reacts to.
    """
    r = c1_returns.reindex(signal["release"]).to_numpy()
    return pd.DataFrame({"release": signal["release"].to_numpy(), "z": signal["z"].to_numpy(), "same_day_return": r}).dropna()
