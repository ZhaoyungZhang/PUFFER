from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.pdg_validation._common import ValidationInputError, representative
from scripts.pdg_validation._pdg import PdgExtender, load_pdg
from scripts.pdg_validation._plan_delta import normalize_mariadb, normalize_mysql, observed_delta


def producer(kind: str, model: str, parameters: dict, policy: str = "FIXED_DISTRIBUTION_EXTEND_SAMPLES") -> dict:
    return {"kind": kind, "model": model, "scale_policy": policy, "parameters": parameters}


def base_document() -> dict:
    return {
        "pdg_ir_version": "pdg-ir/v1",
        "scale_semantics_version": "pdg-scale/v1",
        "dbms": "mysql",
        "run_seed": 7,
        "current_scale": 1,
        "tables": [],
        "relationships": [],
        "column_profiles": [],
    }


def test_mysql_and_mariadb_plan_normalization() -> None:
    mysql = {"json_schema_version": "2.0", "query_type": "select", "query_plan": {
        "access_type": "join", "join_algorithm": "hash", "inputs": [
            {"access_type": "table", "table_name": "t0"},
            {"access_type": "index", "table_name": "t1", "index_access_type": "index_lookup"},
        ]}}
    maria = {"query_block": {"select_id": 1, "filesort": {"temporary_table": {"nested_loop": [
        {"table": {"table_name": "t0", "access_type": "ALL"}},
        {"block-nl-join": {"table": {"table_name": "t1", "access_type": "range"}, "join_type": "BNL"}},
    ]}}}}
    mysql_tree = normalize_mysql(mysql, Path("mysql.json"))
    maria_tree = normalize_mariadb(maria, Path("maria.json"))
    assert mysql_tree["kind"] == "JOIN" and mysql_tree["algorithm"] == "HASH"
    assert maria_tree["kind"] == "SORT"
    assert observed_delta(mysql_tree, mysql_tree)["tree_changed"] is False


def test_independent_and_derived_scale_rules() -> None:
    document = base_document()
    document["tables"] = [{"name": "t0", "base_rows": 10, "indexes": [], "columns": [
        {"name": "c0", "sql_type": "BIGINT", "producer": producer(
            "INDEPENDENT", "LOW_NDV", {"domain_start": 0, "domain_size": 3,
                                         "generation": "row_index_modulo_domain", "value_seed": 11})},
        {"name": "c1", "sql_type": "BIGINT", "producer": producer(
            "INTRA_TABLE_DERIVED", "FUNCTIONAL_DEPENDENCY",
            {"relationship_id": "r0", "role": "dependent", "multiplier": 3, "offset": 7}, "FIXED_MAPPING")},
        {"name": "c2", "sql_type": "VARCHAR(64)", "producer": producer(
            "INDEPENDENT", "NULL_HEAVY", {"null_probability": 0.7, "non_null_model": "UNIFORM",
                                             "minimum": -10, "maximum": 10, "value_seed": 13})},
    ]}]
    document["relationships"] = [{"id": "r0", "type": "FUNCTIONAL_DEPENDENCY", "left": "t0.c0",
                                    "right": "t0.c1", "parameters": {"multiplier": 3, "offset": 7}}]
    extender = PdgExtender(document, 10)
    rows = extender.additional_rows("t0")
    assert len(rows) == 90
    assert all(row[1] == row[0] * 3 + 7 for row in rows)
    assert {row[0] for row in rows} <= {0, 1, 2}


