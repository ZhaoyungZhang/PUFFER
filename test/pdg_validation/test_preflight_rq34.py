"""Preflight regression tests for RQ3/RQ4 validation policy, L3 early-stop, log fields, and Large-only mode."""
from __future__ import annotations

import csv
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pymysql
import pytest
import yaml

from puffer_case_investigator.db import AnalyzeResult, DatabaseProfile, ExplainResult, MySQLAdapter
from scripts import run_ablation_experiment as experiment
from scripts.pdg_validation._common import read_json

ROOT = Path(__file__).resolve().parents[2]


def load_stage(name: str):
    spec = importlib.util.spec_from_file_location(f"preflight_{name}", ROOT / f"scripts/pdg_validation/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_replay(default_ms: float, alt_ms: float, *, equivalent: bool | None = True,
                cost_default: float = 500.0, cost_alt: float = 10.0, timeout_sides: tuple[str, ...] = ()) -> dict:
    return {
        "result_comparison": {"equivalent": equivalent},
        "runtime": {
            "default": {"median_ms": default_ms},
            "counterfactual": {"median_ms": alt_ms},
            "rounds": [{"default_runtime_ms": default_ms, "counterfactual_runtime_ms": alt_ms}] * 5,
        },
        "classification_metrics": {
            "defaultEstimatedCost": cost_default,
            "counterfactualEstimatedCost": cost_alt,
            "defaultRuntimeMedianMs": default_ms,
            "counterfactualRuntimeMedianMs": alt_ms,
            "timeoutObserved": bool(timeout_sides),
            "timeoutLimitMs": 20000 if timeout_sides else None,
            "timeoutObservations": [{"side": s, "stage": "RESULT_COMPARISON", "limit_ms": 20000} for s in timeout_sides],
        },
    }


def test_cli_defaults_and_decoupled_gap_thresholds(tmp_path: Path) -> None:
    s3 = load_stage("03_scale_validate")
    args = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"), "--output-dir", str(tmp_path / "out")])
    assert args.baseline_min_gap_ms == 0.5
    assert args.scaled_min_gap_ms == 5.0
    assert args.scaled_timeout_pass is True
    assert args.allow_result_mismatch is False
    assert args.scaled_timeout_early_stop is True
    assert args.scaled_explain_analyze is False
    assert args.query_timeout_s == 20
    assert args.max_result_rows == 500_000

    # Legacy --min-gap-ms overrides omitted decoupled flags
    legacy = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                            "--output-dir", str(tmp_path / "out"), "--min-gap-ms", "2.5"])
    assert legacy.baseline_min_gap_ms == 2.5
    assert legacy.scaled_min_gap_ms == 2.5

    # Explicit decoupled overrides win over --min-gap-ms
    explicit = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                              "--output-dir", str(tmp_path / "out"), "--min-gap-ms", "2.5",
                              "--baseline-min-gap-ms", "0.2", "--scaled-min-gap-ms", "7.0", "--no-scaled-timeout-pass"])
    assert explicit.baseline_min_gap_ms == 0.2
    assert explicit.scaled_min_gap_ms == 7.0
    assert explicit.scaled_timeout_pass is False


@pytest.mark.parametrize("cell", ["B", "C"])
def test_bc_candidates_bypass_1x_gap_and_speedup_filter_and_activate_at_10x(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, cell: str) -> None:
    s3 = load_stage("03_scale_validate")
    args = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                          "--output-dir", str(tmp_path / "out"), "--min-speedup", "5.0", "--seed", "11"])
    row = {"dbms": "mysql", "cell": cell, "family_id": "f1", "case_id": "case_bc",
           "case_path": str(tmp_path), "cost_ratio": "12.5", "runtime_ratio": "1.05",
           "delta_signature": "sig_bc_123", "pdg_primitives": '["ZIPF","JOIN_FANOUT:ONE_TO_MANY"]'}
    monkeypatch.setattr(s3, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(s3, "extend_database", lambda *_, **__: {"t0": 1000})
    # 1x gap is only 0.1ms (< 0.5ms) and ratio is 1.1x (< 2.0x), yet B/C MUST reach 10x and ACTIVATE!
    replies = iter([make_replay(1.1, 1.0), make_replay(60.0, 5.0)])
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: next(replies))
    result = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert result["status"] == "ACTIVATED"
    assert "ten_x" in result
    assert result["result_equivalence"] == "EQUIVALENT"
    assert result["delta_signature"] == "sig_bc_123"
    assert result["pdg_primitives"] == ["JOIN_FANOUT:ONE_TO_MANY", "ZIPF"]
    assert result["stage1_cell"] == cell
    assert result["stage1_rho_c"] == pytest.approx(12.5)
    assert result["stage1_rho_t"] == pytest.approx(1.05)
    assert isinstance(result["elapsed_s"], float) and result["elapsed_s"] >= 0.0


