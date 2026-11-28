#!/usr/bin/env python3
"""Run a serial data-generation ablation across any supported DBMS, then L1/L2/L3 validation.

The online budget excludes offline validation. Credentials stay in environment
variables; cleanup uses only the exact schema allowlist written before Java runs.
"""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time
from typing import Any, Sequence

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))
from scripts import cleanup_mysql_mariadb_test_schemas as cleanup
from scripts.pdg_validation._common import (ValidationInputError, atomic_json, discover_cases, read_json,
                                            require_output_dir, write_tsv)

CELLS = ("A", "B", "C", "D", "G")
PROFILES = ("random", "column", "column-intra", "column-inter", "full")
try:
    from puffer_case_investigator.backends import BACKENDS as _BACKENDS
except ImportError:
    _BACKENDS = None

if _BACKENDS is not None:
    SCHEMA_PREFIXES = {
        name: backend.schema_prefix
        for name, backend in _BACKENDS.items()
        if name in cleanup.TARGETS
    }
    DBMS_LABELS = {
        name: backend.label
        for name, backend in _BACKENDS.items()
        if name in cleanup.TARGETS
    }
    _SUPPORTED_DBMS_ERR = f"dbms must be one of {', '.join(sorted(SCHEMA_PREFIXES))}"
else:
    SCHEMA_PREFIXES = {"mysql": "mysql971abl", "mariadb": "mariadb1232abl"}
    DBMS_LABELS = {"mysql": "MySQL", "mariadb": "MariaDB"}
    _SUPPORTED_DBMS_ERR = "dbms must be mysql or mariadb"
DEFAULTS = {
    "dbms": "mysql", "data_profiles": ["full"], "duration_seconds": 3600,
    "output_root": "evaluation/data_ablation", "connection_config": "configs/puffer-case-investigator-v1.toml",
    "connection_profile": "mysql-local",
    "java": {"query_generator_mode": "mixed", "workers": 8, "num_tries": 8, "num_queries": 64, "repeat_count": 5,
             "insert_batch_size": 25, "query_timeout_seconds": 5,
             "per_run_timeout_seconds": 600, "explain_analyze": True, "qpg": False,
             "async_report_writes": False,
             "counterfactual_hint_probability": 0.5, "predicate_binding_probability": 0.5,
             "query_rewrite_provider": "wetune-static", "allow_bka_hint": False,
             "wetune_static_worker": "tools/wetune-static-worker/build/install/wetune-static-worker/bin/wetune-static-worker", "wetune_static_timeout_ms": 250},
    "generator": {"min_tables": 2, "max_tables": 4, "min_rows_per_table": 100, "max_rows_per_table": 1000,
                  "initial_scale": 1, "min_columns": 3, "max_columns": 6, "max_indexes_per_table": 3,
                  "single_column_probability": 0.8, "intra_table_probability": 0.6,
                  "inter_table_probability": 0.8, "histogram_refresh": True, "histogram_columns": 2},
    "oracle": {"harmful_runtime_ratio": 3.0, "cost_anomaly_ratio": 10.0,
               "runtime_ratio_lower": 0.9, "runtime_ratio_upper": 1.3,
               "cost_ratio_lower": 0.9, "cost_ratio_upper": 1.1,
               "min_runtime_ms": 0.2, "min_gap_ms": 0.5},
    "validation": {"baseline_min_speedup": 2.0, "baseline_min_gap_ms": 0.5, "scaled_min_gap_ms": 5.0,
                   "min_speedup": 3.0,
                   "allow_result_mismatch": False, "scaled_timeout_pass": True,
                   "scaled_timeout_early_stop": True,
                   "cluster_similarity": 0.7, "scale": 10, "warmups": 1, "rounds": 5,
                   "query_timeout_seconds": 20, "min_win_fraction": 0.8,
                   "amplification_gap_multiplier": 2.0, "max_table_rows": 300000,
                   "max_result_rows": 500000, "max_result_bytes": 2147483648},
}
ENV_FIELDS = {
    "java": {"query_generator_mode": "PERF_QUERY_GENERATOR_MODE", "workers": "WORKERS", "num_tries": "TRIES",
             "num_queries": "NUM_QUERIES", "repeat_count": "REPEAT_COUNT",
             "insert_batch_size": "PERF_INSERT_BATCH_SIZE",
             "query_timeout_seconds": "QUERY_TIMEOUT_SECONDS", "per_run_timeout_seconds": "PER_RUN_TIMEOUT",
             "explain_analyze": "USE_EXPLAIN_ANALYZE", "qpg": "QPG_ENABLE",
             "async_report_writes": "PERF_ASYNC_REPORT_WRITES",
             "counterfactual_hint_probability": "PERF_COUNTERFACTUAL_HINT_PROBABILITY",
             "predicate_binding_probability": "PERF_PREDICATE_BINDING_PROBABILITY",
             "query_rewrite_provider": "PERF_QUERY_REWRITE_PROVIDER", "allow_bka_hint": "PERF_ALLOW_BKA_HINT",
             "wetune_static_worker": "PERF_WETUNE_STATIC_WORKER", "wetune_static_timeout_ms": "PERF_WETUNE_STATIC_TIMEOUT_MS"},
    "generator": {"min_tables": "MIN_TABLES", "max_tables": "MAX_TABLES",
                  "min_rows_per_table": "MIN_ROWS_PER_TABLE", "max_rows_per_table": "MAX_ROWS_PER_TABLE",
                  "initial_scale": "PERF_INITIAL_SCALE",
                  "min_columns": "MIN_COLUMNS", "max_columns": "MAX_COLUMNS",
                  "max_indexes_per_table": "MAX_INDEXES_PER_TABLE",
                  "single_column_probability": "SINGLE_COLUMN_MODEL_PROBABILITY",
                  "intra_table_probability": "MULTI_COLUMN_MODEL_PROBABILITY",
                  "inter_table_probability": "INTER_TABLE_MODEL_PROBABILITY",
                  "histogram_refresh": "ENABLE_HISTOGRAM_REFRESH", "histogram_columns": "MAX_HISTOGRAM_COLUMNS_PER_TABLE"},
    "oracle": {"harmful_runtime_ratio": "PERF_REPORT_HARMFUL_RUNTIME_RATIO",
               "cost_anomaly_ratio": "PERF_REPORT_COST_ANOMALY_RATIO",
               "runtime_ratio_lower": "PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND",
               "runtime_ratio_upper": "PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND",
               "cost_ratio_lower": "PERF_TAXONOMY_COST_RATIO_LOWER_BOUND",
               "cost_ratio_upper": "PERF_TAXONOMY_COST_RATIO_UPPER_BOUND",
               "min_runtime_ms": "PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS",
               "min_gap_ms": "PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS"},
}