def test_all_independent_models_and_anti_correlation_scale_rules() -> None:
    document = base_document()
    document["tables"] = [{"name": "t0", "base_rows": 10, "indexes": [], "columns": [
        {"name": "uniform_col", "sql_type": "BIGINT", "producer": producer(
            "INDEPENDENT", "UNIFORM", {"minimum": -7, "maximum": 9, "value_seed": 101})},
        {"name": "zipf_col", "sql_type": "BIGINT", "producer": producer(
            "INDEPENDENT", "ZIPF", {"domain_start": 4, "domain_size": 5, "exponent": 1.0,
                                      "value_seed": 103})},
        {"name": "gaussian_col", "sql_type": "DOUBLE", "producer": producer(
            "INDEPENDENT", "GAUSSIAN", {"mean": 12.5, "standard_deviation": 2.0,
                                          "rounding": "sql_type", "value_seed": 107})},
        {"name": "ascending_col", "sql_type": "BIGINT", "producer": producer(
            "INDEPENDENT", "MONOTONIC_ASC", {"start": 20, "step": 3,
                                                "direction": "ascending", "value_seed": 109},
            "EXTEND_SEQUENCE")},
        {"name": "descending_col", "sql_type": "BIGINT", "producer": producer(
            "INDEPENDENT", "MONOTONIC_DESC", {"start": 200, "step": 2,
                                                 "direction": "descending", "value_seed": 113},
            "EXTEND_SEQUENCE")},
        {"name": "anti_col", "sql_type": "BIGINT", "producer": producer(
            "INTRA_TABLE_DERIVED", "ANTI_CORRELATION",
            {"relationship_id": "r0", "role": "dependent", "constant": 1000}, "FIXED_MAPPING")},
    ]}]
    document["relationships"] = [{"id": "r0", "type": "ANTI_CORRELATION",
                                    "left": "t0.uniform_col", "right": "t0.anti_col",
                                    "parameters": {"constant": 1000}}]

    rows = PdgExtender(document, 10).additional_rows("t0")
    assert len(rows) == 90
    assert all(-7 <= row[0] <= 9 for row in rows)
    assert {row[1] for row in rows} <= {4, 5, 6, 7, 8}
    assert all(isinstance(row[2], float) for row in rows)
    assert rows[0][3] == 50 and rows[-1][3] == 317
    assert rows[0][4] == 200 and rows[-1][4] == 22
    assert all(row[5] == 1000 - row[0] for row in rows)


@pytest.mark.parametrize("fanout_type,fanout,parent_rows,child_rows", [
    ("ONE_TO_ONE", 1, 10, 10), ("ONE_TO_MANY", 2, 10, 20), ("MANY_TO_MANY", 1, 10, 15)
])
def test_fanout_scale_rules(fanout_type: str, fanout: int, parent_rows: int, child_rows: int) -> None:
    document = base_document()
    parameters = {"fanout_type": fanout_type, "fanout": fanout, "parent_key_start": 1_000_000,
                  "private_key_start": -(2 ** 63)}
    if fanout_type == "MANY_TO_MANY":
        parameters.update({"shared_domain_start": 0, "shared_domain_size": 4})
    relationship = {"id": "r0", "type": "JOIN_FANOUT", "left": "t0.c0", "right": "t1.c0",
                    "parameters": parameters}
    document["relationships"] = [relationship]
    document["tables"] = [
        {"name": "t0", "base_rows": parent_rows, "indexes": [], "columns": [{
            "name": "c0", "sql_type": "BIGINT", "producer": producer(
                "INTER_TABLE_ENDPOINT", "JOIN_FANOUT", {"relationship_id": "r0", "role": "parent", **parameters},
                "PRESERVE_FANOUT")}]},
        {"name": "t1", "base_rows": child_rows, "indexes": [], "columns": [{
            "name": "c0", "sql_type": "BIGINT", "producer": producer(
                "INTER_TABLE_ENDPOINT", "JOIN_FANOUT", {"relationship_id": "r0", "role": "child", **parameters},
                "PRESERVE_FANOUT")}]},
    ]
    extender = PdgExtender(document, 10)
    parent = extender.additional_rows("t0")
    child = extender.additional_rows("t1")
    assert len(parent) == parent_rows * 9 and len(child) == child_rows * 9
    if fanout_type == "ONE_TO_MANY":
        assert child[0][0] == 1_000_000 + child_rows // fanout
    if fanout_type == "MANY_TO_MANY":
        assert {row[0] for row in parent + child} <= {0, 1, 2, 3}