@pytest.mark.parametrize("cell,one_pair,ten_pair", [
    ("A", (10.0, 1.0), (4.0, 0.5)),    # 10x speedup 8x, but gap 3.5ms < 5.0ms -> rejected
    ("G", (2.0, 0.5), (4.5, 0.5)),     # 10x speedup 9x, gap 4.0ms < 5.0ms -> rejected
    ("B", (1.0, 1.0), (4.8, 0.5)),     # B/C activated candidate with gap 4.3ms < 5.0ms -> rejected (COST_ANOMALY or DISAPPEARED)
])
def test_scaled_gap_below_5ms_is_rejected_from_validated_ppi(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, cell: str, one_pair: tuple, ten_pair: tuple) -> None:
    s3 = load_stage("03_scale_validate")
    args = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                          "--output-dir", str(tmp_path / "out"), "--min-speedup", "5.0", "--seed", "13"])
    row = {"dbms": "mysql", "cell": cell, "family_id": "f1", "case_id": "case_small_gap", "case_path": str(tmp_path)}
    monkeypatch.setattr(s3, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(s3, "extend_database", lambda *_, **__: {"t0": 1000})
    replies = iter([make_replay(*one_pair, cost_default=10, cost_alt=10),
                    make_replay(*ten_pair, cost_default=10, cost_alt=10)])
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: next(replies))
    result = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert result["status"] == "DISAPPEARED"
    assert result["ten_x_gate"]["checks"]["absolute_gap"] is False


def test_strict_equivalence_excludes_mismatches_from_validated_ppi(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    s3 = load_stage("03_scale_validate")
    # Default: mismatch fails immediately
    args_strict = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                                 "--output-dir", str(tmp_path / "strict"), "--min-speedup", "5.0", "--seed", "7"])
    row = {"dbms": "mysql", "cell": "G", "family_id": "f1", "case_id": "c_mis", "case_path": str(tmp_path)}
    monkeypatch.setattr(s3, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(s3, "extend_database", lambda *_, **__: {"t0": 1000})
    replies = iter([make_replay(30.0, 5.0, equivalent=True), make_replay(300.0, 10.0, equivalent=False)])
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: next(replies))
    res_strict = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args_strict)
    assert res_strict["status"] == "FAILED"
    assert res_strict["failure_stage"] == "SCALED_10X_RESULT_NOT_EQUIVALENT"
    assert res_strict["result_equivalence"] == "MISMATCH"

    # Relaxed flag: still never inflates validated_ppi_count, only telemetry relaxed_mismatch_pass_count
    args_relaxed = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                                  "--output-dir", str(tmp_path / "relaxed"), "--min-speedup", "5.0",
                                  "--seed", "7", "--allow-result-mismatch"])
    replies2 = iter([make_replay(30.0, 5.0, equivalent=True), make_replay(300.0, 10.0, equivalent=False)])
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: next(replies2))
    res_relaxed = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args_relaxed)
    assert res_relaxed["status"] == "AMPLIFIED"
    assert res_relaxed["result_equivalence"] == "MISMATCH"
    out = tmp_path / "relaxed"
    out.mkdir()
    summary = s3.write_progress(out, args_relaxed, [row], [res_relaxed], "COMPLETE")
    assert summary["validated_ppi_count"] == 0
    assert summary["relaxed_mismatch_pass_count"] == 1


