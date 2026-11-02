#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <numeric>
#include <random>
#include <stdexcept>

#include "pricing/var.h"

using namespace pricing;

namespace {

Position future(std::size_t factor, double qty, double mult) {
  Position p;
  p.kind = Position::Kind::Future;
  p.factor = factor;
  p.quantity = qty;
  p.multiplier = mult;
  return p;
}

Position option(std::size_t factor, double qty, OptionType t, double k, double T, double vol) {
  Position p;
  p.kind = Position::Kind::Option;
  p.factor = factor;
  p.quantity = qty;
  p.multiplier = 10'000;
  p.type = t;
  p.strike = k;
  p.expiry = T;
  p.vol = vol;
  p.rate = 0.04;
  return p;
}

}  // namespace

TEST(VarFromPnl, UsesCeilingTailCount) {
  // P&L -1, -2, ..., -100 plus 100 zeros: 200 scenarios, 99% -> worst 2.
  std::vector<double> pnl(200, 0.0);
  for (int i = 0; i < 100; ++i) pnl[static_cast<std::size_t>(i)] = -(i + 1.0);
  const VarResult r = var_from_pnl(pnl, 0.99);
  EXPECT_EQ(r.tail, 2u);
  EXPECT_DOUBLE_EQ(r.var, 99.0);
  EXPECT_DOUBLE_EQ(r.es, 99.5);
}

TEST(VarFromPnl, ExactMultipleIsNotRoundedUp) {
  // 0.99 * 100 has a floating-point error; the tail must still be exactly 1.
  std::vector<double> pnl(100);
  std::iota(pnl.begin(), pnl.end(), -50.0);
  EXPECT_EQ(var_from_pnl(pnl, 0.99).tail, 1u);
}

