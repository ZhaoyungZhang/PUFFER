# Stage II Offline Consolidation & Scale-Up Quickstart Sample

This directory provides **4 self-contained Stage I witness cases** (`cases/A/` and `cases/B/`) with concrete `pdg_ir.yaml` specifications (`MONOTONIC_ASC` / `SCALE_DOMAIN`, `ZIPF` / `SCALE_FREQ`, `FUNCTIONAL_DEPENDENCY` / `SCALE_DEP`, and `SKEWED_FANOUT` / `SCALE_FREQ`), 100-row-per-table `setup.sql` snapshots, paired queries, MySQL JSON `EXPLAIN` plans, and `metrics.json` records:

- `cases/A/case_001_bound10` & `cases/A/case_002_bound25`: Two bound-constant variations on `(t0, t1)` sharing an identical relation-scoped directed plan delta (**collapsed into 1 exact L1 group** by `01_deduplicate.py`).
- `cases/A/case_003_three_table`: A 3-table extension `(t0, t1, t2)` exhibiting the same schema-agnostic plan delta $\Delta(P_{\mathrm{d}}, P')$ (**kept distinct in L1, then merged into 1 L2 family** at `--threshold 0.70` by `02_cluster.py`).
- `cases/B/case_004_cost_rescued`: A Class `B` cost-rescued candidate ($\rho_C = 38.4$, $\rho_T = 1.15$) kept in its own Stage I class partition (`1` L2 family in Cell `B`).

## Run End-to-End Offline Demo (No Live DBMS Required)

From the repository root:

```bash
OUT_DIR="/tmp/puffer_stage2_demo"
rm -rf "$OUT_DIR"

python3 scripts/pdg_validation/01_deduplicate.py \
  --dbms mysql --input-root examples/stage2_quickstart/cases --output-dir "$OUT_DIR/l1"

python3 scripts/pdg_validation/02_cluster.py \
  --dbms mysql --input-ir "$OUT_DIR/l1/case_deltas.jsonl" --output-dir "$OUT_DIR/l2" --threshold 0.70

python3 scripts/pdg_validation/03_scale_validate.py \
  --dbms mysql --representatives "$OUT_DIR/l2/family_representatives.tsv" \
  --output-dir "$OUT_DIR/l3" --config configs/puffer-case-investigator-v1.toml \
  --profile mysql-local --scale 10 --dry-run
```

Expected funnel: **4 raw Stage I cases -> 3 exact L1 groups -> 2 L2 family representatives** (`mysql-A-L2-00001` and `mysql-B-L2-00002`). Omit `--dry-run` and set `PUFFER_MYSQL_PASSWORD` when attached to a live MySQL 9.7.1 server to execute full 1× and 10× PDG-IR scale-up replay.
