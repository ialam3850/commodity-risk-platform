package com.commodityrisk.ledger.risk;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HistoricalVarTest {
  @Test
  void rejectsNonFiniteScenarios() {
    for (double x : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThatIllegalArgumentException().isThrownBy(() -> HistoricalVar.fromPnl(new double[] {-10, x, 20}, 0.99));
    }
  }

  @Test
  void preservesInputAndHandlesTies() {
    double[] pnl = {2, -3, 1, -3};
    var result = HistoricalVar.fromPnl(pnl, 0.5);
    assertThat(result.var()).isEqualTo(3);
    assertThat(result.es()).isEqualTo(3);
    assertThat(pnl).containsExactly(2, -3, 1, -3);
  }

  @Test
  void handlesOneScenarioAndRejectsEmptyInput() {
    assertThat(HistoricalVar.fromPnl(new double[] {-17.5}, 0.99).es()).isEqualTo(17.5);
    assertThatIllegalArgumentException().isThrownBy(() -> HistoricalVar.fromPnl(new double[0], 0.99));
    assertThatIllegalArgumentException().isThrownBy(() -> HistoricalVar.fromPnl(new double[] {1}, Double.NaN));
  }
}
