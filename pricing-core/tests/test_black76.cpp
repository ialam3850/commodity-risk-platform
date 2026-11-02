#include <gtest/gtest.h>

#include <cmath>
#include <limits>
#include <stdexcept>

#include "pricing/black76.h"

using namespace pricing;

namespace {
OptionSpec spec(OptionType t, double f, double k, double T, double r, double v) {
  return {t, f, k, T, r, v};
}
}  // namespace

// Haug, The Complete Guide to Option Pricing Formulas (2nd ed.), Black-76 example:
// F = 19, K = 19, T = 0.75, r = 10%, sigma = 28% -> call = put = 1.7011.
TEST(Black76, HaugReferenceValue) {
  EXPECT_NEAR(black76_price(spec(OptionType::Call, 19, 19, 0.75, 0.10, 0.28)), 1.7011, 5e-5);
  EXPECT_NEAR(black76_price(spec(OptionType::Put, 19, 19, 0.75, 0.10, 0.28)), 1.7011, 5e-5);
}

// Hull, Options, Futures, and Other Derivatives, European put on a futures:
// F = K = 20, r = 9%, T = 4 months, sigma = 25% -> 1.12.
TEST(Black76, HullReferenceValue) {
  EXPECT_NEAR(black76_price(spec(OptionType::Put, 20, 20, 4.0 / 12, 0.09, 0.25)), 1.12, 5e-3);
}

TEST(Black76, PutCallParity) {
  // c - p = e^{-rT} (F - K) for every strike, expiry and vol.
  for (double k : {2.0, 3.0, 3.5, 4.0, 6.0})
    for (double T : {0.02, 0.25, 1.0, 3.0})
      for (double v : {0.1, 0.5, 1.2}) {
        const double c = black76_price(spec(OptionType::Call, 3.5, k, T, 0.04, v));
        const double p = black76_price(spec(OptionType::Put, 3.5, k, T, 0.04, v));
        EXPECT_NEAR(c - p, std::exp(-0.04 * T) * (3.5 - k), 1e-12) << k << " " << T << " " << v;
      }
}

TEST(Black76, GreeksMatchFiniteDifferences) {
  for (OptionType t : {OptionType::Call, OptionType::Put})
    for (double k : {2.8, 3.5, 4.4}) {
      const OptionSpec o = spec(t, 3.5, k, 0.4, 0.05, 0.55);
      const Greeks g = black76_greeks(o);
      const auto bumped = [&](double df, double dv, double dT, double dr) {
        return black76_price(spec(t, o.forward + df, o.strike, o.expiry + dT, o.rate + dr, o.vol + dv));
      };
      const double hF = 1e-4, hv = 1e-5, hT = 1e-6, hr = 1e-6;
      EXPECT_NEAR(g.delta, (bumped(hF, 0, 0, 0) - bumped(-hF, 0, 0, 0)) / (2 * hF), 1e-7);
      EXPECT_NEAR(g.gamma, (bumped(hF, 0, 0, 0) - 2 * g.price + bumped(-hF, 0, 0, 0)) / (hF * hF), 1e-4);
      EXPECT_NEAR(g.vega, (bumped(0, hv, 0, 0) - bumped(0, -hv, 0, 0)) / (2 * hv), 1e-6);
      EXPECT_NEAR(g.theta, -(bumped(0, 0, hT, 0) - bumped(0, 0, -hT, 0)) / (2 * hT), 1e-5);
      EXPECT_NEAR(g.rho, (bumped(0, 0, 0, hr) - bumped(0, 0, 0, -hr)) / (2 * hr), 1e-6);
    }
}

TEST(Black76, ExpiredOptionIsIntrinsic) {
  EXPECT_DOUBLE_EQ(black76_price(spec(OptionType::Call, 4.0, 3.5, 0.0, 0.05, 0.5)), 0.5);
  EXPECT_DOUBLE_EQ(black76_price(spec(OptionType::Put, 4.0, 3.5, 0.0, 0.05, 0.5)), 0.0);
  // Zero vol: discounted intrinsic, delta is the discount factor or zero.
  const Greeks g = black76_greeks(spec(OptionType::Put, 3.0, 3.5, 1.0, 0.05, 0.0));
  EXPECT_NEAR(g.price, std::exp(-0.05) * 0.5, 1e-15);
  EXPECT_NEAR(g.delta, -std::exp(-0.05), 1e-15);
  EXPECT_EQ(g.gamma, 0.0);
}

TEST(Black76, PriceIsWithinNoArbitrageBounds) {
  for (double v : {0.01, 0.3, 1.0, 5.0}) {
    const double c = black76_price(spec(OptionType::Call, 3.0, 3.2, 0.5, 0.05, v));
    const double df = std::exp(-0.05 * 0.5);
    EXPECT_GE(c, 0.0);
    EXPECT_LE(c, df * 3.0);
  }
}