def absolute(path: str | Path) -> Path:
    path = Path(path)
    return path.resolve() if path.is_absolute() else (REPO_ROOT / path).resolve()


def load_config(path: Path) -> dict[str, Any]:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(raw, dict) or set(raw) - set(DEFAULTS):
        raise ValidationInputError("unknown experiment configuration fields")
    config = {}
    for name, default in DEFAULTS.items():
        value = raw.get(name, {}) if isinstance(default, dict) else raw.get(name, default)
        if isinstance(default, dict):
            if not isinstance(value, dict):
                raise ValidationInputError(f"unknown {name} settings")
            value = dict(value)
            if name == "validation" and "scale_factor" in value:
                if "scale" in value and value["scale"] != value["scale_factor"]:
                    raise ValidationInputError("validation.scale and validation.scale_factor conflict")
                value["scale"] = value.pop("scale_factor")
            if set(value) - set(default):
                raise ValidationInputError(f"unknown {name} settings")
            value = {**default, **value}
        config[name] = value
    if "connection_profile" not in raw:
        config["connection_profile"] = f"{config['dbms']}-local"
    raw_java = raw.get("java") or {}
    if "num_tries" not in raw_java:
        config["java"]["num_tries"] = config["java"]["workers"]
    raw_validation = raw.get("validation") or {}
    if "min_speedup" not in raw_validation:
        config["validation"]["min_speedup"] = config["oracle"]["harmful_runtime_ratio"]
    validate_config(config)
    config["connection_config"] = str(absolute(config["connection_config"]))
    return config


