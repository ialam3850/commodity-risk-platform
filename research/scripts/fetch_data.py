#!/usr/bin/env python3
"""Download (or load from cache) the EIA data and save tidy CSVs.

  python3 research/scripts/fetch_data.py

Writes research/data/ng_futures.csv (daily, contracts 1-4) and
research/data/ng_storage.csv (weekly). With the shared DEMO_KEY this can take
an hour or two of waiting on the rate limit the first time; set EIA_API_KEY
to make it take seconds.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from commodity_research.eia import EIAClient  # noqa: E402

DATA = Path(__file__).resolve().parents[1] / "data"


def main() -> int:
    client = EIAClient(max_wait_s=4 * 3600)
    storage = client.ng_storage()
    storage.to_csv(DATA / "ng_storage.csv")
    print(f"storage: {len(storage)} weeks, {storage.index.min().date()} .. {storage.index.max().date()}")
    # Mid-2012 onwards: the first tradable signal is in 2016 anyway (it needs five years of
    # storage history from 2010 plus a year to scale the surprise), and four contracts since
    # then fit in three API pages, which matters with the rate-limited DEMO_KEY. EIA's futures
    # series end on 2024-04-05: EIA no longer republishes NYMEX prices after that date.
    fut = client.ng_futures(start="2012-06-01")
    fut.to_csv(DATA / "ng_futures.csv", float_format="%.4f")
    print(f"futures: {len(fut)} days, {fut.index.min().date()} .. {fut.index.max().date()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
