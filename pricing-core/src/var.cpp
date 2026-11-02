#include "pricing/var.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <exception>
#include <mutex>
#include <random>
#include <stdexcept>
#include <thread>

namespace pricing {

namespace {

constexpr double kTradingDays = 252.0;
constexpr std::size_t kChunk = 4096;  // paths per random stream

void check_factors(const Portfolio& p) {
  for (double price : p.prices)
    if (!std::isfinite(price) || price <= 0.0)
      throw std::invalid_argument("factor prices must be finite and positive");
  for (const auto& pos : p.positions) {
    if (pos.factor >= p.prices.size())
      throw std::invalid_argument("position refers to an unknown risk factor");
    if (!std::isfinite(pos.quantity) || !std::isfinite(pos.multiplier) || pos.multiplier <= 0.0)
      throw std::invalid_argument("positions require finite quantities and positive multipliers");
    if (pos.kind != Position::Kind::Future && pos.kind != Position::Kind::Option)
      throw std::invalid_argument("unknown position kind");
    if (pos.kind == Position::Kind::Option)
      (void)black76_price({pos.type, p.prices[pos.factor], pos.strike, pos.expiry, pos.rate, pos.vol});
  }
}

// SplitMix64: turns (seed, chunk) into well-spread seeds for independent streams.
std::uint64_t splitmix64(std::uint64_t x) {
  x += 0x9E3779B97F4A7C15ull;
  x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
  x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
  return x ^ (x >> 31);
}

}  // namespace

double Portfolio::value(const std::vector<double>& factor_prices, double elapsed) const {
  if (factor_prices.size() != prices.size())
    throw std::invalid_argument("valuation width != number of factors");
  if (!std::isfinite(elapsed) || elapsed < 0.0)
    throw std::invalid_argument("elapsed time must be finite and nonnegative");
  for (const auto& pos : positions)
    if (pos.factor >= prices.size()) throw std::invalid_argument("unknown position factor");
  double v = 0.0;
  for (const auto& pos : positions) {
    const double f = factor_prices[pos.factor];
    if (pos.kind == Position::Kind::Future) {
      v += pos.quantity * pos.multiplier * (f - prices[pos.factor]);
    } else {
      const OptionSpec o{pos.type, f, pos.strike, std::max(pos.expiry - elapsed, 0.0), pos.rate, pos.vol};
      v += pos.quantity * pos.multiplier * black76_price(o);
    }
  }
  return v;
}

VarResult var_from_pnl(std::vector<double>& pnl, double confidence) {
  if (pnl.empty()) throw std::invalid_argument("no scenarios");
  if (!(confidence > 0.0 && confidence < 1.0)) throw std::invalid_argument("confidence must be in (0, 1)");
  if (!std::all_of(pnl.begin(), pnl.end(), [](double x) { return std::isfinite(x); }))
    throw std::invalid_argument("scenario P&L must be finite");
  VarResult r;
  r.scenarios = pnl.size();
  // ceil((1 - c) N), guarded against 0.99 * 100 = 99.00000000000001-style rounding.
  const double raw = (1.0 - confidence) * static_cast<double>(pnl.size());
  r.tail = std::max<std::size_t>(1, static_cast<std::size_t>(std::ceil(raw - 1e-9)));
  // Only the worst k need to be in order.
  std::partial_sort(pnl.begin(), pnl.begin() + static_cast<std::ptrdiff_t>(r.tail), pnl.end());
  r.var = -pnl[r.tail - 1];
  r.es = -std::accumulate(pnl.begin(), pnl.begin() + static_cast<std::ptrdiff_t>(r.tail), 0.0) /
         static_cast<double>(r.tail);
  return r;
}

VarResult historical_var(const Portfolio& p, const std::vector<std::vector<double>>& log_returns,
                         double confidence, int horizon_days) {
  check_factors(p);
  if (horizon_days < 1) throw std::invalid_argument("horizon must be at least one day");
  const double scale = std::sqrt(static_cast<double>(horizon_days));
  const double elapsed = horizon_days / kTradingDays;
  const double base = p.value(p.prices, 0.0);

  std::vector<double> pnl;
  pnl.reserve(log_returns.size());
  std::vector<double> shocked(p.prices.size());
  for (const auto& row : log_returns) {
    if (row.size() != p.prices.size()) throw std::invalid_argument("scenario width != number of factors");
    for (std::size_t i = 0; i < shocked.size(); ++i) {
      if (!std::isfinite(row[i])) throw std::invalid_argument("historical returns must be finite");
      shocked[i] = p.prices[i] * std::exp(scale * row[i]);
    }
    pnl.push_back(p.value(shocked, elapsed) - base);
  }
  return var_from_pnl(pnl, confidence);
}

std::vector<double> cholesky(const std::vector<double>& a, std::size_t n) {
  if (n == 0 || n > a.size() || a.size() / n != n || a.size() % n != 0)
    throw std::invalid_argument("matrix size mismatch");
  if (!std::all_of(a.begin(), a.end(), [](double x) { return std::isfinite(x); }))
    throw std::invalid_argument("matrix entries must be finite");
  std::vector<double> l(n * n, 0.0);
  for (std::size_t i = 0; i < n; ++i) {
    for (std::size_t j = 0; j <= i; ++j) {
      if (std::fabs(a[i * n + j] - a[j * n + i]) > 1e-12)
        throw std::invalid_argument("matrix is not symmetric");
      double s = a[i * n + j];
      for (std::size_t k = 0; k < j; ++k) s -= l[i * n + k] * l[j * n + k];
      if (i == j) {
        if (s <= 0.0) throw std::invalid_argument("matrix is not positive definite");
        l[i * n + i] = std::sqrt(s);
      } else {
        l[i * n + j] = s / l[j * n + j];
      }
    }
  }
  return l;
}

VarResult monte_carlo_var(const Portfolio& p, const std::vector<double>& vols,
                          const std::vector<double>& correlation, double confidence,
                          const MonteCarloConfig& cfg) {
  check_factors(p);
  const std::size_t n = p.prices.size();
  if (vols.size() != n) throw std::invalid_argument("one vol per factor");
  if (cfg.paths == 0) throw std::invalid_argument("paths must be positive");
  if (cfg.horizon_days < 1) throw std::invalid_argument("horizon must be at least one day");
  if (!(confidence > 0.0 && confidence < 1.0)) throw std::invalid_argument("confidence must be in (0, 1)");
  if (cfg.threads < 0) throw std::invalid_argument("threads must be nonnegative");
  for (double vol : vols)
    if (!std::isfinite(vol) || vol < 0.0) throw std::invalid_argument("volatility must be finite and nonnegative");
  const std::vector<double> chol = cholesky(correlation, n);
  for (std::size_t i = 0; i < n; ++i)
    if (std::fabs(correlation[i * n + i] - 1.0) > 1e-12)
      throw std::invalid_argument("correlation diagonal must equal one");

  const double h = cfg.horizon_days / kTradingDays;
  std::vector<double> sd(n), drift(n);
  for (std::size_t i = 0; i < n; ++i) {
    sd[i] = vols[i] * std::sqrt(h);
    drift[i] = -0.5 * sd[i] * sd[i];  // keeps E[F(h)] = F: futures prices are martingales
  }
  const double base = p.value(p.prices, 0.0);

  std::vector<double> pnl(cfg.paths);
  const std::size_t chunks = (cfg.paths + kChunk - 1) / kChunk;

  auto run_chunk = [&](std::size_t c) {
    std::mt19937_64 rng(splitmix64(cfg.seed ^ splitmix64(c)));
    std::normal_distribution<double> normal;
    std::vector<double> z(n), shocked(n);
    const std::size_t end = std::min(cfg.paths, (c + 1) * kChunk);
    for (std::size_t path = c * kChunk; path < end; ++path) {
      for (auto& zi : z) zi = normal(rng);
      for (std::size_t i = 0; i < n; ++i) {
        double corr = 0.0;
        for (std::size_t k = 0; k <= i; ++k) corr += chol[i * n + k] * z[k];
        shocked[i] = p.prices[i] * std::exp(drift[i] + sd[i] * corr);
      }
      pnl[path] = p.value(shocked, h) - base;
    }
  };

  unsigned threads =
      cfg.threads > 0 ? static_cast<unsigned>(cfg.threads) : std::thread::hardware_concurrency();
  threads = std::max(1u, std::min<unsigned>(threads, static_cast<unsigned>(chunks)));
  if (threads == 1) {
    for (std::size_t c = 0; c < chunks; ++c) run_chunk(c);
  } else {
    // Static round-robin: thread t takes chunks t, t + T, t + 2T, ...
    // Each chunk writes only its own slice of `pnl`, so no locking is needed.
    std::exception_ptr error;
    std::mutex error_mutex;
    std::vector<std::jthread> pool;
    pool.reserve(threads);
    for (unsigned t = 0; t < threads; ++t)
      pool.emplace_back([&, t] {
        try {
          for (std::size_t c = t; c < chunks; c += threads) run_chunk(c);
        } catch (...) {
          std::lock_guard lock(error_mutex);
          if (!error) error = std::current_exception();
        }
      });
    for (auto& th : pool) th.join();
    if (error) std::rethrow_exception(error);
  }
  return var_from_pnl(pnl, confidence);
}

}  // namespace pricing
