#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "pricing/black76.h"

// Value at Risk for a book of futures and options on futures.
//
// Risk factors are futures prices (one per contract month, e.g. NG Jan-27,
// NG Feb-27, CL Jan-27). Every position is revalued in full under each
// scenario (no delta/gamma shortcut), so option convexity is captured.
//
// Conventions:
//   * P&L is in dollars: quantity (contracts) x multiplier (units/contract) x price change.
//   * VaR at confidence c is the loss that is exceeded with probability 1 - c,
//     reported as a positive number. Expected shortfall (ES) is the average
//     loss in that tail.
//   * Empirical quantile: with N scenarios sorted from worst to best, the
//     tail is the worst k = ceil((1 - c) N) of them; VaR is the k-th worst
//     loss and ES the mean of all k.
namespace pricing {

struct Position {
  enum class Kind { Future, Option };
  Kind kind = Kind::Future;
  std::size_t factor = 0;   // index of the futures price this position depends on
  double quantity = 0.0;    // contracts, negative = short
  double multiplier = 0.0;  // units per contract (NG: 10,000 MMBtu; CL: 1,000 bbl)
  // Options only:
  OptionType type = OptionType::Call;
  double strike = 0.0;
  double expiry = 0.0;  // years, today
  double vol = 0.0;     // held constant across scenarios (no vol shocks)
  double rate = 0.0;
};

struct Portfolio {
  std::vector<double> prices;  // today's futures price for each factor
  std::vector<Position> positions;

  // Dollar value of the book if futures prices were `factor_prices` and
  // `elapsed` years had passed (time decay for options). Futures positions
  // are valued relative to today's price, i.e. as their variation margin,
  // since a futures contract itself costs nothing to hold.
  double value(const std::vector<double>& factor_prices, double elapsed = 0.0) const;
};

struct VarResult {
  double var = 0.0;  // positive loss
  double es = 0.0;   // expected shortfall, >= var
  std::size_t scenarios = 0;
  std::size_t tail = 0;  // k: scenarios in the tail
};

// Historical simulation: each row of `log_returns` (scenarios x factors) is
// one past day's change in ln(price). Each is applied to today's prices.
// For a horizon of h days the returns are scaled by sqrt(h) - the usual
// shortcut, exact only for i.i.d. normal returns; overlapping h-day windows
// are the alternative.
VarResult historical_var(const Portfolio& p, const std::vector<std::vector<double>>& log_returns,
                         double confidence, int horizon_days = 1);

// Monte Carlo: correlated lognormal shocks with zero drift,
//   ln F_i(h) = ln F_i + sigma_i sqrt(h/252) Z_i - sigma_i^2 h / (2*252),   Z ~ N(0, C),
// where C = `correlation` (row-major, factors x factors, must be positive
// definite). Paths are split into fixed-size chunks, each with its own
// random stream seeded from (seed, chunk index), so the answer is identical
// for any `threads` value.
struct MonteCarloConfig {
  std::size_t paths = 100'000;
  int horizon_days = 1;
  std::uint64_t seed = 42;
  int threads = 0;  // 0 = std::thread::hardware_concurrency()
};
VarResult monte_carlo_var(const Portfolio& p, const std::vector<double>& vols,
                          const std::vector<double>& correlation, double confidence,
                          const MonteCarloConfig& cfg);

// Exposed for tests: VaR/ES of a list of P&L values (destroys order).
VarResult var_from_pnl(std::vector<double>& pnl, double confidence);

// Lower-triangular L with L L^T = A (row-major n x n). Throws std::invalid_argument
// if A is not symmetric positive definite.
std::vector<double> cholesky(const std::vector<double>& a, std::size_t n);

}  // namespace pricing
