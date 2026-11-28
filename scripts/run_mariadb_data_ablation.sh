#!/usr/bin/env bash
# Complete Java discovery, schema cleanup and L1/L2/L3 for all five MariaDB profiles.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$repo_root"

exec python3 -u scripts/run_ablation_experiment.py \
    --config configs/mariadb-data-ablation.yaml \
    "$@"
