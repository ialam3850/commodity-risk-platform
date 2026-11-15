package com.commodityrisk.ledger.risk;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PricingInputTest {
  @Test
  void impliedVolRejectsInvalidInputsWithoutThrowing() {
    for (double x : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThat(Black76.impliedVol(Black76.Type.CALL, x, 3, 3, 1, 0.04)).isNaN();
      assertThat(Black76.impliedVol(Black76.Type.CALL, 0.2, 3, 3, 1, x)).isNaN();
    }
    assertThat(Black76.impliedVol(null, 0.2, 3, 3, 1, 0.04)).isNaN();
  }

  @Test
  void rejectsNonFiniteInputs() {
    for (double x : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, x, 3, 1, 0.04, 0.6));
      assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, x, 1, 0.04, 0.6));
      assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, 3, x, 0.04, 0.6));
      assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, 3, 1, x, 0.6));
      assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, 3, 1, 0.04, x));
    }
  }

  @Test
  void rejectsInvalidDomains() {
    assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(null, 3, 3, 1, 0.04, 0.6));
    assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 0, 3, 1, 0.04, 0.6));
    assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, 3, -1, 0.04, 0.6));
    assertThatIllegalArgumentException().isThrownBy(() -> Black76.greeks(Black76.Type.CALL, 3, 3, 1, 0.04, -0.6));
  }

  @Test
  void zeroTimeAndNegativeRatesRemainValid() {
    assertThat(Black76.greeks(Black76.Type.CALL, 4, 3, 0, -0.02, 0).price()).isEqualTo(1);
    assertThat(Black76.greeks(Black76.Type.CALL, 4, 3, 1, -0.02, 0).price()).isGreaterThan(1);
  }
}
