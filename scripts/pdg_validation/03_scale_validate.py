#!/usr/bin/env python3
"""Stage L3: clean 1x and PDG-preserving 10x validation across MySQL, MariaDB, TiDB, CockroachDB, and OceanBase."""

from __future__ import annotations

import argparse
from copy import copy
import csv
import hashlib
import json
import math
import os
import secrets
import signal
import statistics
import sys
import time
from collections import Counter
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Mapping, Sequence

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

try:
    from puffer_case_investigator.backends import BACKENDS
    DBMS_CHOICES = tuple(BACKENDS)
except ImportError:
    DBMS_CHOICES = ("mysql", "mariadb", "tidb", "oceanbase", "cockroachdb")

from scripts.pdg_validation._common import (ValidationInputError, atomic_json, parse_cells, read_json, require_output_dir,
                                            write_json, write_tsv)
from scripts.pdg_validation._pdg import PdgExtender, extend_database, extract_pdg_primitives, load_pdg
from scripts.pdg_validation._plan_delta import analyze_case


STATUS_VALUES = ("AMPLIFIED", "SUSTAINED", "ACTIVATED", "COST_ANOMALY", "DISAPPEARED", "FAILED")
DEFAULT_BASELINE_MIN_GAP_MS = 0.5
DEFAULT_SCALED_MIN_GAP_MS = 5.0
DEFAULT_QUERY_TIMEOUT_S = 20
DEFAULT_MAX_RESULT_ROWS = 500_000


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--representatives", type=Path, help="family_representatives.tsv from Stage L2")
    parser.add_argument("--dbms", choices=DBMS_CHOICES)
    parser.add_argument("--cells", default="A,B,C,D,G")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--config", type=Path, default=REPO_ROOT / "configs/puffer-case-investigator-v1.toml")
    parser.add_argument("--profile")
    parser.add_argument("--scale", "--scale-factor", dest="scale", type=int, default=10,
                        help="PDG scaling factor for L3 (default: 10; set 1 for Large-only same-scale validation without re-scaling)")
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--query-timeout-s", type=int, default=DEFAULT_QUERY_TIMEOUT_S)
    parser.add_argument("--timeout-as-limit", action="store_true",
                        help="Classify server timeouts using censored runtime bounds; list affected cases separately")
    parser.add_argument("--scaled-timeout-early-stop", action=argparse.BooleanOptionalAction, default=True,
                        help="Stop scaled rounds early once a timeout + decisive fast counterfactual or both-side timeout is confirmed")
    parser.add_argument("--scaled-explain-analyze", action=argparse.BooleanOptionalAction, default=False,
                        help="Collect EXPLAIN ANALYZE at 10x (disabled by default to avoid redundant slow/timeout executions)")
    parser.add_argument("--baseline-min-speedup", type=float, default=2.0,
                        help="1x admission threshold, independent of the scaled performance threshold")
    parser.add_argument("--baseline-min-gap-ms", type=float, default=None,
                        help=(f"Minimum 1x runtime gap in ms (default: {DEFAULT_BASELINE_MIN_GAP_MS}); "
                              "applied only at 1x and never filters Stage I Class B/C candidates from 10x scaling"))
    parser.add_argument("--allow-result-mismatch", action="store_true", default=False,
                        help="Continue timing unequal results; preserve the comparison evidence")
    parser.add_argument("--scaled-timeout-pass", action=argparse.BooleanOptionalAction, default=True,
                        help="Count a scaled default-plan timeout as a pass requiring manual review (default: enabled)")
    parser.add_argument("--min-speedup", type=float, default=3.0)
    parser.add_argument("--scaled-min-gap-ms", type=float, default=None,
                        help=(f"Minimum 10x scaled runtime gap in ms for AMPLIFIED/ACTIVATED/SUSTAINED "
                              f"(default: {DEFAULT_SCALED_MIN_GAP_MS}, aligned with Paper Algorithm 4)"))
    parser.add_argument("--min-gap-ms", type=float, default=None,
                        help=("Legacy shared runtime gap override in ms; when provided explicitly, sets any omitted "
                              "--baseline-min-gap-ms and --scaled-min-gap-ms to this value"))
    parser.add_argument("--min-runtime-ms", type=float, default=0.2)
    parser.add_argument("--min-win-fraction", type=float, default=0.80)
    parser.add_argument("--cost-anomaly-ratio", type=float, default=10.0)
    parser.add_argument("--runtime-similar-lower", type=float, default=0.9)
    parser.add_argument("--runtime-similar-upper", type=float, default=1.3)
    parser.add_argument("--amplification-gap-multiplier", type=float, default=2.0)
    parser.add_argument("--max-result-rows", type=int, default=DEFAULT_MAX_RESULT_ROWS)
    parser.add_argument("--max-result-bytes", type=int, default=2 * 1024 * 1024 * 1024)
    parser.add_argument("--max-table-rows", type=int, default=300_000)
    parser.add_argument("--seed", type=int, help="Randomly generated and persisted when omitted")
    parser.add_argument("--resume", action="store_true", help="Reuse completed per-case results in this output directory")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--prioritize-historical-gap", action="store_true",
                        help="Process largest persisted absolute-gap representatives first (useful for bounded smoke tests).")
    parser.add_argument("--resummarize", action="store_true",
                        help="Rebuild summary.json, results.tsv, and report.md from persisted manifest and case results.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.min_gap_ms is not None:
        if args.baseline_min_gap_ms is None:
            args.baseline_min_gap_ms = args.min_gap_ms
        if args.scaled_min_gap_ms is None:
            args.scaled_min_gap_ms = args.min_gap_ms
    else:
        if args.baseline_min_gap_ms is None:
            args.baseline_min_gap_ms = DEFAULT_BASELINE_MIN_GAP_MS
        if args.scaled_min_gap_ms is None:
            args.scaled_min_gap_ms = DEFAULT_SCALED_MIN_GAP_MS
        args.min_gap_ms = args.scaled_min_gap_ms
    if not args.self_test and not args.resummarize and (
            args.representatives is None or args.dbms is None or args.output_dir is None):
        parser.error("--representatives, --dbms and --output-dir are required")
    if args.resummarize and args.output_dir is None:
        parser.error("--output-dir is required with --resummarize")
    try:
        args.cells = parse_cells(args.cells)
    except ValidationInputError as error:
        parser.error(str(error))
    args.profile = args.profile or f"{args.dbms}-local" if args.dbms else args.profile
    if args.scale < 1 or args.rounds < 1 or args.warmups < 0 or args.query_timeout_s < 1:
        parser.error("invalid scale or measurement counts")
    if (args.baseline_min_speedup <= 1 or args.min_speedup <= 1 or args.min_gap_ms < 0
            or args.baseline_min_gap_ms < 0 or args.scaled_min_gap_ms < 0
            or args.min_runtime_ms < 0 or not 0 < args.min_win_fraction <= 1):
        parser.error("invalid runtime-oracle thresholds")
    if args.cost_anomaly_ratio <= 1 or args.amplification_gap_multiplier < 1:
        parser.error("cost anomaly and amplification multipliers must be >1 and >=1 respectively")
    if not 0 < args.runtime_similar_lower <= 1 <= args.runtime_similar_upper:
        parser.error("runtime similarity interval must include 1")
    return args


def read_representatives(path: Path, dbms: str, cells: Sequence[str], limit: int,
                         prioritize_historical_gap: bool = False) -> list[dict[str, str]]:
    try:
        with path.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
    except OSError as error:
        raise ValidationInputError(f"cannot read representatives {path}: {error}") from error
    selected = [row for row in rows if row.get("dbms") == dbms and row.get("cell") in cells]
    seen = set()
    for row in selected:
        if not row.get("family_id") and row.get("group_id"):
            row["family_id"] = row["group_id"]
        if not row.get("case_path") or not row.get("case_id") or not row.get("family_id"):
            raise ValidationInputError("representative row lacks family_id/case_id/case_path")
        key = (row["cell"], row["family_id"])
        if key in seen or Path(row["family_id"]).name != row["family_id"] or row["family_id"] in (".", ".."):
            raise ValidationInputError("duplicate or unsafe representative family_id")
        seen.add(key)
    if prioritize_historical_gap:
        selected.sort(key=lambda row: (-float(row.get("absolute_runtime_gap_ms") or -1), row["case_id"]))
    else:
        selected.sort(key=lambda row: (row["cell"], row["family_id"], row["case_id"]))
    return selected[:limit] if limit > 0 else selected


def sandbox_name(row: Mapping[str, str], phase: str, namespace: str = "") -> str:
    digest = hashlib.sha256(f"{namespace}:{row['dbms']}:{row['family_id']}:{phase}".encode()).hexdigest()[:16]
    return f"puffer_ci_pdg_{phase}_{digest}"


def runtime_gate(runtime: Mapping[str, Any] | None, args: argparse.Namespace,
                 expected_slower_side: str | None = None) -> dict[str, Any]:
    if not isinstance(runtime, Mapping):
        return {"valid": False, "reason": "RUNTIME_NOT_MEASURED"}
    default = runtime.get("default", {})
    alt = runtime.get("counterfactual", {})
    rounds = runtime.get("rounds", [])
    if not isinstance(default, Mapping) or not isinstance(alt, Mapping) or not isinstance(rounds, list):
        return {"valid": False, "reason": "RUNTIME_MALFORMED"}
    default_median = float(default.get("median_ms", 0.0))
    alt_median = float(alt.get("median_ms", 0.0))
    if not all(math.isfinite(value) and value > 0 for value in (default_median, alt_median)):
        return {"valid": False, "reason": "RUNTIME_MALFORMED"}
    slower_side = "DEFAULT" if default_median > alt_median else "COUNTERFACTUAL"
    ratio = max(default_median, alt_median) / min(default_median, alt_median)
    gap = abs(default_median - alt_median)
    wins = 0
    for item in rounds:
        if not isinstance(item, Mapping):
            continue
        default_ms = float(item.get("default_runtime_ms", 0.0))
        alt_ms = float(item.get("counterfactual_runtime_ms", 0.0))
        if (slower_side == "DEFAULT" and default_ms > alt_ms) or (
                slower_side == "COUNTERFACTUAL" and alt_ms > default_ms):
            wins += 1
    required_wins = math.ceil(len(rounds) * args.min_win_fraction)
    checks = {
        "ratio": ratio >= args.min_speedup,
        "absolute_gap": gap >= args.min_gap_ms,
        "runtime_floor": min(default_median, alt_median) >= getattr(args, "min_runtime_ms", 0.2),
        "paired_direction": bool(rounds) and wins >= required_wins,
        "same_slower_side": expected_slower_side is None or slower_side == expected_slower_side,
    }
    default_bound = any(item.get("default_timeout_bound", False) for item in rounds if isinstance(item, Mapping))
    alt_bound = any(item.get("counterfactual_timeout_bound", False) for item in rounds if isinstance(item, Mapping))
    inconclusive = ((default_bound and alt_bound)
                    or (default_bound and not alt_bound and not all(checks.values()))
                    or (alt_bound and not default_bound and slower_side != "COUNTERFACTUAL"))
    checks["censoring_decisive"] = not inconclusive
    return {"valid": all(checks.values()), "slower_side": slower_side, "ratio": ratio,
            "default_to_counterfactual_ratio": default_median / alt_median,
            "absolute_gap_ms": gap, "wins": wins, "required_wins": required_wins, "checks": checks,
            "runtime_censored": default_bound or alt_bound, "timeout_inconclusive": inconclusive}


def self_test() -> None:
    class Args:
        min_speedup = 1.30
        min_gap_ms = 100.0
        min_win_fraction = 0.80
    runtime = {"default": {"median_ms": 260.0}, "counterfactual": {"median_ms": 100.0},
               "rounds": [{"default_runtime_ms": 250.0, "counterfactual_runtime_ms": 100.0}] * 5}
    assert runtime_gate(runtime, Args())["valid"]
    assert not runtime_gate(runtime, Args(), "COUNTERFACTUAL")["valid"]
    print("scale validation self-test: ok")


def safe_error(error: BaseException) -> dict[str, str]:
    message = str(error)
    for name in ("PUFFER_MYSQL_PASSWORD", "PUFFER_MARIADB_PASSWORD", "PUFFER_OCEANBASE_PASSWORD", "MYSQL_PWD"):
        secret = os.environ.get(name)
        if secret:
            message = message.replace(secret, "<redacted>")
    return {"type": type(error).__name__, "message": message[:2000]}


def replay_phase(adapter: Any, case: Any, sandbox: str, args: argparse.Namespace, seed: int,
                 *, scaled: bool = False) -> dict[str, Any]:
    options: dict[str, Any] = {"timeout_as_limit": True} if getattr(args, "timeout_as_limit", False) else {}
    if getattr(args, "allow_result_mismatch", False):
        options["continue_on_result_mismatch"] = True
    if scaled:
        if not getattr(args, "scaled_explain_analyze", False):
            options["collect_analyze"] = False
        if getattr(args, "scaled_timeout_pass", False):
            options.update(timeout_as_limit=True, scaled_default_timeout_pass=True)
        if getattr(args, "scaled_timeout_early_stop", True):
            options.update(
                timeout_early_stop=True,
                early_stop_min_speedup=getattr(args, "min_speedup", 1.30),
                early_stop_min_gap_ms=getattr(args, "scaled_min_gap_ms", DEFAULT_SCALED_MIN_GAP_MS),
            )
    replay = adapter.replay(case, sandbox, warmups_per_query=args.warmups, rounds=args.rounds, seed=seed,
                            max_rows=args.max_result_rows, max_bytes=args.max_result_bytes,
                            bootstrap_iterations=2000, **options)
    return replay.to_dict()


def record_phase_evidence(result: dict[str, Any], phase: str) -> bool:
    replay = result[phase]
    comparison = replay.get("result_comparison", {})
    metrics = replay.get("classification_metrics", {})
    equivalent = comparison.get("equivalent")
    result["equivalence_verified"] = result.get("equivalence_verified", True) and equivalent is True
    if metrics.get("timeoutObserved"):
        result["timeout_observed"] = True
        result.setdefault("timeout_details", []).append({
            "phase": phase, "limit_ms": metrics.get("timeoutLimitMs"),
            "runtime_censored": metrics.get("runtimeCensored", False),
            "equivalence_verified": equivalent is True,
            "observations": metrics.get("timeoutObservations", [])})
    # Unknown equivalence is reported explicitly, never changed to True.
    return equivalent is True or (equivalent is None and metrics.get("timeoutObserved", False))


def cost_evidence(replay: Mapping[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    metrics = replay.get("classification_metrics", {})
    default = metrics.get("defaultEstimatedCost")
    alt = metrics.get("counterfactualEstimatedCost")
    ratio = None
    if isinstance(default, (int, float)) and isinstance(alt, (int, float)) and default > 0 and alt > 0:
        ratio = default / alt
        if not math.isfinite(ratio):
            ratio = None
    return {"default_cost": default, "counterfactual_cost": alt, "cost_ratio": ratio,
            "anomalous": ratio is not None and ratio >= getattr(args, "cost_anomaly_ratio", 5.0)}


def runtime_similar(replay: Mapping[str, Any], args: argparse.Namespace) -> bool:
    gate = runtime_gate(replay.get("runtime"), args)
    ratio = gate.get("default_to_counterfactual_ratio")
    if ratio is None:
        return False
    checks = gate["checks"]
    return (getattr(args, "runtime_similar_lower", 0.9) <= ratio <= getattr(args, "runtime_similar_upper", 1.3)
            or not checks["runtime_floor"] or gate["absolute_gap_ms"] < args.min_gap_ms)


def cleanup_phase(adapter: Any, sandbox: str, result: dict[str, Any]) -> None:
    try:
        adapter.cleanup(sandbox)
    except Exception as error:
        result.setdefault("cleanup_errors", []).append({"sandbox": sandbox, **safe_error(error)})


def _parse_float(value: Any) -> float | None:
    if value is None or value == "" or isinstance(value, bool):
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


def _parse_primitives(raw: Any) -> list[str]:
    if isinstance(raw, list):
        return sorted({str(item) for item in raw if item})
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, list):
                return sorted({str(item) for item in parsed if item})
        except json.JSONDecodeError:
            return sorted({part.strip() for part in raw.split(",") if part.strip()})
    return []


def compute_phase_equivalence(replay: Mapping[str, Any] | None) -> str:
    if not isinstance(replay, Mapping):
        return "NOT_EVALUATED"
    comparison = replay.get("result_comparison")
    if not isinstance(comparison, Mapping):
        return "NOT_EVALUATED"
    eq = comparison.get("equivalent")
    if eq is True:
        return "EQUIVALENT"
    if eq is False:
        return "MISMATCH"
    if eq is None:
        return "TIMEOUT_UNVERIFIED"
    return "NOT_EVALUATED"


def compute_result_equivalence(result: Mapping[str, Any]) -> str:
    """Return exact equivalence status: EQUIVALENT | MISMATCH | TIMEOUT_UNVERIFIED | NOT_EVALUATED."""
    evaluated = []
    timeout_unverified = False
    for phase in ("one_x", "ten_x"):
        replay = result.get(phase)
        if not isinstance(replay, Mapping):
            continue
        comparison = replay.get("result_comparison")
        if not isinstance(comparison, Mapping):
            continue
        eq = comparison.get("equivalent")
        evaluated.append(eq)
        if eq is False:
            return "MISMATCH"
        if eq is None:
            timeout_unverified = True
    if not evaluated:
        return "NOT_EVALUATED"
    if timeout_unverified:
        return "TIMEOUT_UNVERIFIED"
    if all(eq is True for eq in evaluated):
        return "EQUIVALENT"
    return "NOT_EVALUATED"


def validate_one(row: Mapping[str, str], adapter: Any, args: argparse.Namespace) -> dict[str, Any]:
    from puffer_case_investigator.case_parser import parse_case
    from puffer_case_investigator.db import QueryLimitExceeded

    started = time.monotonic()
    phase_elapsed: dict[str, float] = {}
    stage1_rho_c = _parse_float(row.get("cost_ratio"))
    stage1_rho_t = _parse_float(row.get("runtime_ratio"))
    stage1_gap_ms = _parse_float(row.get("absolute_runtime_gap_ms"))
    delta_signature = str(row.get("delta_signature") or "").strip() or None
    pdg_primitives = _parse_primitives(row.get("pdg_primitives"))
    result: dict[str, Any] = {
        "dbms": args.dbms,
        "cell": row["cell"],
        "stage1_cell": row["cell"],
        "stage1_rho_c": stage1_rho_c,
        "stage1_rho_t": stage1_rho_t,
        "stage1_absolute_runtime_gap_ms": stage1_gap_ms,
        "delta_signature": delta_signature,
        "pdg_primitives": pdg_primitives,
        "family_id": row["family_id"],
        "case_id": row["case_id"],
        "case_path": row["case_path"],
        "cleanup_errors": [],
    }
    case_path = Path(row["case_path"])
    if stage1_rho_c is None or stage1_rho_t is None or not pdg_primitives:
        metrics_path = case_path / "metrics.json"
        if metrics_path.is_file():
            try:
                metrics = read_json(metrics_path)
                if isinstance(metrics, Mapping):
                    if result["stage1_rho_c"] is None:
                        result["stage1_rho_c"] = _parse_float(metrics.get("costRatio"))
                    if result["stage1_rho_t"] is None:
                        result["stage1_rho_t"] = _parse_float(
                            metrics.get("runtimeRatio", metrics.get("runtimeSpeedup")))
                    if result["stage1_absolute_runtime_gap_ms"] is None:
                        result["stage1_absolute_runtime_gap_ms"] = _parse_float(
                            metrics.get("absoluteRuntimeGapMs", metrics.get("runtimeAbsGapMs")))
                    if not result["pdg_primitives"] and isinstance(metrics.get("pdgPrimitives"), list):
                        result["pdg_primitives"] = _parse_primitives(metrics.get("pdgPrimitives"))
            except Exception:
                pass
    if result["delta_signature"] is None:
        try:
            analyzed = analyze_case(case_path, row["cell"], args.dbms)
            result["delta_signature"] = analyzed.get("delta_signature")
            if not result["pdg_primitives"]:
                result["pdg_primitives"] = analyzed.get("pdg_primitives", [])
        except Exception:
            pass

    def finalize(payload: dict[str, Any]) -> dict[str, Any]:
        merged = {**result, **payload}
        eq_status = compute_result_equivalence(merged)
        merged["result_equivalence"] = eq_status
        merged["one_x_result_equivalence"] = compute_phase_equivalence(merged.get("one_x"))
        one_x_gate = merged.get("one_x_gate")
        if isinstance(one_x_gate, Mapping):
            merged.setdefault("baseline_speedup", one_x_gate.get("default_to_counterfactual_ratio"))
            merged.setdefault("baseline_gap_ms", one_x_gate.get("absolute_gap_ms"))
        else:
            merged.setdefault("baseline_speedup", None)
            merged.setdefault("baseline_gap_ms", None)
        if "equivalence_verified" not in merged:
            merged["equivalence_verified"] = eq_status == "EQUIVALENT"
        elapsed = round(time.monotonic() - started, 4)
        merged["elapsed_s"] = elapsed
        merged["phase_elapsed_s"] = {
            "setup_1x_s": phase_elapsed.get("setup_1x_s", 0.0),
            "replay_1x_s": phase_elapsed.get("replay_1x_s", 0.0),
            "extend_10x_s": phase_elapsed.get("extend_10x_s", 0.0),
            "replay_10x_s": phase_elapsed.get("replay_10x_s", 0.0),
            "total_s": elapsed,
        }
        return merged

    namespace_path = getattr(args, "output_dir", None)
    namespace = str(namespace_path.resolve()) if namespace_path is not None else ""
    one_x = sandbox_name(row, "b", namespace)
    ten_x = sandbox_name(row, "s", namespace)
    result["sandboxes"] = {"one_x": one_x, "ten_x": ten_x}
    same_scale = int(getattr(args, "scale", 10)) == 1
    result["same_scale_validation"] = same_scale
    try:
        pdg = load_pdg(case_path / "pdg_ir.yaml")
        if pdg["dbms"] != args.dbms:
            raise ValidationInputError("case PDG DBMS does not match validation DBMS")
        if not result["pdg_primitives"] and isinstance(pdg, Mapping) and pdg.get("tables"):
            result["pdg_primitives"] = extract_pdg_primitives(pdg)
        case = parse_case(case_path, load_setup=True)
    except Exception as error:
        return finalize({"status": "FAILED", "failure_stage": "INPUT", "error": safe_error(error)})
    if getattr(args, "resume", False) and not same_scale:
        # A prior interruption can leave the scaled sandbox behind even if this
        # retry exits after 1×; never let it contaminate later representatives.
        cleanup_phase(adapter, ten_x, result)
    try:
        t_phase = time.monotonic()
        adapter.prepare_sandbox(case, one_x)
        phase_elapsed["setup_1x_s"] = round(time.monotonic() - t_phase, 4)
        t_phase = time.monotonic()
        result["one_x"] = replay_phase(adapter, case, one_x, args, args.seed, scaled=same_scale)
        phase_elapsed["replay_1x_s"] = round(time.monotonic() - t_phase, 4)
    except QueryLimitExceeded as error:
        return finalize({"status": "FAILED",
                         "failure_stage": "SAME_SCALE_RESULT_LIMIT" if same_scale else "CLEAN_1X_RESULT_LIMIT",
                         "result_limit_exceeded": True, "error": safe_error(error)})
    except Exception as error:
        return finalize({"status": "FAILED", "failure_stage": "CLEAN_1X", "error": safe_error(error)})
    finally:
        cleanup_phase(adapter, one_x, result)
    one_x_equivalent = record_phase_evidence(result, "one_x")
    if same_scale:
        # Large-only (s_0=10, scale_factor=1): skip extend_10x completely and evaluate
        # directly at the current s_0 scale using the exact same final verdict thresholds
        # as Selective 10x (min_speedup, scaled_min_gap_ms=5.0, strict equivalence, scaled_timeout_pass).
        phase_elapsed["extend_10x_s"] = 0.0
        phase_elapsed["replay_10x_s"] = 0.0
        result["scaled_row_counts"] = {
            t["name"]: int(t["base_rows"]) for t in pdg.get("tables", ()) if isinstance(t, Mapping) and "name" in t
        }
        observations = result["one_x"].get("classification_metrics", {}).get("timeoutObservations", [])
        if getattr(args, "scaled_timeout_pass", True) and any(item["side"] == "default" for item in observations):
            both = any(item["side"] == "counterfactual" for item in observations)
            return finalize({
                "status": "SUSTAINED",
                "reason": ("SAME_SCALE_BOTH_PLANS_TIMEOUT_MANUAL_REVIEW" if both
                           else "SAME_SCALE_DEFAULT_TIMEOUT_MANUAL_REVIEW"),
                "manual_review_required": True,
                "timeout_pass": True,
                "same_scale_validated": True,
            })
        if not one_x_equivalent and not getattr(args, "allow_result_mismatch", False):
            return finalize({"status": "FAILED", "failure_stage": "CLEAN_1X_RESULT_NOT_EQUIVALENT"})
        final_args = copy(args)
        final_args.min_gap_ms = getattr(args, "scaled_min_gap_ms", args.min_gap_ms)
        final_gate = runtime_gate(result["one_x"].get("runtime"), final_args, "DEFAULT")
        result["one_x_gate"] = final_gate
        result["ten_x_gate"] = final_gate
        result["one_x_cost"] = cost_evidence(result["one_x"], args)
        if final_gate.get("timeout_inconclusive"):
            return finalize({"status": "FAILED", "failure_stage": "SAME_SCALE_TIMEOUT_INCONCLUSIVE"})
        if final_gate["valid"]:
            return finalize({
                "status": "SUSTAINED",
                "reason": "VALIDATED_SAME_SCALE",
                "same_scale_validated": True,
            })
        if row["cell"] in ("B", "C") and result["one_x_cost"]["anomalous"]:
            only_cost = runtime_similar(result["one_x"], final_args)
            return finalize({
                "status": "COST_ANOMALY",
                "cost_anomaly_only": only_cost,
                "reason": "COST_ANOMALY_ONLY" if only_cost else "COST_RUNTIME_INVERSION_WITHOUT_HARMFUL_SLOWDOWN",
            })
        return finalize({"status": "DISAPPEARED", "reason": "SAME_SCALE_RUNTIME_EFFECT_NOT_RETAINED"})
    if not one_x_equivalent and not getattr(args, "allow_result_mismatch", False):
        return finalize({"status": "FAILED", "failure_stage": "CLEAN_1X_RESULT_NOT_EQUIVALENT"})
    # A performance bug requires the original plan to be slower, not merely a
    # difference in either direction (important for C).
    baseline_args = copy(args)
    baseline_args.min_speedup = getattr(args, "baseline_min_speedup", args.min_speedup)
    baseline_args.min_gap_ms = getattr(args, "baseline_min_gap_ms", args.min_gap_ms)
    baseline_gate = runtime_gate(result["one_x"].get("runtime"), baseline_args, "DEFAULT")
    result["one_x_gate"] = baseline_gate
    if baseline_gate.get("timeout_inconclusive"):
        return finalize({"status": "FAILED", "failure_stage": "CLEAN_1X_TIMEOUT_INCONCLUSIVE"})
    result["one_x_cost"] = cost_evidence(result["one_x"], args)
    # Stage I Class B/C (Cost-Rescued) candidates never get filtered by 1x speedup or 1x gap thresholds.
    if not baseline_gate["valid"] and row["cell"] not in ("B", "C"):
        return finalize({"status": "DISAPPEARED", "reason": "CLEAN_1X_RUNTIME_EFFECT_NOT_RETAINED"})
    try:
        t_phase = time.monotonic()
        adapter.prepare_sandbox(case, ten_x)
        result["scaled_row_counts"] = extend_database(adapter, ten_x, pdg, scale=args.scale,
                                                       hard_cap_rows=args.max_table_rows)
        phase_elapsed["extend_10x_s"] = round(time.monotonic() - t_phase, 4)
        t_phase = time.monotonic()
        result["ten_x"] = replay_phase(adapter, case, ten_x, args, args.seed + 1, scaled=True)
        phase_elapsed["replay_10x_s"] = round(time.monotonic() - t_phase, 4)
    except QueryLimitExceeded as error:
        return finalize({"status": "FAILED", "failure_stage": "SCALED_10X_RESULT_LIMIT",
                         "result_limit_exceeded": True, "error": safe_error(error)})
    except Exception as error:
        return finalize({"status": "FAILED", "failure_stage": "SCALED_10X", "error": safe_error(error)})
    finally:
        cleanup_phase(adapter, ten_x, result)
    scaled_equivalent = record_phase_evidence(result, "ten_x")
    observations = result["ten_x"].get("classification_metrics", {}).get("timeoutObservations", [])
    if getattr(args, "scaled_timeout_pass", True) and any(item["side"] == "default" for item in observations):
        both = any(item["side"] == "counterfactual" for item in observations)
        return finalize({"status": "SUSTAINED" if baseline_gate["valid"] else "ACTIVATED",
                         "reason": "SCALED_BOTH_PLANS_TIMEOUT_MANUAL_REVIEW" if both else "SCALED_DEFAULT_TIMEOUT_MANUAL_REVIEW",
                         "manual_review_required": True, "timeout_pass": True})
    if not scaled_equivalent and not getattr(args, "allow_result_mismatch", False):
        return finalize({"status": "FAILED", "failure_stage": "SCALED_10X_RESULT_NOT_EQUIVALENT"})
    scaled_args = copy(args)
    scaled_args.min_gap_ms = getattr(args, "scaled_min_gap_ms", args.min_gap_ms)
    scaled_gate = runtime_gate(result["ten_x"].get("runtime"), scaled_args, "DEFAULT")
    result["ten_x_gate"] = scaled_gate
    if scaled_gate.get("timeout_inconclusive"):
        return finalize({"status": "FAILED", "failure_stage": "SCALED_10X_TIMEOUT_INCONCLUSIVE"})
    result["ten_x_cost"] = cost_evidence(result["ten_x"], args)
    if not scaled_gate["valid"]:
        if not baseline_gate["valid"] and result["one_x_cost"]["anomalous"] and result["ten_x_cost"]["anomalous"]:
            only_cost = runtime_similar(result["one_x"], baseline_args) and runtime_similar(result["ten_x"], scaled_args)
            return finalize({"status": "COST_ANOMALY", "cost_anomaly_only": only_cost,
                             "reason": "COST_ANOMALY_ONLY" if only_cost else "COST_RUNTIME_INVERSION_WITHOUT_HARMFUL_SLOWDOWN"})
        return finalize({"status": "DISAPPEARED", "reason": "SCALED_RUNTIME_EFFECT_NOT_RETAINED"})
    if not baseline_gate["valid"]:
        return finalize({"status": "ACTIVATED", "reason": "LATENT_CANDIDATE_BECAME_HARMFUL_AT_SCALE"})
    if baseline_gate.get("runtime_censored"):
        return finalize({"status": "SUSTAINED", "reason": "BOTH_SCALES_HARMFUL_BASELINE_GAP_CENSORED"})
    required_gap = baseline_gate["absolute_gap_ms"] * args.amplification_gap_multiplier
    result["amplification"] = {"baseline_gap_ms": baseline_gate["absolute_gap_ms"],
                                "scaled_gap_ms": scaled_gate["absolute_gap_ms"],
                                "required_scaled_gap_ms": required_gap}
    if scaled_gate["absolute_gap_ms"] >= required_gap:
        return finalize({"status": "AMPLIFIED", "reason": "RUNTIME_GAP_AMPLIFIED"})
    return finalize({"status": "SUSTAINED", "reason": "RUNTIME_EFFECT_SUSTAINED"})


def flatten(result: Mapping[str, Any]) -> dict[str, Any]:
    one = result.get("one_x", {}) if isinstance(result.get("one_x"), Mapping) else {}
    ten = result.get("ten_x", {}) if isinstance(result.get("ten_x"), Mapping) else {}
    one_metrics = one.get("classification_metrics", {}) if isinstance(one, Mapping) else {}
    ten_metrics = ten.get("classification_metrics", {}) if isinstance(ten, Mapping) else {}
    error = result.get("error", {}) if isinstance(result.get("error"), Mapping) else {}
    return {"dbms": result.get("dbms"), "cell": result.get("cell"), "family_id": result.get("family_id"),
            "case_id": result.get("case_id"), "delta_signature": result.get("delta_signature"),
            "pdg_primitives": result.get("pdg_primitives"),
            "stage1_rho_c": result.get("stage1_rho_c"), "stage1_rho_t": result.get("stage1_rho_t"),
            "elapsed_s": result.get("elapsed_s"),
            "replay_1x_s": result.get("phase_elapsed_s", {}).get("replay_1x_s") if isinstance(result.get("phase_elapsed_s"), Mapping) else None,
            "extend_10x_s": result.get("phase_elapsed_s", {}).get("extend_10x_s") if isinstance(result.get("phase_elapsed_s"), Mapping) else None,
            "baseline_speedup": result.get("baseline_speedup"),
            "baseline_gap_ms": result.get("baseline_gap_ms"),
            "one_x_result_equivalence": result.get("one_x_result_equivalence") or compute_phase_equivalence(result.get("one_x")),
            "same_scale_validation": bool(result.get("same_scale_validation", False)),
            "status": result.get("status"), "reason": result.get("reason"),
            "failure_stage": result.get("failure_stage"), "error": error.get("message"),
            "timeout_observed": bool(result.get("timeout_observed")),
            "timeout_pass": bool(result.get("timeout_pass")),
            "manual_review_required": bool(result.get("manual_review_required")),
            "result_equivalence": result.get("result_equivalence") or compute_result_equivalence(result),
            "equivalence_verified": result.get("equivalence_verified", True),
            "one_x_default_cost": one_metrics.get("defaultEstimatedCost"),
            "one_x_alt_cost": one_metrics.get("counterfactualEstimatedCost"),
            "one_x_default_ms": one_metrics.get("defaultRuntimeMedianMs"),
            "one_x_alt_ms": one_metrics.get("counterfactualRuntimeMedianMs"),
            "one_x_ratio": result.get("one_x_gate", {}).get("ratio") if isinstance(result.get("one_x_gate"), Mapping) else None,
            "ten_x_default_cost": ten_metrics.get("defaultEstimatedCost"),
            "ten_x_alt_cost": ten_metrics.get("counterfactualEstimatedCost"),
            "ten_x_default_ms": ten_metrics.get("defaultRuntimeMedianMs"),
            "ten_x_alt_ms": ten_metrics.get("counterfactualRuntimeMedianMs"),
            "ten_x_ratio": result.get("ten_x_gate", {}).get("ratio") if isinstance(result.get("ten_x_gate"), Mapping) else None,
            "cost_anomaly_only": result.get("cost_anomaly_only", False),
            "one_x_cost_ratio": result.get("one_x_cost", {}).get("cost_ratio"),
            "ten_x_cost_ratio": result.get("ten_x_cost", {}).get("cost_ratio"),
            "case_path": result.get("case_path")}


def _has_result_mismatch(item: Mapping[str, Any]) -> bool:
    if item.get("result_equivalence") == "MISMATCH":
        return True
    return any(
        isinstance(item.get(phase), Mapping)
        and isinstance(item[phase].get("result_comparison"), Mapping)
        and item[phase]["result_comparison"].get("equivalent") is False
        for phase in ("one_x", "ten_x")
    )


def _cell_matrix_row(cell_results: list[dict[str, Any]]) -> dict[str, int]:
    strict = [item for item in cell_results if not _has_result_mismatch(item)]
    counts = Counter(str(item["status"]) for item in cell_results)
    strict_counts = Counter(str(item["status"]) for item in strict)
    amp = strict_counts.get("AMPLIFIED", 0)
    act = strict_counts.get("ACTIVATED", 0)
    sus = strict_counts.get("SUSTAINED", 0)
    ca = counts.get("COST_ANOMALY", 0)
    dis = counts.get("DISAPPEARED", 0)
    fail = counts.get("FAILED", 0)
    return {
        "AMPLIFIED": amp,
        "ACTIVATED": act,
        "SUSTAINED": sus,
        "VALIDATED_PPI": amp + act + sus,
        "COST_ANOMALY": ca,
        "DISAPPEARED": dis,
        "FAILED": fail,
        "NOT_VALIDATED": dis + fail,
        "TOTAL": len(cell_results),
    }


def write_progress(output_dir: Path, args: argparse.Namespace, selected: list[dict[str, str]],
                   results: list[dict[str, Any]], state: str) -> dict[str, Any]:
    completed = {(item["cell"], item["family_id"]) for item in results}
    pending = [row for row in selected if (row["cell"], row["family_id"]) not in completed]
    counts = Counter(str(result["status"]) for result in results)
    performance = [item for item in results if item["status"] in ("AMPLIFIED", "SUSTAINED", "ACTIVATED")]
    strict_performance = [item for item in performance if not _has_result_mismatch(item)]
    relaxed_mismatch_pass_count = len(performance) - len(strict_performance)
    strict_counts = Counter(str(item["status"]) for item in strict_performance)
    timeout_cases = [item for item in results if item.get("timeout_observed")]
    elapsed_values = [float(item["elapsed_s"]) for item in results if isinstance(item.get("elapsed_s"), (int, float))]
    amplified_direct = strict_counts.get("AMPLIFIED", 0)
    amplified_rescued = strict_counts.get("ACTIVATED", 0)
    sustained = strict_counts.get("SUSTAINED", 0)
    amplified_total = amplified_direct + amplified_rescued
    validated_ppi_count = amplified_direct + amplified_rescued + sustained
    n_val = len(selected) if selected else len(results)
    validation_pass_rate = (validated_ppi_count / n_val) if n_val else 0.0
    completed_validation_pass_rate = (validated_ppi_count / len(results)) if results else 0.0
    stage1_to_stage2_matrix = {
        cell: _cell_matrix_row([item for item in results if item.get("cell") == cell])
        for cell in args.cells
    }
    stage1_to_stage2_matrix["TOTAL"] = _cell_matrix_row(results)
    stage1_to_stage2_summary = {
        "rescued_from_bc": sum(stage1_to_stage2_matrix[c]["VALIDATED_PPI"] for c in ("B", "C") if c in stage1_to_stage2_matrix),
        "bc_activated": sum(stage1_to_stage2_matrix[c]["ACTIVATED"] for c in ("B", "C") if c in stage1_to_stage2_matrix),
        "bc_cost_anomaly": sum(stage1_to_stage2_matrix[c]["COST_ANOMALY"] for c in ("B", "C") if c in stage1_to_stage2_matrix),
        "bc_total": sum(stage1_to_stage2_matrix[c]["TOTAL"] for c in ("B", "C") if c in stage1_to_stage2_matrix),
        "from_dg": sum(stage1_to_stage2_matrix[c]["VALIDATED_PPI"] for c in ("D", "G") if c in stage1_to_stage2_matrix),
        "from_a": stage1_to_stage2_matrix["A"]["VALIDATED_PPI"] if "A" in stage1_to_stage2_matrix else 0,
    }
    summary = {"state": state, "dbms": args.dbms, "selected_count": len(selected),
               "validation_policy": {"baseline_min_speedup": getattr(args, "baseline_min_speedup", args.min_speedup),
                                     "scaled_min_speedup": args.min_speedup,
                                     "baseline_min_gap_ms": getattr(args, "baseline_min_gap_ms", args.min_gap_ms),
                                     "scaled_min_gap_ms": getattr(args, "scaled_min_gap_ms", args.min_gap_ms),
                                     "allow_result_mismatch": getattr(args, "allow_result_mismatch", False),
                                     "scaled_timeout_pass": getattr(args, "scaled_timeout_pass", True),
                                     "scaled_timeout_early_stop": getattr(args, "scaled_timeout_early_stop", True),
                                     "scaled_explain_analyze": getattr(args, "scaled_explain_analyze", False)},
               "timing_summary": {"measured_cases": len(elapsed_values),
                                  "total_elapsed_s": round(sum(elapsed_values), 4),
                                  "mean_elapsed_s": round(statistics.fmean(elapsed_values), 4) if elapsed_values else 0.0,
                                  "median_elapsed_s": round(statistics.median(elapsed_values), 4) if elapsed_values else 0.0},
               "relaxed_mismatch_pass_count": relaxed_mismatch_pass_count,
               "completed_count": len(results), "pending_count": len(pending),
               "pending_case_ids": [row["case_id"] for row in pending],
               "status_counts": {status: counts.get(status, 0) for status in STATUS_VALUES},
               "amplified_direct": amplified_direct,
               "amplified_rescued": amplified_rescued,
               "sustained": sustained,
               "amplified_total": amplified_total,
               "validated_ppi_count": validated_ppi_count,
               "validation_pass_rate": validation_pass_rate,
               "completed_validation_pass_rate": completed_validation_pass_rate,
               "classified_performance_candidates": len(performance),
               "validated_performance_candidates": sum(
                   item.get("equivalence_verified", True) or item.get("timeout_pass", False)
                   or getattr(args, "allow_result_mismatch", False) for item in performance),
               "unverified_timeout_performance_candidates": sum(
                   bool(item.get("timeout_observed")) and not item.get("equivalence_verified", True) for item in performance),
               "manual_review_timeout_count": sum(bool(item.get("timeout_pass")) for item in performance),
               "manual_review_timeout_case_ids": [item["case_id"] for item in performance if item.get("timeout_pass")],
               "result_mismatch_count": sum(any(item.get(phase, {}).get("result_comparison", {}).get("equivalent") is False
                                                for phase in ("one_x", "ten_x")) for item in results),
               "passed_without_equivalence_count": sum(not item.get("equivalence_verified", True) for item in performance),
               "timeout_case_count": len(timeout_cases),
               "timeout_case_ids": [item["case_id"] for item in timeout_cases],
               "timeout_cases": [{"case_id": item["case_id"], "status": item["status"],
                                  "equivalence_verified": item.get("equivalence_verified", True),
                                  "details": item.get("timeout_details", [])} for item in timeout_cases],
               "cost_anomaly_only_count": sum(bool(item.get("cost_anomaly_only")) for item in results),
               "status_counts_by_cell": {cell: {status: sum(
                   item["cell"] == cell and item["status"] == status for item in results) for status in STATUS_VALUES}
                   for cell in args.cells},
               "stage1_to_stage2_matrix": stage1_to_stage2_matrix,
               "stage1_to_stage2_summary": stage1_to_stage2_summary,
               "cleanup_errors": [error for item in results for error in item.get("cleanup_errors", [])],
               "case_ids": {status.lower(): [result["case_id"] for result in results if result["status"] == status]
                            for status in STATUS_VALUES}}
    flat = [flatten(result) for result in results]
    write_tsv(output_dir / "results.tsv", flat, list(flatten({})))
    write_tsv(output_dir / "pending.tsv", pending, ("dbms", "cell", "family_id", "case_id", "case_path"))
    lines = [f"# {args.dbms} PDG 1×/{args.scale}× validation", "",
             f"- State: `{state}`; selected: `{len(selected)}`; completed: `{len(results)}`; pending: `{len(pending)}`",
             f"- Status counts: `{json.dumps(summary['status_counts'], sort_keys=True)}`",
             (f"- Validated PPI (AMP+ACT+SUS): `{validated_ppi_count}` / `{n_val}` (`{validation_pass_rate * 100:.2f}%`); "
              f"breakdown: `AMP={amplified_direct}`, `ACT={amplified_rescued}`, `SUS={sustained}`"),
             f"- Timeout passes requiring manual review: `{summary['manual_review_timeout_count']}`",
             f"- Passed without verified result equivalence: `{summary['passed_without_equivalence_count']}`", "",
             "## Stage I Cell → Stage II Status Matrix", "",
             "| Stage I Cell | AMP | ACT | SUS | Validated PPI | COST_ANOMALY | DISAPPEARED | FAILED | Total |",
             "|---|---|---|---|---|---|---|---|---|"]
    for cell_key in [*args.cells, "TOTAL"]:
        row_m = stage1_to_stage2_matrix[cell_key]
        lines.append(
            f"| {cell_key} | {row_m['AMPLIFIED']} | {row_m['ACTIVATED']} | {row_m['SUSTAINED']} | "
            f"{row_m['VALIDATED_PPI']} | {row_m['COST_ANOMALY']} | {row_m['DISAPPEARED']} | "
            f"{row_m['FAILED']} | {row_m['TOTAL']} |")
    lines += ["", "| Status | Case IDs |", "|---|---|"]
    for status in STATUS_VALUES:
        values = summary["case_ids"][status.lower()]
        lines.append(f"| {status} | {', '.join(values) if values else '-'} |")
    if timeout_cases:
        lines += ["", "## Timeout cases", "",
                  "Server deadlines are lower bounds, not exact runtimes. Observed timeout bounds are reused in later rounds.",
                  "Scaled default-plan timeout passes are counted under the requested policy and require manual review.",
                  "", "| Case ID | Status | Equivalence verified | Phase / timed-out side / limit ms |", "|---|---|---|---|"]
        for item in timeout_cases:
            details = "; ".join(f"{phase['phase']} / {observation['side']} / {observation['limit_ms']}"
                                for phase in item.get("timeout_details", []) for observation in phase["observations"])
            lines.append(f"| {item['case_id']} | {item['status']} | {item.get('equivalence_verified', True)} | {details} |")
    lines += ["", "ACTIVATED: a B/C candidate without harmful 1× slowdown becomes harmful after scaling.",
              "COST_ANOMALY is separate evidence, not a validated harmful performance bug.",
              "Result mismatch evidence is retained; --allow-result-mismatch continues performance validation. Timeout passes require manual review.",
              "Queries retain their original literal constants; v1 performs no predicate rebinding.", ""]
    (output_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")
    atomic_json(output_dir / "summary.json", summary)
    return summary


def run(args: argparse.Namespace) -> int:
    selected = read_representatives(args.representatives.resolve(), args.dbms, args.cells, args.limit,
                                    args.prioritize_historical_gap)
    output_dir = args.output_dir.resolve()
    old_manifest = read_json(output_dir / "manifest.json") if args.resume else None
    if not args.resume:
        require_output_dir(output_dir)
    args.seed = args.seed if args.seed is not None else (
        old_manifest["seed"] if old_manifest else secrets.randbelow(2 ** 31))
    manifest = {"stage": "L3_PDG_SCALE_VALIDATION", "dbms": args.dbms,
                "representatives": str(args.representatives.resolve()), "selected_count": len(selected),
                "representatives_sha256": hashlib.sha256(args.representatives.read_bytes()).hexdigest(),
                "selected": selected, "seed": args.seed,
                "config": str(args.config.resolve()), "profile": args.profile,
                "config_sha256": hashlib.sha256(args.config.read_bytes()).hexdigest(),
                "cells": list(args.cells), "scale": args.scale, "warmups": args.warmups, "rounds": args.rounds,
                "query_timeout_s": args.query_timeout_s, "min_speedup": args.min_speedup,
                "baseline_min_speedup": args.baseline_min_speedup,
                "baseline_min_gap_ms": args.baseline_min_gap_ms,
                "scaled_min_gap_ms": args.scaled_min_gap_ms,
                "allow_result_mismatch": args.allow_result_mismatch,
                "scaled_timeout_pass": args.scaled_timeout_pass,
                "scaled_timeout_early_stop": args.scaled_timeout_early_stop,
                "scaled_explain_analyze": args.scaled_explain_analyze,
                "min_gap_ms": args.min_gap_ms, "min_runtime_ms": args.min_runtime_ms,
                "min_win_fraction": args.min_win_fraction,
                "cost_anomaly_ratio": args.cost_anomaly_ratio,
                "runtime_similar_lower": args.runtime_similar_lower, "runtime_similar_upper": args.runtime_similar_upper,
                "max_result_rows": args.max_result_rows, "max_result_bytes": args.max_result_bytes,
                "max_table_rows": args.max_table_rows,
                "amplification_gap_multiplier": args.amplification_gap_multiplier,
                "predicate_rebinding": False, "dry_run": bool(args.dry_run)}
    if getattr(args, "timeout_as_limit", False):
        manifest["timeout_runtime_policy"] = "SERVER_DEADLINE_LOWER_BOUND_V1"
    if old_manifest is not None and old_manifest != manifest:
        raise ValidationInputError("resume inputs/settings differ from persisted manifest; use a new output directory")
    atomic_json(output_dir / "manifest.json", manifest)
    if args.dry_run:
        validated_inputs = []
        for row in selected:
            pdg = load_pdg(Path(row["case_path"]) / "pdg_ir.yaml")
            target_rows = (
                {table["name"]: int(table["base_rows"]) for table in pdg["tables"]}
                if args.scale == 1
                else PdgExtender(pdg, args.scale).target_rows
            )
            validated_inputs.append({"case_id": row["case_id"], "target_rows": target_rows})
        write_json(output_dir / "summary.json", {"state": "DRY_RUN", **manifest, "validated_inputs": validated_inputs})
        print(json.dumps({"dry_run": True, "selected": len(selected)}, indent=2))
        return 0

    results: list[dict[str, Any]] = []
    pending = []
    for row in selected:
        result_path = output_dir / "cases" / row["cell"] / f"{row['family_id']}.json"
        if args.resume and result_path.is_file():
            result = read_json(result_path)
            if (result.get("case_id"), result.get("cell"), result.get("family_id"), result.get("case_path")) != (
                    row["case_id"], row["cell"], row["family_id"], row["case_path"]) or result.get("status") not in STATUS_VALUES:
                raise ValidationInputError(f"incompatible cached result: {result_path}")
            results.append(result)
        else:
            pending.append(row)
    cleanup_pending = [result for result in results if result.get("cleanup_errors")]
    summary = write_progress(output_dir, args, selected, results, "RUNNING" if pending or cleanup_pending else "COMPLETE")
    if not pending and not cleanup_pending:
        print(json.dumps(summary, indent=2))
        return 0

    from puffer_case_investigator.config import load_settings
    from puffer_case_investigator.db import DatabaseProfile, create_adapter

    try:
        settings = load_settings(REPO_ROOT, args.config.resolve())
        profile_settings = settings.profile(args.profile)
        if profile_settings.dbms != args.dbms:
            raise ValidationInputError("profile DBMS does not match --dbms")
        profile = DatabaseProfile.from_settings(profile_settings, query_timeout_s=args.query_timeout_s)
        adapter = create_adapter(profile)
        adapter.validate_expected_version()
        for result in cleanup_pending:
            errors = result.pop("cleanup_errors")
            for sandbox in dict.fromkeys(error["sandbox"] for error in errors):
                cleanup_phase(adapter, sandbox, result)
            atomic_json(output_dir / "cases" / result["cell"] / f"{result['family_id']}.json", result)
        for row in pending:
            result = validate_one(row, adapter, args)
            atomic_json(output_dir / "cases" / row["cell"] / f"{row['family_id']}.json", result)
            results.append(result)
            write_progress(output_dir, args, selected, results, "RUNNING")
    except KeyboardInterrupt:
        write_progress(output_dir, args, selected, results, "INTERRUPTED")
        return 130
    except Exception:
        write_progress(output_dir, args, selected, results, "ERROR")
        raise
    final_state = "CLEANUP_REQUIRED" if any(result.get("cleanup_errors") for result in results) else "COMPLETE"
    summary = write_progress(output_dir, args, selected, results, final_state)
    print(json.dumps(summary, indent=2))
    return 0


def handle_interrupt(*unused: Any) -> None:
    raise KeyboardInterrupt


def resummarize_output_dir(output_dir: Path) -> dict[str, Any]:
    resolved = output_dir.resolve()
    manifest = read_json(resolved / "manifest.json")
    existing_summary = read_json(resolved / "summary.json") if (resolved / "summary.json").is_file() else {}
    selected = list(manifest.get("selected", []))
    ns = SimpleNamespace(
        dbms=manifest["dbms"],
        cells=tuple(manifest.get("cells") or ("A", "B", "C", "D", "G")),
        scale=int(manifest.get("scale", 10)),
        min_speedup=float(manifest.get("min_speedup", 1.30)),
        baseline_min_speedup=float(manifest.get("baseline_min_speedup") or manifest.get("min_speedup", 1.30)),
        min_gap_ms=float(manifest.get("min_gap_ms", manifest.get("scaled_min_gap_ms", DEFAULT_SCALED_MIN_GAP_MS))),
        baseline_min_gap_ms=float(
            manifest.get("baseline_min_gap_ms")
            if manifest.get("baseline_min_gap_ms") is not None
            else manifest.get("min_gap_ms", DEFAULT_BASELINE_MIN_GAP_MS)),
        scaled_min_gap_ms=float(
            manifest.get("scaled_min_gap_ms")
            if manifest.get("scaled_min_gap_ms") is not None
            else manifest.get("min_gap_ms", DEFAULT_SCALED_MIN_GAP_MS)),
        allow_result_mismatch=bool(manifest.get("allow_result_mismatch", False)),
        scaled_timeout_pass=bool(manifest.get("scaled_timeout_pass", True)),
        scaled_timeout_early_stop=bool(manifest.get("scaled_timeout_early_stop", True)),
        scaled_explain_analyze=bool(manifest.get("scaled_explain_analyze", False)),
    )
    results: list[dict[str, Any]] = []
    for row in selected:
        result_path = resolved / "cases" / row["cell"] / f"{row['family_id']}.json"
        if result_path.is_file():
            results.append(read_json(result_path))
    prev_state = existing_summary.get("state")
    if prev_state in ("COMPLETE", "CLEANUP_REQUIRED", "INTERRUPTED", "ERROR"):
        state = prev_state
    else:
        state = "COMPLETE" if len(results) == len(selected) else "RUNNING"
    if state == "COMPLETE" and any(item.get("cleanup_errors") for item in results):
        state = "CLEANUP_REQUIRED"
    return write_progress(resolved, ns, selected, results, state)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if args.resummarize:
        try:
            summary = resummarize_output_dir(args.output_dir)
            print(json.dumps(summary, indent=2))
            return 0
        except (ValidationInputError, OSError) as error:
            print(f"error: {safe_error(error)['message']}", file=sys.stderr)
            return 2
    previous = signal.signal(signal.SIGTERM, handle_interrupt)
    try:
        return run(args)
    except (ValidationInputError, OSError) as error:
        print(f"error: {safe_error(error)['message']}", file=sys.stderr)
        return 2
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    raise SystemExit(main())
