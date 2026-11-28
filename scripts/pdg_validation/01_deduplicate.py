#!/usr/bin/env python3
"""Stage L1: exact directed plan-delta deduplication across MySQL, MariaDB, TiDB, CockroachDB, and OceanBase."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Sequence

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from puffer_case_investigator.backends import BACKENDS

from scripts.pdg_validation._common import (ValidationInputError, atomic_json, discover_cases, parse_cells, representative,
                                            require_output_dir, write_json, write_jsonl, write_tsv)
from scripts.pdg_validation._plan_delta import analyze_case, normalize_mariadb, normalize_mysql, observed_delta


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-root", type=Path)
    parser.add_argument("--dbms", choices=tuple(BACKENDS))
    parser.add_argument("--cells", default="A,B,C,D,G")
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if not args.self_test and (args.input_root is None or args.dbms is None or args.output_dir is None):
        parser.error("--input-root, --dbms and --output-dir are required")
    try:
        args.cells = parse_cells(args.cells)
    except ValidationInputError as error:
        parser.error(str(error))
    return args


def self_test() -> None:
    mysql = {
        "json_schema_version": "2.0", "query_type": "select",
        "query_plan": {"access_type": "table", "table_name": "t0", "operation": "Table scan on t0"},
    }
    maria = {"query_block": {"select_id": 1, "filesort": {"temporary_table": {"nested_loop": [
        {"table": {"table_name": "t0", "access_type": "ALL"}},
        {"block-nl-join": {"table": {"table_name": "t1", "access_type": "ALL"}, "join_type": "BNL"}},
    ]}}}}
    mysql_node = normalize_mysql(mysql, Path("mysql.json"))
    maria_node = normalize_mariadb(maria, Path("maria.json"))
    assert mysql_node["kind"] == "SCAN"
    assert maria_node["kind"] == "SORT"
    delta = observed_delta(mysql_node, mysql_node)
    assert not delta["added_facts"] and not delta["removed_facts"] and not delta["tree_changed"]
    print("deduplicate self-test: ok")


def run(args: argparse.Namespace) -> int:
    input_root = args.input_root.resolve()
    output_dir = args.output_dir.resolve()
    cases = discover_cases(input_root, args.cells)
    if not input_root.is_dir():
        raise ValidationInputError(f"input root is not a directory: {input_root}")
    require_output_dir(output_dir)
    manifest = {
        "stage": "L1_EXACT_DIRECTED_DELTA",
        "dbms": args.dbms,
        "input_root": str(input_root),
        "cells": list(args.cells),
        "discovered_cases": len(cases),
        "dry_run": bool(args.dry_run),
    }
    write_json(output_dir / "manifest.json", manifest)
    if args.dry_run:
        print(json.dumps(manifest, indent=2))
        return 0

    records: list[dict[str, Any]] = []
    unsupported: list[dict[str, str]] = []
    discovered = Counter(cell for cell, _ in cases)
    for cell, case_dir in cases:
        try:
            records.append(analyze_case(case_dir, cell, args.dbms))
        except Exception as error:
            unsupported.append({"cell": cell, "case_id": case_dir.name, "case_path": str(case_dir),
                                "reason": f"{type(error).__name__}: {error}"})
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        groups[(record["cell"], record["strict_signature"]["sha256"])].append(record)
    group_rows: list[dict[str, Any]] = []
    member_rows: list[dict[str, Any]] = []
    representative_rows: list[dict[str, Any]] = []
    for ordinal, ((cell, signature), members) in enumerate(sorted(groups.items()), 1):
        group_id = f"{args.dbms}-{cell}-L1-{ordinal:05d}"
        selected = representative(members)
        group_rows.append({"group_id": group_id, "dbms": args.dbms, "cell": cell, "member_count": len(members),
                           "strict_signature_sha256": signature, "representative_case_id": selected["case_id"]})
        representative_rows.append({"group_id": group_id, "dbms": args.dbms, "cell": cell,
                                    "case_id": selected["case_id"], "case_path": selected["case_path"],
                                    "delta_signature": selected.get("delta_signature"),
                                    "pdg_primitives": selected.get("pdg_primitives", []),
                                    **selected["phenotype"]})
        for member in sorted(members, key=lambda item: item["case_id"]):
            member["exact_group_id"] = group_id
            member["exact_group_member_count"] = len(members)
            member["exact_group_representative"] = member is selected
            member_rows.append({"group_id": group_id, "cell": cell, "case_id": member["case_id"],
                                "case_path": member["case_path"], "representative": member is selected})
    records.sort(key=lambda item: (item["cell"], item["case_id"]))
    write_jsonl(output_dir / "case_deltas.jsonl", records)
    write_tsv(output_dir / "exact_groups.tsv", group_rows,
              ("group_id", "dbms", "cell", "member_count", "strict_signature_sha256", "representative_case_id"))
    write_tsv(output_dir / "members.tsv", member_rows,
              ("group_id", "cell", "case_id", "case_path", "representative"))
    write_tsv(output_dir / "representatives.tsv", representative_rows,
              ("group_id", "dbms", "cell", "case_id", "case_path", "delta_signature", "pdg_primitives",
               "absolute_runtime_gap_ms", "runtime_ratio", "cost_ratio"))
    write_tsv(output_dir / "unsupported.tsv", unsupported, ("cell", "case_id", "case_path", "reason"))
    coverage = {
        "dbms": args.dbms,
        "discovered": {cell: discovered[cell] for cell in args.cells},
        "processed": {cell: sum(record["cell"] == cell for record in records) for cell in args.cells},
        "unsupported": {cell: sum(record["cell"] == cell for record in unsupported) for cell in args.cells},
        "discovered_count": len(cases),
        "processed_count": len(records),
        "unsupported_count": len(unsupported),
        "exact_group_count": len(groups),
    }
    write_json(output_dir / "coverage.json", coverage)
    atomic_json(output_dir / "summary.json", {**coverage, "representative_count": len(representative_rows)})
    print(json.dumps({"processed": len(records), "groups": len(groups), "unsupported": len(unsupported)}, indent=2))
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
