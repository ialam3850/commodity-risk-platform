import sys
import unittest
from pathlib import Path
from unittest.mock import patch
import numpy as np
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from commodity_research.contracts import roll_dates
from commodity_research.returns import nearby_log_returns, naive_log_returns

class ReturnInputsTest(unittest.TestCase):

    def test_empty_and_single_day_keep_the_output_schema(self):
        empty = pd.DataFrame(columns=['RNGC1'], index=pd.DatetimeIndex([]), dtype=float)
        self.assertEqual(list(nearby_log_returns(empty).columns), ['C1'])
        self.assertTrue(roll_dates(empty.index).empty)
        one = pd.DataFrame({'RNGC1': [3.0]}, index=pd.to_datetime(['2024-01-02']))
        self.assertTrue(nearby_log_returns(one).empty)

    def test_bad_prices_and_duplicate_dates_are_rejected(self):
        dates = pd.to_datetime(['2024-01-02', '2024-01-03'])
        for value in [0.0, -1.0, np.inf, -np.inf]:
            frame = pd.DataFrame({'RNGC1': [3.0, value]}, index=dates)
            for fn in [nearby_log_returns, naive_log_returns]:
                with self.assertRaises(ValueError):
                    fn(frame)
        with self.assertRaises(ValueError):
            nearby_log_returns(pd.DataFrame({'RNGC1': [3.0, 4.0]}, index=[dates[0], dates[0]]))

    def test_unsorted_dates_are_sorted_and_missing_prices_stay_missing(self):
        dates = pd.to_datetime(['2024-01-04', '2024-01-02', '2024-01-03'])
        frame = pd.DataFrame({'RNGC1': [np.nan, 3.0, 3.3]}, index=dates)
        result = nearby_log_returns(frame)
        self.assertAlmostEqual(result.iloc[0, 0], np.log(1.1))
        self.assertTrue(np.isnan(result.iloc[1, 0]))