def test_measure_pair_timeout_early_stop_and_skip_scaled_explain_analyze(monkeypatch: pytest.MonkeyPatch) -> None:
    db = MySQLAdapter(DatabaseProfile(name="test", dbms="mysql", host="localhost", port=3306,
                                      user="test", password_env="TEST_PASSWORD", query_timeout_s=20))
    db._active_version = "9.7.1"
    calls: list[str] = []
    monkeypatch.setattr(db, "_with_database", lambda _: SimpleNamespace(close=lambda: None))

    def consume(_, query, __):
        calls.append(query)
        if query == "SELECT 1":
            raise pymysql.err.OperationalError(3024, "deadline")

    monkeypatch.setattr(db, "_consume_query", consume)
    measurement = db.measure_pair("puffer_ci_test", "SELECT 1", "SELECT 2", warmups_per_query=1, rounds=5,
                                  timeout_as_limit=True, timeout_early_stop=True, early_stop_min_speedup=5.0,
                                  early_stop_min_gap_ms=5.0, bootstrap_iterations=100)
    assert measurement.early_stopped is True
    assert measurement.early_stop_reason == "DEFAULT_TIMEOUT_FAST_COUNTERFACTUAL"
    assert len(measurement.rounds) == 1
    assert calls.count("SELECT 1") == 1
    assert calls.count("SELECT 2") == 2  # 1 warmup + 1 confirmed measurement round (not 6!)

    # Verify collect_analyze=False skips explain_analyze completely
    analyze_called = []
    monkeypatch.setattr(db, "refresh_statistics", lambda _: None)
    monkeypatch.setattr(db, "explain", lambda *_: ExplainResult("{}", {}, 1.0))
    monkeypatch.setattr(db, "explain_analyze", lambda *_: (analyze_called.append(True), AnalyzeResult("{}", {}))[1])
    monkeypatch.setattr(db, "compare_query_results", lambda *_, **__: {"equivalent": True})
    res = db.replay(SimpleNamespace(original_query="SELECT 1", counterfactual_query="SELECT 2"),
                    "puffer_ci_test", warmups_per_query=0, rounds=5, timeout_as_limit=True,
                    collect_analyze=False, timeout_early_stop=True, bootstrap_iterations=100).to_dict()
    assert analyze_called == []
    assert res["classification_metrics"]["timeoutEarlyStopped"] is True