def test_referential_and_rst_target_counts() -> None:
    document = base_document()
    document["relationships"] = [{"id": "r0", "type": "REFERENTIAL_CORRELATION", "left": "R.c0",
                                    "right": "S.c0", "parameters": {"match_probability": 0.9,
                                    "match_period": 10, "parent_key_start": 1_000_000,
                                    "private_key_start": -(2 ** 63)}}]
    endpoint = {"relationship_id": "r0", "match_probability": 0.9, "match_period": 10,
                "parent_key_start": 1_000_000, "private_key_start": -(2 ** 63)}
    uniform = {"minimum": -100, "maximum": 100, "value_seed": 17}
    document["tables"] = [
        {"name": "R", "base_rows": 100, "indexes": [], "columns": [{"name": "c0", "sql_type": "BIGINT",
          "producer": producer("INTER_TABLE_ENDPOINT", "REFERENTIAL_CORRELATION", {**endpoint, "role": "parent"})}]},
        {"name": "S", "base_rows": 240, "indexes": [], "columns": [{"name": "c0", "sql_type": "BIGINT",
          "producer": producer("INTER_TABLE_ENDPOINT", "REFERENTIAL_CORRELATION", {**endpoint, "role": "child"})}]},
        {"name": "T", "base_rows": 100, "indexes": [], "columns": [{"name": "c0", "sql_type": "BIGINT",
          "producer": producer("INDEPENDENT", "UNIFORM", uniform)}]},
    ]
    extender = PdgExtender(document, 10)
    assert extender.target_rows == {"R": 1000, "S": 2400, "T": 1000}
    child = extender.additional_rows("S")
    assert child[9][0] < 0
    assert child[10][0] >= 1_000_000


@pytest.mark.parametrize("model,parameters", [
    ("ANTI_CORRELATION", {"constant": 1000000}),
    ("FUNCTIONAL_DEPENDENCY", {"multiplier": 3, "offset": 7}),
])
def test_private_keys_and_derived_columns_match_java_long_overflow(model, parameters) -> None:
    document = base_document()
    endpoint = {"parent_key_start": 1000000, "private_key_start": -(1 << 63), "match_period": 10}
    document["relationships"] = [
        {"id": "join", "type": "REFERENTIAL_CORRELATION", "left": "parent.c0", "right": "child.c0",
         "parameters": endpoint},
        {"id": "derived", "type": model, "left": "child.c0", "right": "child.c1", "parameters": parameters}]
    document["tables"] = [
        {"name": name, "base_rows": 10, "columns": [{"name": "c0", "sql_type": "BIGINT", "producer": producer(
            "INTER_TABLE_ENDPOINT", "REFERENTIAL_CORRELATION", {"relationship_id": "join", "role": name})}]}
        for name in ("parent", "child")]
    document["tables"][1]["columns"].append({"name": "c1", "sql_type": "BIGINT", "producer": producer(
        "INTRA_TABLE_DERIVED", model, {"relationship_id": "derived", **parameters})})
    rows = PdgExtender(document, 10).additional_rows("child")
    for source, derived in rows:
        raw = 1000000 - source if model == "ANTI_CORRELATION" else source * 3 + 7
        expected = ((raw + (1 << 63)) % (1 << 64)) - (1 << 63)
        assert derived == expected
        assert -(1 << 63) <= derived < (1 << 63)
    assert rows[9][0] == -(1 << 63) + 19
    assert rows[9][1] < 0


