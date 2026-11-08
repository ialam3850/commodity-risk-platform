#!/usr/bin/env python3
"""Run the storage-surprise study on the cached EIA data and write the results.

  python3 research/scripts/fetch_data.py      # once
  python3 research/scripts/run_backtest.py

Outputs: research/output/backtest.json, research/output/trades_oos.csv and
plots in docs/img/ (equity curve, report-day reaction, parameter grid).
"""

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from commodity_research import backtest as bt  # noqa: E402
from commodity_research.returns import naive_log_returns, nearby_log_returns  # noqa: E402
from commodity_research.contracts import roll_dates  # noqa: E402
from commodity_research.storage_signal import surprise_signal  # noqa: E402

# Overridable so the tests can run the script on synthetic data.
DATA = Path(os.environ.get("RESEARCH_DATA_DIR", ROOT / "data"))
OUT = Path(os.environ.get("RESEARCH_OUTPUT_DIR", ROOT / "output"))
IMG = Path(os.environ.get("RESEARCH_IMG_DIR", ROOT.parent / "docs" / "img"))


def load():
    fut = pd.read_csv(DATA / "ng_futures.csv", index_col="date", parse_dates=True).sort_index()
    fut = fut.dropna(subset=["RNGC1", "RNGC2"])
    storage = pd.read_csv(DATA / "ng_storage.csv", index_col="week_ending", parse_dates=True)["storage_bcf"]
    return fut, storage


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    IMG.mkdir(parents=True, exist_ok=True)
    fut, storage = load()
    rets = nearby_log_returns(fut)
    naive = naive_log_returns(fut)
    c1, price = rets["C1"], fut["RNGC1"]
    rolls = roll_dates(fut.index)

    signal = surprise_signal(storage, fut.index)
    weeks = len(signal)
    react = bt.reaction(signal, c1)
    corr = float(np.corrcoef(react["z"], react["same_day_return"])[0, 1])
    # Average report-day move for big surprises, in percent.
    big_bear = react[react["z"] >= 1.0]["same_day_return"].mean() * 100
    big_bull = react[react["z"] <= -1.0]["same_day_return"].mean() * 100

    g = bt.grid(signal, c1, price)
    oos, choices = bt.walk_forward(signal, c1, price)
    oos_weeks = int((signal["release"].dt.year >= choices["year"].min()).sum()) if len(choices) else 0
    oos_stats = bt.stats(oos["net"], oos_weeks)
    oos_gross = bt.stats(oos["gross"], oos_weeks)
    best_is = g.sort_values("sharpe", ascending=False).iloc[0]

    roll_abs_naive = naive["C1"].loc[naive.index.isin(rolls)].abs().mean() * 100
    roll_abs_adj = rets["C1"].loc[rets.index.isin(rolls)].abs().mean() * 100
    normal_abs = rets["C1"].loc[~rets.index.isin(rolls)].abs().mean() * 100

    summary = {
        "data": {
            "futures_first": str(fut.index.min().date()), "futures_last": str(fut.index.max().date()),
            "trading_days": len(fut), "storage_weeks": int(storage.size), "signal_weeks": weeks,
            "signal_first": str(signal["release"].min().date()), "signal_last": str(signal["release"].max().date()),
        },
        "roll_adjustment": {"roll_days": int(len(rolls)), "mean_abs_naive_roll_day_pct": roll_abs_naive,
                            "mean_abs_adjusted_roll_day_pct": roll_abs_adj, "mean_abs_other_days_pct": normal_abs},
        "reaction": {"corr_z_same_day_return": corr, "mean_return_pct_z_ge_1": big_bear,
                     "mean_return_pct_z_le_minus1": big_bull, "n": len(react)},
        "in_sample_best": {k: (float(v) if isinstance(v, (int, float, np.floating, np.integer)) else v)
                           for k, v in best_is.to_dict().items()},
        "walk_forward": {"net": oos_stats, "gross": oos_gross, "first_test_year": int(choices["year"].min()),
                         "last_test_year": int(choices["year"].max())},
        "costs_per_round_trip": bt.ROUND_TRIP_COST,
    }
    (OUT / "backtest.json").write_text(json.dumps(summary, indent=2, default=float))
    oos.to_csv(OUT / "trades_oos.csv", index=False)
    choices.to_csv(OUT / "walk_forward_choices.csv", index=False)
    g.to_csv(OUT / "grid_in_sample.csv", index=False)

    print(json.dumps(summary, indent=2, default=float))
    print("\nIn-sample grid (net Sharpe):")
    print(g.pivot(index="threshold", columns="hold", values="sharpe").round(2).to_string())
    print("\nWalk-forward choices:")
    print(choices.round(2).to_string(index=False))
    plot(oos, react, g, choices, corr)
    return 0


def plot(oos, react, g, choices, corr):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(9, 3.6))
    ax.plot(oos["exit"], oos["net"].cumsum(), color="C0", lw=1.4, label="net of costs")
    ax.plot(oos["exit"], oos["gross"].cumsum(), color="C0", lw=0.8, ls="--", alpha=0.6, label="before costs")
    ax.axhline(0, color="0.5", lw=0.8)
    ax.set_ylabel("cumulative P&L ($ per contract)")
    ax.set_title(f"Storage-surprise rule, walk-forward out of sample {choices['year'].min()}-{choices['year'].max()}")
    ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(IMG / "backtest_equity.png", dpi=120)

    fig, ax = plt.subplots(figsize=(6, 4))
    ax.scatter(react["z"], react["same_day_return"] * 100, s=8, alpha=0.5)
    b = np.polyfit(react["z"], react["same_day_return"] * 100, 1)
    xs = np.linspace(react["z"].min(), react["z"].max(), 50)
    ax.plot(xs, np.polyval(b, xs), color="C3", lw=1.5, label=f"slope {b[0]:.2f}% per z")
    ax.axhline(0, color="0.6", lw=0.6)
    ax.axvline(0, color="0.6", lw=0.6)
    ax.set_xlabel("storage surprise z (positive = bigger build than normal)")
    ax.set_ylabel("front-month return on report day (%)")
    ax.set_title(f"Report-day return vs. storage surprise (correlation {corr:.2f})")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(IMG / "storage_reaction.png", dpi=120)

    fig, ax = plt.subplots(figsize=(5, 3.2))
    piv = g.pivot(index="threshold", columns="hold", values="sharpe")
    im = ax.imshow(piv.to_numpy(), cmap="RdBu", vmin=-1, vmax=1, aspect="auto")
    ax.set_xticks(range(len(piv.columns)), piv.columns)
    ax.set_yticks(range(len(piv.index)), piv.index)
    ax.set_xlabel("holding period (days)")
    ax.set_ylabel("|z| threshold")
    for i in range(piv.shape[0]):
        for j in range(piv.shape[1]):
            ax.text(j, i, f"{piv.iat[i, j]:.2f}", ha="center", va="center", fontsize=8)
    ax.set_title("In-sample Sharpe, every parameter pair")
    fig.colorbar(im, ax=ax, shrink=0.8)
    fig.tight_layout()
    fig.savefig(IMG / "backtest_grid.png", dpi=120)


if __name__ == "__main__":
    sys.exit(main())
