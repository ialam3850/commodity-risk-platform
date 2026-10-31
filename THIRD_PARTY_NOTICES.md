# Third-party notices

No third-party source code is copied into this repository. Dependencies are
fetched at build time:

| Dependency | Version | License | Used by |
|---|---|---|---|
| Spring Boot (web, validation, JDBC, actuator) | 3.5.16 | Apache-2.0 | ledger-service |
| Flyway | managed by Spring Boot | Apache-2.0 | ledger-service |
| PostgreSQL JDBC driver | managed by Spring Boot | BSD-2-Clause | ledger-service |
| springdoc-openapi | 2.8.17 | Apache-2.0 | ledger-service |
| JUnit 5, AssertJ, Testcontainers | managed by Spring Boot | EPL-2.0 / Apache-2.0 / MIT | ledger-service tests |
| Maven Wrapper | 3.3.4 | Apache-2.0 | ledger-service (`mvnw`) |
| GoogleTest | 1.15.2 | BSD-3-Clause | pricing-core tests |
| pybind11 | 2.13.6 | BSD-3-Clause | pricing-core Python module |
| numpy, pandas, matplotlib, requests | see research/requirements.txt | BSD / PSF-based / Apache-2.0 | research |

## Data

`research/data/*.csv` are U.S. Energy Information Administration data
(NYMEX natural gas futures settlement prices, contracts 1–4; weekly working
gas in storage, Lower 48), downloaded from the EIA Open Data API. EIA data is
public domain; EIA asks that it be cited as the source. Futures prices are
originally from the CME Group / NYMEX as republished by EIA.

## Algorithms and references (no code copied)

* F. Black, "The pricing of commodity contracts", *Journal of Financial Economics* 3 (1976).
* E. G. Haug, *The Complete Guide to Option Pricing Formulas*, 2nd ed. (2007) — Black-76 reference value used in tests.
* J. C. Hull, *Options, Futures, and Other Derivatives* — futures-option example used in tests.
* W. J. Cody, "Rational Chebyshev approximations for the error function", *Mathematics of Computation* 23 (1969) — `erfc` in the Java service.
* P. Jorion, *Value at Risk*, 3rd ed. (2006) — historical simulation and Monte Carlo VaR, expected shortfall.
* CME Group contract specifications for Henry Hub Natural Gas (NG) and WTI Crude Oil (CL) futures — multipliers, tick sizes and last-trading-day rules.
* Stripe, "Designing robust and predictable APIs with idempotency" — the Idempotency-Key pattern.