def test_invalid_yaml_and_runtime_direction(tmp_path: Path) -> None:
    with pytest.raises(ValidationInputError, match="cannot load PDG-IR"):
        load_pdg(tmp_path / "missing.yaml")

    path = tmp_path / "bad.yaml"
    path.write_text(yaml.safe_dump({"pdg_ir_version": "future"}), encoding="utf-8")
    with pytest.raises(ValidationInputError):
        load_pdg(path)

    module_path = REPO_ROOT / "scripts/pdg_validation/03_scale_validate.py"
    spec = importlib.util.spec_from_file_location("pdg_scale_validate", module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    args = type("Args", (), {"min_speedup": 1.3, "min_gap_ms": 100.0, "min_win_fraction": 0.8})()
    runtime = {"default": {"median_ms": 300.0}, "counterfactual": {"median_ms": 100.0},
               "rounds": [{"default_runtime_ms": 300.0, "counterfactual_runtime_ms": 100.0}] * 5}
    assert module.runtime_gate(runtime, args)["valid"]
    assert not module.runtime_gate(runtime, args, "COUNTERFACTUAL")["valid"]


def test_unknown_scale_version_and_deterministic_representative(tmp_path: Path) -> None:
    document = base_document()
    document["scale_semantics_version"] = "pdg-scale/future"
    document["tables"] = [{"name": "t0", "base_rows": 1, "columns": [{
        "name": "c0", "sql_type": "BIGINT",
        "producer": producer("INDEPENDENT", "UNIFORM", {"minimum": 0, "maximum": 1, "value_seed": 1}),
    }]}]
    path = tmp_path / "future.yaml"
    path.write_text(yaml.safe_dump(document), encoding="utf-8")
    with pytest.raises(ValidationInputError, match="scale_semantics_version"):
        load_pdg(path)

    records = [
        {"case_id": "case_c", "phenotype": {"absolute_runtime_gap_ms": 10.0, "runtime_ratio": 3.0}},
        {"case_id": "case_b", "phenotype": {"absolute_runtime_gap_ms": 12.0, "runtime_ratio": 2.0}},
        {"case_id": "case_a", "phenotype": {"absolute_runtime_gap_ms": 12.0, "runtime_ratio": 2.0}},
    ]
    assert representative(records)["case_id"] == "case_a"


def test_scale_validation_timeout_and_inequivalent_statuses(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module_path = REPO_ROOT / "scripts/pdg_validation/03_scale_validate.py"
    spec = importlib.util.spec_from_file_location("pdg_scale_statuses", module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    case_dir = tmp_path / "case_1"
    case_dir.mkdir()
    row = {"dbms": "mysql", "cell": "A", "family_id": "mysql-A-L2-00001",
           "case_id": "case_1", "case_path": str(case_dir)}
    args = type("Args", (), {
        "dbms": "mysql", "scale": 10, "seed": 7, "max_table_rows": 300_000,
        "min_speedup": 1.01, "baseline_min_speedup": 1.01, "min_gap_ms": 0.0, "min_win_fraction": 0.6,
    })()

    class Adapter:
        def prepare_sandbox(self, case: object, sandbox: str) -> None:
            pass

        def cleanup(self, sandbox: str) -> None:
            pass

    monkeypatch.setattr(module, "load_pdg", lambda path: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda path, load_setup: object())
    monkeypatch.setattr(module, "extend_database", lambda *unused, **ignored: {"t0": 10})
    successful = {
        "result_comparison": {"equivalent": True},
        "runtime": {
            "default": {"median_ms": 2.0}, "counterfactual": {"median_ms": 1.0},
            "rounds": [{"default_runtime_ms": 2.0, "counterfactual_runtime_ms": 1.0}] * 5,
        },
    }
    calls = iter((successful, TimeoutError("query timeout")))

    def replay_or_raise(*unused: object, **ignored: object) -> dict:
        value = next(calls)
        if isinstance(value, BaseException):
            raise value
        return value

    monkeypatch.setattr(module, "replay_phase", replay_or_raise)
    timed_out = module.validate_one(row, Adapter(), args)
    assert timed_out["status"] == "FAILED"
    assert timed_out["failure_stage"] == "SCALED_10X"
    assert timed_out["error"]["type"] == "TimeoutError"

    monkeypatch.setattr(module, "replay_phase",
                        lambda *unused, **ignored: {"result_comparison": {"equivalent": False}})
    inequivalent = module.validate_one(row, Adapter(), args)
    assert inequivalent["status"] == "FAILED"
    assert inequivalent["failure_stage"] == "CLEAN_1X_RESULT_NOT_EQUIVALENT"


def test_new_section4_primitives_and_canonical_scale_policies() -> None:
    document = base_document()
    document["relationships"] = [
        {
            "id": "r_skew",
            "type": "SKEWED_FANOUT",
            "left": "R.c0",
            "right": "S.c0",
            "parameters": {
                "shared_domain_start": 100000,
                "shared_domain_size": 8,
                "exponent": 1.2,
                "parent_value_seed": 101,
                "child_value_seed": 202,
            },
        },
        {
            "id": "r_disjoint",
            "type": "DISJOINT_RANGE",
            "left": "R.c1",
            "right": "S.c1",
            "parameters": {
                "range_start": 500000,
                "range_width": 20,
                "overlap_ratio": 0.05,
                "range_shift": 19,
            },
        },
        {
            "id": "r_rank",
            "type": "RANK_COUPLE",
            "left": "R.c0",
            "right": "R.c2",
            "parameters": {
                "rho": 0.95,
                "minimum": -1000,
                "maximum": 1000,
                "value_seed": 303,
            },
        },
        {
            "id": "r_cond",
            "type": "COND_PATTERN",
            "left": "S.c0",
            "right": "S.c2",
            "parameters": {
                "trigger_modulus": 10,
                "trigger_remainder": 2,
                "cond_min": 900000,
                "cond_max": 900050,
                "bg_min": -1000,
                "bg_max": 1000,
                "value_seed": 404,
            },
        },
    ]
    document["tables"] = [
        {
            "name": "R",
            "base_rows": 20,
            "indexes": [],
            "columns": [
                {
                    "name": "c0",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "SKEWED_FANOUT",
                        {"relationship_id": "r_skew", "role": "parent"},
                        "SCALE_FREQ",
                    ),
                },
                {
                    "name": "c1",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "DISJOINT_RANGE",
                        {"relationship_id": "r_disjoint", "role": "parent"},
                        "SCALE_DOMAIN",
                    ),
                },
                {
                    "name": "c2",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTRA_TABLE_DERIVED",
                        "RANK_COUPLE",
                        {"relationship_id": "r_rank", "role": "dependent"},
                        "SCALE_DEP",
                    ),
                },
                {
                    "name": "c3",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INDEPENDENT",
                        "POINT_SPIKE",
                        {
                            "spike_value": 4242,
                            "spike_period": 5,
                            "minimum": -100,
                            "maximum": 100,
                            "value_seed": 505,
                        },
                        "SCALE_FREQ",
                    ),
                },
            ],
        },
        {
            "name": "S",
            "base_rows": 20,
            "indexes": [],
            "columns": [
                {
                    "name": "c0",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "SKEWED_FANOUT",
                        {"relationship_id": "r_skew", "role": "child"},
                        "SCALE_FREQ",
                    ),
                },
                {
                    "name": "c1",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "DISJOINT_RANGE",
                        {"relationship_id": "r_disjoint", "role": "child"},
                        "SCALE_DOMAIN",
                    ),
                },
                {
                    "name": "c2",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTRA_TABLE_DERIVED",
                        "COND_PATTERN",
                        {"relationship_id": "r_cond", "role": "dependent"},
                        "SCALE_DEP",
                    ),
                },
            ],
        },
    ]
    extender = PdgExtender(document, 3)
    r_rows = extender.additional_rows("R")
    s_rows = extender.additional_rows("S")
    assert len(r_rows) == 40
    assert len(s_rows) == 40
    # POINT_SPIKE hits 4242 at every row_index % 5 == 0
    assert r_rows[0][3] == 4242
    # SKEWED_FANOUT stays within shared domain [100000, 100008)
    assert all(100000 <= row[0] < 100008 for row in r_rows + s_rows)
    # COND_PATTERN places triggered rows into [900000, 900050]
    for row in s_rows:
        if (row[0] % 10) < 2:
            assert 900000 <= row[2] <= 900050
        else:
            assert -1000 <= row[2] <= 1000