TEST(HistoricalVar, LinearBookMatchesHandCalculation) {
  // Long 2 NG futures at $3.00: P&L = 2 * 10,000 * 3.00 * (e^r - 1).
  Portfolio p{{3.0}, {future(0, 2, 10'000)}};
  std::vector<std::vector<double>> rets;
  for (int i = 1; i <= 100; ++i) rets.push_back({(i - 50) * 0.001});  // -4.9% .. +5.0%
  const VarResult r = historical_var(p, rets, 0.95);
  EXPECT_EQ(r.tail, 5u);
  EXPECT_NEAR(r.var, -2 * 10'000 * 3.0 * (std::exp(-0.045) - 1.0), 1e-6);  // 5th worst return is -4.5%
  EXPECT_GT(r.es, r.var);
}

TEST(HistoricalVar, ShortBookLosesOnRallies) {
  Portfolio p{{3.0}, {future(0, -1, 10'000)}};
  const std::vector<std::vector<double>> rets = {{-0.10}, {0.0}, {0.10}};
  const VarResult r = historical_var(p, rets, 0.6);  // tail = ceil(0.4 * 3) = 2
  EXPECT_NEAR(r.es, 0.5 * 10'000 * 3.0 * (std::exp(0.10) - 1.0), 1e-6);
}

TEST(HistoricalVar, RejectsBadInputs) {
  Portfolio p{{3.0}, {future(1, 1, 10'000)}};
  EXPECT_THROW(historical_var(p, {{0.0}}, 0.99), std::invalid_argument);  // factor 1 doesn't exist
  Portfolio q{{3.0}, {future(0, 1, 10'000)}};
  EXPECT_THROW(historical_var(q, {{0.0, 0.0}}, 0.99), std::invalid_argument);
  EXPECT_THROW(historical_var(q, {{0.0}}, 1.0), std::invalid_argument);
}

TEST(Cholesky, KnownTwoByTwo) {
  const auto l = cholesky({4, 2, 2, 3}, 2);
  EXPECT_DOUBLE_EQ(l[0], 2.0);
  EXPECT_DOUBLE_EQ(l[1], 0.0);
  EXPECT_DOUBLE_EQ(l[2], 1.0);
  EXPECT_NEAR(l[3], std::sqrt(2.0), 1e-15);
  EXPECT_THROW(cholesky({1, 2, 2, 1}, 2), std::invalid_argument);      // not positive definite
  EXPECT_THROW(cholesky({1, 0.5, 0.4, 1}, 2), std::invalid_argument);  // not symmetric
}

TEST(MonteCarloVar, LinearBookMatchesLognormalFormula) {
  // One long future: P&L = Q (F e^{X} - F), X ~ N(-s^2/2, s^2), so the loss
  // quantile is known in closed form: VaR = -Q F (e^{-s^2/2 - 2.3263 s} - 1).
  const double F = 3.0, Q = 10 * 10'000.0, vol = 0.6;
  Portfolio p{{F}, {future(0, 10, 10'000)}};
  MonteCarloConfig cfg;
  cfg.paths = 400'000;
  const VarResult r = monte_carlo_var(p, {vol}, {1.0}, 0.99, cfg);
  const double s = vol * std::sqrt(1.0 / 252);
  const double exact = -Q * F * (std::exp(-0.5 * s * s - 2.326347874 * s) - 1.0);
  EXPECT_NEAR(r.var / exact, 1.0, 0.01);
}

TEST(MonteCarloVar, SameAnswerForAnyThreadCount) {
  Portfolio p{{3.0, 3.2},
              {future(0, 5, 10'000), future(1, -3, 10'000), option(0, -4, OptionType::Call, 3.5, 0.2, 0.7)}};
  const std::vector<double> corr = {1.0, 0.95, 0.95, 1.0};
  MonteCarloConfig cfg;
  cfg.paths = 50'000;
  cfg.threads = 1;
  const VarResult one = monte_carlo_var(p, {0.6, 0.55}, corr, 0.99, cfg);
  for (int t : {2, 3, 8}) {
    cfg.threads = t;
    const VarResult many = monte_carlo_var(p, {0.6, 0.55}, corr, 0.99, cfg);
    EXPECT_EQ(one.var, many.var) << t << " threads";
    EXPECT_EQ(one.es, many.es) << t << " threads";
  }
}

TEST(MonteCarloVar, CalendarSpreadIsSaferThanOutright) {
  // Long Jan / short Feb with correlation 0.95 risks much less than long Jan alone.
  const std::vector<double> corr = {1.0, 0.95, 0.95, 1.0};
  MonteCarloConfig cfg;
  cfg.paths = 100'000;
  Portfolio outright{{3.0, 3.2}, {future(0, 10, 10'000)}};
  Portfolio spread{{3.0, 3.2}, {future(0, 10, 10'000), future(1, -10, 10'000)}};
  const double v_out = monte_carlo_var(outright, {0.6, 0.6}, corr, 0.99, cfg).var;
  const double v_spr = monte_carlo_var(spread, {0.6, 0.6}, corr, 0.99, cfg).var;
  EXPECT_LT(v_spr, 0.4 * v_out);  // sqrt(2 (1 - 0.95)) ~ 0.32 for equal sizes
}

TEST(MonteCarloVar, LongOptionCannotLoseMoreThanItsPremium) {
  const Position c = option(0, 1, OptionType::Call, 4.5, 0.05, 1.0);  // far OTM, nearly worthless
  Portfolio p{{3.0}, {c}};
  const double premium = 10'000 * black76_price({c.type, 3.0, c.strike, c.expiry, c.rate, c.vol});
  MonteCarloConfig cfg;
  cfg.paths = 20'000;
  cfg.horizon_days = 10;
  const VarResult r = monte_carlo_var(p, {1.0}, {1.0}, 0.99, cfg);
  EXPECT_LE(r.es, premium + 1e-9);
  EXPECT_GT(r.var, 0.0);
}

TEST(MonteCarloVar, ShortOptionsHaveFatterTailsThanLinearEquivalent) {
  // A short at-the-money straddle is delta-neutral, but gamma makes both big
  // up and big down moves expensive, so its VaR is far above zero.
  Portfolio p{
      {3.0},
      {option(0, -10, OptionType::Call, 3.0, 0.1, 0.6), option(0, -10, OptionType::Put, 3.0, 0.1, 0.6)}};
  MonteCarloConfig cfg;
  cfg.paths = 50'000;
  cfg.horizon_days = 5;
  const VarResult r = monte_carlo_var(p, {0.6}, {1.0}, 0.99, cfg);
  EXPECT_GT(r.var, 0.0);
  EXPECT_GE(r.es, r.var);
}

TEST(VarFromPnl, RejectsNonFiniteScenariosBeforeSorting) {
  for (double x : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity(),
                   -std::numeric_limits<double>::infinity()}) {
    std::vector<double> pnl{-10, x, 20};
    EXPECT_THROW(var_from_pnl(pnl, 0.99), std::invalid_argument);
  }
}

TEST(VarFromPnl, HandlesSingleScenariosAndTiedTails) {
  std::vector<double> one{-17.5};
  const auto result = var_from_pnl(one, 0.99);
  EXPECT_DOUBLE_EQ(result.var, 17.5);
  EXPECT_DOUBLE_EQ(result.es, 17.5);
  std::vector<double> tied{-3, -3, 1, 2};
  EXPECT_DOUBLE_EQ(var_from_pnl(tied, 0.5).es, 3.0);
}

TEST(Portfolio, RejectsValuationShapeErrors) {
  Portfolio p{{3.0}, {future(0, 1, 10000)}};
  EXPECT_THROW(p.value({}), std::invalid_argument);
  EXPECT_THROW(p.value({3, 4}), std::invalid_argument);
  EXPECT_THROW(p.value({3}, -1), std::invalid_argument);
  p.positions[0].factor = 1;
  EXPECT_THROW(p.value({3}), std::invalid_argument);
}

TEST(HistoricalVar, RejectsInvalidFactorPricesAndReturns) {
  Portfolio p{{3.0}, {future(0, 1, 10000)}};
  EXPECT_THROW(historical_var(p, {{std::numeric_limits<double>::quiet_NaN()}}, 0.99), std::invalid_argument);
  p.prices[0] = 0;
  EXPECT_THROW(historical_var(p, {{0.01}}, 0.99), std::invalid_argument);
  p.prices[0] = 3;
  p.positions[0].multiplier = -1;
  EXPECT_THROW(historical_var(p, {{0.01}}, 0.99), std::invalid_argument);
}

TEST(Cholesky, RejectsEmptyAndNonFiniteMatrices) {
  EXPECT_THROW(cholesky({}, 0), std::invalid_argument);
  EXPECT_THROW(cholesky({1}, std::numeric_limits<std::size_t>::max()), std::invalid_argument);
  EXPECT_THROW(cholesky({std::numeric_limits<double>::quiet_NaN()}, 1), std::invalid_argument);
}

TEST(MonteCarloVar, RejectsInvalidVolatilityAndCorrelation) {
  Portfolio p{{3.0}, {future(0, 1, 10000)}};
  MonteCarloConfig cfg;
  cfg.paths = 10;
  EXPECT_THROW(monte_carlo_var(p, {-0.1}, {1}, 0.99, cfg), std::invalid_argument);
  EXPECT_THROW(monte_carlo_var(p, {std::numeric_limits<double>::infinity()}, {1}, 0.99, cfg), std::invalid_argument);
  EXPECT_THROW(monte_carlo_var(p, {0.6}, {2}, 0.99, cfg), std::invalid_argument);
  cfg.threads = -1;
  EXPECT_THROW(monte_carlo_var(p, {0.6}, {1}, 0.99, cfg), std::invalid_argument);
}

TEST(MonteCarloVar, WorkerErrorsAreReturnedAsExceptions) {
  Portfolio p{{3.0}, {option(0, 1, OptionType::Call, 3.5, 0.5, 0.6)}};
  MonteCarloConfig cfg;
  cfg.paths = 10000;
  for (int threads : {1, 2}) {
    cfg.threads = threads;
    EXPECT_THROW(monte_carlo_var(p, {1e308}, {1}, 0.99, cfg), std::invalid_argument);
  }
}
