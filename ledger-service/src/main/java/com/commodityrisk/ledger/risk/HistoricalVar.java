package com.commodityrisk.ledger.risk;

import java.util.Arrays;

/**
 * VaR and expected shortfall from a list of scenario P&amp;Ls, using the same empirical-quantile rule
 * as pricing-core/src/var.cpp: with N scenarios the tail is the worst k = ceil((1 - c) N); VaR is
 * the k-th worst loss and ES the mean of the k worst. Both are reported as positive losses.
 */
public final class HistoricalVar {

  public record Result(double var, double es, int scenarios, int tail) {}

  private HistoricalVar() {}

  public static Result fromPnl(double[] pnl, double confidence) {
    if (pnl.length == 0) throw new IllegalArgumentException("no scenarios");
    if (!(confidence > 0 && confidence < 1)) throw new IllegalArgumentException("confidence must be in (0, 1)");
    for (double value : pnl) {
      if (!Double.isFinite(value)) throw new IllegalArgumentException("scenario P&L must be finite");
    }
    double[] sorted = pnl.clone();
    Arrays.sort(sorted); // worst (most negative) first
    // ceil((1 - c) N), guarded against 0.99 * 100 = 99.00000000000001-style rounding.
    int tail = Math.max(1, (int) Math.ceil((1 - confidence) * sorted.length - 1e-9));
    double sum = 0;
    for (int i = 0; i < tail; i++) sum += sorted[i];
    return new Result(-sorted[tail - 1], -sum / tail, sorted.length, tail);
  }
}
