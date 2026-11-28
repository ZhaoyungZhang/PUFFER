#!/usr/bin/env python3
"""Stage L2: fact-only complete-linkage clustering of unified PlanDeltaIR."""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    import numpy as np
    from scipy.cluster.hierarchy import fcluster, linkage
    _HAS_SCIPY = True
except ImportError:
    np = None  # type: ignore[assignment]
    fcluster = None  # type: ignore[assignment]
    linkage = None  # type: ignore[assignment]
    _HAS_SCIPY = False

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from puffer_case_investigator.backends import BACKENDS

from scripts.pdg_validation._common import (ValidationInputError, atomic_json, load_jsonl, parse_cells, representative,
                                            require_output_dir, write_json, write_tsv)
from scripts.pdg_validation._plan_delta import IR_VERSION, canonical_delta_signature, delta_features_from_record


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-ir", type=Path, help="Path to L1 case_deltas.jsonl")
    parser.add_argument("--l1-dir", type=Path, help="L1 output directory containing case_deltas.jsonl (alternative to --input-ir)")
    parser.add_argument("--dbms", choices=tuple(BACKENDS))
    parser.add_argument("--cells", default="A,B,C,D,G")
    parser.add_argument("--threshold", "--similarity-threshold", dest="threshold", type=float, default=0.70)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.input_ir is None and args.l1_dir is not None:
        args.input_ir = args.l1_dir / "case_deltas.jsonl"
        if args.dbms is None and (args.l1_dir / "manifest.json").is_file():
            try:
                manifest_data = json.loads((args.l1_dir / "manifest.json").read_text(encoding="utf-8"))
                if manifest_data.get("dbms") in BACKENDS:
                    args.dbms = manifest_data["dbms"]
            except Exception:
                pass
    if not args.self_test and (args.input_ir is None or args.dbms is None or args.output_dir is None):
        parser.error("--input-ir (or --l1-dir), --dbms and --output-dir are required")
    if not 0 <= args.threshold <= 1:
        parser.error("--threshold must be in [0,1]")
    try:
        args.cells = parse_cells(args.cells)
    except ValidationInputError as error:
        parser.error(str(error))
    return args


def feature(record: Mapping[str, Any]) -> Counter[str]:
    return Counter(delta_features_from_record(record))


def similarity(left: Counter[str], right: Counter[str]) -> float:
    keys = set(left) | set(right)
    union = sum(max(left[key], right[key]) for key in keys)
    if union == 0:
        return 1.0
    return sum(min(left[key], right[key]) for key in keys) / union


def _cluster_indexes_pure_python(features: Sequence[Counter[str]], threshold: float) -> list[list[int]]:
    n = len(features)
    max_dist = (1.0 - threshold) + 1e-12
    clusters: dict[int, list[int]] = {i: [i] for i in range(n)}
    dist: dict[tuple[int, int], float] = {}
    for left in range(n - 1):
        for right in range(left + 1, n):
            dist[(left, right)] = 1.0 - similarity(features[left], features[right])
    next_id = n
    while len(clusters) > 1:
        active = sorted(clusters.keys())
        best_pair: tuple[int, int] | None = None
        best_dist = float("inf")
        for idx_a in range(len(active) - 1):
            a = active[idx_a]
            for idx_b in range(idx_a + 1, len(active)):
                b = active[idx_b]
                d = dist[(a, b)]
                if d < best_dist:
                    best_dist = d
                    best_pair = (a, b)
        if best_pair is None or best_dist > max_dist:
            break
        a, b = best_pair
        merged = sorted(clusters.pop(a) + clusters.pop(b))
        for other in clusters:
            d_a = dist[(min(a, other), max(a, other))]
            d_b = dist[(min(b, other), max(b, other))]
            dist[(other, next_id)] = max(d_a, d_b)
        clusters[next_id] = merged
        next_id += 1
    return sorted(clusters.values(), key=lambda indexes: min(indexes))


