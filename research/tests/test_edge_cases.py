import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from commodity_research import backtest as bt
from commodity_research.contracts import roll_dates
from commodity_research.returns import nearby_log_returns, naive_log_returns
from commodity_research.storage_signal import five_year_norm, release_dates, surprise_signal, weekly_changes


class ReturnInputsTest(unittest.TestCase):
    def test_empty_and_single_day_keep_the_output_schema(self):
        empty = pd.DataFrame(columns=['RNGC1'], index=pd.DatetimeIndex([]), dtype=float)
        self.assertEqual(list(nearby_log_returns(empty).columns), ['C1'])
        self.assertTrue(roll_dates(empty.index).empty)
        one = pd.DataFrame({'RNGC1': [3.]}, index=pd.to_datetime(['2024-01-02']))
        self.assertTrue(nearby_log_returns(one).empty)

    def test_bad_prices_and_duplicate_dates_are_rejected(self):
        dates = pd.to_datetime(['2024-01-02', '2024-01-03'])
        for value in [0., -1., np.inf, -np.inf]:
            frame = pd.DataFrame({'RNGC1': [3., value]}, index=dates)
            for fn in [nearby_log_returns, naive_log_returns]:
                with self.assertRaises(ValueError):
                    fn(frame)
        with self.assertRaises(ValueError):
            nearby_log_returns(pd.DataFrame({'RNGC1': [3., 4.]}, index=[dates[0], dates[0]]))

    def test_unsorted_dates_are_sorted_and_missing_prices_stay_missing(self):
        dates = pd.to_datetime(['2024-01-04', '2024-01-02', '2024-01-03'])
        frame = pd.DataFrame({'RNGC1': [np.nan, 3., 3.3]}, index=dates)
        result = nearby_log_returns(frame)
        self.assertAlmostEqual(result.iloc[0, 0], np.log(1.1))
        self.assertTrue(np.isnan(result.iloc[1, 0]))

class StorageInputsTest(unittest.TestCase):
    def test_duplicate_observations_and_bad_windows_are_rejected(self):
        dates = pd.date_range('2010-01-01', periods=120, freq='W-FRI')
        storage = pd.Series(np.arange(120.) + 2000, index=dates)
        for window in [0, 51, 52.5]:
            with self.assertRaises(ValueError):
                surprise_signal(storage, pd.bdate_range('2010', '2013'), window)
        for years in [0, -1, 1.5]:
            with self.assertRaises(ValueError):
                five_year_norm(weekly_changes(storage), years)
        duplicate = pd.Series([2000., 2001.], index=[dates[0], dates[0]])
        with self.assertRaises(ValueError):
            weekly_changes(duplicate)

    def test_zero_historical_variance_does_not_create_an_infinite_signal(self):
        dates = pd.date_range('2010-01-01', periods=120, freq='W-FRI')
        storage = pd.Series(2000. + np.arange(120), index=dates)
        storage.iloc[-1] += 50
        changes = weekly_changes(storage)
        with patch('commodity_research.storage_signal.five_year_norm',
                   return_value=pd.Series(1., index=changes.index)):
            result = surprise_signal(storage, pd.bdate_range('2010', '2013'))
        self.assertTrue(result.empty)

    def test_release_calendar_requires_sorted_trading_days(self):
        with self.assertRaises(ValueError):
            release_dates(pd.to_datetime(['2024-01-05']), pd.to_datetime(['2024-01-12', '2024-01-11']))

class BacktestEdgesTest(unittest.TestCase):
    def test_no_trade_walk_forward_keeps_datetime_columns(self):
        days = pd.bdate_range('2015-01-01', '2020-12-31')
        thursdays = days[days.dayofweek == 3]
        signal = pd.DataFrame({'z': np.zeros(len(thursdays)), 'release': thursdays})
        returns = pd.Series(0., index=days)
        price = pd.Series(3., index=days)
        trades, choices = bt.walk_forward(signal, returns, price)
        self.assertTrue(trades.empty)
        self.assertTrue(choices.empty)
        self.assertTrue(pd.api.types.is_datetime64_any_dtype(trades['exit']))

    def test_bad_parameters_and_costs_are_rejected(self):
        for threshold, hold in [(np.nan, 1), (-1, 1), (1, 0), (1, 5), (1, 1.5)]:
            with self.assertRaises(ValueError):
                bt.Params(threshold, hold)
        days = pd.bdate_range('2024-01-01', periods=10)
        empty = pd.DataFrame({'z': [], 'release': pd.to_datetime([])})
        with self.assertRaises(ValueError):
            bt.trades(empty, pd.Series(0., index=days), pd.Series(3., index=days), bt.Params(1, 1), cost=-1)

    def test_missing_prices_and_nonfinite_signals_do_not_create_trades(self):
        days = pd.bdate_range('2024-01-01', periods=10)
        signal = pd.DataFrame({'z': [np.inf, 2.], 'release': [days[2], days[3]]})
        price = pd.Series(np.nan, index=days)
        result = bt.trades(signal, pd.Series(0., index=days), price, bt.Params(1, 1))
        self.assertTrue(result.empty)

class BacktestStatsTest(unittest.TestCase):
    def test_empty_and_zero_variance_stats_are_defined(self):
        empty = bt.stats(pd.Series([], dtype=float), weeks=0)
        self.assertEqual(empty['total'], 0)
        self.assertEqual(empty['max_drawdown'], 0)
        self.assertTrue(np.isnan(empty['sharpe']))
        self.assertTrue(np.isnan(bt.stats(pd.Series([0., 0.]), weeks=2)['sharpe']))

    def test_invalid_observations_and_week_counts_are_rejected(self):
        for value in [np.nan, np.inf]:
            with self.assertRaises(ValueError):
                bt.stats(pd.Series([value]), weeks=1)
        for weeks in [-1, 0, 1.5]:
            with self.assertRaises(ValueError):
                bt.stats(pd.Series([5.]), weeks=weeks)

    def test_drawdown_starts_at_zero_and_zero_weeks_affect_sharpe(self):
        pnl = pd.Series([-100., 20., -30.])
        self.assertEqual(bt.stats(pnl, weeks=3)['max_drawdown'], 110.)
        weekly = np.r_[pnl.to_numpy(), np.zeros(7)]
        self.assertAlmostEqual(bt.stats(pnl, weeks=10)['sharpe'], weekly.mean() / weekly.std(ddof=1) * np.sqrt(52))
