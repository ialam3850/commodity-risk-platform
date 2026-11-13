# Commodity risk platform

A futures ledger, pricing core, and research pipeline for commodity risk.

## Available components

- `pricing-core/`: C++20 Black-76 prices, Greeks, implied volatility, and tests.
- Historical and Monte Carlo VaR, expected shortfall, portfolio valuation, and pricing fixtures.
- `research/`: futures contract calendars and roll-adjusted nearby returns.
- EIA futures and storage downloads with pagination, retries, and a local cache.
- Storage surprises based on a five-year norm and a trading-day release calendar.
- Walk-forward backtests, transaction costs, committed observations, and result plots.
- `ledger-service/`: Java 21, PostgreSQL migrations, product rules, and instrument endpoints.
- Fill booking, idempotent retries, balanced postings, deposits, and book balances.
- Batch settlement prices and factor returns with request validation.

## Run the checks

Run these commands from the repository root.

C++ requires CMake 3.20+, Ninja, and a C++20 compiler.

```bash
cmake -S pricing-core -B pricing-core/build -G Ninja -DPC_BUILD_PYTHON=OFF
cmake --build pricing-core/build
ctest --test-dir pricing-core/build --output-on-failure
```

Python requires Python 3.10 or later.

```bash
python -m pip install -r research/requirements.txt
(cd research && python -m unittest discover -s tests -v)
```

Run the backtest on the committed observations:

```bash
python research/scripts/run_backtest.py
```

The service requires Java 21. Unit tests run without a database; the full
suite starts PostgreSQL through Testcontainers and requires Docker.

```bash
(cd ledger-service && ./mvnw test)
(cd ledger-service && ./mvnw verify)
```