def test_partial_ref_scale_overlap_distributes_over_new_parent_keys() -> None:
    document = base_document()
    endpoint = {
        "relationship_id": "r_partial",
        "match_probability": 0.5,
        "match_count": 1,
        "match_period": 2,
        "parent_key_start": 1_000_000,
        "private_key_start": -(1 << 63),
    }
    document["relationships"] = [
        {
            "id": "r_partial",
            "type": "REFERENTIAL_CORRELATION",
            "left": "R.c0",
            "right": "S.c0",
            "parameters": endpoint,
        }
    ]
    document["tables"] = [
        {
            "name": "R",
            "base_rows": 10,
            "indexes": [],
            "columns": [
                {
                    "name": "c0",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "REFERENTIAL_CORRELATION",
                        {**endpoint, "role": "parent"},
                        "SCALE_DOMAIN",
                    ),
                }
            ],
        },
        {
            "name": "S",
            "base_rows": 20,
            "indexes": [],
            "columns": [
                {
                    "name": "c0",
                    "sql_type": "BIGINT",
                    "producer": producer(
                        "INTER_TABLE_ENDPOINT",
                        "REFERENTIAL_CORRELATION",
                        {**endpoint, "role": "child"},
                        "SCALE_OVERLAP",
                    ),
                }
            ],
        },
    ]
    extender = PdgExtender(document, 3)
    new_parent_keys = {row[0] for row in extender.additional_rows("R")}
    assert new_parent_keys == set(range(1_000_010, 1_000_030))
    new_child_rows = [row[0] for row in extender.additional_rows("S")]
    matched = [val for val in new_child_rows if val >= 1_000_000]
    unmatched = [val for val in new_child_rows if val < 0]
    assert len(matched) == 20
    assert len(unmatched) == 20
    assert set(matched) <= new_parent_keys


