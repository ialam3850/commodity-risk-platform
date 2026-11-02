// Writes a grid of Black-76 prices and Greeks to CSV. The Java service has its
// own Black-76 implementation (so it doesn't need to load native code), and its
// tests check it against this file: two independent implementations agreeing
// to 1e-9 is good evidence both are right.
//
//   ./gen_golden > ../testdata/black76_golden.csv

#include <cstdio>

#include "pricing/black76.h"

using namespace pricing;

int main() {
  std::printf("type,forward,strike,expiry,rate,vol,price,delta,gamma,vega,theta,rho\n");
  const double forwards[] = {2.0, 3.5, 70.0};
  const double moneyness[] = {0.7, 0.95, 1.0, 1.05, 1.4};
  const double expiries[] = {0.02, 0.25, 1.0, 2.5};
  const double rates[] = {0.0, 0.045};
  const double vols[] = {0.15, 0.45, 1.1};
  for (OptionType t : {OptionType::Call, OptionType::Put})
    for (double f : forwards)
      for (double m : moneyness)
        for (double T : expiries)
          for (double r : rates)
            for (double v : vols) {
              const double k = f * m;
              const Greeks g = black76_greeks({t, f, k, T, r, v});
              std::printf("%s,%.10g,%.10g,%.10g,%.10g,%.10g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g\n",
                          t == OptionType::Call ? "call" : "put", f, k, T, r, v, g.price, g.delta, g.gamma,
                          g.vega, g.theta, g.rho);
            }
  return 0;
}
