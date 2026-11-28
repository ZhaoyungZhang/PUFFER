# PDG-IR Offline Stage II Consolidation & Validation

Install the pinned dependencies once:

```sh
python3 -m pip install -r scripts/pdg_validation/requirements.txt
```

The three Stage II entrypoints are separated into composable stages supporting all five target DBMSs (`mysql`, `mariadb`, `tidb`, `cockroachdb`, `oceanbase`):

1. `01_deduplicate.py` extracts DBMS-specific `EXPLAIN` plans into unified directed `pdg-plan-delta/v1` descriptors and performs exact **L1** plan-pair deduplication.
2. `02_cluster.py` runs complete-linkage **L2** plan-delta clustering under weighted Jaccard similarity (default threshold $\tau_{\mathrm{sim}} = 0.70$, via `--threshold` / `--similarity-threshold`) and picks one top-ranked representative per family.
3. `03_scale_validate.py` replays each L2 representative in fresh 1× and 10× sandboxes, reconstructing the scaled database $D_s$ from `pdg_ir.yaml` via PDG-IR, rebuilding indexes, refreshing statistics (`ANALYZE`), and running interleaved timing rounds.

Each DBMS should be processed in its own invocation. The default Stage I admission cells are `A,B,C,D,G`. Every entrypoint supports `--dry-run`, and none mutates source Stage I case directories.

## Example Invocation

Try the bundled self-contained witness cases in [`../../examples/stage2_quickstart/`](../../examples/stage2_quickstart/README.md) first (uses `--dry-run` on L3 so no live DBMS is needed), or point `--input-root` at a live campaign directory:

```sh
python3 scripts/pdg_validation/01_deduplicate.py \
  --dbms mysql --input-root examples/stage2_quickstart/cases --output-dir /tmp/puffer_stage2_demo/l1

python3 scripts/pdg_validation/02_cluster.py \
  --dbms mysql --input-ir /tmp/puffer_stage2_demo/l1/case_deltas.jsonl \
  --output-dir /tmp/puffer_stage2_demo/l2 --threshold 0.70

# Pass --dry-run for offline verification, or set PUFFER_MYSQL_PASSWORD="your_password" without --dry-run:
python3 scripts/pdg_validation/03_scale_validate.py \
  --dbms mysql --representatives /tmp/puffer_stage2_demo/l2/family_representatives.tsv \
  --output-dir /tmp/puffer_stage2_demo/l3 --dry-run
```

Connection profiles are loaded from `configs/puffer-case-investigator-v1.toml` by default (`--config` and `--profile` select another profile). Password environment variables follow `PUFFER_<DBMS>_PASSWORD` (`PUFFER_MYSQL_PASSWORD`, `PUFFER_MARIADB_PASSWORD`, `PUFFER_TIDB_PASSWORD`, `PUFFER_COCKROACHDB_PASSWORD`, `PUFFER_OCEANBASE_PASSWORD`).

## Outputs and Validation Verdicts

- **L1 (`01_deduplicate.py`)**: Writes `case_deltas.jsonl`, exact groups, members, representatives, unsupported cases, and coverage summaries.
- **L2 (`02_cluster.py`)**: Clusters L1 representatives and expands `family_members.tsv` back to every raw member; writes family/member/representative TSVs plus JSON coverage and summary files.
- **L3 (`03_scale_validate.py`)**: Writes `results.tsv`, `summary.json`, and `report.md`, retaining per-case 1×/10× evidence under its output directory.

By default, L3 uses **1 warm-up pass and 5 interleaved measurement rounds** (`--warmups 1 --rounds 5`), a **20 s query timeout** (`--query-timeout-s 20`), **3.0× minimum speedup** (`--min-speedup 3.0`), **5.0 ms minimum scaled absolute gap** (`--scaled-min-gap-ms 5.0`), **2.0× amplification gap multiplier** (`--amplification-gap-multiplier 2.0`), and **80% paired-round direction agreement** (`--min-win-fraction 0.8`).

`AMPLIFIED`, `SUSTAINED`, `ACTIVATED`, `COST_ANOMALY`, `DISAPPEARED`, and `FAILED` are mutually exclusive terminal states:
- Only original-plan slowdown qualifies as harmful; a slower counterfactual does not.
- Stage I cost-rescued classes `B` and `C` always advance directly to 10× scale validation even if 1× runtime is similar (`ACTIVATED` denotes a latent defect that manifests at 10×).
- **Timeout handling (`--scaled-timeout-pass`, enabled by default)**: When the original plan $Q$ times out at $T_{\max} = 20\;\mathrm{s}$ while the counterfactual plan $Q'$ finishes cleanly, L3 records the conservative speedup lower bound $\hat{\rho}_T^{(s)} \ge T_{\max} / T'^{(s)}$ and marks `manual_review_required: true`. When **both** $Q$ and $Q'$ time out (`SCALED_BOTH_PLANS_TIMEOUT_MANUAL_REVIEW`), L3 flags the case for manual inspection; in the paper's Table 7 precision audit, all dual-plan timeout cases are conservatively counted as false positives (`242 / 256 = 94.5%` audited precision).

## Self-Tests

All three entrypoints include built-in offline self-tests requiring no live database server:

```sh
python3 scripts/pdg_validation/01_deduplicate.py --self-test
python3 scripts/pdg_validation/02_cluster.py --self-test
python3 scripts/pdg_validation/03_scale_validate.py --self-test
```