def validate_config(config: dict[str, Any]) -> None:
    if config["dbms"] not in SCHEMA_PREFIXES:
        raise ValidationInputError(_SUPPORTED_DBMS_ERR)
    if config["java"]["query_generator_mode"] not in ("structured", "expanded", "mixed"):
        raise ValidationInputError("query_generator_mode must be structured, expanded, or mixed")
    profiles = config["data_profiles"]
    if not isinstance(profiles, list) or not profiles or any(item not in PROFILES for item in profiles) or len(set(profiles)) != len(profiles):
        raise ValidationInputError(f"data_profiles must be distinct members of {PROFILES}")
    for section, defaults in DEFAULTS.items():
        if not isinstance(defaults, dict):
            continue
        for field, default in defaults.items():
            value = config[section][field]
            valid_type = type(value) in (int, float) if type(default) is float else type(value) is type(default)
            if not valid_type:
                raise ValidationInputError(f"invalid type for {section}.{field}")
    for name, value in [("duration_seconds", config["duration_seconds"]), *config["java"].items(),
                        *config["generator"].items(), *config["oracle"].items(), *config["validation"].items()]:
        if isinstance(value, (int, float)) and (not float("-inf") < value < float("inf") or value < 0):
            raise ValidationInputError(f"invalid nonnegative setting {name}")
    for name, value in [("duration_seconds", config["duration_seconds"]),
                        ("initial_scale", config["generator"]["initial_scale"]),
                        *[(key, config["java"][key]) for key in (
                            "workers", "num_tries", "num_queries", "repeat_count", "insert_batch_size",
                            "query_timeout_seconds", "per_run_timeout_seconds")],
                        *[(key, config["validation"][key]) for key in ("rounds", "query_timeout_seconds", "max_table_rows",
                                                                     "max_result_rows", "max_result_bytes")]]:
        if type(value) is not int or value < 1:
            raise ValidationInputError(f"{name} must be a positive integer")
    generator, oracle, validation = (config[name] for name in ("generator", "oracle", "validation"))
    for name in ("tables", "rows_per_table", "columns"):
        if not 1 <= generator[f"min_{name}"] <= generator[f"max_{name}"]:
            raise ValidationInputError(f"invalid generator {name} bounds")
    for name in ("single_column_probability", "intra_table_probability", "inter_table_probability"):
        if not 0 <= generator[name] <= 1:
            raise ValidationInputError(f"{name} must be in [0,1]")
    if not 3 <= generator["min_columns"] <= generator["max_columns"] <= 8 or not 1 <= generator["max_indexes_per_table"] <= 8:
        raise ValidationInputError("model-v1 requires 3..8 columns and 1..8 maximum indexes")
    if not 0 <= config["java"]["counterfactual_hint_probability"] <= 1:
        raise ValidationInputError("counterfactual_hint_probability must be in [0,1]")
    if not 0 <= config["java"]["predicate_binding_probability"] <= 1:
        raise ValidationInputError("predicate_binding_probability must be in [0,1]")
    if not 1 <= config["java"]["insert_batch_size"] <= 2000:
        raise ValidationInputError("insert_batch_size must be in [1,2000]")
    if oracle["harmful_runtime_ratio"] <= 1 or oracle["cost_anomaly_ratio"] <= 1:
        raise ValidationInputError("report ratios must exceed 1")
    if validation["min_speedup"] <= 1:
        raise ValidationInputError("validation.min_speedup must exceed 1")
    if not (0 < oracle["runtime_ratio_lower"] <= 1 <= oracle["runtime_ratio_upper"]
            and 0 < oracle["cost_ratio_lower"] <= 1 <= oracle["cost_ratio_upper"]):
        raise ValidationInputError("classification intervals must include 1")
    if validation["scale"] < 1 or not 0 <= validation["cluster_similarity"] <= 1 or not 0 < validation["min_win_fraction"] <= 1:
        raise ValidationInputError("invalid validation scale/similarity/win fraction")
    if validation["amplification_gap_multiplier"] < 1:
        raise ValidationInputError("amplification_gap_multiplier must be at least 1")


