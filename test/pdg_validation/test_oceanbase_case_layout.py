from pathlib import Path

from scripts.pdg_validation._common import discover_cases


def test_discovers_oceanbase_writer_layout_alongside_direct_cases(tmp_path: Path) -> None:
    nested = tmp_path / "oceanbase" / "G" / "case_nested"
    direct = tmp_path / "D" / "case_direct"
    diagnostic = tmp_path / "diagnostic_cases" / "B" / "case_observation"
    for path in (nested, direct, diagnostic):
        path.mkdir(parents=True)
    found = discover_cases(tmp_path, ("B", "D", "G"))
    assert set(found) == {("B", diagnostic), ("D", direct), ("G", nested)}
    assert discover_cases(tmp_path / "oceanbase", ("G",)) == [("G", nested)]