def test_l1_l2_l3_rq34_required_fields_and_large_only_initial_scale(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # 1. Verify Large-only initial_scale in run_ablation_experiment.py
    cfg_path = tmp_path / "exp.yaml"
    cfg_path.write_text(yaml.safe_dump({
        "dbms": "mysql",
        "data_profiles": ["full"],
        "generator": {"initial_scale": 10},
    }), encoding="utf-8")
    cfg = experiment.load_config(cfg_path)
    assert cfg["generator"]["initial_scale"] == 10
    assert cfg["validation"]["allow_result_mismatch"] is False
    assert cfg["validation"]["query_timeout_seconds"] == 20
    assert cfg["validation"]["max_result_rows"] == 500_000
    target = SimpleNamespace(host="127.0.0.1", port=34971, user="sqlancer", default_password="pwd")
    env = experiment.online_environment(cfg, tmp_path / "arm", {"seed": 42, "data_profile": "full", "schema_prefix": "mysql971abl"}, target)
    assert env["PERF_INITIAL_SCALE"] == "10"

    # CLI --initial-scale override in dry-run
    run_dir = tmp_path / "dry_run_large"
    monkeypatch.setattr(experiment, "connection_target", lambda *_: pytest.fail("dry run must not connect"))
    assert experiment.main(["--config", str(cfg_path), "--initial-scale", "10", "--run-dir", str(run_dir), "--dry-run"]) == 0
    assert read_json(run_dir / "effective_config.json")["generator"]["initial_scale"] == 10

    # 2. Verify L1 -> L2 propagates delta_signature, pdg_primitives, stage1_rho_c, stage1_rho_t
    case_dir = tmp_path / "cases" / "B" / "case_001"
    case_dir.mkdir(parents=True)
    (case_dir / "default_explain.json").write_text(json.dumps({
        "json_schema_version": "2.0", "query_type": "select",
        "query_plan": {"access_type": "table", "table_name": "t0", "operation": "Table scan on t0"},
    }), encoding="utf-8")
    (case_dir / "counterfactual_explain.json").write_text(json.dumps({
        "json_schema_version": "2.0", "query_type": "select",
        "query_plan": {"access_type": "index", "table_name": "t0", "index_access_type": "index_lookup", "operation": "Index lookup on t0"},
    }), encoding="utf-8")
    (case_dir / "metrics.json").write_text(json.dumps({
        "baseQuery": "SELECT * FROM t0 WHERE c0 = 1",
        "costRatio": 42.5, "runtimeRatio": 1.12, "absoluteRuntimeGapMs": 0.4,
    }), encoding="utf-8")
    (case_dir / "pdg_ir.yaml").write_text(yaml.safe_dump({
        "pdg_ir_version": "pdg-ir/v1", "scale_semantics_version": "pdg-scale/v1", "dbms": "mysql",
        "tables": [{"name": "t0", "base_rows": 100, "columns": [
            {"name": "c0", "sql_type": "BIGINT", "producer": {"kind": "INDEPENDENT", "model": "ZIPF", "parameters": {}}},
            {"name": "c1", "sql_type": "BIGINT", "producer": {"kind": "INDEPENDENT", "model": "UNIFORM", "parameters": {}}},
        ]}],
        "relationships": [{"id": "r0", "type": "FUNCTIONAL_DEPENDENCY", "left": "t0.c0", "right": "t0.c1", "parameters": {}}],
    }), encoding="utf-8")

    s1 = load_stage("01_deduplicate")
    s2 = load_stage("02_cluster")
    assert s1.main(["--dbms", "mysql", "--input-root", str(tmp_path / "cases"), "--output-dir", str(tmp_path / "l1")]) == 0
    assert s2.main(["--dbms", "mysql", "--input-ir", str(tmp_path / "l1/case_deltas.jsonl"), "--output-dir", str(tmp_path / "l2")]) == 0

    families = read_json(tmp_path / "l2/families.json")
    assert len(families) == 1
    fam = families[0]
    assert isinstance(fam["delta_signature"], str) and len(fam["delta_signature"]) == 64
    assert fam["pdg_primitives"] == ["FUNCTIONAL_DEPENDENCY", "ZIPF"]
    assert fam["stage1_cell"] == "B"
    assert fam["stage1_rho_c"] == pytest.approx(42.5)
    assert fam["stage1_rho_t"] == pytest.approx(1.12)

    with (tmp_path / "l2/family_representatives.tsv").open(encoding="utf-8") as fh:
        rep_row = next(csv.DictReader(fh, delimiter="\t"))
    assert rep_row["delta_signature"] == fam["delta_signature"]
    assert json.loads(rep_row["pdg_primitives"]) == ["FUNCTIONAL_DEPENDENCY", "ZIPF"]


def test_large_only_same_scale_skips_extend_and_applies_5ms_gate(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    s3 = load_stage("03_scale_validate")
    args = s3.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "r.tsv"),
                          "--output-dir", str(tmp_path / "out"), "--scale-factor", "1",
                          "--min-speedup", "5.0", "--seed", "19"])
    assert args.scale == 1
    row = {"dbms": "mysql", "cell": "B", "family_id": "f1", "case_id": "case_large", "case_path": str(tmp_path)}
    monkeypatch.setattr(s3, "load_pdg", lambda _: {"dbms": "mysql", "tables": [{"name": "t0", "base_rows": 5000, "columns": []}]})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(s3, "extend_database", lambda *_, **__: pytest.fail("scale=1 must NEVER call extend_database"))

    # Case 1: speedup=10x, gap=18ms >= 5.0ms, EQUIVALENT -> SUSTAINED (VALIDATED_SAME_SCALE)
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: make_replay(20.0, 2.0, equivalent=True))
    res_pass = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert res_pass["status"] == "SUSTAINED"
    assert res_pass["reason"] == "VALIDATED_SAME_SCALE"
    assert res_pass["same_scale_validation"] is True
    assert res_pass["phase_elapsed_s"]["extend_10x_s"] == 0.0
    assert res_pass["scaled_row_counts"] == {"t0": 5000}
    assert res_pass["baseline_speedup"] == pytest.approx(10.0)
    assert res_pass["baseline_gap_ms"] == pytest.approx(18.0)
    assert res_pass["one_x_result_equivalence"] == "EQUIVALENT"
    out = tmp_path / "out_pass"
    out.mkdir()
    summary = s3.write_progress(out, args, [row], [res_pass], "COMPLETE")
    assert summary["validated_ppi_count"] == 1

    # Case 2: speedup=8x, gap=3.5ms < 5.0ms -> rejected even at same-scale (DISAPPEARED)
    monkeypatch.setattr(s3, "replay_phase", lambda *_, **__: make_replay(4.0, 0.5, equivalent=True, cost_default=10, cost_alt=10))
    res_small = s3.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert res_small["status"] == "DISAPPEARED"
    assert res_small["phase_elapsed_s"]["extend_10x_s"] == 0.0

    # Case 3: run_ablation_experiment auto-sets validation.scale=1 for --initial-scale 10 and excludes MySQL BKA
    cfg_path = tmp_path / "mysql_exp.yaml"
    cfg_path.write_text(yaml.safe_dump({"dbms": "mysql", "data_profiles": ["full"], "validation": {"scale": 10}}), encoding="utf-8")
    run_dir = tmp_path / "large_auto"
    monkeypatch.setattr(experiment, "connection_target", lambda *_: pytest.fail("dry run must not connect"))
    assert experiment.main(["--config", str(cfg_path), "--initial-scale", "10", "--run-dir", str(run_dir), "--dry-run"]) == 0
    eff = read_json(run_dir / "effective_config.json")
    assert eff["generator"]["initial_scale"] == 10
    assert eff["validation"]["scale"] == 1
    target = SimpleNamespace(host="127.0.0.1", port=34971, user="sqlancer", default_password="pwd")
    env = experiment.online_environment(eff, tmp_path / "arm", {"seed": 7, "data_profile": "full", "schema_prefix": "mysql971abl"}, target)
    assert env["PERF_HINT_EXCLUDE_TYPES"] == "NO_BNL,BKA"


