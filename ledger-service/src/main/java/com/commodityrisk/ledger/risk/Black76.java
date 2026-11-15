package com.commodityrisk.ledger.risk;

/**
 * Black (1976) prices and Greeks for European options on futures - the Java twin of
 * pricing-core/src/black76.cpp. Both are tested against testdata/black76_golden.csv, so the service
 * and the C++ library can't drift apart.
 *
 * <pre>
 *   call = e^{-rT} [F N(d1) - K N(d2)],  put = e^{-rT} [K N(-d2) - F N(-d1)]
 *   d1 = [ln(F/K) + sigma^2 T / 2] / (sigma sqrt T),  d2 = d1 - sigma sqrt T
 * </pre>
 */
public final class Black76 {

  public enum Type {
    CALL,
    PUT
  }

  public record Greeks(double price, double delta, double gamma, double vega, double theta, double rho) {}

  private Black76() {}

  public static Greeks greeks(Type type, double forward, double strike, double expiry, double rate, double vol) {
    if (type == null || !Double.isFinite(forward) || !Double.isFinite(strike)
        || !Double.isFinite(expiry) || !Double.isFinite(rate) || !Double.isFinite(vol)
        || forward <= 0 || strike <= 0 || expiry < 0 || vol < 0) {
      throw new IllegalArgumentException("Black-76 requires finite inputs, positive prices, and nonnegative time and volatility");
    }
    double t = Math.max(expiry, 0.0);
    double df = Math.exp(-rate * t);
    double sd = vol * Math.sqrt(t);
    boolean call = type == Type.CALL;

    if (expiry <= 0 || sd <= 0 || forward <= 0 || strike <= 0) {
      double price = df * Math.max(call ? forward - strike : strike - forward, 0.0);
      boolean itm = call ? forward > strike : forward < strike;
      double delta = itm ? (call ? df : -df) : 0.0;
      return new Greeks(price, delta, 0.0, 0.0, 0.0, -t * price);
    }

    double d1 = (Math.log(forward / strike) + 0.5 * sd * sd) / sd;
    double d2 = d1 - sd;
    double nd1 = pdf(d1);
    double price = call ? df * (forward * cdf(d1) - strike * cdf(d2)) : df * (strike * cdf(-d2) - forward * cdf(-d1));
    double delta = call ? df * cdf(d1) : -df * cdf(-d1);
    double gamma = df * nd1 / (forward * sd);
    double vega = df * forward * nd1 * Math.sqrt(expiry);
    double theta = rate * price - df * forward * nd1 * vol / (2.0 * Math.sqrt(expiry));
    double rho = -expiry * price;
    return new Greeks(price, delta, gamma, vega, theta, rho);
  }

  static double pdf(double x) {
    return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
  }

  /** Standard normal CDF via erfc, like std::erfc in the C++ version. */
  static double cdf(double x) {
    return 0.5 * erfc(-x / Math.sqrt(2.0));
  }

  /**
   * Complementary error function, W. J. Cody's rational Chebyshev approximations (the algorithm
   * behind most C library erfc implementations), accurate to about 1e-16 relative.
   */
  static double erfc(double x) {
    double ax = Math.abs(x);
    double r;
    if (ax < 0.5) {
      double z = x * x;
      double num = (((0.1857777061846031526730 * z + 3.161123743870565596947) * z + 113.8641541510501556495) * z
              + 377.4852376853020208137) * z + 3209.377589138469472562;
      double den = (((z + 23.60129095234412093499) * z + 244.0246379344441733056) * z + 1282.616526077372275645) * z
          + 2844.236833439170622273;
      return 1.0 - x * num / den;
    } else if (ax < 4.0) {
      double num = (((((((2.15311535474403846343e-8 * ax + 0.564188496988670089180) * ax + 8.88314979438837594118)
                              * ax + 66.1191906371416294775) * ax + 298.635138197400131132) * ax
                          + 881.952221241769090411) * ax + 1712.04761263407058314) * ax + 2051.07837782607146532) * ax
          + 1230.33935479799725272;
      double den = (((((((ax + 15.7449261107098347253) * ax + 117.693950891312499305) * ax + 537.181101862009857509)
                              * ax + 1621.38957456669018874) * ax + 3290.79923573345962678) * ax
                          + 4362.61909014324715820) * ax + 3439.36767414372163696) * ax
          + 1230.33935480374942043;
      r = Math.exp(-ax * ax) * num / den;
    } else {
      double z = 1.0 / (ax * ax);
      double num = ((((0.0163153871373020978498 * z + 0.305326634961232344035) * z + 0.360344899949804439429) * z
                  + 0.125781726111229246204) * z + 0.0160837851487422766278) * z + 0.000658749161529837803157;
      double den = ((((z + 2.56852019228982242072) * z + 1.87295284992346725209) * z + 0.527905102951428412248) * z
              + 0.0605183413124413191178) * z + 0.00233520497626869185443;
      r = Math.exp(-ax * ax) / ax * (1.0 / Math.sqrt(Math.PI) - z * num / den);
    }
    return x < 0 ? 2.0 - r : r;
  }

  /** Implied volatility by bisection-safeguarded Newton; NaN if the price is outside arbitrage bounds. */
  public static double impliedVol(Type type, double price, double forward, double strike, double expiry, double rate) {
    if (type == null || !Double.isFinite(price) || !Double.isFinite(forward) || !Double.isFinite(strike)
        || !Double.isFinite(expiry) || !Double.isFinite(rate)
        || expiry <= 0 || forward <= 0 || strike <= 0) return Double.NaN;
    double df = Math.exp(-rate * expiry);
    double lower = df * Math.max(type == Type.CALL ? forward - strike : strike - forward, 0.0);
    double upper = df * (type == Type.CALL ? forward : strike);
    if (!(price > lower) || !(price < upper)) return Double.NaN;
    double lo = 1e-9, hi = 10.0;
    while (greeks(type, forward, strike, expiry, rate, hi).price() < price && hi < 1e3) hi *= 2;
    double vol = Math.min(Math.max(Math.sqrt(2 * Math.PI / expiry) * price / (df * forward), 0.01), hi);
    for (int i = 0; i < 100; i++) {
      Greeks g = greeks(type, forward, strike, expiry, rate, vol);
      double diff = g.price() - price;
      if (Math.abs(diff) < 1e-10) return vol;
      if (diff > 0) hi = vol;
      else lo = vol;
      double next = g.vega() > 1e-12 ? vol - diff / g.vega() : -1;
      if (!(next > lo && next < hi)) next = 0.5 * (lo + hi);
      if (Math.abs(next - vol) < 1e-15) {
        return Math.abs(greeks(type, forward, strike, expiry, rate, next).price() - price) < 1e-10
            ? next : Double.NaN;
      }
      vol = next;
    }
    return Math.abs(greeks(type, forward, strike, expiry, rate, vol).price() - price) < 1e-10
        ? vol : Double.NaN;
  }
}