TEST(ImpliedVol, RoundTripsAcrossStrikesAndExpiries) {
  for (OptionType t : {OptionType::Call, OptionType::Put})
    for (double k : {1.5, 2.5, 3.0, 3.5, 5.0, 8.0})
      for (double T : {0.01, 0.1, 0.5, 2.0})
        for (double v : {0.05, 0.3, 0.8, 2.0}) {
          const double price = black76_price(spec(t, 3.0, k, T, 0.03, v));
          // Skip prices so close to intrinsic that vol is not identifiable in double precision.
          const double df = std::exp(-0.03 * T);
          const double intrinsic = df * std::max(t == OptionType::Call ? 3.0 - k : k - 3.0, 0.0);
          if (price - intrinsic < 1e-10) continue;
          const auto iv = implied_vol(price, t, 3.0, k, T, 0.03);
          ASSERT_TRUE(iv.has_value()) << k << " " << T << " " << v;
          // Compare prices rather than vols: deep OTM vol is ill-conditioned.
          EXPECT_NEAR(black76_price(spec(t, 3.0, k, T, 0.03, *iv)), price, 1e-9);
        }
}

TEST(ImpliedVol, RejectsPricesOutsideBounds) {
  const double df = std::exp(-0.03 * 0.5);
  EXPECT_FALSE(implied_vol(0.0, OptionType::Call, 3.0, 3.0, 0.5, 0.03).has_value());
  EXPECT_FALSE(implied_vol(df * 3.0, OptionType::Call, 3.0, 3.0, 0.5, 0.03).has_value());
  EXPECT_FALSE(implied_vol(df * 0.4, OptionType::Put, 3.0, 3.5, 0.5, 0.03).has_value());  // = intrinsic
  EXPECT_FALSE(implied_vol(0.1, OptionType::Put, 3.0, 3.5, 0.0, 0.03).has_value());       // expired
}

TEST(Black76, RejectsInvalidNumericsAndDomains) {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double inf = std::numeric_limits<double>::infinity();
  for (double x : {nan, inf, -inf}) {
    EXPECT_THROW(black76_price(spec(OptionType::Call, x, 3, 1, 0.04, 0.6)), std::invalid_argument);
    EXPECT_THROW(black76_price(spec(OptionType::Call, 3, x, 1, 0.04, 0.6)), std::invalid_argument);
    EXPECT_THROW(black76_price(spec(OptionType::Call, 3, 3, x, 0.04, 0.6)), std::invalid_argument);
    EXPECT_THROW(black76_price(spec(OptionType::Call, 3, 3, 1, x, 0.6)), std::invalid_argument);
    EXPECT_THROW(black76_price(spec(OptionType::Call, 3, 3, 1, 0.04, x)), std::invalid_argument);
  }
  EXPECT_THROW(black76_price(spec(OptionType::Call, 0, 3, 1, 0.04, 0.6)), std::invalid_argument);
  EXPECT_THROW(black76_price(spec(OptionType::Call, 3, -1, 1, 0.04, 0.6)), std::invalid_argument);
  EXPECT_THROW(black76_price(spec(OptionType::Call, 3, 3, -1, 0.04, 0.6)), std::invalid_argument);
  EXPECT_THROW(black76_price(spec(OptionType::Call, 3, 3, 1, 0.04, -0.6)), std::invalid_argument);
}

TEST(Black76, AllowsNegativeRatesAndZeroTimeValue) {
  EXPECT_GT(black76_price(spec(OptionType::Call, 4, 3, 1, -0.02, 0)), 1.0);
  EXPECT_DOUBLE_EQ(black76_price(spec(OptionType::Call, 4, 3, 0, 0.04, 0)), 1.0);
}

TEST(ImpliedVol, RejectsBadSolverSettingsAndNonFiniteInputs) {
  const double price = black76_price(spec(OptionType::Call, 3, 3.5, 0.5, 0.04, 0.7));
  EXPECT_FALSE(implied_vol(price, OptionType::Call, 3, 3.5, 0.5, 0.04, 0, 100));
  EXPECT_FALSE(implied_vol(price, OptionType::Call, 3, 3.5, 0.5, 0.04, 1e-10, 0));
  for (double x : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()}) {
    EXPECT_FALSE(implied_vol(x, OptionType::Call, 3, 3.5, 0.5, 0.04));
    EXPECT_FALSE(implied_vol(price, OptionType::Call, x, 3.5, 0.5, 0.04));
    EXPECT_FALSE(implied_vol(price, OptionType::Call, 3, 3.5, 0.5, x));
    EXPECT_FALSE(implied_vol(price, OptionType::Call, 3, 3.5, 0.5, 0.04, x));
  }
}

TEST(ImpliedVol, DoesNotReportAnUnconvergedIterationAsSuccess) {
  const double price = black76_price(spec(OptionType::Put, 3, 4.5, 0.8, 0.04, 1.7));
  EXPECT_FALSE(implied_vol(price, OptionType::Put, 3, 4.5, 0.8, 0.04, 1e-12, 1));
  const auto vol = implied_vol(price, OptionType::Put, 3, 4.5, 0.8, 0.04);
  ASSERT_TRUE(vol);
  EXPECT_NEAR(black76_price(spec(OptionType::Put, 3, 4.5, 0.8, 0.04, *vol)), price, 1e-10);
}