def test_l2_features_strip_table_and_index_names_and_capture_join_order() -> None:
    from scripts.pdg_validation._plan_delta import canonical_delta_signature, delta_features_from_record

    # Same operator transition (TABLE -> INDEX_LOOKUP) on different table names (t0 vs t2) and index names (i1 vs i7)
    rec_a = {
        "dbms": "cockroachdb",
        "observed_delta": {
            "removed_facts": [{"fact": "SCAN|TABLE|None||t0", "count": 1}],
            "added_facts": [{"fact": "SCAN|INDEX_LOOKUP|None|index=i1|t0", "count": 1}],
            "tree_changed": True,
        },
        "normalized_plans": {
            "base": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t0"}, {"kind": "SCAN", "relation": "t1"}]},
            "alt": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t0"}, {"kind": "SCAN", "relation": "t1"}]},
        },
        "context": {"has_distinct": False, "has_order_by": False, "has_limit": False},
    }
    rec_b = {
        "dbms": "cockroachdb",
        "observed_delta": {
            "removed_facts": [{"fact": "SCAN|TABLE|None||t2", "count": 1}],
            "added_facts": [{"fact": "SCAN|INDEX_LOOKUP|None|index=i7|t2", "count": 1}],
            "tree_changed": True,
        },
        "normalized_plans": {
            "base": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t2"}, {"kind": "SCAN", "relation": "t3"}]},
            "alt": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t2"}, {"kind": "SCAN", "relation": "t3"}]},
        },
        "context": {"has_distinct": True, "has_order_by": False, "has_limit": False},
    }
    feats_a = delta_features_from_record(rec_a)
    feats_b = delta_features_from_record(rec_b)
    assert feats_a == feats_b
    assert not any("t0" in k or "t2" in k or "i1" in k or "i7" in k for k in feats_a)
    assert canonical_delta_signature(rec_a) == canonical_delta_signature(rec_b)

    # Pure join-order permutation (identical operators on swapped tables) captures DELTA:JOIN_ORDER_CHANGED
    rec_order = {
        "dbms": "mysql",
        "observed_delta": {
            "removed_facts": [{"fact": "JOIN|JOIN|NESTED_LOOP||t0,t1", "count": 1}],
            "added_facts": [{"fact": "JOIN|JOIN|NESTED_LOOP||t1,t2", "count": 1}],
            "tree_changed": True,
        },
        "normalized_plans": {
            "base": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t0"}, {"kind": "SCAN", "relation": "t1"}]},
            "alt": {"kind": "JOIN", "children": [{"kind": "SCAN", "relation": "t1"}, {"kind": "SCAN", "relation": "t0"}]},
        },
        "context": {},
    }
    feats_order = delta_features_from_record(rec_order)
    assert feats_order == {"DELTA:JOIN_ORDER_CHANGED": 1}

    # CockroachDB EXPLAIN(OPT, VERBOSE) histogram/literal noise and join=<table_name> must not leak into L2 features
    rec_crdb = {
        "dbms": "cockroachdb",
        "observed_delta": {
            "removed_facts": [
                {"fact": "HISTOGRAM(12)=|HISTOGRAM(12)=|None||t0", "count": 1},
                {"fact": "GAUSSIAN_1_3_1246'|GAUSSIAN_1_3_1246'|None||NONE", "count": 1},
                {"fact": "JOIN|INDEX_JOIN|None|join=t1|t0,t1", "count": 1},
                {"fact": "JOIN|INNER_JOIN|None|join=(lookup|t0,t1", "count": 1},
            ],
            "added_facts": [
                {"fact": "JOIN|INDEX_JOIN|None|join=t2|t0,t2", "count": 1},
                {"fact": "JOIN|INNER_JOIN|None|join=(hash)|t0,t2", "count": 1},
            ],
            "tree_changed": True,
        },
        "context": {},
    }
    feats_crdb = delta_features_from_record(rec_crdb)
    assert feats_crdb == {
        "ADD:JOIN|INNER_JOIN|None|join=hash": 1,
        "REMOVE:JOIN|INNER_JOIN|None|join=lookup": 1,
    }