def connection_target(config: dict[str, Any]) -> cleanup.Target:
    from puffer_case_investigator.config import load_settings
    from puffer_case_investigator.db import DatabaseProfile
    settings = load_settings(REPO_ROOT, absolute(config["connection_config"]))
    profile = DatabaseProfile.from_settings(settings.profile(config["connection_profile"]))
    if profile.dbms != config["dbms"]:
        raise ValidationInputError(f"connection_profile must refer to {config['dbms']}")
    return replace(cleanup.TARGETS[config["dbms"]], host=profile.host, port=profile.port, user=profile.user,
                   password_env=profile.password_env, default_password=profile.resolved_password(),
                   expected_version_prefix=profile.expected_version_prefix)


def run_command(command: list[str], log: Path, environment: dict[str, str] | None = None) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"Running {Path(command[0]).name}; log: {log}", flush=True)
    with log.open("a", encoding="utf-8") as handle:
        process = subprocess.Popen(command, cwd=REPO_ROOT, stdout=handle, stderr=subprocess.STDOUT,
                                   env=environment, start_new_session=True)
        try:
            code = process.wait()
        except KeyboardInterrupt:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise
    if code in (130, -signal.SIGINT, -signal.SIGTERM):
        raise KeyboardInterrupt
    if code:
        raise ValidationInputError(f"command exited {code}; inspect {log}")


def online_environment(config: dict[str, Any], arm: Path, meta: dict[str, Any], target: cleanup.Target) -> dict[str, str]:
    # Avoid inherited campaign knobs silently changing one experimental arm.
    controlled = set(ENV_FIELDS[section][field] for section in ENV_FIELDS for field in ENV_FIELDS[section])
    env = {key: value for key, value in os.environ.items() if key not in controlled and not key.startswith((
        "PERF_", "QPG_", "SQLANCER_"))}
    for section, fields in ENV_FIELDS.items():
        for key, variable in fields.items():
            value = config[section][key]
            env[variable] = str(int(value)) if isinstance(value, bool) else str(value)
    env.update({"DBMS": config["dbms"], "HOST": target.host, "PORT": str(target.port), "USERNAME": target.user,
                "PASSWORD": target.default_password, "DB_PREFIX_BASE": meta["schema_prefix"],
                "START_SEED": str(meta["seed"]), "DURATION_SECONDS": str(config["duration_seconds"]),
                "MAX_SEED_SECONDS": "0", "MAX_CAMPAIGN_DURATION_SECONDS": str(max(604800, config["duration_seconds"])),
                "DATA_GENERATOR_MODE": "model-v1",
                "DATA_ABLATION_PROFILE": meta["data_profile"], "PERF_TAXONOMY_ENABLE": "1",
                "PERF_TAXONOMY_REPORT_CELLS": ",".join(CELLS), "PERF_CONTINUE_AFTER_FORMAL_WITNESS": "1",
                "PERF_HINT_EXCLUDE_TYPES": (
                    ("NO_BNL" if config["java"].get("allow_bka_hint") else "NO_BNL,BKA")
                    if config["dbms"] == "mysql" else ""
                ),
                "COST_THRESHOLD": str(config["oracle"]["cost_anomaly_ratio"]),
                "RUNTIME_THRESHOLD": str(config["oracle"]["harmful_runtime_ratio"]), "SUMMARY_INTERVAL_SECONDS": "300",
                "FEEDBACK_STATE_PATH": str(arm / "online/feedback"), "SQLANCER_BUG_REPORT_DIR": str(arm / "cases"),
                "SQLANCER_LOG_DIR": str(arm / "online/sqlancer_logs"), "LOG_DIR": str(arm / "online/campaign_logs"),
                "MASTER_LOG": str(arm / "online/campaign_logs/master.log"),
                "PID_FILE": str(arm / "online/campaign_logs/campaign.pid"),
                "SUMMARY_TXT": str(arm / "online/campaign_logs/summary.txt"),
                "SUMMARY_JSON": str(arm / "online/campaign_logs/summary.json"),
                "SCHEMA_MANIFEST_PATH": str(arm / "run_schemas.tsv")})
    return env


