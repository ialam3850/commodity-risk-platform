package com.commodityrisk.ledger.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Black76Test {

  /**
   * testdata/black76_golden.csv is written by the C++ pricing core (pricing-core/tools/gen_golden).
   * Two independent implementations agreeing on 720 cases is strong evidence both are right; the C++
   * one is separately checked against textbook values and finite differences.
   */
  @Test
  void matchesTheCppPricingCore() throws IOException {
    List<String> lines = Files.readAllLines(Path.of("..", "testdata", "black76_golden.csv"));
    assertThat(lines).hasSizeGreaterThan(700);
    int checked = 0;
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split(",");
      Black76.Type type = f[0].equals("call") ? Black76.Type.CALL : Black76.Type.PUT;
      double[] in = new double[5];
      for (int i = 0; i < 5; i++) in[i] = Double.parseDouble(f[i + 1]);
      Black76.Greeks g = Black76.greeks(type, in[0], in[1], in[2], in[3], in[4]);
      double[] actual = {g.price(), g.delta(), g.gamma(), g.vega(), g.theta(), g.rho()};
      for (int i = 0; i < 6; i++) {
        double expected = Double.parseDouble(f[i + 6]);
        assertThat(actual[i]).as("%s column %d", line, i).isCloseTo(expected, within(1e-10 * Math.max(1, Math.abs(expected))));
      }
      checked++;
    }
    assertThat(checked).isEqualTo(720);
  }

  @Test
  void haugReferenceValue() {
    assertThat(Black76.greeks(Black76.Type.CALL, 19, 19, 0.75, 0.10, 0.28).price()).isCloseTo(1.7011, within(5e-5));
  }

  @Test
  void erfcAgreesWithKnownValues() {
    assertThat(Black76.erfc(0.0)).isEqualTo(1.0);
    assertThat(Black76.erfc(0.5)).isCloseTo(0.4795001221869535, within(1e-15));
    assertThat(Black76.erfc(1.0)).isCloseTo(0.15729920705028513, within(1e-15));
    assertThat(Black76.erfc(-1.0)).isCloseTo(1.842700792949715, within(1e-15));
    assertThat(Black76.erfc(5.0)).isCloseTo(1.5374597944280351e-12, within(1e-25));
  }

  @Test
  void impliedVolRoundTrips() {
    for (double k : new double[] {2.0, 3.0, 4.5}) {
      for (double vol : new double[] {0.2, 0.7, 1.5}) {
        double price = Black76.greeks(Black76.Type.PUT, 3.0, k, 0.5, 0.04, vol).price();
        assertThat(Black76.impliedVol(Black76.Type.PUT, price, 3.0, k, 0.5, 0.04)).isCloseTo(vol, within(1e-7));
      }
    }
    assertThat(Black76.impliedVol(Black76.Type.CALL, 5.0, 3.0, 3.0, 0.5, 0.04)).isNaN();
  }

  @Test
  void historicalVarUsesTheSameQuantileRuleAsCpp() {
    double[] pnl = new double[200];
    for (int i = 0; i < 100; i++) pnl[i] = -(i + 1);
    HistoricalVar.Result r = HistoricalVar.fromPnl(pnl, 0.99);
    assertThat(r.tail()).isEqualTo(2);
    assertThat(r.var()).isEqualTo(99.0);
    assertThat(r.es()).isEqualTo(99.5);
    double[] hundred = new double[100];
    for (int i = 0; i < 100; i++) hundred[i] = i - 50;
    assertThat(HistoricalVar.fromPnl(hundred, 0.99).tail()).isEqualTo(1);
  }
}
