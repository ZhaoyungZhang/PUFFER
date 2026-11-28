from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("command", ["smoke", "qpg", "campaign", "qpg-campaign"])
def test_mariadb_launcher_forwards_ablation_and_fivefold_cost_gate(tmp_path: Path, command: str) -> None:
    repo = tmp_path / "repo"
    scripts = repo / "scripts"
    scripts.mkdir(parents=True)
    for name in ("run_mariadb_12_3_2_sqlancer.sh", "perfhint_properties.sh"):
        shutil.copyfile(ROOT / "scripts" / name, scripts / name)
    binaries = tmp_path / "bin"
    binaries.mkdir()
    captured = tmp_path / "captured.json"
    java = binaries / "java"
    java.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                    "with open(os.environ['CAPTURE_PATH'], 'w') as f:\n"
                    "    json.dump({'argv': sys.argv[1:]}, f)\n")
    client = binaries / "mariadb"
    client.write_text("#!/bin/sh\necho 12.3.2-MariaDB\n")
    campaign = scripts / "run-perfhint-threshold-campaign.sh"
    campaign.write_text("#!/usr/bin/env python3\nimport json, os\n"
                        "with open(os.environ['CAPTURE_PATH'], 'w') as f:\n"
                        "    json.dump({key: os.environ.get(key) for key in "
                        "['DBMS', 'DATA_ABLATION_PROFILE', 'PERF_REPORT_COST_ANOMALY_RATIO']}, f)\n")
    for executable in (java, client, campaign):
        executable.chmod(0o755)
    jar = tmp_path / "sqlancer.jar"
    jar.touch()
    properties = tmp_path / "ablation.properties"
    properties.write_text("DATA_ABLATION_PROFILE=column-inter\n")
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("PERF_", "QPG_", "SQLANCER_"))}
    environment.pop("COST_THRESHOLD", None)
    environment.update({"PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                        "CAPTURE_PATH": str(captured), "INSTALL_DIR": str(tmp_path),
                        "JAR_PATH": str(jar), "REPORT_ROOT": str(tmp_path / "reports"),
                        "RUN_LOG_DIR": str(tmp_path / "reports/logs"),
                        "CAMPAIGN_LOG_DIR": str(tmp_path / "reports/campaign"),
                        "SQLANCER_LOG_DIR": str(tmp_path / "reports/sqlancer"),
                        "DATA_GENERATOR_MODE": "model-v1", "RUN_SETUP": "0"})
    result = subprocess.run(["bash", str(scripts / "run_mariadb_12_3_2_sqlancer.sh"),
                             "--config", str(properties), command],
                            env=environment, text=True, capture_output=True, timeout=15)
    assert result.returncode == 0, result.stderr
    actual = json.loads(captured.read_text())
    if command in ("smoke", "qpg"):
        args = actual["argv"]
        assert args[args.index("--perf-data-ablation-profile") + 1] == "column-inter"
        assert args[args.index("--perf-report-cost-anomaly-ratio") + 1] == "10.0"
    else:
        assert actual == {"DBMS": "mariadb", "DATA_ABLATION_PROFILE": "column-inter",
                          "PERF_REPORT_COST_ANOMALY_RATIO": "10.0"}
