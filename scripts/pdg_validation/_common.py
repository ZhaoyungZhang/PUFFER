from __future__ import annotations

import csv
import hashlib
import json
import os
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


SUPPORTED_CELLS = frozenset("ABCDFGH")


class ValidationInputError(ValueError):
    pass


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def parse_cells(raw: str) -> tuple[str, ...]:
    cells = tuple(dict.fromkeys(part.strip().upper() for part in raw.split(",") if part.strip()))
    if not cells or any(cell not in SUPPORTED_CELLS for cell in cells):
        raise ValidationInputError("cells must be a non-empty subset of A,B,C,D,F,G,H")
    return cells


def require_output_dir(path: Path) -> None:
    if path.exists() and not path.is_dir():
        raise ValidationInputError(f"output path is not a directory: {path}")
    if path.is_dir() and any(path.iterdir()):
        raise ValidationInputError(f"output directory must be empty: {path}")
    path.mkdir(parents=True, exist_ok=True)


def discover_cases(root: Path, cells: Sequence[str]) -> list[tuple[str, Path]]:
    result: list[tuple[str, Path]] = []
    for cell in cells:
        directories = [root / cell, root / "oceanbase" / cell]
        if cell in ("B", "C"):
            # Read old runs as well, but new Java reports never create this layout.
            directories.append(root / "diagnostic_cases" / cell)
        seen = set()
        for cell_dir in directories:
            for path in sorted(cell_dir.glob("case_*")):
                if path.is_dir() and path.resolve() not in seen:
                    seen.add(path.resolve())
                    result.append((cell, path))
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValidationInputError(f"cannot read JSON {path}: {error}") from error


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_jsonl(path: Path, values: Iterable[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for value in values:
            handle.write(canonical_json(value) + "\n")


def write_tsv(path: Path, rows: Sequence[Mapping[str, Any]], fields: Sequence[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(fields), delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({field: _cell(row.get(field)) for field in fields})


def _cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list, tuple)):
        return canonical_json(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValidationInputError(f"cannot read {path}: {error}") from error
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValidationInputError(f"invalid JSON at {path}:{line_number}: {error}") from error
        if not isinstance(value, dict):
            raise ValidationInputError(f"record at {path}:{line_number} is not an object")
        result.append(value)
    return result


def phenotype(metrics: Mapping[str, Any], cell: str) -> dict[str, Any]:
    def number(*names: str) -> float | None:
        for name in names:
            value = metrics.get(name)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                return float(value)
        return None

    return {
        "oracle_cell": cell,
        "runtime_ratio": number("runtimeRatio", "runtimeSpeedup"),
        "absolute_runtime_gap_ms": number("absoluteRuntimeGapMs", "runtimeAbsGapMs"),
        "cost_ratio": number("costRatio"),
        "base_runtime_median_ms": number("defaultRuntimeMedianMs"),
        "alt_runtime_median_ms": number("counterfactualRuntimeMedianMs", "hintedRuntimeMedianMs"),
    }


def representative(records: Sequence[Mapping[str, Any]]) -> Mapping[str, Any]:
    def key(record: Mapping[str, Any]) -> tuple[float, float, str]:
        pheno = record.get("phenotype", {})
        gap = pheno.get("absolute_runtime_gap_ms") if isinstance(pheno, Mapping) else None
        ratio = pheno.get("runtime_ratio") if isinstance(pheno, Mapping) else None
        return (
            -(float(gap) if isinstance(gap, (int, float)) else -1.0),
            -(float(ratio) if isinstance(ratio, (int, float)) else -1.0),
            str(record.get("case_id", "")),
        )

    return sorted(records, key=key)[0]


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
