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
if __name__ == '__main__':
    unittest.main()
