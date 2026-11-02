// Monte Carlo VaR throughput: paths per second for a realistic book
// (12 NG contract months, futures plus options), by thread count.
//
//   ./bench_var [paths]

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <thread>

#include "pricing/var.h"

using namespace pricing;

int main(int argc, char** argv) {
  const std::size_t paths = argc > 1 ? std::strtoull(argv[1], nullptr, 10) : 200'000;
  constexpr std::size_t kMonths = 12;

  Portfolio p;
  std::vector<double> vols(kMonths);
  for (std::size_t i = 0; i < kMonths; ++i) {
    p.prices.push_back(3.0 + 0.1 * static_cast<double>(i % 4));  // winter months priced higher
    vols[i] = 0.65 - 0.02 * static_cast<double>(i);              // front months more volatile
    Position fut;
    fut.factor = i;
    fut.quantity = (i % 2 == 0) ? 10.0 : -8.0;
    fut.multiplier = 10'000;
    p.positions.push_back(fut);
    Position opt = fut;
    opt.kind = Position::Kind::Option;
    opt.quantity = -5.0;
    opt.type = (i % 2 == 0) ? OptionType::Call : OptionType::Put;
    opt.strike = p.prices[i];
    opt.expiry = 0.08 * static_cast<double>(i + 1);
    opt.vol = vols[i];
    opt.rate = 0.04;
    p.positions.push_back(opt);
  }
  // Correlation decays with distance between contract months.
  std::vector<double> corr(kMonths * kMonths);
  for (std::size_t i = 0; i < kMonths; ++i)
    for (std::size_t j = 0; j < kMonths; ++j)
      corr[i * kMonths + j] = std::pow(0.97, std::abs(static_cast<double>(i) - static_cast<double>(j)));

  std::printf("book: %zu factors, %zu positions; %zu paths, 99%% 1-day VaR\n", kMonths, p.positions.size(),
              paths);
  std::printf("%8s %10s %14s %14s\n", "threads", "seconds", "paths/s", "VaR ($)");
  const unsigned hw = std::max(1u, std::thread::hardware_concurrency());
  for (unsigned t = 1; t <= hw; t *= 2) {
    MonteCarloConfig cfg;
    cfg.paths = paths;
    cfg.threads = static_cast<int>(t);
    const auto t0 = std::chrono::steady_clock::now();
    const VarResult r = monte_carlo_var(p, vols, corr, 0.99, cfg);
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("%8u %10.3f %14.0f %14.0f\n", t, s, static_cast<double>(paths) / s, r.var);
  }
  return 0;
}
