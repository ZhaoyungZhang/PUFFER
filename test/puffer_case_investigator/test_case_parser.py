from __future__ import annotations

import json
from pathlib import Path

import pytest

from puffer_case_investigator import CaseParseError, DBMS, NineCellCategory, parse_case


def write_case(
    root: Path,
    name: str = "case_001",
    *,
    dbms: str = "mysql",
    original: str = "SELECT t.c0 FROM t",
    counterfactual: str = "SELECT /*+ INDEX(t i0) */ t.c0 FROM t",
    extra_metrics: dict | None = None,
) -> Path:
    case_dir = root / name
    case_dir.mkdir()
    metrics = {
        "dbms": dbms,
        "databaseVersion": "9.7.1" if dbms == "mysql" else "12.3.2-MariaDB",
        "baseQuery": original,
        "counterfactualQuery": counterfactual,
        "resultEquivalent": True,
        "runtimeValidated": True,
        "timeoutObserved": False,
        "defaultEstimatedCost": 20.0,
        "counterfactualEstimatedCost": 10.0,
        "defaultRuntimeMedianMs": 20.0,
        "counterfactualRuntimeMedianMs": 5.0,
        "nineCellCategory": "A",
        "mutationKind": "HINT",
        "mutationFamily": "INDEX",
        "mutationId": "INDEX:i0",
    }
    metrics.update(extra_metrics or {})
    (case_dir / "metrics.json").write_text(json.dumps(metrics), encoding="utf-8")
    (case_dir / "setup.sql").write_text("CREATE TABLE t(c0 INT);", encoding="utf-8")
    (case_dir / "original_query.sql").write_text(original + ";\n", encoding="utf-8")
    (case_dir / "counterfactual_query.sql").write_text(counterfactual + ";\n", encoding="utf-8")
    (case_dir / "signature.json").write_text("{\"signature\":\"x\"}", encoding="utf-8")
    (case_dir / "default_explain.json").write_text("{\"query_cost\":20}", encoding="utf-8")
    (case_dir / "counterfactual_explain.json").write_text("{\"query_cost\":10}", encoding="utf-8")
    return case_dir


def test_parse_case_accepts_mysql_and_optional_files(tmp_path: Path) -> None:
    case_dir = write_case(tmp_path)
    parsed = parse_case(case_dir)
    assert parsed.case_id == "case_001"
    assert parsed.dbms is DBMS.MYSQL
    assert parsed.original_category is NineCellCategory.A
    assert parsed.signature == {"signature": "x"}
    assert parsed.to_dict()["case_dir"] == str(case_dir.resolve())


def test_parse_case_accepts_mariadb_version_suffix(tmp_path: Path) -> None:
    parsed = parse_case(write_case(tmp_path, dbms="mariadb"))
    assert parsed.dbms is DBMS.MARIADB
    assert parsed.database_version == "12.3.2-MariaDB"


def test_parse_case_reports_missing_and_mixed_artifacts(tmp_path: Path) -> None:
    incomplete = tmp_path / "case_incomplete"
    incomplete.mkdir()
    with pytest.raises(CaseParseError, match="missing required"):
        parse_case(incomplete)

    mixed = write_case(tmp_path, "case_mixed")
    (mixed / "original_query.sql").write_text("SELECT 2", encoding="utf-8")
    with pytest.raises(CaseParseError, match="does not match"):
        parse_case(mixed)


def test_parse_case_requires_both_plan_artifacts(tmp_path: Path) -> None:
    case_dir = write_case(tmp_path)
    (case_dir / "default_explain.json").unlink()
    with pytest.raises(CaseParseError, match="default_explain"):
        parse_case(case_dir)


def test_parse_case_accepts_historical_text_plan_aliases_and_bug_case_name(tmp_path: Path) -> None:
    case_dir = write_case(tmp_path, name="bug_case_42")
    (case_dir / "default_explain.json").unlink()
    (case_dir / "counterfactual_explain.json").unlink()
    (case_dir / "default_explain.txt").write_text("table scan", encoding="utf-8")
    (case_dir / "hinted_explain.txt").write_text("index lookup", encoding="utf-8")
    parsed = parse_case(case_dir)
    assert parsed.case_id == "bug_case_42"
    assert parsed.default_explain == "table scan"
    assert parsed.counterfactual_explain == "index lookup"


def test_index_parser_does_not_load_large_setup_body(tmp_path: Path) -> None:
    case_dir = write_case(tmp_path)
    (case_dir / "setup.sql").write_text("x" * 100_000, encoding="utf-8")
    parsed = parse_case(case_dir, load_setup=False)
    assert parsed.setup_sql == ""
