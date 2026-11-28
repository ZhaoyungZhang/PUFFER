from types import SimpleNamespace

import pymysql
import pytest

from puffer_case_investigator.db import (
    AnalyzeResult, DatabaseProfile, ExplainResult, MySQLAdapter, QueryLimitExceeded, is_statement_timeout,
)
from puffer_case_investigator.results import digest_rows


def adapter():
    return MySQLAdapter(DatabaseProfile(name="test", dbms="mysql", host="localhost", port=3306,
                                        user="test", password_env="TEST_PASSWORD", query_timeout_s=120))


@pytest.mark.parametrize("error,expected", [
    (pymysql.err.OperationalError(3024, "deadline"), True),
    (pymysql.err.OperationalError(1969, "deadline"), True),
    (pymysql.err.OperationalError(2013, "lost connection"), False),
    (pymysql.err.OperationalError(1205, "lock wait"), False),
    (TimeoutError("socket"), False), (QueryLimitExceeded("row limit"), False),
])
def test_only_statement_deadlines_are_censored(error, expected):
    assert is_statement_timeout(error) is expected


def test_timeout_is_cached_as_a_flagged_bound_and_other_side_is_measured(monkeypatch):
    db = adapter()
    calls = []
    monkeypatch.setattr(db, "_with_database", lambda _: SimpleNamespace(close=lambda: None))

    def consume(_, query, __):
        calls.append(query)
        if query == "SELECT 1":
            raise pymysql.err.OperationalError(3024, "deadline")

    monkeypatch.setattr(db, "_consume_query", consume)
    result = db.measure_pair("puffer_ci_test", "SELECT 1", "SELECT 2", warmups_per_query=1,
                             rounds=5, timeout_as_limit=True, bootstrap_iterations=10)
    assert calls.count("SELECT 1") == 1
    assert calls.count("SELECT 2") == 6
    assert result.default.samples_ms == (120000.0,) * 5
    assert all(row.default_timeout_bound and not row.counterfactual_timeout_bound for row in result.rounds)
    assert result.timeout_observations == ({"side": "default", "stage": "WARMUP", "round_index": 0,
                                            "limit_ms": 120000},)


def test_default_policy_still_raises_deadlines(monkeypatch):
    db = adapter()
    monkeypatch.setattr(db, "_with_database", lambda _: SimpleNamespace(close=lambda: None))
    monkeypatch.setattr(db, "_consume_query", lambda *_: (_ for _ in ()).throw(
        pymysql.err.OperationalError(3024, "deadline")))
    with pytest.raises(pymysql.err.OperationalError):
        db.measure_pair("puffer_ci_test", "SELECT 1", "SELECT 2", rounds=1)


def test_timeout_never_claims_complete_results_are_equal(monkeypatch):
    db = adapter()
    db._active_version = "9.7.1"
    monkeypatch.setattr(db, "refresh_statistics", lambda _: None)
    monkeypatch.setattr(db, "explain", lambda *_: ExplainResult("{}", {}, 1.0))
    monkeypatch.setattr(db, "explain_analyze", lambda *_: AnalyzeResult("{}", {}))
    monkeypatch.setattr(db, "_with_database", lambda _: SimpleNamespace(close=lambda: None))
    monkeypatch.setattr(db, "_consume_query", lambda *_: None)

    def digest(_, query, **__):
        if query == "SELECT 1":
            raise pymysql.err.OperationalError(3024, "deadline")
        return digest_rows([(1,)], ordered=False)

    monkeypatch.setattr(db, "result_digest", digest)
    result = db.replay(SimpleNamespace(original_query="SELECT 1", counterfactual_query="SELECT 2"),
                       "puffer_ci_test", timeout_as_limit=True, rounds=3, warmups_per_query=0,
                       bootstrap_iterations=10).to_dict()
    assert result["result_comparison"]["equivalent"] is None
    assert result["classification_metrics"]["resultEquivalent"] is None
    assert result["classification_metrics"]["runtimeCensored"]
    assert result["runtime"]["default"]["median_ms"] == 120000.0
