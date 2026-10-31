#pragma once

#include <optional>

// Black (1976) model for European options on futures.
//
// A futures price has no carry (you pay nothing to hold a futures contract),
// so Black-Scholes with the spot price replaced by the futures price F and
// no dividend/drift term gives:
//
//   call = e^{-rT} [ F N(d1) - K N(d2) ]
//   put  = e^{-rT} [ K N(-d2) - F N(-d1) ]
//   d1   = [ ln(F/K) + sigma^2 T / 2 ] / (sigma sqrt(T)),   d2 = d1 - sigma sqrt(T)
//
// NYMEX natural gas (ON) and crude (LO) options are options on futures, so
// this is the model the market quotes their implied volatilities in.
//
// Units: T in years, r and sigma annualised (continuous compounding),
// prices per unit of the underlying (e.g. $/MMBtu). Multiply by the
// contract size to get dollars per contract.
namespace pricing {

enum class OptionType { Call, Put };

struct OptionSpec {
  OptionType type;
  double forward;  // F: futures price
  double strike;   // K
  double expiry;   // T: years to expiry
  double rate;     // r: continuously compounded risk-free rate
  double vol;      // sigma: annualised volatility of F
};

struct Greeks {
  double price;
  double delta;  // dV/dF
  double gamma;  // d2V/dF2
  double vega;   // dV/dsigma, per 1.00 of vol (divide by 100 for "per vol point")
  double theta;  // dV/dt as calendar time passes (= -dV/dT), per year
  double rho;    // dV/dr
};

double black76_price(const OptionSpec& o);
Greeks black76_greeks(const OptionSpec& o);

// Volatility that reproduces `price`. Newton's method on vega, falling back to
// bisection when a Newton step would leave the bracket. Returns nullopt when
// the price is outside the no-arbitrage bounds (below discounted intrinsic
// value or above the discounted forward/strike), where no volatility exists.
std::optional<double> implied_vol(double price, OptionType type, double forward, double strike, double expiry,
                                  double rate, double tol = 1e-10, int max_iter = 100);

// Standard normal CDF and density, exposed for tests and the VaR code.
double norm_cdf(double x);
double norm_pdf(double x);

}  // namespace pricing
