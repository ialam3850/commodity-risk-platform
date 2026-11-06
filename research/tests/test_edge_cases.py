import sys
import unittest
from pathlib import Path
from unittest.mock import patch
import numpy as np
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from commodity_research.contracts import roll_dates
from commodity_research.returns import nearby_log_returns, naive_log_returns
from commodity_research.storage_signal import five_year_norm, release_dates, surprise_signal, weekly_changes

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

class StorageInputsTest(unittest.TestCase):

    def test_duplicate_observations_and_bad_windows_are_rejected(self):
        dates = pd.date_range('2010-01-01', periods=120, freq='W-FRI')
        storage = pd.Series(np.arange(120.0) + 2000, index=dates)
        for window in [0, 51, 52.5]:
            with self.assertRaises(ValueError):
                surprise_signal(storage, pd.bdate_range('2010', '2013'), window)
        for years in [0, -1, 1.5]:
            with self.assertRaises(ValueError):
                five_year_norm(weekly_changes(storage), years)
        duplicate = pd.Series([2000.0, 2001.0], index=[dates[0], dates[0]])
        with self.assertRaises(ValueError):
            weekly_changes(duplicate)

    def test_zero_historical_variance_does_not_create_an_infinite_signal(self):
        dates = pd.date_range('2010-01-01', periods=120, freq='W-FRI')
        storage = pd.Series(2000.0 + np.arange(120), index=dates)
        storage.iloc[-1] += 50
        changes = weekly_changes(storage)
        with patch('commodity_research.storage_signal.five_year_norm', return_value=pd.Series(1.0, index=changes.index)):
            result = surprise_signal(storage, pd.bdate_range('2010', '2013'))
        self.assertTrue(result.empty)

    def test_release_calendar_requires_sorted_trading_days(self):
        with self.assertRaises(ValueError):
            release_dates(pd.to_datetime(['2024-01-05']), pd.to_datetime(['2024-01-12', '2024-01-11']))