def test_rq34_new_java_knobs_independent_timeouts_and_l1_representatives(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # 1. Explicit new knobs and decoupled Stage I (5s) vs L3 (20s) timeouts + min_speedup / cost_anomaly_ratio
    cfg_path = tmp_path / "rq34.yaml"
    cfg_path.write_text(yaml.safe_dump({
        "dbms": "mysql",
        "data_profiles": ["full"],
        "java": {
            "workers": 8,
            "num_tries": 1000,
            "num_queries": 64,
            "insert_batch_size": 500,
            "async_report_writes": True,
            "query_timeout_seconds": 5,
            "per_run_timeout_seconds": 3600,
            "query_rewrite_provider": "wetune-static",
            "wetune_static_worker": "/tmp/worker",
            "wetune_static_timeout_ms": 250,
        },
        "oracle": {
            "harmful_runtime_ratio": 3.0,
            "cost_anomaly_ratio": 10.0,
        },
        "validation": {
            "query_timeout_seconds": 20,
            "min_speedup": 3.0,
        },
    }), encoding="utf-8")
    cfg = experiment.load_config(cfg_path)
    assert cfg["java"]["insert_batch_size"] == 500
    assert cfg["java"]["num_tries"] == 1000
    assert cfg["java"]["async_report_writes"] is True
    assert cfg["java"]["query_timeout_seconds"] == 5
    assert cfg["validation"]["query_timeout_seconds"] == 20
    assert cfg["validation"]["min_speedup"] == pytest.approx(3.0)

    target = SimpleNamespace(host="127.0.0.1", port=34971, user="sqlancer", default_password="pwd")
    env = experiment.online_environment(cfg, tmp_path / "arm", {"seed": 99, "data_profile": "full", "schema_prefix": "mysql971abl"}, target)
    assert env["WORKERS"] == "8"
    assert env["TRIES"] == "1000"
    assert env["NUM_QUERIES"] == "64"
    assert env["PERF_INSERT_BATCH_SIZE"] == "500"
    assert env["PERF_ASYNC_REPORT_WRITES"] == "1"
    assert env["QUERY_TIMEOUT_SECONDS"] == "5"
    assert env["PERF_REPORT_HARMFUL_RUNTIME_RATIO"] == "3.0"
    assert env["PERF_REPORT_COST_ANOMALY_RATIO"] == "10.0"

    arm = tmp_path / "arm_offline"
    arm.mkdir()
    for stage in ("l1", "l2"):
        d = arm / stage
        d.mkdir()
        (d / "summary.json").write_text("{}", encoding="utf-8")
    meta = {"seed": 99, "l1": str(arm / "l1"), "l2": str(arm / "l2")}
    l3_calls = []

    def fake_run(cmd, log):
        l3_calls.append(cmd)
        out = Path(cmd[cmd.index("--output-dir") + 1])
        out.mkdir(parents=True, exist_ok=True)
        (out / "summary.json").write_text('{"cleanup_errors": []}', encoding="utf-8")

    monkeypatch.setattr(experiment, "run_command", fake_run)
    experiment.offline(cfg, arm, meta)
    assert len(l3_calls) == 1
    l3_cmd = l3_calls[0]
    assert l3_cmd[l3_cmd.index("--query-timeout-s") + 1] == "20"
    assert l3_cmd[l3_cmd.index("--min-speedup") + 1] == "3.0"
    assert l3_cmd[l3_cmd.index("--cost-anomaly-ratio") + 1] == "10.0"

    # 2. Backward compatibility defaults & invalid insert_batch_size bounds
    legacy_path = tmp_path / "legacy.yaml"
    legacy_path.write_text(yaml.safe_dump({
        "dbms": "mysql",
        "java": {"workers": 4},
        "oracle": {"harmful_runtime_ratio": 4.0},
    }), encoding="utf-8")
    legacy_cfg = experiment.load_config(legacy_path)
    assert legacy_cfg["java"]["num_tries"] == 4
    assert legacy_cfg["java"]["insert_batch_size"] == 25
    assert legacy_cfg["java"]["async_report_writes"] is False
    assert legacy_cfg["validation"]["min_speedup"] == pytest.approx(4.0)

    for bad_batch in (0, 2001):
        bad_path = tmp_path / f"bad_{bad_batch}.yaml"
        bad_path.write_text(yaml.safe_dump({"dbms": "mysql", "java": {"insert_batch_size": bad_batch}}), encoding="utf-8")
        with pytest.raises(experiment.ValidationInputError):
            experiment.load_config(bad_path)

    # 3. 03_scale_validate accepts L1 representatives.tsv with group_id instead of family_id
    l1_reps = tmp_path / "representatives.tsv"
    with l1_reps.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["dbms", "cell", "group_id", "case_id", "case_path"], delimiter="\t")
        writer.writeheader()
        writer.writerow({"dbms": "mysql", "cell": "B", "group_id": "grp_001", "case_id": "case_1", "case_path": str(tmp_path / "c1")})
    s3 = load_stage("03_scale_validate")
    loaded = s3.read_representatives(l1_reps, "mysql", ("A", "B", "C", "D", "G"), 0)
    assert len(loaded) == 1
    assert loaded[0]["family_id"] == "grp_001"


