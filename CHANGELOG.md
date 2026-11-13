# Changes

## Store settlements and factor returns

Add validated batch market-data endpoints, transactional rollback checks, and repeat-upsert tests.

## Book fills with balanced postings and idempotent retries

Add book balances, deposits, fill booking, and append-only accounting. Check retries, concurrency, and ledger invariants.

## Start the Java service and instrument database

Add the Spring Boot application, PostgreSQL schema, product rules, instrument endpoints, and database smoke tests.

## Add walk-forward backtests and committed data

Select parameters on prior years, account for transaction costs, and handle empty trading periods.

## Add the storage surprise signal

Build release dates and a five-year storage norm using only observations available before each release.

## Add the EIA data client

Fetch futures and storage observations with pagination, caching, bounded retries, and offline client tests.

## Add contract calendars and roll-adjusted returns

Handle nearby futures contracts and calculate returns without treating a contract roll as a market move.

## Add historical and Monte Carlo portfolio risk

Value futures and option books, calculate VaR and expected shortfall, and add benchmarks and shared pricing fixtures.

## Add Black-76 option pricing and Greeks

Implement futures option prices, Greeks, and implied volatility with domain checks and reference tests.

## Set up the repository

Add the project outline, license notices, formatting rules, ignore rules, and repository checks.