def test_unified_validated_ppi_activated_accounting_and_resummarize(tmp_path: Path) -> None:
    module_path = REPO_ROOT / "scripts/pdg_validation/03_scale_validate.py"
    spec = importlib.util.spec_from_file_location("pdg_scale_unified", module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    out_dir = tmp_path / "l3"
    out_dir.mkdir(parents=True)
    selected = [
        {"dbms": "mariadb", "cell": "A", "family_id": "f_a", "case_id": "c_a", "case_path": "/tmp/ca"},
        {"dbms": "mariadb", "cell": "B", "family_id": "f_b1", "case_id": "c_b1", "case_path": "/tmp/cb1"},
        {"dbms": "mariadb", "cell": "C", "family_id": "f_c1", "case_id": "c_c1", "case_path": "/tmp/cc1"},
        {"dbms": "mariadb", "cell": "D", "family_id": "f_d1", "case_id": "c_d1", "case_path": "/tmp/cd1"},
        {"dbms": "mariadb", "cell": "G", "family_id": "f_g1", "case_id": "c_g1", "case_path": "/tmp/cg1"},
    ]
    results = [
        {**selected[0], "status": "AMPLIFIED", "equivalence_verified": True},
        {**selected[1], "status": "ACTIVATED", "equivalence_verified": True},
        {**selected[2], "status": "COST_ANOMALY", "cost_anomaly_only": True, "equivalence_verified": True},
        {**selected[3], "status": "SUSTAINED", "equivalence_verified": True},
        {**selected[4], "status": "DISAPPEARED", "equivalence_verified": True},
    ]
    for res in results:
        case_file = out_dir / "cases" / res["cell"] / f"{res['family_id']}.json"
        case_file.parent.mkdir(parents=True, exist_ok=True)
        case_file.write_text(json.dumps(res), encoding="utf-8")
    manifest = {
        "dbms": "mariadb", "cells": ["A", "B", "C", "D", "G"], "scale": 10,
        "min_speedup": 5.0, "baseline_min_speedup": 2.0,
        "allow_result_mismatch": True, "scaled_timeout_pass": True,
        "selected": selected,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    assert module.main(["--resummarize", "--output-dir", str(out_dir)]) == 0
    summary = json.loads((out_dir / "summary.json").read_text(encoding="utf-8"))
    assert summary["amplified_direct"] == 1
    assert summary["amplified_rescued"] == 1
    assert summary["sustained"] == 1
    assert summary["amplified_total"] == 2
    assert summary["validated_ppi_count"] == 3
    assert summary["validation_pass_rate"] == pytest.approx(0.6)
    matrix = summary["stage1_to_stage2_matrix"]
    assert matrix["B"]["ACTIVATED"] == 1 and matrix["B"]["VALIDATED_PPI"] == 1
    assert matrix["C"]["COST_ANOMALY"] == 1 and matrix["C"]["VALIDATED_PPI"] == 0
    assert matrix["TOTAL"]["VALIDATED_PPI"] == 3 and matrix["TOTAL"]["NOT_VALIDATED"] == 1
    assert summary["stage1_to_stage2_summary"]["rescued_from_bc"] == 1
    assert summary["stage1_to_stage2_summary"]["bc_activated"] == 1
    report_text = (out_dir / "report.md").read_text(encoding="utf-8")
    assert "Stage I Cell → Stage II Status Matrix" in report_text
    assert "Validated PPI (AMP+ACT+SUS): `3` / `5` (`60.00%`)" in report_text


def test_decoupled_gap_thresholds_and_foolproof_cli_defaults(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module_path = REPO_ROOT / "scripts/pdg_validation/03_scale_validate.py"
    spec = importlib.util.spec_from_file_location("pdg_scale_defaults", module_path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    base_argv = ["--dbms", "mysql", "--representatives", str(tmp_path / "reps.tsv"),
                 "--output-dir", str(tmp_path / "l3")]
    defaults = module.parse_args(base_argv)
    assert defaults.baseline_min_gap_ms == pytest.approx(0.5)
    assert defaults.scaled_min_gap_ms == pytest.approx(5.0)
    assert defaults.min_gap_ms == pytest.approx(5.0)
    assert defaults.scaled_timeout_pass is True
    assert defaults.allow_result_mismatch is False

    disabled = module.parse_args([*base_argv, "--no-scaled-timeout-pass"])
    assert disabled.scaled_timeout_pass is False

    legacy = module.parse_args([*base_argv, "--min-gap-ms", "1.5"])
    assert legacy.baseline_min_gap_ms == pytest.approx(1.5)
    assert legacy.scaled_min_gap_ms == pytest.approx(1.5)

    partial = module.parse_args([*base_argv, "--baseline-min-gap-ms", "0.2", "--min-gap-ms", "8.0"])
    assert partial.baseline_min_gap_ms == pytest.approx(0.2)
    assert partial.scaled_min_gap_ms == pytest.approx(8.0)

    def _replay(default_ms: float, alt_ms: float) -> dict:
        return {
            "result_comparison": {"equivalent": True},
            "runtime": {
                "default": {"median_ms": default_ms},
                "counterfactual": {"median_ms": alt_ms},
                "rounds": [{"default_runtime_ms": default_ms, "counterfactual_runtime_ms": alt_ms}] * 5,
            },
            "classification_metrics": {"defaultEstimatedCost": 10.0, "counterfactualEstimatedCost": 10.0},
        }

    case_dir = tmp_path / "case_gap"
    case_dir.mkdir()
    monkeypatch.setattr(module, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(module, "extend_database", lambda *_, **__: {"t0": 1000})
    adapter = type("Adapter", (), {
        "prepare_sandbox": lambda self, *_: None,
        "cleanup": lambda self, *_: None,
    })()

    # 1) B/C candidates must NOT be blocked by 1x baseline_min_gap_ms (e.g. 0.1ms < 0.5ms) and become ACTIVATED if 10x >= 5.0ms
    for cell in ("B", "C"):
        calls = iter((_replay(1.1, 1.0), _replay(20.0, 2.0)))
        monkeypatch.setattr(module, "replay_phase", lambda *_, **__: next(calls))
        res = module.validate_one(
            {"dbms": "mysql", "cell": cell, "family_id": f"f_{cell}", "case_id": f"c_{cell}", "case_path": str(case_dir)},
            adapter, module.parse_args([*base_argv, "--min-speedup", "5.0", "--seed", "7"]),
        )
        assert res["status"] == "ACTIVATED"
        assert "ten_x" in res

    # 2) Cell A/G candidates with 1x gap < 0.5ms (0.1ms) stop cleanly at 1x
    calls = iter((_replay(1.1, 1.0), _replay(20.0, 2.0)))
    monkeypatch.setattr(module, "replay_phase", lambda *_, **__: next(calls))
    res_a_small_1x = module.validate_one(
        {"dbms": "mysql", "cell": "A", "family_id": "f_a1", "case_id": "c_a1", "case_path": str(case_dir)},
        adapter, module.parse_args([*base_argv, "--min-speedup", "5.0", "--seed", "7"]),
    )
    assert res_a_small_1x["status"] == "DISAPPEARED"
    assert res_a_small_1x["reason"] == "CLEAN_1X_RUNTIME_EFFECT_NOT_RETAINED"

    # 3) 10x gap < scaled_min_gap_ms (5.0ms) rejects both AMPLIFIED (Cell A: 1x gap=1.5ms >=0.5ms, 10x gap=4.0ms <5.0ms)
    #    and ACTIVATED (Cell B: 10x gap=4.0ms <5.0ms), even when speedup ratio meets min_speedup=1.3
    for cell in ("A", "B"):
        calls = iter((_replay(3.0, 1.5), _replay(6.0, 2.0)))
        monkeypatch.setattr(module, "replay_phase", lambda *_, **__: next(calls))
        res_small_10x = module.validate_one(
            {"dbms": "mysql", "cell": cell, "family_id": f"f_{cell}2", "case_id": f"c_{cell}2", "case_path": str(case_dir)},
            adapter, module.parse_args([*base_argv, "--min-speedup", "1.3", "--seed", "7"]),
        )
        assert res_small_10x["status"] == "DISAPPEARED"
        assert res_small_10x["reason"] == "SCALED_RUNTIME_EFFECT_NOT_RETAINED"


def test_run_ablation_experiment_defaults_and_strict_equivalence(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from scripts import run_ablation_experiment as experiment
    from scripts.pdg_validation._common import atomic_json

    assert experiment.DEFAULTS["validation"]["allow_result_mismatch"] is False
    assert experiment.DEFAULTS["validation"]["scaled_timeout_pass"] is True
    assert experiment.DEFAULTS["validation"]["baseline_min_gap_ms"] == pytest.approx(0.5)
    assert experiment.DEFAULTS["validation"]["scaled_min_gap_ms"] == pytest.approx(5.0)

    cfg_path = tmp_path / "exp.yaml"
    cfg_path.write_text(yaml.safe_dump({"dbms": "mysql"}), encoding="utf-8")
    config = experiment.load_config(cfg_path)
    arm = tmp_path / "arm"
    arm.mkdir()
    meta = {"seed": 11}
    for st in ("l1", "l2"):
        d = arm / st
        d.mkdir()
        atomic_json(d / "summary.json", {})
        meta[st] = str(d)
    commands = []

    def fake_run(cmd, _log):
        commands.append(cmd)
        out = Path(cmd[cmd.index("--output-dir") + 1])
        out.mkdir(parents=True, exist_ok=True)
        atomic_json(out / "summary.json", {"cleanup_errors": []})

    monkeypatch.setattr(experiment, "run_command", fake_run)
    experiment.offline(config, arm, meta)
    cmd = commands[0]
    assert "--allow-result-mismatch" not in cmd
    assert "--scaled-timeout-pass" in cmd
    assert "--no-scaled-timeout-pass" not in cmd
    assert cmd[cmd.index("--baseline-min-gap-ms") + 1] == "0.5"
    assert cmd[cmd.index("--scaled-min-gap-ms") + 1] == "5.0"

    config["validation"]["scaled_timeout_pass"] = False
    meta2 = {"seed": 12, "l1": meta["l1"], "l2": meta["l2"]}
    arm2 = tmp_path / "arm2"
    arm2.mkdir()
    experiment.offline(config, arm2, meta2)
    cmd2 = commands[1]
    assert "--no-scaled-timeout-pass" in cmd2
    assert "--scaled-timeout-pass" not in cmd2