def clean_arm(arm: Path, meta: dict[str, Any], target: cleanup.Target) -> dict[str, Any]:
    names_file = arm / "run_schemas.tsv"
    if not names_file.is_file():
        raise ValidationInputError(f"missing run schema allowlist: {names_file}; refusing broad cleanup")
    names_by_target = cleanup.read_names_file(names_file)
    if any(names for dbms, names in names_by_target.items() if dbms != target.name):
        raise ValidationInputError("run schema allowlist contains entries for another DBMS")
    names = names_by_target[target.name]
    if any(not name.startswith(meta["schema_prefix"]) for name in names):
        raise ValidationInputError("run schema allowlist contains a name outside this run's prefix")
    cleanup.verify_target(target)
    inventory = cleanup.inventory_from_catalog(target)
    selected = cleanup.select_candidates(target, inventory, "names-only", [meta["schema_prefix"]], set(), names)
    # Persist exact resolved targets before any destructive command.
    atomic_json(arm / "cleanup.json", {"state": "RUNNING", "selected": selected, "allowlist": str(names_file)})
    _, errors = cleanup.drop_candidates(target, selected, 200)
    remaining = sorted(set(selected) & set(cleanup.inventory_from_catalog(target))) if selected else []
    report = {"state": "FAILED" if errors or remaining else "COMPLETE", "allowlist": str(names_file),
              "selected": selected, "dropped_count": len(selected) - len(remaining), "remaining": remaining, "errors": errors}
    atomic_json(arm / "cleanup.json", report)
    if errors or remaining:
        raise ValidationInputError(f"run-scoped cleanup incomplete; inspect {arm / 'cleanup.json'}")
    return report


def offline(config: dict[str, Any], arm: Path, meta: dict[str, Any]) -> None:
    cells = ",".join(CELLS)
    for stage, script in (("l1", "01_deduplicate.py"), ("l2", "02_cluster.py")):
        old = Path(meta[stage]) if stage in meta else None
        if old is not None and (old / "summary.json").is_file():
            continue
        out = arm / "offline" / f"{stage}-{secrets.token_hex(3)}"
        meta[stage] = str(out)
        atomic_json(arm / "state.json", meta)
        inputs = (["--input-root", str(arm / "cases")] if stage == "l1" else [
            "--input-ir", str(Path(meta["l1"]) / "case_deltas.jsonl"),
            "--threshold", str(config["validation"]["cluster_similarity"])])
        run_command([sys.executable, str(REPO_ROOT / "scripts/pdg_validation" / script),
                     "--dbms", config["dbms"], "--cells", cells, "--output-dir", str(out), *inputs], arm / f"{stage}.log")
    out = Path(meta["l3"]) if meta.get("l3") else arm / "offline/l3"
    meta["l3"] = str(out)
    atomic_json(arm / "state.json", meta)
    validation, oracle = config["validation"], config["oracle"]
    options = {"scale": validation["scale"], "warmups": validation["warmups"], "rounds": validation["rounds"],
               "query-timeout-s": validation["query_timeout_seconds"], "min-win-fraction": validation["min_win_fraction"],
               "amplification-gap-multiplier": validation["amplification_gap_multiplier"],
               "baseline-min-speedup": validation.get("baseline_min_speedup", 2.0),
               "min-speedup": validation.get("min_speedup", oracle["harmful_runtime_ratio"]),
               "baseline-min-gap-ms": validation.get("baseline_min_gap_ms", oracle["min_gap_ms"]),
               "scaled-min-gap-ms": validation.get("scaled_min_gap_ms", 5.0),
               "min-runtime-ms": oracle["min_runtime_ms"], "cost-anomaly-ratio": oracle["cost_anomaly_ratio"],
               "runtime-similar-lower": oracle["runtime_ratio_lower"],
               "runtime-similar-upper": oracle["runtime_ratio_upper"],
               "max-table-rows": validation["max_table_rows"], "max-result-rows": validation["max_result_rows"],
               "max-result-bytes": validation["max_result_bytes"], "seed": meta["seed"]}
    command = [sys.executable, str(REPO_ROOT / "scripts/pdg_validation/03_scale_validate.py"),
               "--dbms", config["dbms"], "--cells", cells, "--config", config["connection_config"],
               "--profile", config["connection_profile"], "--output-dir", str(out),
               "--representatives", str(Path(meta["l2"]) / "family_representatives.tsv")]
    if validation.get("allow_result_mismatch", False):
        command.append("--allow-result-mismatch")
    if validation.get("scaled_timeout_pass", True):
        command.append("--scaled-timeout-pass")
    else:
        command.append("--no-scaled-timeout-pass")
    if validation.get("scaled_timeout_early_stop", True):
        command.append("--scaled-timeout-early-stop")
    else:
        command.append("--no-scaled-timeout-early-stop")
    for name, value in options.items():
        command.extend([f"--{name}", str(value)])
    if (out / "manifest.json").exists():
        command.append("--resume")
    run_command(command, arm / "l3.log")
    if read_json(out / "summary.json").get("cleanup_errors"):
        raise ValidationInputError(f"L3 sandbox cleanup needs attention; inspect {out / 'summary.json'}")


