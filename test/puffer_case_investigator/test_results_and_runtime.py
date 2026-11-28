import datetime as dt
import decimal

import pytest

from puffer_case_investigator import (
    ResultLimitExceeded,
    compare_results,
    digest_rows,
    paired_execution_order,
    summarize_paired_runtimes,
)


def test_unordered_results_compare_complete_multisets() -> None:
    left = [(1, None), (1, None), (2, "x")]
    right = [(2, "x"), (1, None), (1, None)]
    comparison = compare_results(left, right, ordered=False)
    assert comparison.equivalent
    assert comparison.left.row_count == 3

    missing_duplicate = compare_results(left, [(2, "x"), (1, None)], ordered=False)
    assert not missing_duplicate.equivalent
    assert missing_duplicate.reason == "row_count_mismatch"


def test_ordered_results_detect_sequence_change() -> None:
    comparison = compare_results([(1,), (2,)], [(2,), (1,)], ordered=True)
    assert not comparison.equivalent
    assert comparison.reason == "ordered_rows_differ"


def test_result_types_null_bytes_decimal_and_datetime_are_stable() -> None:
    row = (
        None,
        b"\x00\xff",
        decimal.Decimal("1.2300"),
        dt.datetime(2026, 9, 11, 1, 2, 3, 4),
    )
    assert compare_results([row], [row], ordered=True).equivalent
    assert not compare_results([(1,)], [(True,)], ordered=True).equivalent


def test_result_limits_are_enforced_without_sampling() -> None:
    with pytest.raises(ResultLimitExceeded, match="row limit"):
        digest_rows([(1,), (2,)], ordered=False, max_rows=1)
    with pytest.raises(ResultLimitExceeded, match="byte limit"):
        digest_rows([("large",)], ordered=False, max_bytes=1)


def test_balanced_schedule_and_paired_statistics() -> None:
    schedule = paired_execution_order(9)
    assert schedule[0] == ("default", "counterfactual")
    assert schedule[1] == ("counterfactual", "default")
    assert len(schedule) == 9

    default = [100, 101, 99, 102, 98, 100, 103, 97, 101]
    counterfactual = [10, 11, 9, 10, 10, 9, 11, 10, 9]
    stats = summarize_paired_runtimes(default, counterfactual, bootstrap_iterations=500)
    repeated = summarize_paired_runtimes(default, counterfactual, bootstrap_iterations=500)
    assert stats.counterfactual_wins == 9
    assert stats.median_speedup == 10.0
    assert stats.median_absolute_gap_ms == 90.0
    assert stats.bootstrap_speedup_lower_bound > 8.0
    assert stats == repeated


def test_paired_statistics_reject_bad_or_unpaired_samples() -> None:
    with pytest.raises(ValueError, match="same number"):
        summarize_paired_runtimes([1.0], [1.0, 2.0])
    with pytest.raises(ValueError, match="positive"):
        summarize_paired_runtimes([0.0], [1.0])


def test_runtime_gap_is_absolute_when_counterfactual_is_slower() -> None:
    stats = summarize_paired_runtimes(
        [5.0, 5.0, 5.0],
        [20.0, 20.0, 20.0],
        bootstrap_iterations=100,
    )
    assert stats.median_absolute_gap_ms == 15.0
    assert stats.counterfactual_wins == 0