def cluster_indexes(features: Sequence[Counter[str]], threshold: float) -> list[list[int]]:
    if not features:
        return []
    if len(features) == 1:
        return [[0]]
    if not _HAS_SCIPY:
        return _cluster_indexes_pure_python(features, threshold)
    distances: list[float] = []
    for left in range(len(features) - 1):
        for right in range(left + 1, len(features)):
            distances.append(1.0 - similarity(features[left], features[right]))
    hierarchy = linkage(np.asarray(distances, dtype=float), method="complete", optimal_ordering=False)
    labels = fcluster(hierarchy, t=(1.0 - threshold) + 1e-12, criterion="distance")
    result: dict[int, list[int]] = {}
    for index, label in enumerate(labels.tolist()):
        result.setdefault(int(label), []).append(index)
    return sorted(result.values(), key=lambda indexes: min(indexes))


def self_test() -> None:
    a = Counter({"ADD:SCAN": 1, "CTX:DISTINCT": 1})
    b = Counter({"ADD:SCAN": 1, "CTX:DISTINCT": 1})
    c = Counter({"REMOVE:JOIN": 1, "CTX:LIMIT": 1})
    assert math.isclose(similarity(a, b), 1.0)
    assert len(cluster_indexes([a, b, c], 0.8)) == 2
    print("cluster self-test: ok")


