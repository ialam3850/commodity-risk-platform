"""Roll-adjusted daily returns of nearby futures contracts.

A naive return on the "Contract 1" series is wrong on roll days: yesterday's
contract 1 was (say) the November contract, today's is December, and the
price gap between two different contracts is not a gain or loss anyone made.
In winter contango that gap can be 5-10%, larger than most real daily moves.

The fix: on a roll day, compare today's contract k with yesterday's contract
k+1, which is the *same* contract. Contract n (the furthest one in the data)
has no k+1 to compare against, so its roll-day return is left missing.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .contracts import roll_dates


def _checked_prices(prices: pd.DataFrame) -> pd.DataFrame:
    if not isinstance(prices.index, pd.DatetimeIndex) or prices.index.hasnans or prices.index.has_duplicates:
        raise ValueError("prices need unique, valid trading dates")
    prices = prices.sort_index().astype(float)
    values = prices.to_numpy()
    if np.isinf(values).any() or (values <= 0).any():
        raise ValueError("observed prices must be finite and positive")
    return prices


def nearby_log_returns(prices: pd.DataFrame) -> pd.DataFrame:
    """Daily log returns per nearby column (RNGC1..RNGCn -> C1..Cn), roll-adjusted.

    `prices` is indexed by trading day with columns in nearby order.
    """
    prices = _checked_prices(prices)
    cols = list(prices.columns)
    logp = np.log(prices)
    out = logp.diff()
    rolls = roll_dates(prices.index)
    is_roll = prices.index.isin(rolls)
    for k, col in enumerate(cols):
        if k + 1 < len(cols):
            same_contract_yesterday = logp[cols[k + 1]].shift(1)
            out.loc[is_roll, col] = (logp[col] - same_contract_yesterday)[is_roll]
        else:
            out.loc[is_roll, col] = np.nan
    out.columns = [f"C{k + 1}" for k in range(len(cols))]
    return out.iloc[1:]


def naive_log_returns(prices: pd.DataFrame) -> pd.DataFrame:
    """Unadjusted returns, for comparison only."""
    out = np.log(_checked_prices(prices)).diff().iloc[1:]
    out.columns = [f"C{k + 1}" for k in range(len(prices.columns))]
    return out
