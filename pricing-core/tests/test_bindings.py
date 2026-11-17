"""Run with PYTHONPATH=pricing-core/build from the repository root."""
import csv
from pathlib import Path
import unittest

import pricing_core as pc


class BindingsTest(unittest.TestCase):
    def test_all_golden_prices_and_greeks(self):
        path = Path(__file__).resolve().parents[2] / 'testdata/black76_golden.csv'
        with path.open() as source:
            rows = list(csv.reader(source))[1:]
        self.assertEqual(len(rows), 720)
        for row in rows:
            args = [float(x) for x in row[1:6]]
            greeks = pc.black76_greeks(row[0], *args)
            for name, expected in zip(['price', 'delta', 'gamma', 'vega', 'theta', 'rho'], row[6:]):
                value = float(expected)
                self.assertAlmostEqual(getattr(greeks, name), value, delta=1e-10 * max(1, abs(value)))

    def test_implied_vol_round_trip_and_bounds(self):
        price = pc.black76_price('call', 3., 3.5, 0.5, 0.04, 0.7)
        self.assertAlmostEqual(pc.implied_vol(price, 'call', 3., 3.5, 0.5, 0.04), 0.7, places=8)
        self.assertIsNone(pc.implied_vol(100., 'call', 3., 3.5, 0.5, 0.04))

    def test_invalid_cpp_inputs_become_python_value_errors(self):
        with self.assertRaises(ValueError):
            pc.black76_price('call', float('nan'), 3., 1., 0.04, 0.6)
        portfolio = pc.Portfolio([3.], [pc.Position.future(1, 1., 10000.)])
        with self.assertRaises(ValueError):
            pc.historical_var(portfolio, [[0.01]], 0.99)

    def test_option_type_can_be_changed_from_python(self):
        option = pc.Position.option(0, 1., 10000., 'call', 3., 0.5, 0.6, 0.04)
        option.type = pc.OptionType.Put
        portfolio = pc.Portfolio([4.], [option])
        expected = 10000 * pc.black76_price('put', 4., 3., 0.5, 0.04, 0.6)
        self.assertAlmostEqual(portfolio.value([4.]), expected)

    def test_historical_risk_matches_a_linear_book(self):
        portfolio = pc.Portfolio([3.], [pc.Position.future(0, 2., 10000.)])
        result = pc.historical_var(portfolio, [[-0.1], [0.], [0.1]], 0.99)
        import math
        self.assertAlmostEqual(result.var, -60000 * math.expm1(-0.1), places=8)
        self.assertEqual(result.tail, 1)

    def test_monte_carlo_is_deterministic_across_thread_counts(self):
        portfolio = pc.Portfolio([3.], [pc.Position.future(0, 1., 10000.)])
        args = (portfolio, [0.6], [[1.]], 0.99)
        one = pc.monte_carlo_var(*args, paths=10000, seed=17, threads=1)
        many = pc.monte_carlo_var(*args, paths=10000, seed=17, threads=3)
        self.assertEqual((one.var, one.es), (many.var, many.es))
        with self.assertRaises(ValueError):
            pc.monte_carlo_var(portfolio, [0.6], [[1., 0.]], 0.99, paths=10)


if __name__ == '__main__':
    unittest.main(verbosity=2)
