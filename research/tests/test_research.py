"""Tests for the research pipeline, on synthetic data with known answers.

    cd research && python3 -m unittest discover -s tests -v
"""
import sys
import unittest
from pathlib import Path
import numpy as np
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from commodity_research.contracts import front_month, last_trade_date, nearby_months, roll_dates, symbol
from commodity_research.returns import naive_log_returns, nearby_log_returns
from commodity_research.storage_signal import five_year_norm, release_dates, surprise_signal, weekly_changes

def trading_days(start='2023-01-01', end='2025-12-31'):
    days = pd.bdate_range(start, end)
    holidays = pd.to_datetime(['2024-03-29', '2024-11-28', '2024-12-25', '2025-01-01', '2024-07-04'])
    return days[~days.isin(holidays)]

class ContractCalendarTest(unittest.TestCase):

    def test_last_trade_dates_match_cme(self):
        days = trading_days()
        self.assertEqual(last_trade_date(pd.Period('2024-12', 'M'), days), pd.Timestamp('2024-11-26'))
        self.assertEqual(last_trade_date(pd.Period('2024-04', 'M'), days), pd.Timestamp('2024-03-26'))
        self.assertEqual(last_trade_date(pd.Period('2025-01', 'M'), days), pd.Timestamp('2024-12-27'))

    def test_front_month_rolls_the_day_after_expiry(self):
        days = trading_days()
        self.assertEqual(front_month(pd.Timestamp('2024-12-27'), days), pd.Period('2025-01', 'M'))
        self.assertEqual(front_month(pd.Timestamp('2024-12-30'), days), pd.Period('2025-02', 'M'))
        self.assertIn(pd.Timestamp('2024-12-30'), roll_dates(days))
        self.assertEqual(len(roll_dates(days[days.year == 2024])), 12)

    def test_symbols(self):
        self.assertEqual(symbol('NG', pd.Period('2027-01', 'M')), 'NGF27')
        self.assertEqual(symbol('NG', pd.Period('2026-12', 'M')), 'NGZ26')

class RollAdjustmentTest(unittest.TestCase):

    def test_adjusted_returns_recover_each_contracts_own_return(self):
        days = trading_days('2024-01-01', '2024-12-31')
        nearby = nearby_months(days, days, n=3)
        t = np.arange(len(days))
        prices = pd.DataFrame(index=days)
        for k in range(3):
            months = nearby[f'C{k + 1}']
            offset = np.array([(m - pd.Period('2024-01', 'M')).n for m in months])
            prices[f'RNGC{k + 1}'] = 3.0 * 1.05 ** offset * 1.001 ** t
        adj = nearby_log_returns(prices)
        naive = naive_log_returns(prices)
        np.testing.assert_allclose(adj['C1'], np.log(1.001), atol=1e-12)
        np.testing.assert_allclose(adj['C2'], np.log(1.001), atol=1e-12)
        self.assertEqual(adj['C3'].isna().sum(), len(roll_dates(days)))
        self.assertAlmostEqual(naive['C1'].max(), np.log(1.05 * 1.001), places=9)

class StorageSignalTest(unittest.TestCase):

    def setUp(self):
        weeks = pd.date_range('2010-01-01', '2020-12-25', freq='W-FRI')
        seasonal = 60 * np.sin(2 * np.pi * (weeks.isocalendar().week.to_numpy() - 14) / 52)
        rng = np.random.default_rng(1)
        change = seasonal + rng.normal(0, 10, len(weeks))
        self.storage = pd.Series(2000 + np.cumsum(change), index=weeks)
        self.days = pd.bdate_range('2010-01-01', '2021-01-31')

    def test_norm_needs_five_prior_years_and_uses_only_the_past(self):
        changes = weekly_changes(self.storage)
        norm = five_year_norm(changes)
        self.assertTrue(norm[changes.index.year < 2015].isna().all())
        self.assertTrue(norm[changes.index.year >= 2016].notna().all())
        t = changes.index[changes.index.year == 2018][10]
        wk = t.isocalendar().week
        same = changes[(changes.index.isocalendar().week == wk) & changes.index.year.isin(range(2013, 2018))]
        self.assertAlmostEqual(norm[t], same.mean())

    def test_signal_does_not_look_ahead(self):
        full = surprise_signal(self.storage, self.days)
        cut = pd.Timestamp('2018-06-01')
        edited = self.storage.copy()
        edited[edited.index > cut] += np.linspace(0, 500, (edited.index > cut).sum())
        changed = surprise_signal(edited, self.days)
        before = full.index[full.index <= cut]
        pd.testing.assert_frame_equal(full.loc[before], changed.loc[before])

    def test_release_is_the_thursday_after_week_end(self):
        r = release_dates(pd.DatetimeIndex(['2024-11-22', '2024-11-15']), trading_days())
        self.assertEqual(r[0], pd.Timestamp('2024-11-29'))
        self.assertEqual(r[1], pd.Timestamp('2024-11-21'))
if __name__ == '__main__':
    unittest.main()
