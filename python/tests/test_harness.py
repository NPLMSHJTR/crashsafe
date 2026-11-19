"""Tests for prefix semantics and report generation."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from crashsafe.harness import expected_state
from crashsafe.report import write_report


class PrefixTests(unittest.TestCase):
    def test_only_complete_records_count(self) -> None:
        plan = {
            "records": [
                {"key": "a", "value": "1", "start": 0, "end": 10},
                {"key": "b", "value": "2", "start": 10, "end": 25},
            ]
        }
        self.assertEqual(expected_state(plan, 0), {})
        self.assertEqual(expected_state(plan, 9), {})
        self.assertEqual(expected_state(plan, 10), {"a": "1"})
        self.assertEqual(expected_state(plan, 24), {"a": "1"})
        self.assertEqual(expected_state(plan, 25), {"a": "1", "b": "2"})

    def test_later_records_override_earlier_ones(self) -> None:
        plan = {
            "records": [
                {"key": "a", "value": "old", "start": 0, "end": 10},
                {"key": "a", "value": "new", "start": 10, "end": 20},
            ]
        }
        self.assertEqual(expected_state(plan, 10)["a"], "old")
        self.assertEqual(expected_state(plan, 20)["a"], "new")


class ReportTests(unittest.TestCase):
    def test_writes_markdown_and_metrics(self) -> None:
        result = {
            "records": 10,
            "bytes": 100,
            "truncation": [
                {
                    "offset": 0,
                    "expected_keys": 0,
                    "recovered_keys": 0,
                    "match": True,
                    "repaired_bytes": 0,
                },
                {
                    "offset": 50,
                    "expected_keys": 3,
                    "recovered_keys": 3,
                    "match": True,
                    "repaired_bytes": 7,
                },
            ],
            "compaction": [
                {"scenario": "crash before rename", "keys": 3, "match": True},
                {"scenario": "crash after rename", "keys": 3, "match": True},
            ],
            "stress": {"trials": 20, "failures": 0, "phantom_recoveries": 0},
        }
        with tempfile.TemporaryDirectory() as tmp:
            paths = write_report(result, Path(tmp))
            self.assertTrue(paths["report"].is_file())
            text = paths["report"].read_text(encoding="utf-8")
            self.assertIn("truncation failures: 0", text)
            self.assertIn("crash after rename", text)
            self.assertIn("phantom keys", text)


if __name__ == "__main__":
    unittest.main()
