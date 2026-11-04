import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from commodity_research.eia import EIAClient, EIAError


class Response:
    def __init__(self, payload=None, status=200, headers=None):
        self.payload = payload
        self.status_code = status
        self.headers = headers or {}
        self.text = 'test response'

    def json(self):
        return self.payload


class Session:
    def __init__(self, *responses):
        self.responses = iter(responses)
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append(kwargs['params'])
        return next(self.responses)


def page(data, total):
    return Response({'response': {'data': data, 'total': total}})


class EIAPaginationTest(unittest.TestCase):
    def test_multiple_pages_and_cache_reuse(self):
        first = {'period': '2024-01-02', 'series': 'RNGC1', 'value': '3.0'}
        second = {**first, 'period': '2024-01-03', 'value': '3.1'}
        session = Session(page([first], 2), page([second], 2))
        with tempfile.TemporaryDirectory() as folder:
            client = EIAClient(cache_dir=folder, session=session)
            result = client.series('natural-gas/pri/fut', ['RNGC1'], 'daily')
            self.assertEqual(result['value'].tolist(), [3., 3.1])
            self.assertEqual([c['offset'] for c in session.calls], [0, 1])
            self.assertEqual(len(client.series('natural-gas/pri/fut', ['RNGC1'], 'daily')), 2)
            self.assertEqual(len(session.calls), 2)

    def test_incomplete_or_malformed_responses_fail(self):
        for response in [page([], 10), Response({'response': {'data': [], 'total': 'bad'}}),
                         Response({'response': {'data': {}}})]:
            with tempfile.TemporaryDirectory() as folder:
                client = EIAClient(cache_dir=folder, session=Session(response))
                with self.assertRaises(EIAError):
                    client.series('route', ['RNGC1'], 'daily')

    def test_duplicate_rows_are_rejected_and_nonfinite_values_are_dropped(self):
        row = {'period': '2024-01-02', 'series': 'RNGC1', 'value': 'inf'}
        with tempfile.TemporaryDirectory() as folder:
            client = EIAClient(cache_dir=folder, session=Session(page([row], 1)))
            self.assertTrue(client.series('route', ['RNGC1'], 'daily').empty)
        with tempfile.TemporaryDirectory() as folder:
            client = EIAClient(cache_dir=folder, session=Session(page([row, row], 2)))
            with self.assertRaises(EIAError):
                client.series('route', ['RNGC1'], 'daily')

class EIARetryTest(unittest.TestCase):
    def test_retry_sleep_respects_the_remaining_budget(self):
        session = Session(Response(status=429, headers={'Retry-After': '9999'}), Response(status=429))
        with tempfile.TemporaryDirectory() as folder, patch('commodity_research.eia.time.sleep') as sleep:
            client = EIAClient(cache_dir=folder, session=session, max_wait_s=3)
            with self.assertRaises(EIAError):
                client.series('route', ['RNGC1'], 'daily')
            sleep.assert_called_once_with(3)

    def test_bad_retry_headers_fall_back_without_unbounded_waiting(self):
        for value in ['tomorrow', '-1', 'nan', 'inf']:
            session = Session(Response(status=429, headers={'Retry-After': value}), page([], 0))
            with tempfile.TemporaryDirectory() as folder, patch('commodity_research.eia.time.sleep') as sleep:
                result = EIAClient(cache_dir=folder, session=session, max_wait_s=2).series('route', ['RNGC1'], 'daily')
                self.assertTrue(result.empty)
                sleep.assert_called_once_with(2)

    def test_zero_budget_and_invalid_budgets(self):
        with tempfile.TemporaryDirectory() as folder:
            client = EIAClient(cache_dir=folder, session=Session(Response(status=429)), max_wait_s=0)
            with patch('commodity_research.eia.time.sleep') as sleep, self.assertRaises(EIAError):
                client.series('route', ['RNGC1'], 'daily')
            sleep.assert_not_called()
            for value in [-1, float('nan'), float('inf')]:
                with self.assertRaises(ValueError):
                    EIAClient(cache_dir=folder, max_wait_s=value)
