"""Client for the U.S. Energy Information Administration Open Data API (v2).

  https://www.eia.gov/opendata/documentation.php

Series used by this project:

  natural-gas/pri/fut   daily  RNGC1..RNGC4  NYMEX Henry Hub natural gas futures,
                                             contracts 1-4 (nearest to 4th-nearest), $/MMBtu.
                                             The series end on 2024-04-05 (EIA stopped
                                             republishing NYMEX prices).
  natural-gas/stor/wkly weekly NW2_EPG0_SWO_R48_BCF  working gas in storage, Lower 48, Bcf

The API needs a key. A free one comes from https://www.eia.gov/opendata/register.php;
set it as EIA_API_KEY. Without one the shared DEMO_KEY is used, which is
rate-limited to a handful of requests per hour, so every response is cached
on disk and a re-run makes no requests at all.
"""

from __future__ import annotations

import hashlib
import json
import os
import numpy as np
import time
import math
import tempfile
from pathlib import Path

import pandas as pd
import requests

API_ROOT = "https://api.eia.gov/v2"
PAGE = 5000  # the API's maximum rows per request

FUTURES_ROUTE = "natural-gas/pri/fut"
STORAGE_ROUTE = "natural-gas/stor/wkly"
NG_CONTRACTS = ["RNGC1", "RNGC2", "RNGC3", "RNGC4"]
STORAGE_L48 = "NW2_EPG0_SWO_R48_BCF"

DEFAULT_CACHE = Path(__file__).resolve().parent.parent / "data" / "cache"


class EIAError(RuntimeError):
    pass


class EIAClient:
    def __init__(self, api_key: str | None = None, cache_dir: Path | str = DEFAULT_CACHE,
                 session: requests.Session | None = None, max_wait_s: float = 3900.0):
        self.api_key = api_key or os.environ.get("EIA_API_KEY") or "DEMO_KEY"
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.session = session or requests.Session()
        if not math.isfinite(max_wait_s) or max_wait_s < 0:
            raise ValueError("max_wait_s must be finite and nonnegative")
        self.max_wait_s = max_wait_s

    # -- public -------------------------------------------------------------------

    def series(self, route: str, series_ids: list[str], frequency: str, start: str | None = None,
               end: str | None = None) -> pd.DataFrame:
        """All observations for the given series as a tidy frame: period, series, value."""
        rows: list[dict] = []
        offset = 0
        while True:
            params = {
                "frequency": frequency,
                "data[0]": "value",
                "sort[0][column]": "period",
                "sort[0][direction]": "asc",
                "offset": offset,
                "length": PAGE,
            }
            for i, s in enumerate(series_ids):
                params[f"facets[series][{i}]"] = s
            if start:
                params["start"] = start
            if end:
                params["end"] = end
            payload = self._get(f"{route}/data/", params)
            resp = payload.get("response") if isinstance(payload, dict) else None
            if not isinstance(resp, dict) or not isinstance(resp.get("data"), list):
                raise EIAError("EIA response must contain a data array")
            batch = resp["data"]
            try:
                total = int(resp["total"])
            except (KeyError, TypeError, ValueError, OverflowError):
                raise EIAError("EIA response has an invalid row count") from None
            if total < offset + len(batch):
                raise EIAError("EIA row count is smaller than the received data")
            if not batch and offset < total:
                raise EIAError("EIA pagination ended before all rows arrived")
            rows.extend(batch)
            offset += len(batch)
            if not batch or offset >= total:
                break
        if not rows:
            return pd.DataFrame(columns=["period", "series", "value"])
        df = pd.DataFrame(rows)
        if not {"period", "series", "value"}.issubset(df.columns):
            raise EIAError("EIA observations are missing required columns")
        df = df[["period", "series", "value"]]
        if df.duplicated(["period", "series"]).any():
            raise EIAError("EIA returned duplicate observations")
        df["period"] = pd.to_datetime(df["period"])
        df["value"] = pd.to_numeric(df["value"], errors="coerce").replace([np.inf, -np.inf], np.nan)
        return df.dropna(subset=["value"]).sort_values(["series", "period"]).reset_index(drop=True)

    def ng_futures(self, start: str = "1994-01-01") -> pd.DataFrame:
        """Daily settlement prices of NG contracts 1-4, one column per contract."""
        df = self.series(FUTURES_ROUTE, NG_CONTRACTS, "daily", start=start)
        wide = df.pivot(index="period", columns="series", values="value").sort_index()
        wide.index.name = "date"
        return wide[[c for c in NG_CONTRACTS if c in wide.columns]]

    def ng_storage(self, start: str = "1994-01-01") -> pd.Series:
        """Weekly Lower-48 working gas in storage (Bcf), indexed by week-ending date (Friday)."""
        df = self.series(STORAGE_ROUTE, [STORAGE_L48], "weekly", start=start)
        s = df.set_index("period")["value"].sort_index()
        s.index.name = "week_ending"
        s.name = "storage_bcf"
        return s

    # -- internals ----------------------------------------------------------------

    def _cache_path(self, path: str, params: dict) -> Path:
        key = json.dumps({"path": path, "params": params}, sort_keys=True)  # the API key is not part of it
        return self.cache_dir / (hashlib.sha256(key.encode()).hexdigest()[:24] + ".json")

    def _get(self, path: str, params: dict) -> dict:
        cache = self._cache_path(path, params)
        if cache.exists():
            return json.loads(cache.read_text())
        waited = 0.0
        delay = 60.0
        while True:
            r = self.session.get(f"{API_ROOT}/{path}", params={**params, "api_key": self.api_key}, timeout=60)
            if r.status_code == 429:
                if waited >= self.max_wait_s:
                    raise EIAError("EIA rate limit: still limited after waiting; set EIA_API_KEY to your own key")
                try:
                    retry_after = float(r.headers.get("Retry-After", delay))
                except (TypeError, ValueError):
                    retry_after = delay
                if not math.isfinite(retry_after) or retry_after <= 0:
                    retry_after = delay
                retry_after = min(retry_after, self.max_wait_s - waited)
                print(f"EIA rate limit hit; waiting {retry_after:.0f} s", flush=True)
                time.sleep(retry_after)
                waited += retry_after
                delay = min(delay * 2, 900.0)
                continue
            if r.status_code != 200:
                raise EIAError(f"EIA {r.status_code}: {r.text[:300]}")
            payload = r.json()
            if "response" not in payload:
                raise EIAError(f"unexpected EIA payload: {str(payload)[:300]}")
            tmp = None
            try:
                with tempfile.NamedTemporaryFile(mode="w", dir=self.cache_dir, suffix=".tmp", delete=False) as out:
                    tmp = Path(out.name)
                    json.dump(payload, out)
                tmp.replace(cache)
            finally:
                if tmp is not None:
                    tmp.unlink(missing_ok=True)
            return payload
