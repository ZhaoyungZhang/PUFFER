"""Parser for persisted PUFFER PERFORMANCE_HINT case directories."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

from .models import CaseArtifact, DBMS


class CaseParseError(ValueError):
    """Raised when a directory cannot be treated as a persisted case."""


_REQUIRED_FILE_ALIASES = {
    "metrics": ("metrics.json",),
    "setup": ("setup.sql",),
    "original_query": ("original_query.sql", "original.sql"),
    "counterfactual_query": ("counterfactual_query.sql", "hinted_query.sql"),
    # Earlier campaigns used textual plans; current runs store JSON plans.
    "default_explain": ("default_explain.json", "default_explain.txt"),
    "counterfactual_explain": (
        "counterfactual_explain.json",
        "counterfactual_explain.txt",
        "hinted_explain.json",
        "hinted_explain.txt",
    ),
}

_OPTIONAL_FILE_ALIASES = {"signature": ("signature.json",)}

_FINGERPRINT_FILE_ROLES = (
    "metrics",
    "original_query",
    "counterfactual_query",
    "default_explain",
    "counterfactual_explain",
)


def parse_case(case_dir: Path | str, *, load_setup: bool = True) -> CaseArtifact:
    """Parse one persisted case.

    The local fingerprint index needs metrics, Q/Q′, and plans but never the
    potentially large setup body.  ``load_setup=False`` keeps indexing
    incremental without weakening the full-artifact checks used before a live
    replay.
    """

    root = Path(case_dir).expanduser().resolve()
    if not root.is_dir():
        raise CaseParseError(f"case directory does not exist: {root}")
    source_files: Dict[str, Path] = {}
    missing: list[str] = []
    for role, filenames in _REQUIRED_FILE_ALIASES.items():
        path = _resolve_file(root, filenames)
        if path is None:
            missing.append("one of " + ", ".join(filenames))
        else:
            source_files[role] = path
    if missing:
        raise CaseParseError("missing required case files: " + ", ".join(sorted(missing)))

    metrics = _read_json_object(source_files["metrics"], "metrics")
    dbms = _parse_dbms(metrics.get("dbms"))
    version = str(metrics.get("databaseVersion") or "").strip()
    if not version:
        raise CaseParseError("metrics.json is missing databaseVersion")

    setup_sql = (
        _read_nonempty_text(source_files["setup"], "setup.sql") if load_setup else ""
    )
    original_query = _read_nonempty_text(source_files["original_query"], "original_query.sql")
    counterfactual_query = _read_nonempty_text(
        source_files["counterfactual_query"], "counterfactual_query.sql"
    )
    _cross_check_query_fields(
        metrics,
        ("baseQuery", "originalQuery", "defaultQuery"),
        original_query,
        source_files["original_query"].name,
    )
    _cross_check_query_fields(
        metrics,
        ("counterfactualQuery", "hintedQuery"),
        counterfactual_query,
        source_files["counterfactual_query"].name,
    )

    optional_payloads: Dict[str, Optional[Any]] = {"signature": None}
    for role, filenames in _OPTIONAL_FILE_ALIASES.items():
        path = _resolve_file(root, filenames)
        if path is not None:
            source_files[role] = path
            optional_payloads[role] = _read_json(path, role)
    signature = optional_payloads["signature"]
    if signature is not None and not isinstance(signature, Mapping):
        raise CaseParseError("signature.json must contain a JSON object")

    return CaseArtifact(
        case_dir=root,
        case_id=root.name,
        dbms=dbms,
        database_version=version,
        setup_sql=setup_sql,
        original_query=original_query,
        counterfactual_query=counterfactual_query,
        metrics=metrics,
        signature=signature,
        default_explain=_read_plan(source_files["default_explain"], "default plan"),
        counterfactual_explain=_read_plan(
            source_files["counterfactual_explain"], "counterfactual plan"
        ),
        source_files=source_files,
    )


def _resolve_file(root: Path, filenames: tuple[str, ...]) -> Optional[Path]:
    for filename in filenames:
        candidate = root / filename
        if candidate.is_file():
            return candidate
    return None


def fingerprint_source_stamp(case_dir: Path | str) -> tuple[int, int]:
    """Return the change stamp for the files used to build a local fingerprint.

    This intentionally avoids reading setup.sql.  It lets the SQLite index
    detect an unchanged case before parsing its potentially very large data
    setup, while a live analysis always performs the full parse.
    """

    root = Path(case_dir).expanduser().resolve()
    if not root.is_dir():
        raise CaseParseError(f"case directory does not exist: {root}")
    paths: list[Path] = []
    for role in _FINGERPRINT_FILE_ROLES:
        path = _resolve_file(root, _REQUIRED_FILE_ALIASES[role])
        if path is None:
            choices = ", ".join(_REQUIRED_FILE_ALIASES[role])
            raise CaseParseError(f"missing fingerprint source for {role}: one of {choices}")
        paths.append(path)
    stats = [path.stat() for path in paths]
    return max(item.st_mtime_ns for item in stats), sum(item.st_size for item in stats)


def _parse_dbms(raw: Any) -> DBMS:
    value = str(raw or "").strip().lower()
    try:
        return DBMS(value)
    except ValueError as error:
        raise CaseParseError(f"unsupported or missing dbms in metrics.json: {raw!r}") from error


def _read_json(path: Path, role: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise CaseParseError(f"cannot read {role} file {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise CaseParseError(f"invalid JSON in {role} file {path}: {error}") from error


def _read_json_object(path: Path, role: str) -> Mapping[str, Any]:
    payload = _read_json(path, role)
    if not isinstance(payload, Mapping):
        raise CaseParseError(f"{path.name} must contain a JSON object")
    return payload


def _read_nonempty_text(path: Path, role: str) -> str:
    try:
        content = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise CaseParseError(f"cannot read {role} file {path}: {error}") from error
    if not content:
        raise CaseParseError(f"{role} is empty")
    return content


def _cross_check_query_fields(
    metrics: Mapping[str, Any], metrics_keys: tuple[str, ...], file_query: str, filename: str
) -> None:
    for metrics_key in metrics_keys:
        raw = metrics.get(metrics_key)
        if raw is None:
            continue
        if _collapse_sql(str(raw)) != _collapse_sql(file_query):
            raise CaseParseError(
                f"{filename} does not match metrics.json field {metrics_key}; "
                "the artifact may be incomplete or mixed"
            )


def _read_plan(path: Path, role: str) -> Any:
    if path.suffix.lower() == ".json":
        return _read_json(path, role)
    return _read_nonempty_text(path, role)


def _collapse_sql(sql: str) -> str:
    return " ".join(sql.rstrip("; \t\r\n").split())
