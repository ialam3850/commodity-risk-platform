#include "pricing/black76.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace pricing {

namespace {
constexpr double kInvSqrt2 = 0.70710678118654752440;
constexpr double kInvSqrt2Pi = 0.39894228040143267794;
constexpr double kTwoPi = 6.28318530717958647693;

bool is_call(OptionType t) {
  return t == OptionType::Call;
}

// Discounted intrinsic value: what the option is worth with zero time value.
double intrinsic(const OptionSpec& o) {
  const double df = std::exp(-o.rate * std::max(o.expiry, 0.0));
  const double payoff = is_call(o.type) ? o.forward - o.strike : o.strike - o.forward;
  return df * std::max(payoff, 0.0);
}
}  // namespace

double norm_cdf(double x) {
  return 0.5 * std::erfc(-x * kInvSqrt2);
}
double norm_pdf(double x) {
  return kInvSqrt2Pi * std::exp(-0.5 * x * x);
}

double black76_price(const OptionSpec& o) {
  return black76_greeks(o).price;
}

Greeks black76_greeks(const OptionSpec& o) {
  if ((o.type != OptionType::Call && o.type != OptionType::Put) ||
      !std::isfinite(o.forward) || !std::isfinite(o.strike) || !std::isfinite(o.expiry) ||
      !std::isfinite(o.rate) || !std::isfinite(o.vol) || o.forward <= 0.0 || o.strike <= 0.0 ||
      o.expiry < 0.0 || o.vol < 0.0)
    throw std::invalid_argument("Black-76 requires finite inputs, positive prices, and nonnegative time and volatility");
  Greeks g{};
  const double sd = o.vol * std::sqrt(std::max(o.expiry, 0.0));  // total standard deviation of ln F
  const double df = std::exp(-o.rate * std::max(o.expiry, 0.0));

  // Expired, or no volatility: the option is worth its discounted intrinsic
  // value, and the only non-zero sensitivity is the step-function delta.
  if (o.expiry <= 0.0 || sd <= 0.0 || o.forward <= 0.0 || o.strike <= 0.0) {
    g.price = intrinsic(o);
    const bool itm = is_call(o.type) ? o.forward > o.strike : o.forward < o.strike;
    g.delta = itm ? (is_call(o.type) ? df : -df) : 0.0;
    g.rho = -std::max(o.expiry, 0.0) * g.price;
    return g;
  }

  const double d1 = (std::log(o.forward / o.strike) + 0.5 * sd * sd) / sd;
  const double d2 = d1 - sd;
  const double nd1 = norm_pdf(d1);
  const double sqrt_t = std::sqrt(o.expiry);

  if (is_call(o.type)) {
    g.price = df * (o.forward * norm_cdf(d1) - o.strike * norm_cdf(d2));
    g.delta = df * norm_cdf(d1);
  } else {
    g.price = df * (o.strike * norm_cdf(-d2) - o.forward * norm_cdf(-d1));
    g.delta = -df * norm_cdf(-d1);
  }
  // Gamma and vega are the same for calls and puts (put-call parity is linear in F).
  g.gamma = df * nd1 / (o.forward * sd);
  g.vega = df * o.forward * nd1 * sqrt_t;
  // V = e^{-rT} B(T): dV/dT = -r V + e^{-rT} F n(d1) sigma / (2 sqrt T); theta = -dV/dT.
  g.theta = o.rate * g.price - df * o.forward * nd1 * o.vol / (2.0 * sqrt_t);
  // Only the discount factor depends on r (F is a traded price, not r-driven).
  g.rho = -o.expiry * g.price;
  return g;
}

std::optional<double> implied_vol(double price, OptionType type, double forward, double strike, double expiry,
                                  double rate, double tol, int max_iter) {
  if ((type != OptionType::Call && type != OptionType::Put) ||
      !std::isfinite(price) || !std::isfinite(forward) || !std::isfinite(strike) ||
      !std::isfinite(expiry) || !std::isfinite(rate) || !std::isfinite(tol) ||
      tol <= 0.0 || max_iter < 1 || expiry <= 0.0 || forward <= 0.0 || strike <= 0.0)
    return std::nullopt;
  OptionSpec o{type, forward, strike, expiry, rate, 0.0};
  const double df = std::exp(-rate * expiry);
  const double lower = intrinsic(o);
  const double upper = df * (is_call(type) ? forward : strike);
  if (!(price > lower) || !(price < upper)) return std::nullopt;

  // Price is increasing in vol, so [lo, hi] always brackets the answer.
  double lo = 1e-9, hi = 10.0;
  o.vol = hi;
  while (black76_price(o) < price && hi < 1e3) {
    hi *= 2.0;
    o.vol = hi;
  }

  // Starting guess from the Brenner-Subrahmanyam at-the-money approximation.
  double vol = std::clamp(std::sqrt(kTwoPi / expiry) * price / (df * forward), 0.01, hi);
  for (int i = 0; i < max_iter; ++i) {
    o.vol = vol;
    const Greeks g = black76_greeks(o);
    const double diff = g.price - price;
    if (std::fabs(diff) < tol) return vol;
    if (diff > 0.0) {
      hi = vol;
    } else {
      lo = vol;
    }
    double next = g.vega > 1e-12 ? vol - diff / g.vega : -1.0;
    if (!(next > lo && next < hi)) next = 0.5 * (lo + hi);  // Newton left the bracket: bisect
    if (std::fabs(next - vol) < 1e-15) {
      o.vol = next;
      return std::fabs(black76_price(o) - price) < tol ? std::optional<double>(next) : std::nullopt;
    }
    vol = next;
  }
  o.vol = vol;
  return std::fabs(black76_price(o) - price) < tol ? std::optional<double>(vol) : std::nullopt;
}

}  // namespace pricing