def report(run_dir: Path, config: dict[str, Any]) -> None:
    rows, details = [], []
    for profile in config["data_profiles"]:
        arm = run_dir / profile
        meta = read_json(arm / "state.json") if (arm / "state.json").is_file() else {}
        counts = {cell: 0 for cell in CELLS}
        for cell, _ in discover_cases(arm / "cases", CELLS):
            counts[cell] += 1
        summaries = {}
        for stage in ("l1", "l2", "l3"):
            path = Path(meta[stage]) / "summary.json" if stage in meta else None
            summaries[stage] = read_json(path) if path and path.is_file() else {}
        l1, l2, l3 = (summaries[stage] for stage in ("l1", "l2", "l3"))
        online_summary_path = arm / "online/campaign_logs/summary.json"
        online_summary = read_json(online_summary_path) if online_summary_path.is_file() else {}
        telemetry = online_summary.get("telemetry", {})
        minutes = meta.get("online_elapsed_seconds", 0) / 60
        amp_direct = l3.get("amplified_direct", l3.get("status_counts", {}).get("AMPLIFIED", 0))
        amp_rescued = l3.get("amplified_rescued", l3.get("status_counts", {}).get("ACTIVATED", 0))
        sus_count = l3.get("sustained", l3.get("status_counts", {}).get("SUSTAINED", 0))
        validated_ppi = l3.get("validated_ppi_count", amp_direct + amp_rescued + sus_count)
        selected_count = l3.get("selected_count", 0)
        row = {"data_profile": profile, "state": meta.get("state", "NOT_STARTED"), "seed": meta.get("seed"),
               **counts, "L0_candidates": sum(counts.values()), "online_minutes": minutes,
               "online_query_checks": telemetry.get("queries", 0),
               "runtime_measured_pairs": telemetry.get("runtimeMeasured", 0),
               "candidates_per_minute": sum(counts.values()) / minutes if minutes else 0,
               "L1_representatives": l1.get("representative_count", 0),
               "L1_unsupported": l1.get("unsupported_count", 0),
               "L2_without_L1_input": l2.get("all_records_if_no_l1", 0),
               "L2_actual_input": l2.get("clustered_records", 0), "L2_families": l2.get("family_count", 0),
               "L3_completed": l3.get("completed_count", 0), "L3_pending": l3.get("pending_count", l2.get("family_count", 0)),
               **{status: l3.get("status_counts", {}).get(status, 0) for status in (
                   "AMPLIFIED", "SUSTAINED", "ACTIVATED", "COST_ANOMALY", "DISAPPEARED", "FAILED")},
               "amplified_direct": amp_direct,
               "amplified_rescued": amp_rescued,
               "sustained": sus_count,
               "amplified_total": amp_direct + amp_rescued,
               "validated_ppi_count": validated_ppi,
               "validated_performance_candidates": l3.get("validated_performance_candidates", 0),
               "classified_performance_candidates": l3.get("classified_performance_candidates",
                                                            l3.get("validated_performance_candidates", 0)),
               "unverified_timeout_performance_candidates": l3.get("unverified_timeout_performance_candidates", 0),
               "timeout_case_count": l3.get("timeout_case_count", 0),
               "manual_review_timeout_count": l3.get("manual_review_timeout_count", 0),
               "passed_without_equivalence_count": l3.get("passed_without_equivalence_count", 0),
               "cost_anomaly_only": l3.get("cost_anomaly_only_count", 0)}
        row["validation_pass_rate"] = validated_ppi / selected_count if selected_count else 0.0
        row["ppi_yield"] = validated_ppi / minutes if minutes else 0.0
        row["validated_fraction_of_L3_representatives"] = (
            row["validated_performance_candidates"] / selected_count if selected_count else 0)
        row["validated_per_online_minute"] = row["validated_performance_candidates"] / minutes if minutes else 0
        rows.append(row)
        details.append({"data_profile": profile, "online": meta, "online_telemetry": telemetry,
                        "stages": summaries,
                        "stage1_to_stage2_matrix": l3.get("stage1_to_stage2_matrix", {}),
                        "stage1_to_stage2_summary": l3.get("stage1_to_stage2_summary", {}),
                        "counts": row})
    atomic_json(run_dir / "summary.json", {"dbms": config["dbms"], "arms": details})
    write_tsv(run_dir / "summary.tsv", rows, list(rows[0]))
    lines = [f"# {DBMS_LABELS[config['dbms']]} data-generation ablation", "",
             "Counts are candidate artifacts / representative families, not confirmed unique community bugs.", "",
             "| Profile | State | A/B/C/D/G | L0 → L1 → L2 → Validated PPI (AMP/ACT/SUS) | Pass rate | candidates/min | L3 pending |",
             "|---|---|---|---|---|---|---|"]
    for row in rows:
        ppi_breakdown = f"{row['validated_ppi_count']} ({row['amplified_direct']}/{row['amplified_rescued']}/{row['sustained']})"
        lines.append(f"| {row['data_profile']} | {row['state']} | {'/'.join(str(row[c]) for c in CELLS)} | "
                     f"{row['L0_candidates']} → {row['L1_representatives']} → {row['L2_families']} → "
                     f"{ppi_breakdown} | {row['validation_pass_rate'] * 100:.1f}% | {row['candidates_per_minute']:.3f} | {row['L3_pending']} |")
    lines += ["", "Validated PPI = AMPLIFIED (AMP) + ACTIVATED (ACT, B/C rescued) + SUSTAINED (SUS); COST_ANOMALY is reported separately.",
              "Performance passes follow the persisted validation policy; result mismatches are retained and timeout passes require manual review.",
              "Ratios use all selected L3 representatives, including failed/pending cases; partial runs are not final results.",
              "Online elapsed time includes campaign shutdown; the configured budget excludes offline validation.", ""]
    for row in rows:
        if row["timeout_case_count"]:
            lines.append(f"{row['data_profile']}: timeout cases={row['timeout_case_count']}; "
                         f"unverified timeout performance classifications={row['unverified_timeout_performance_candidates']}.")
    (run_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--data-profile", choices=PROFILES, nargs="+",
                        help="Run these profiles serially, completing Java and L1/L2/L3 for each in order")
    parser.add_argument("--duration-seconds", type=int)
    parser.add_argument("--initial-scale", type=int,
                        help="Override generator.initial_scale (s_0; e.g., 10 for RQ4c Large-only mode)")
    parser.add_argument("--scale-factor", "--scale", dest="scale_factor", type=int,
                        help="Override validation.scale (e.g., 1 for Large-only same-scale L3 validation without re-scaling)")
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--offline-only", action="store_true", help="Reuse saved settings and resume offline stages only")
    parser.add_argument("--dry-run", action="store_true", help="Write a run plan, without Java, database access or cleanup")
    args = parser.parse_args(argv)
    if args.offline_only and (not args.run_dir or args.config or args.data_profile or args.duration_seconds
                              or args.initial_scale is not None or args.scale_factor is not None or args.dry_run):
        parser.error("--offline-only requires --run-dir and reuses its saved configuration without overrides")
    if not args.offline_only and not args.config:
        parser.error("--config is required for a new experiment")
    previous_handler = signal.signal(signal.SIGTERM, handle_interrupt)
    try:
        config = read_json(args.run_dir.resolve() / "effective_config.json") if args.offline_only else load_config(args.config)
        if args.data_profile:
            config["data_profiles"] = args.data_profile
        if args.duration_seconds is not None:
            config["duration_seconds"] = args.duration_seconds
        if args.initial_scale is not None:
            config["generator"]["initial_scale"] = args.initial_scale
            if args.initial_scale > 1 and args.scale_factor is None:
                # Prevent accidental double-scaling (e.g. s_0=10 -> 100x) in Large-only mode.
                config["validation"]["scale"] = 1
        if args.scale_factor is not None:
            config["validation"]["scale"] = args.scale_factor
        validate_config(config)
        run_dir = args.run_dir.resolve() if args.run_dir else absolute(config["output_root"]) / (
            datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(3))
        if not args.offline_only:
            require_output_dir(run_dir)
            atomic_json(run_dir / "effective_config.json", config)
            revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True).strip()
            atomic_json(run_dir / "provenance.json", {"git_head": revision,
                "git_dirty": bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=REPO_ROOT)),
                "jar_sha256": hashlib.sha256((REPO_ROOT / "target/sqlancer-2.0.0.jar").read_bytes()).hexdigest()
                    if (REPO_ROOT / "target/sqlancer-2.0.0.jar").is_file() else None,
                "connection_config_sha256": hashlib.sha256(absolute(config["connection_config"]).read_bytes()).hexdigest()})
            for profile in config["data_profiles"]:
                arm = run_dir / profile
                (arm / "cases").mkdir(parents=True)
                meta = {"data_profile": profile, "seed": secrets.randbelow(2 ** 31), "state": "PLANNED",
                        "schema_prefix": SCHEMA_PREFIXES[config["dbms"]] + secrets.token_hex(6)}
                atomic_json(arm / "state.json", meta)
        elif read_json(run_dir / "provenance.json")["connection_config_sha256"] != hashlib.sha256(
                absolute(config["connection_config"]).read_bytes()).hexdigest():
            raise ValidationInputError("connection configuration changed; restore the run's original profile")
        report(run_dir, config)
        print(f"Experiment directory: {run_dir}", flush=True)
        if args.dry_run:
            return 0
        target = connection_target(config)
        cleanup.verify_target(target)
        for profile in config["data_profiles"]:
            arm = run_dir / profile
            meta = read_json(arm / "state.json")
            if args.offline_only and meta["state"] == "PLANNED":
                continue
            try:
                if not args.offline_only:
                    jar = REPO_ROOT / "target/sqlancer-2.0.0.jar"
                    if not jar.is_file() or any(path.stat().st_mtime > jar.stat().st_mtime for path in (REPO_ROOT / "src").rglob("*.java")):
                        raise ValidationInputError("build current Java sources first: mvn -DskipTests package")
                    meta["state"] = "ONLINE_RUNNING"
                    atomic_json(arm / "state.json", meta)
                    (arm / "run_schemas.tsv").touch()
                    started = time.monotonic()
                    try:
                        run_command(["bash", str(REPO_ROOT / "scripts" / "run-perfhint-threshold-campaign.sh"), "--foreground"],
                                    arm / "online.log", online_environment(config, arm, meta, target))
                        meta["online_finished"] = True
                    finally:
                        meta["online_elapsed_seconds"] = time.monotonic() - started
                        atomic_json(arm / "state.json", meta)
                meta["state"] = "CLEANUP"
                atomic_json(arm / "state.json", meta)
                clean_arm(arm, meta, target)
                meta["state"] = "OFFLINE_RUNNING"
                atomic_json(arm / "state.json", meta)
                started = time.monotonic()
                try:
                    offline(config, arm, meta)
                finally:
                    meta["offline_elapsed_seconds"] = meta.get("offline_elapsed_seconds", 0) + time.monotonic() - started
                meta["state"] = "COMPLETE" if meta.get("online_finished") else "COMPLETE_PARTIAL_ONLINE"
            except KeyboardInterrupt:
                meta["state"] = "INTERRUPTED"
                raise
            except Exception:
                meta["state"] = "ERROR"
                raise
            finally:
                atomic_json(arm / "state.json", meta)
                report(run_dir, config)
        return 0
    except KeyboardInterrupt:
        print("Interrupted; completed validation cases remain saved. Resume with --offline-only --run-dir.", file=sys.stderr)
        return 130
    except (OSError, ValueError, RuntimeError, KeyError, yaml.YAMLError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    finally:
        signal.signal(signal.SIGTERM, previous_handler)


def handle_interrupt(*unused: Any) -> None:
    raise KeyboardInterrupt


if __name__ == "__main__":
    raise SystemExit(main())