def run(args: argparse.Namespace) -> int:
    records = load_jsonl(args.input_ir.resolve())
    selected: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for record in records:
        if record.get("ir_version") != IR_VERSION:
            raise ValidationInputError(f"unsupported IR version {record.get('ir_version')!r}")
        if record.get("dbms") != args.dbms:
            raise ValidationInputError("input mixes DBMSs or does not match --dbms")
        if record.get("cell") not in args.cells:
            continue
        key = (str(record.get("cell")), str(record.get("case_id")))
        if key in seen:
            raise ValidationInputError(f"duplicate case {key}")
        seen.add(key)
        selected.append(record)
    # Keep all records for lineage, but cluster only the exact-dedup representatives.
    # Older standalone IR without L1 metadata is treated as singleton groups.
    exact_members: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for record in selected:
        group = str(record.get("exact_group_id", record["case_id"]))
        exact_members[(record["cell"], group)].append(record)
    l1_records = []
    for (cell, group), members in exact_members.items():
        representatives = [item for item in members if item.get("exact_group_representative", True)]
        if len(representatives) != 1:
            raise ValidationInputError(f"L1 group {cell}/{group} must have exactly one representative")
        l1_records.extend(representatives)
    output_dir = args.output_dir.resolve()
    require_output_dir(output_dir)
    manifest = {"stage": "L2_FACT_ONLY_COMPLETE_LINKAGE", "dbms": args.dbms,
                "input_ir": str(args.input_ir.resolve()), "cells": list(args.cells),
                "threshold": args.threshold, "selected_records": len(selected),
                "l1_representative_count": len(l1_records), "dry_run": bool(args.dry_run)}
    write_json(output_dir / "manifest.json", manifest)
    if args.dry_run:
        print(json.dumps(manifest, indent=2))
        return 0

    members_rows: list[dict[str, Any]] = []
    representative_rows: list[dict[str, Any]] = []
    family_documents: list[dict[str, Any]] = []
    family_ordinal = 0
    for cell in args.cells:
        cell_records = sorted((record for record in l1_records if record["cell"] == cell), key=lambda r: r["case_id"])
        if not cell_records:
            continue
        features = [feature(record) for record in cell_records]
        for indexes in cluster_indexes(features, args.threshold):
            family_ordinal += 1
            family_id = f"{args.dbms}-{cell}-L2-{family_ordinal:05d}"
            members = [cell_records[index] for index in indexes]
            selected_record = representative(members)
            expanded_members = []
            for member in members:
                group_id = str(member.get("exact_group_id", member["case_id"]))
                expanded_members.extend(exact_members[(cell, group_id)])
            pairwise = [similarity(features[left], features[right]) for position, left in enumerate(indexes)
                        for right in indexes[position + 1:]]
            minimum_similarity = min(pairwise) if pairwise else 1.0
            delta_sig = selected_record.get("delta_signature") or canonical_delta_signature(selected_record)
            delta_feats = selected_record.get("delta_features") or delta_features_from_record(selected_record)
            pdg_prims = selected_record.get("pdg_primitives") or []
            pheno = selected_record.get("phenotype", {})
            family_documents.append({
                "family_id": family_id, "dbms": args.dbms, "cell": cell, "member_count": len(expanded_members),
                "l1_representative_count": len(members),
                "minimum_pairwise_similarity": minimum_similarity,
                "representative_case_id": selected_record["case_id"],
                "delta_signature": delta_sig,
                "delta_features": delta_feats,
                "pdg_primitives": pdg_prims,
                "stage1_cell": cell,
                "stage1_rho_c": pheno.get("cost_ratio"),
                "stage1_rho_t": pheno.get("runtime_ratio"),
                "interpretation": "similar observable plan deltas; not a root-cause identity claim",
            })
            representative_rows.append({
                "family_id": family_id, "dbms": args.dbms, "cell": cell,
                "case_id": selected_record["case_id"], "case_path": selected_record["case_path"],
                "delta_signature": delta_sig,
                "pdg_primitives": pdg_prims,
                "member_count": len(expanded_members), "l1_representative_count": len(members),
                "minimum_pairwise_similarity": minimum_similarity,
                **pheno,
            })
            for member in expanded_members:
                members_rows.append({"family_id": family_id, "dbms": args.dbms, "cell": cell,
                                     "case_id": member["case_id"], "case_path": member["case_path"],
                                     "exact_group_id": member.get("exact_group_id", member["case_id"]),
                                     "l1_representative": member.get("exact_group_representative", True),
                                     "representative": member is selected_record})
    write_tsv(output_dir / "family_members.tsv", members_rows,
              ("family_id", "dbms", "cell", "case_id", "case_path", "exact_group_id", "l1_representative", "representative"))
    write_tsv(output_dir / "family_representatives.tsv", representative_rows,
              ("family_id", "dbms", "cell", "case_id", "case_path", "delta_signature", "pdg_primitives",
               "member_count", "l1_representative_count",
               "minimum_pairwise_similarity", "absolute_runtime_gap_ms", "runtime_ratio", "cost_ratio"))
    write_json(output_dir / "families.json", family_documents)
    coverage = {"dbms": args.dbms, "input_records": len(records), "selected_records": len(selected),
                "all_records_if_no_l1": len(selected), "clustered_records": len(l1_records),
                "l1_members_skipped_for_clustering": len(selected) - len(l1_records),
                "pair_comparisons_if_no_l1": sum(
                    (count * (count - 1)) // 2 for count in Counter(item["cell"] for item in selected).values()),
                "pair_comparisons_after_l1": sum(
                    (count * (count - 1)) // 2 for count in Counter(item["cell"] for item in l1_records).values()),
                "family_count": len(family_documents),
                "families_by_cell": {cell: sum(item["cell"] == cell for item in family_documents) for cell in args.cells}}
    write_json(output_dir / "coverage.json", coverage)
    report = [f"# {args.dbms} PDG Plan-Delta families", "", f"- Threshold: `{args.threshold:.2f}` (complete linkage)",
              f"- All cases without L1: `{len(selected)}`", f"- Actually clustered L1 representatives: `{len(l1_records)}`",
              f"- Families: `{len(family_documents)}`", "",
              "Families mean similar observable plan deltas; they do not claim a shared optimizer root cause.", ""]
    (output_dir / "report.md").write_text("\n".join(report), encoding="utf-8")
    atomic_json(output_dir / "summary.json", coverage)
    print(json.dumps(coverage, indent=2))
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    try:
        return run(args)
    except ValidationInputError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
