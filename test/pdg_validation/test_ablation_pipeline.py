from __future__ import annotations

import csv
import importlib.util
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from scripts import cleanup_mysql_mariadb_test_schemas as cleanup
from scripts import run_ablation_experiment as experiment
from scripts.pdg_validation._common import ValidationInputError, discover_cases, read_json, write_jsonl, write_tsv
from scripts.pdg_validation._plan_delta import IR_VERSION

ROOT = Path(__file__).resolve().parents[2]


def stage(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"scripts/pdg_validation/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def scale_args(module, tmp_path: Path, *extra: str):
    return module.parse_args(["--dbms", "mysql", "--representatives", str(tmp_path / "reps.tsv"),
                              "--output-dir", str(tmp_path / "l3"), "--min-speedup", "5", "--min-gap-ms", "0.5", *extra])


def replay(default: float, alt: float) -> dict:
    return {"result_comparison": {"equivalent": True}, "runtime": {
        "default": {"median_ms": default}, "counterfactual": {"median_ms": alt},
        "rounds": [{"default_runtime_ms": default, "counterfactual_runtime_ms": alt}] * 5,
    }, "classification_metrics": {"defaultEstimatedCost": 500, "counterfactualEstimatedCost": 10,
                                    "defaultRuntimeMedianMs": default, "counterfactualRuntimeMedianMs": alt}}


def test_strict_cleanup_does_not_merge_default_patterns(tmp_path: Path) -> None:
    target = cleanup.TARGETS["mysql"]
    inventory = ["mysql", "mysql971ph1230", "mysql971ablmine100", "mysql971ablmine101", "user_database"]
    assert cleanup.select_candidates(target, inventory, "prefix-only", ["mysql971ablmine"], set()) == inventory[2:4]
    assert cleanup.select_candidates(target, inventory, "names-only", ["mysql971ablmine"], set(),
                                     ["mysql971ablmine100"]) == ["mysql971ablmine100"]
    assert cleanup.select_candidates(target, inventory, "names-only", [], set(), []) == []
    assert cleanup.select_candidates(target, inventory, "generated", [], set()) == ["mysql971ph1230"]
    with pytest.raises(ValueError, match="explicit"):
        cleanup.select_candidates(target, inventory, "prefix-only", [], set())
    with pytest.raises(ValueError, match="allowlist"):
        cleanup.select_candidates(target, inventory, "names-only", [], set())
    with pytest.raises(ValueError, match="unsafe"):
        cleanup.select_candidates(target, inventory, "names-only", [], set(), ["mysql"])
    manifest = tmp_path / "names.tsv"
    manifest.write_text("mysql\tmysql971ablmine100\n", encoding="utf-8")
    assert cleanup.read_names_file(manifest)["mysql"] == ["mysql971ablmine100"]


def test_l2_uses_l1_representatives_and_preserves_all_members(tmp_path: Path) -> None:
    module = stage("02_cluster")
    records = []
    for index in range(4):
        records.append({"ir_version": IR_VERSION, "dbms": "mysql", "cell": "B", "case_id": f"case_{index}",
                        "case_path": str(tmp_path / f"case_{index}"), "exact_group_id": f"g{index // 2}",
                        "exact_group_representative": index % 2 == 0, "exact_group_member_count": 2,
                        "observed_delta": {"removed_facts": [], "added_facts": []}, "context": {},
                        "phenotype": {"absolute_runtime_gap_ms": index, "runtime_ratio": 1.15}})
    source = tmp_path / "ir.jsonl"
    write_jsonl(source, records)
    out = tmp_path / "l2"
    assert module.main(["--dbms", "mysql", "--input-ir", str(source), "--output-dir", str(out)]) == 0
    summary = read_json(out / "summary.json")
    assert summary["all_records_if_no_l1"] == 4
    assert summary["clustered_records"] == 2
    assert summary["family_count"] == 1
    assert summary["pair_comparisons_if_no_l1"] == 6
    assert summary["pair_comparisons_after_l1"] == 1
    with (out / "family_members.tsv").open() as handle:
        members = list(csv.DictReader(handle, delimiter="\t"))
    assert len(members) == 4
    assert sum(item["l1_representative"] == "true" for item in members) == 2
    with (out / "family_representatives.tsv").open() as handle:
        representative = next(csv.DictReader(handle, delimiter="\t"))
    assert representative["member_count"] == "4"
    assert representative["l1_representative_count"] == "2"


def test_zero_candidates_all_stages_no_database(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = tmp_path / "cases"
    source.mkdir()
    assert stage("01_deduplicate").main(["--dbms", "mysql", "--input-root", str(source),
                                        "--output-dir", str(tmp_path / "l1")]) == 0
    assert read_json(tmp_path / "l1/summary.json")["discovered"] == dict.fromkeys("ABCDG", 0)
    assert stage("02_cluster").main(["--dbms", "mysql", "--input-ir", str(tmp_path / "l1/case_deltas.jsonl"),
                                    "--output-dir", str(tmp_path / "l2")]) == 0
    module = stage("03_scale_validate")
    monkeypatch.setattr("puffer_case_investigator.db.create_adapter", lambda *_: pytest.fail("must not connect"))
    assert module.main(["--dbms", "mysql", "--representatives", str(tmp_path / "l2/family_representatives.tsv"),
                        "--output-dir", str(tmp_path / "l3")]) == 0
    summary = read_json(tmp_path / "l3/summary.json")
    assert summary["state"] == "COMPLETE" and summary["pending_count"] == 0
    assert all(count == 0 for count in summary["status_counts"].values())


def test_legacy_and_flat_bc_cases_are_both_discovered(tmp_path: Path) -> None:
    for relative in ("A/case_a", "B/case_new_b", "diagnostic_cases/B/case_old_b", "diagnostic_cases/C/case_c"):
        (tmp_path / relative).mkdir(parents=True)
    assert [(cell, path.name) for cell, path in discover_cases(tmp_path, "ABCDG")] == [
        ("A", "case_a"), ("B", "case_new_b"), ("B", "case_old_b"), ("C", "case_c")]


@pytest.mark.parametrize("cell,one,ten,status,cost_only", [
    ("B", (11.5, 10), (600, 10), "ACTIVATED", False),
    ("B", (11.5, 10), (115, 100), "COST_ANOMALY", True),
    ("C", (1, 10), (10, 100), "COST_ANOMALY", False),
    ("A", (30, 10), (300, 10), "AMPLIFIED", False),
    ("A", (60, 10), (600, 10), "AMPLIFIED", False),
    ("D", (60, 10), (70, 10), "SUSTAINED", False),
    ("G", (60, 10), (12, 10), "DISAPPEARED", False),
])
def test_scale_branches(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, cell, one, ten, status, cost_only) -> None:
    module = stage("03_scale_validate")
    args = scale_args(module, tmp_path, "--seed", "7")
    row = {"dbms": "mysql", "cell": cell, "family_id": "f1", "case_id": "case_1", "case_path": str(tmp_path)}
    monkeypatch.setattr(module, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(module, "extend_database", lambda *_, **__: {"t0": 1000})
    replies = iter((replay(*one), replay(*ten)))
    monkeypatch.setattr(module, "replay_phase", lambda *_, **__: next(replies))
    adapter = SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None)
    result = module.validate_one(row, adapter, args)
    assert result["status"] == status
    assert result.get("cost_anomaly_only", False) is cost_only
    assert "ten_x" in result


@pytest.mark.parametrize("default_bound,alt_bound,alt_ms,status", [
    (True, False, 1000, "AMPLIFIED"),
    (True, False, 30000, "FAILED"),
    (True, True, 120000, "FAILED"),
    (False, True, 120000, "DISAPPEARED"),
])
def test_censored_runtime_classification_and_equivalence_accounting(
        tmp_path, monkeypatch, default_bound, alt_bound, alt_ms, status):
    module = stage("03_scale_validate")
    args = scale_args(module, tmp_path, "--timeout-as-limit", "--no-scaled-timeout-pass", "--seed", "7")
    row = {"dbms": "mysql", "cell": "A", "family_id": "f1", "case_id": "case_1", "case_path": str(tmp_path)}
    monkeypatch.setattr(module, "load_pdg", lambda _: {"dbms": "mysql"})
    monkeypatch.setattr("puffer_case_investigator.case_parser.parse_case", lambda *_, **__: object())
    monkeypatch.setattr(module, "extend_database", lambda *_, **__: {"t0": 1000})
    scaled = replay(120000 if default_bound else 1000, alt_ms)
    scaled["result_comparison"]["equivalent"] = None
    scaled["classification_metrics"].update(timeoutObserved=True, timeoutLimitMs=120000, runtimeCensored=True,
                                           timeoutObservations=[{"side": "default" if default_bound else "counterfactual",
                                                                 "stage": "RESULT_COMPARISON", "limit_ms": 120000}])
    for item in scaled["runtime"]["rounds"]:
        item.update(default_timeout_bound=default_bound, counterfactual_timeout_bound=alt_bound)
    replies = iter((replay(60, 10), scaled))
    monkeypatch.setattr(module, "replay_phase", lambda *_, **__: next(replies))
    result = module.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert result["status"] == status
    assert result["timeout_observed"] and not result["equivalence_verified"]
    out = tmp_path / "progress"
    out.mkdir()
    summary = module.write_progress(out, args, [row], [result], "COMPLETE")
    assert summary["status_counts"][status] == 1
    assert summary["timeout_case_ids"] == ["case_1"]
    assert summary["validated_performance_candidates"] == 0
    assert summary["unverified_timeout_performance_candidates"] == (status == "AMPLIFIED")


def test_interrupted_validation_resumes_only_pending(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    module = stage("03_scale_validate")
    rows = [{"dbms": "mysql", "cell": "B", "family_id": f"f{index}", "case_id": f"case_{index}",
             "case_path": str(tmp_path / f"case_{index}")} for index in range(2)]
    write_tsv(tmp_path / "reps.tsv", rows, list(rows[0]))
    monkeypatch.setattr("puffer_case_investigator.config.load_settings", lambda *_: SimpleNamespace(
        profile=lambda _: SimpleNamespace(dbms="mysql")))
    monkeypatch.setattr("puffer_case_investigator.db.DatabaseProfile.from_settings", lambda *_, **__: object())
    monkeypatch.setattr("puffer_case_investigator.db.create_adapter", lambda _: SimpleNamespace(validate_expected_version=lambda: None))
    visited = []

    def validate(row, *_):
        visited.append(row["case_id"])
        if row["case_id"] == "case_1":
            raise KeyboardInterrupt
        return {**row, "status": "COST_ANOMALY", "cost_anomaly_only": True}

    monkeypatch.setattr(module, "validate_one", validate)
    assert module.run(scale_args(module, tmp_path)) == 130
    summary = read_json(tmp_path / "l3/summary.json")
    assert summary["state"] == "INTERRUPTED"
    assert summary["completed_count"] == 1 and summary["pending_case_ids"] == ["case_1"]
    original_seed = read_json(tmp_path / "l3/manifest.json")["seed"]

    def finish(row, *_):
        visited.append(row["case_id"])
        return {**row, "status": "ACTIVATED"}

    monkeypatch.setattr(module, "validate_one", finish)
    assert module.run(scale_args(module, tmp_path, "--resume")) == 0
    assert visited == ["case_0", "case_1", "case_1"]
    assert read_json(tmp_path / "l3/manifest.json")["seed"] == original_seed
    summary = read_json(tmp_path / "l3/summary.json")
    assert summary["pending_count"] == 0 and summary["validated_performance_candidates"] == 1
    with pytest.raises(ValidationInputError, match="settings differ"):
        module.run(scale_args(module, tmp_path, "--resume", "--min-speedup", "6"))


@pytest.mark.parametrize("dbms", ["mysql", "mariadb", "tidb"])
def test_experiment_plan_random_seeds_and_no_database(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, dbms: str) -> None:
    monkeypatch.setattr(experiment, "connection_target", lambda *_: pytest.fail("dry run must not connect"))
    config_path = tmp_path / "experiment.yaml"
    config_path.write_text(yaml.safe_dump({"dbms": dbms, "data_profiles": ["random", "full"]}), encoding="utf-8")
    run_dir = tmp_path / "run"
    assert experiment.main(["--config", str(config_path), "--run-dir", str(run_dir), "--dry-run"]) == 0
    config = read_json(run_dir / "effective_config.json")
    assert config["generator"]["max_indexes_per_table"] == 3
    assert config["oracle"]["cost_anomaly_ratio"] == 10
    assert config["connection_profile"] == f"{dbms}-local"
    assert config["validation"]["cluster_similarity"] == 0.7
    first = read_json(run_dir / "random/state.json")
    second = read_json(run_dir / "full/state.json")
    assert type(first["seed"]) is int and first["schema_prefix"] != second["schema_prefix"]
    assert first["schema_prefix"].startswith(experiment.SCHEMA_PREFIXES[dbms])
    summary = read_json(run_dir / "summary.json")
    assert summary["dbms"] == dbms and summary["arms"][0]["counts"]["L0_candidates"] == 0
    assert experiment.DBMS_LABELS[dbms] in (run_dir / "report.md").read_text()
    config["dbms"] = "unsupported"
    with pytest.raises(ValidationInputError, match="dbms must be"):
        experiment.validate_config(config)


@pytest.mark.parametrize("dbms", ["mysql", "mariadb"])
def test_experiment_cleanup_uses_exact_manifest(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, dbms: str) -> None:
    prefix = experiment.SCHEMA_PREFIXES[dbms] + "run"
    manifest = tmp_path / "run_schemas.tsv"
    manifest.write_text(f"{dbms}\t{prefix}10\n", encoding="utf-8")
    target = cleanup.TARGETS[dbms]
    monkeypatch.setattr(cleanup, "verify_target", lambda _: target.expected_version_prefix)
    inventories = iter(([prefix + "10", prefix + "11", "other_run"], [prefix + "11", "other_run"]))
    monkeypatch.setattr(cleanup, "inventory_from_catalog", lambda _: next(inventories))
    deleted = []
    monkeypatch.setattr(cleanup, "drop_candidates", lambda _, names, __: (deleted.extend(names) or len(names), []))
    experiment.clean_arm(tmp_path, {"schema_prefix": prefix}, target)
    assert deleted == [prefix + "10"]
    manifest.write_text(f"{dbms}\tother_run\n", encoding="utf-8")
    with pytest.raises(ValidationInputError, match="outside"):
        experiment.clean_arm(tmp_path, {"schema_prefix": prefix}, target)
    other_dbms = "mysql" if dbms == "mariadb" else "mariadb"
    manifest.write_text(f"{other_dbms}\t{prefix}10\n", encoding="utf-8")
    with pytest.raises(ValidationInputError, match="another DBMS"):
        experiment.clean_arm(tmp_path, {"schema_prefix": prefix}, target)


@pytest.mark.parametrize("dbms", ["mysql", "mariadb"])
def test_experiment_connection_matches_selected_dbms(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, dbms: str) -> None:
    config_path = tmp_path / "experiment.yaml"
    config_path.write_text(yaml.safe_dump({"dbms": dbms}), encoding="utf-8")
    config = experiment.load_config(config_path)
    monkeypatch.setenv(f"PUFFER_{dbms.upper()}_PASSWORD", "test-password")
    target = experiment.connection_target(config)
    assert target.name == dbms and target.port == cleanup.TARGETS[dbms].port
    assert target.client == cleanup.TARGETS[dbms].client and target.default_password == "test-password"
    config["dbms"] = "mysql" if dbms == "mariadb" else "mariadb"
    with pytest.raises(ValidationInputError, match="connection_profile must refer"):
        experiment.connection_target(config)


def test_mariadb_example_runs_all_five_hourly_profiles() -> None:
    config = experiment.load_config(ROOT / "configs/mariadb-data-ablation.yaml")
    assert config["dbms"] == "mariadb" and config["connection_profile"] == "mariadb-local"
    assert config["data_profiles"] == list(experiment.PROFILES)
    assert config["duration_seconds"] == 3600
    assert config["oracle"]["cost_anomaly_ratio"] == 10.0
    assert config["validation"]["cluster_similarity"] == 0.7


@pytest.mark.parametrize("stderr,returncode,failed", [
    ("WARNING: option --ssl-verify-server-cert is disabled.\n", 0, False),
    ("mysql: [Warning] Using a password on the command line interface can be insecure.\n", 0, False),
    ("WARNING: connection failed\n", 1, True),
    ("ERROR 1010 (HY000): Error dropping database\n", 0, True),
    ("WARNING: connection warning\nERROR 1010 (HY000): Error dropping database\n", 0, True),
    ("unrecognized client diagnostic\n", 0, True),
])
def test_cleanup_distinguishes_client_warnings_from_failed_drops(
        monkeypatch: pytest.MonkeyPatch, stderr: str, returncode: int, failed: bool) -> None:
    monkeypatch.setattr(cleanup, "run_sql", lambda *_args, **_kwargs: SimpleNamespace(
        returncode=returncode, stderr=stderr))
    count, errors = cleanup.drop_candidates(cleanup.TARGETS["mariadb"], ["mariadb1232abltest10"], 200)
    assert count == 1 and bool(errors) is failed


@pytest.mark.parametrize("fail_profile", [None, "column-intra"])
@pytest.mark.parametrize("dbms", ["mysql", "mariadb"])
def test_ordered_profiles_finish_validation_before_next_java(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch, fail_profile: str | None, dbms: str) -> None:
    profiles = list(experiment.PROFILES)
    repo = tmp_path / "repo"
    (repo / "target").mkdir(parents=True)
    (repo / "src").mkdir()
    (repo / "target/sqlancer-2.0.0.jar").write_bytes(b"test jar")
    (repo / "connection.toml").write_text("", encoding="utf-8")
    config_path = tmp_path / "experiment.yaml"
    config_path.write_text(yaml.safe_dump({"dbms": dbms, "connection_config": str(repo / "connection.toml")}), encoding="utf-8")
    run_dir = tmp_path / "run"
    monkeypatch.setattr(experiment, "REPO_ROOT", repo)
    monkeypatch.setattr(experiment.subprocess, "check_output", lambda command, **_: (
        "test-head\n" if command[1] == "rev-parse" else ""))
    monkeypatch.setattr(experiment, "connection_target", lambda _: cleanup.TARGETS[dbms])
    monkeypatch.setattr(cleanup, "verify_target", lambda _: None)
    events = []

    def clean(arm, *_):
        events.append((arm.name, "cleanup"))

    def run(command, log, environment=None):
        profile = log.parent.name
        if command[0] == "bash":
            for previous in profiles[:profiles.index(profile)]:
                assert read_json(run_dir / previous / "state.json")["state"] == "COMPLETE"
            assert environment["DATA_ABLATION_PROFILE"] == profile
            assert environment["DURATION_SECONDS"] == "3600"
            assert environment["DBMS"] == dbms
            assert environment["PERF_REPORT_COST_ANOMALY_RATIO"] == "10.0"
            assert environment["COST_THRESHOLD"] == "10.0"
            assert environment["PORT"] == str(cleanup.TARGETS[dbms].port)
            events.append((profile, "java"))
            return
        stage_name = Path(command[1]).name[:2]
        assert command[command.index("--dbms") + 1] == dbms
        if stage_name == "02":
            assert command[command.index("--threshold") + 1] == "0.7"
        if stage_name == "03":
            assert command[command.index("--profile") + 1] == f"{dbms}-local"
            assert command[command.index("--cost-anomaly-ratio") + 1] == "10.0"
        events.append((profile, stage_name))
        if profile == fail_profile and stage_name == "03":
            raise ValidationInputError("simulated validation command failure")
        output = Path(command[command.index("--output-dir") + 1])
        output.mkdir(parents=True)
        (output / "summary.json").write_text("{}", encoding="utf-8")

    monkeypatch.setattr(experiment, "clean_arm", clean)
    monkeypatch.setattr(experiment, "run_command", run)
    assert experiment.main(["--config", str(config_path), "--data-profile", *profiles,
                            "--run-dir", str(run_dir)]) == (2 if fail_profile else 0)
    assert read_json(run_dir / "effective_config.json")["data_profiles"] == profiles
    expected_profiles = profiles[:profiles.index(fail_profile) + 1] if fail_profile else profiles
    assert events == [(profile, stage_name) for profile in expected_profiles
                      for stage_name in ("java", "cleanup", "01", "02", "03")]
    states = [read_json(run_dir / profile / "state.json")["state"] for profile in profiles]
    expected_states = (["COMPLETE"] * profiles.index(fail_profile) + ["ERROR"]
                       + ["PLANNED"] * (len(profiles) - profiles.index(fail_profile) - 1)
                       if fail_profile else ["COMPLETE"] * len(profiles))
    assert states == expected_states
