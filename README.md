# PUFFER: Small Data, Big Slowdowns — Data-Aware Triggering and Detection of Optimizer-Induced Performance Issues in DBMSs

**PUFFER** is an automated black-box testing framework for triggering and detecting optimizer-induced performance issues (performance bugs) in relational and distributed database management systems (DBMSs). Built on top of [SQLancer](https://github.com/sqlancer/sqlancer), PUFFER integrates **data-aware test-case generation** with a **two-stage performance oracle** bridged by a **Parameterized Data Generation Intermediate Representation (PDG-IR)**.

Across five widely used production DBMSs (**MySQL**, **MariaDB**, **TiDB**, **CockroachDB**, and **OceanBase**), PUFFER has uncovered **56 optimizer-induced performance issues**, of which **52 have been confirmed or fixed (92.9%)** by official developers (**51 confirmed, 1 fixed**, and 4 awaiting triage in OceanBase). These findings demonstrate that severe optimizer defects—reaching up to **43,083×** slowdowns with an overall median slowdown of **69.9×** on a median validation database size of **17,500 rows** (**44.6%** reproduced on ≤ 10,000 rows, **81.5%** exceeding 10×, **40.7%** exceeding 100×, and **16.7%** exceeding 1,000×)—can be rapidly triggered on compact database instances (100–1,000 rows per table at exploration scale $s_0 = 1$) and validated via selective PDG-IR scale-up.

---

## Key Contributions & Architecture

### 1. Data-Aware Test-Case Generation (Section 4)

- **Multi-Granularity Database Generation**: Co-designs schema, indexes, and data distributions on compact instances (100–1,000 rows per table at exploration scale $s_0 = 1$) across three granularities while enforcing a *unique-producer invariant* over a column dependency DAG $G_{\mathrm{dep}}$:
  - *Single-Column Distributions*: `UNIFORM`, `GAUSSIAN`, `ZIPF`, `LOW_NDV`, `MONOTONIC` (`MONOTONIC_ASC` / `MONOTONIC_DESC`), `NULL_HEAVY`, and `POINT_SPIKE`.
  - *Intra-Table Dependencies*: `FUNC_MAP` ($B = g(A)$, implemented via `FUNCTIONAL_DEPENDENCY` / `ANTI_CORRELATION`), `RANK_COUPLE` (rank correlation $r_s$ with unchanged marginals), and `COND_PATTERN` (conditional trigger-target patterns).
  - *Inter-Table Relationships*: `PARENT_CHILD` ($1:1, 1:f$, implemented via `JOIN_FANOUT:ONE_TO_ONE` / `ONE_TO_MANY`), `MANY_TO_MANY` (`JOIN_FANOUT:MANY_TO_MANY`), `PARTIAL_REF` (overlap fraction $\eta \in (0,1)$, implemented via `REFERENTIAL_CORRELATION`), `SKEWED_FANOUT` (shared hot keys with Zipf skew $\alpha$), and `DISJOINT_RANGE` (shifted near-disjoint key domains).
- **Parameterized Data Generation IR (PDG-IR) & Scaling Policies**:
  - Separates generation semantics $\mathcal{G} = (\mathcal{S}, \mathcal{M}, \mathcal{R}, \boldsymbol{\pi})$ from materialized rows $D_s = \mathrm{Instantiate}(\mathcal{G}, s)$ (`pdg_ir.yaml`), enabling rapid compact exploration at $s_0 = 1$ and deterministic, distribution-preserving reconstruction at larger scales ($s = 10$).
  - *PDG-IR Scaling Policies*:
    - `SCALE_FREQ`: Scales per-key frequencies linearly ($f \propto s$) over a fixed domain and distribution shape ($J_s \simeq s^d J_1$ for $d$-way equality joins).
    - `SCALE_DOMAIN`: Expands key domains or ranges linearly ($\propto s$), preserving per-key fanout $f$ in `PARENT_CHILD` ($J_s \propto s$) or relative range gaps in `DISJOINT_RANGE`.
    - `SCALE_DEP`: Reapplies functional maps $g(\cdot)$, rank couplings $r_s$, or conditional trigger patterns across $s n$ rows.
    - `SCALE_OVERLAP`: Scales matched and unmatched key pools proportionally ($\propto s$) to preserve overlap fraction $\eta$.
- **Data-Aware Query & One-Step Equivalent Variant Generation**:
  - *Zero-Query In-Memory Profiling*: Extracts an in-memory profile $\Phi_D$ (value histograms $h_c$, CDFs $F_c$, and sorted per-key frequencies $f_X(k)$) in a single $O(|D|)$ pass without issuing DBMS queries.
  - *Data-Aware Predicate Binding*: Samples shape-aware target bands $I = [l, u]$ ($[10, 40{,}000]$ intermediate rows for result-collapsing queries with `DISTINCT`, `GROUP BY`, aggregates, or `LIMIT`; $[10, \min(8{,}000, \max(3{,}000, 8n_0))]$ for plain `SELECT` queries), builds candidate pools $\Theta_j$ ($|\Theta_j| \le 6$) from heavy hitters/quantiles or `FUNC_MAP` projections $g(a)$, and executes a 2-pass bounded coordinate search over materialization-free post-filter join cardinality estimates $m_B(\boldsymbol{\theta})$. Applied with probability $p_{\mathrm{bind}} = 0.5$ across $N = 64$ base queries per database.
  - *One-Step Equivalent Transformations*: Generates up to $M = 16$ equivalent variants $Q'_{i,j} \equiv Q_i$ per base query within the same DBMS, each applying one localized intervention: either a **DBMS-native optimizer hint** (join ordering: `JOIN_ORDER`, `JOIN_PREFIX`/`SUFFIX`, `STRAIGHT_JOIN`; join methods: `HASH_JOIN`, `BKA`, `NO_BNL`, `MERGE_JOIN`, `INL_JOIN`; access paths: `INDEX`/`NO_INDEX`, `ORDER_INDEX`, `GROUP_INDEX`, `INDEX_MERGE`) or a **one-step semantics-preserving query rewrite** (`maxRewriteSteps=1`) from **650 SMT-verified WeTune static equivalence rules** after rendering schema constraints (`PRIMARY KEY`, `UNIQUE`, `NOT NULL`) into WeTune-compatible DDL.

### 2. Two-Stage Performance Oracle (Section 5)

- **Stage I: Cost–Runtime Screening**:
  - Invokes `EXPLAIN` on $(Q, Q')$ to obtain $(P_{\mathrm{d}}, C_{\mathrm{d}})$ and $(P', C')$, skipping execution immediately when normalized physical plans match ($\mathrm{Norm}(P_{\mathrm{d}}) = \mathrm{Norm}(P')$).
  - Otherwise executes $(Q, Q')$ on the compact instance $D$ (with a 5 s Stage I query timeout) to compute estimated-cost ratio $\rho_C = C_{\mathrm{d}} / C'$ and measured runtime ratio $\rho_T = T_{\mathrm{d}} / T'$, mapping $(\rho_C, \rho_T)$ onto a **$3 \times 3$ cost–runtime classification grid** with similarity bands $(\tau_C, \tau_T) = (1.1, 1.3)$.
  - Admits candidates across five active diagnostic classes using permissive admission gates:
    - *Runtime-Gap Classes* (**A**: missed better plan, **D**: cost blind spot, **G**: cost inversion) admitted when $\rho_T \ge \theta_T$ ($\theta_T = 3$), **without an absolute-time floor** so sub-millisecond compact slowdowns are retained.
    - *Cost-Rescued Classes* (**A**, **B**: overhead-masked, **C**: startup crossover) admitted when $\rho_C \ge \theta_C$ ($\theta_C = 10$), rescuing optimizer-preferred plans whose runtime savings are masked on compact data.
    - Remaining cells (**E, F, H, I**) are discarded at zero Stage II cost.
- **Stage II: Deduplication and Targeted Validation**:
  - *L1 Plan-Pair Deduplication*: Encodes `EXPLAIN` plans into normalized physical operator trees $\mathrm{Norm}(\cdot)$ (retaining operator type, access path, join algorithm, structural flags, and descendant base-relation scope while stripping literals, aliases, and cost/row estimates) and collapses candidates per Stage I class sharing an identical relation-scoped directed plan difference from $\mathrm{Norm}(P_{\mathrm{d}})$ to $\mathrm{Norm}(P')$.
  - *L2 Plan-Delta Clustering*: Strips concrete relation scopes and index names to form the schema-agnostic plan delta $\Delta(P_{\mathrm{d}}, P') = (\mathcal{O}(P_{\mathrm{d}}) \setminus \mathcal{O}(P'), \mathcal{O}(P') \setminus \mathcal{O}(P_{\mathrm{d}}), \delta_{\mathrm{ord}}(P_{\mathrm{d}}, P'))$, clustering candidates per Stage I class via complete-linkage weighted Jaccard similarity at threshold $\tau_{\mathrm{sim}} = 0.70$ into **L2 families** and selecting one top-ranked representative per family.
  - *L3 Repeated Execution and Selective Scale-Up*: Evaluates each L2 representative using **1 warm-up pass + $R = 5$ interleaved replay trials** $(Q, Q', \dots)$ at $1\times$ (filtering transient $1\times$ jitter on runtime-admitted candidates while routing cost-rescued candidates directly to scale-up), then selectively reconstructs $D_s$ at scale $s = 10$ via PDG-IR, rebuilds indexes, runs `ANALYZE`, and repeats interleaved replay under timeout guard $T_{\max} = 20\;\mathrm{s}$. Using validation thresholds $\tau_{\mathrm{val}} = 3$, amplification factor $\gamma = 2$, and minimum scaled absolute gap $\delta_{\min} = 5\;\mathrm{ms}$, L3 emits validated **Potential Performance Issues (PPIs)** with verdicts `Amplified`, `Activated`, or `Sustained`.

---

## Repository Structure

```text
PUFFER/
├── pom.xml                                  # Maven build configuration (Java 11 core)
├── README.md                                # Project documentation, setup guide, and 56-bug catalog
├── LICENSE.md                               # MIT License
├── configs/                                 # Campaign .properties profiles, ablation YAML configs, & connection TOML
│   └── puffer-case-investigator-v1.toml     # Stage II / investigator DBMS connection profiles
├── src/sqlancer/                            # Core Java source tree (built upon SQLancer)
│   ├── common/perfhint/                     # [PUFFER Core] Cost-runtime 3x3 classifier, multi-granularity planner,
│   │   │                                    #   data-aware predicate binder, hint/rewrite scheduler, WeTune bridge
│   │   └── pdg/                             # [PUFFER Core] PDG-IR specification, planner, materializer, YAML codec
│   ├── mysql/                               # [PUFFER Target] MySQL PERFORMANCE_HINT oracle & data generator
│   ├── mariadb/                             # [PUFFER Target] MariaDB PERFORMANCE_HINT oracle & data generator
│   ├── tidb/                                # [PUFFER Target] TiDB PERFORMANCE_HINT oracle & data generator
│   ├── cockroachdb/                         # [PUFFER Target] CockroachDB PERFORMANCE_HINT oracle & data generator
│   └── oceanbase/                           # [PUFFER Target] OceanBase PERFORMANCE_HINT oracle & data generator
├── puffer_case_investigator/                # Core Python DBMS adapters, plan parsers & replay utilities for Stage II
├── test/                                    # Java & Python unit tests (PDG-IR, WeTune protocol, Stage II validator)
│   ├── sqlancer/                            # JUnit tests for PDG-IR, predicate binding, and cost-runtime classification
│   ├── pdg_validation/                      # Python unit tests for Stage II L1/L2/L3 & ablation runner
│   └── puffer_case_investigator/            # Python unit tests for Stage II DB adapters, case parser & runtime utils
├── tools/
│   └── wetune-static-worker/                # [PUFFER Rewrite Worker] Java 17 Gradle worker for 650 WeTune static rules
├── third_party/
│   └── wetune/                              # Prebuilt WeTune core JARs (lib/) & static rule bank (rules.txt)
├── scripts/                                 # Campaign runners, ablation drivers, and Stage II L1/L2/L3 validator
│   ├── pdg_validation/                      # [PUFFER Stage II] 01_deduplicate.py (L1), 02_cluster.py (L2),
│   │                                        #   03_scale_validate.py (L3), and PDG-IR scale-up engine (_pdg.py)
│   ├── run_ablation_experiment.py           # End-to-end online + offline (L1/L2/L3) ablation experiment driver
│   ├── perfhint_properties.sh               # Shared properties loader & WeTune worker preflight checker
│   ├── run-perfhint-threshold-campaign.sh   # Master threshold campaign orchestrator
│   └── run_*_sqlancer.sh                    # Per-DBMS campaign wrapper scripts
├── examples/
│   └── stage2_quickstart/                   # Self-contained Stage I witness cases for instant Stage II L1/L2/L3 dry-run
└── experiments_data/                        # Experimental artifacts, datasets, and bug reproductions
    ├── bug_reports/                         # [RQ1] Machine-readable summary CSVs & 56 official issue tracker links
    ├── baseline_comparison/                 # [RQ2] 8-hour end-to-end comparison data against 5 SOTA baselines
    └── ablation_study/                      # [RQ3 & RQ4] Data-aware generation & two-stage oracle ablation data
```

---

## Prerequisites & Setup

### 1. System Requirements
- **Java 11+** (for building and running the main PUFFER / SQLancer JAR via Maven)
- **Java 17+** (required when enabling the `wetune-static` query rewrite worker)
- **Apache Maven 3.6+**
- **Python 3.11+** (or **Python 3.9+** with `pip install -r scripts/pdg_validation/requirements.txt`, for Stage II offline L1/L2/L3 scripts in `scripts/pdg_validation/`)
- Target DBMS instances: **MySQL 9.7.1**, **MariaDB 12.3.2**, **TiDB v8.5.0**, **CockroachDB v26.2.2**, or **OceanBase v4.5.0.0**

### 2. Building the Core PUFFER Engine

From the repository root, compile and package the fat JAR using Maven:

```bash
mvn clean package -DskipTests
```

This produces `target/sqlancer-2.0.0.jar` and copies runtime dependencies into `target/lib/`.

### 3. Building the WeTune Static Rewrite Worker (`tools/wetune-static-worker`)

PUFFER integrates WeTune's static bank of 650 SMT-verified equivalence rules through an isolated Java 17 subprocess (`tools/wetune-static-worker`) communicating over JSONL.

To make the repository **100% self-contained and immune to external GitLab network issues**, we have committed the 5 prebuilt WeTune core JARs (`common-0.1.jar`, `sql-0.1.jar`, `stmt-0.1.jar`, `spes-0.1.jar`, `superopt-0.1.jar`, plus `z3-4.8.9.jar`, ~3.4 MB total) directly in [`third_party/wetune/lib/`](third_party/wetune/lib) along with the rule bank [`third_party/wetune/wtune_data/prepared/rules.txt`](third_party/wetune/wtune_data/prepared/rules.txt).

Build and verify the worker in one step (requires **Java 17+**):

```bash
cd tools/wetune-static-worker
./gradlew --no-daemon installDist

# Smoke test the worker (should output a JSONL "status":"ready" message and exit 0)
./build/install/wetune-static-worker/bin/wetune-static-worker < /dev/null
cd ../..
```

*(See [`third_party/wetune/README.md`](third_party/wetune/README.md) for upstream provenance and license details of the bundled WeTune Apache-2.0 JARs, Z3 MIT JAR, and 650-rule bank.)*

---

## Running PUFFER

### Option A: Using Campaign Wrapper Scripts (Recommended)

The campaign wrapper scripts under [`scripts/`](scripts/) and [`configs/`](configs/) automatically manage DBMS connection settings, WeTune worker preflight checks, and Stage I candidate snapshots:

```bash
# Quick single-seed smoke test against a running MySQL 9.7.1 instance:
./scripts/run_mysql_9_7_1_sqlancer.sh --config configs/mysql-9.7.1-qpg-perfhint-optimistic.properties smoke

# Full QPG-guided campaign on MySQL 9.7.1 or MariaDB 12.3.2 (with WeTune static rewrites):
./scripts/run_mysql_9_7_1_sqlancer.sh --config configs/mysql-9.7.1-qpg-perfhint-optimistic.properties qpg-campaign
./scripts/run_mariadb_12_3_2_sqlancer.sh --config configs/mariadb-12.3.2-qpg-perfhint-wetune-1w.properties qpg-campaign

# Full QPG-guided campaigns on TiDB v8.5.0, CockroachDB v26.2.2, or OceanBase v4.5.0.0:
./scripts/run_tidb_8_5_0_sqlancer.sh --config configs/tidb-8.5.0-qpg-perfhint-wetune-1w.properties qpg-campaign
./scripts/run_cockroachdb_26_2_sqlancer.sh --config configs/cockroachdb-26.2-qpg-perfhint-wetune-1w.properties qpg-campaign
./scripts/run_oceanbase_4_5_sqlancer.sh --config configs/oceanbase-4.5.0-qpg-perfhint-wetune-1w.properties qpg-campaign
```

### Option B: Direct Java CLI Invocation

You can also invoke `target/sqlancer-2.0.0.jar` directly with the `PERFORMANCE_HINT` oracle using the paper's default parameters (100–1,000 initial rows per table at $s_0 = 1$, $N = 64$ base queries per database, predicate binding probability $p_{\mathrm{bind}} = 0.5$, Stage I similarity bands $(\tau_C, \tau_T) = (1.1, 1.3)$, admission gates $(\theta_T, \theta_C) = (3.0, 10.0)$, and a 5 s Stage I query timeout; the `0.2 ms` / `0.5 ms` parameters in `configs/*.yaml` and `configs/*.properties` act solely as a sub-millisecond clock-jitter/divide-by-zero guard rather than the 5 ms+ absolute runtime floors used by compact runtime-only oracles (Section 6.5)):

```bash
java -jar target/sqlancer-2.0.0.jar \
  --host 127.0.0.1 --port 3306 --username root --password "" \
  --num-threads 4 --num-queries 64 --timeout-seconds 3600 \
  mysql \
  --oracle PERFORMANCE_HINT \
  --perf-data-generator-mode model-v1 \
  --perf-data-ablation-profile full \
  --perf-min-rows-per-table 100 \
  --perf-max-rows-per-table 1000 \
  --perf-initial-scale 1 \
  --perf-query-generator-mode mixed \
  --perf-predicate-binding-probability 0.5 \
  --perf-query-rewrite-provider wetune-static \
  --perf-wetune-static-worker "$(pwd)/tools/wetune-static-worker/build/install/wetune-static-worker/bin/wetune-static-worker" \
  --perf-counterfactual-hint-probability 0.5 \
  --perf-timeout-seconds 5 \
  --perf-taxonomy-cost-ratio-lower-bound 0.9 \
  --perf-taxonomy-cost-ratio-upper-bound 1.1 \
  --perf-taxonomy-runtime-ratio-lower-bound 0.9 \
  --perf-taxonomy-runtime-ratio-upper-bound 1.3 \
  --perf-report-harmful-runtime-ratio 3.0 \
  --perf-report-cost-anomaly-ratio 10.0
```

### Stage II: Running Offline L1 Deduplication, L2 Clustering, and L3 PDG-IR Scale-Up Validation

After Stage I outputs candidate cases (each containing `setup.sql`, `pdg_ir.yaml`, `original_query.sql`, `counterfactual_query.sql`, `default_explain.json`/`.txt`, `counterfactual_explain.json`/`.txt`, and `metrics.json`), run the Stage II consolidation and validation pipeline in [`scripts/pdg_validation/`](scripts/pdg_validation/).

You can test the Stage II pipeline immediately without a running DBMS using the bundled witness cases in [`examples/stage2_quickstart/cases`](examples/stage2_quickstart/cases) (pass `--dry-run` on Step 3 when no live MySQL instance is attached, or omit `--dry-run` with `PUFFER_MYSQL_PASSWORD` set to execute live 1x/10x validation):

```bash
STAGE1_CASES_DIR="examples/stage2_quickstart/cases"
STAGE2_OUT_DIR="/tmp/puffer_stage2_demo"
rm -rf "$STAGE2_OUT_DIR"

# Step 1 (L1): Relation-scoped directed plan-pair deduplication
python3 scripts/pdg_validation/01_deduplicate.py \
  --input-root "$STAGE1_CASES_DIR" \
  --dbms mysql \
  --output-dir "$STAGE2_OUT_DIR/l1"

# Step 2 (L2): Schema-agnostic plan-delta clustering and representative selection (tau_sim = 0.70)
python3 scripts/pdg_validation/02_cluster.py \
  --input-ir "$STAGE2_OUT_DIR/l1/case_deltas.jsonl" \
  --dbms mysql \
  --output-dir "$STAGE2_OUT_DIR/l2" \
  --threshold 0.70

# Step 3 (L3): Interleaved repeated execution (1x) and PDG-IR scale-up validation (s = 10)
# (Add --dry-run to verify inputs and emit manifest without connecting to a live DBMS)
python3 scripts/pdg_validation/03_scale_validate.py \
  --representatives "$STAGE2_OUT_DIR/l2/family_representatives.tsv" \
  --dbms mysql \
  --output-dir "$STAGE2_OUT_DIR/l3" \
  --config configs/puffer-case-investigator-v1.toml \
  --profile mysql-local \
  --scale 10 \
  --warmups 1 \
  --rounds 5 \
  --query-timeout-s 20 \
  --min-speedup 3.0 \
  --amplification-gap-multiplier 2.0 \
  --scaled-min-gap-ms 5.0 \
  --dry-run
```

Or run the unified online + offline ablation driver:

```bash
python3 scripts/run_ablation_experiment.py --config configs/mysql-data-ablation.yaml
```

---

## Experimental Data Directory (`experiments_data/`)

All paper artifacts and experimental datasets corresponding to Section 6 (RQ1–RQ4) are organized under [`experiments_data/`](experiments_data/):

1. **[`experiments_data/bug_reports/`](experiments_data/bug_reports/) (RQ1: Real-World Issue Detection)**:
   Contains machine-readable summary CSV tables (`issue_data_volumes.csv`, `issue_component_counts.csv`) and direct links to all **56 public official issue tracker reports** (52 confirmed or fixed, 92.9%) across MySQL, MariaDB, TiDB, CockroachDB, and OceanBase, where self-contained reproduction SQL, paired plans, and developer confirmations are hosted (corresponding to Tables 2–4 and Figure 6 in the paper).
2. **[`experiments_data/baseline_comparison/`](experiments_data/baseline_comparison/) (RQ2: Comparison with Existing Techniques)**:
   Contains 8-hour end-to-end evaluation datasets, cross-tool root-cause overlap audits (71 distinct issue patterns), discovery timelines, slowdown distributions, and optimizer/execution code-coverage traces comparing PUFFER against five state-of-the-art baselines: AMOEBA, CERT, PUPPY, HULK, and SCor across MySQL, MariaDB, TiDB, and CockroachDB (corresponding to Table 5 and Figure 7 in the paper).
3. **[`experiments_data/ablation_study/`](experiments_data/ablation_study/) (RQ3 & RQ4: Data-Aware Generation & Two-Stage Oracle Ablations)**:
   Contains experimental data for the multi-granularity database generation ablation (`Random`, `Column`, `+Intra`, `+Inter`, `Full`), predicate-binding probability sensitivity ($p_{\mathrm{bind}} \in \{0.0, 0.25, 0.5, 0.75, 1.0\}$), compact exploration + selective scale-up (`Selective` vs. `Large-only`), Stage I joint cost–runtime screening vs. single-signal oracles, Stage II L1/L2/L3 consolidation and validation trade-offs, and transferring Stage II to external tools (corresponding to Tables 6–8 and Figures 8–9 in the paper).

---

## Detected Performance Issues (56-Bug Catalog)

PUFFER uncovered **56 optimizer-induced performance issues** across five production DBMSs, of which **52 have been confirmed or fixed (92.9%)** (**51 confirmed, 1 fixed**) by official developers.

### Summary Table

| DBMS | Tested Version | Reported | Confirmed / Fixed | Confirmed | Fixed | Submitted | LO | CE | CM | PO | Median Size (Rows) | Median Slowdown |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **MySQL** | 9.7.1 | 15 | 15 | 15 | 0 | 0 | 3 | 3 | 2 | 7 | 3,164 | **82.2×** |
| **MariaDB** | 12.3.2 | 10 | 10 | 10 | 0 | 0 | 3 | 1 | 5 | 1 | 21,450 | **36.1×** |
| **TiDB** | v8.5.0 | 18 | 18 | 17 | 1 | 0 | 8 | 1 | 5 | 4 | 14,570 | **90.7×** |
| **CockroachDB** | v26.2.2 | 7 | 7 | 7 | 0 | 0 | 5 | 1 | 0 | 1 | 18,000 | **67.8×** |
| **OceanBase** | v4.5.0.0 | 6 | 2 | 2 | 0 | 4 | 5 | 1 | 0 | 0 | 20,000 | **82.9×** |
| **Total / Overall** | — | **56** | **52 (92.9%)** | **51** | **1** | **4** | **24** | **7** | **12** | **13** | **17,500** | **69.9×** |

*(Optimizer Components: **LO** = Logical Optimization, **CE** = Cardinality Estimation, **CM** = Cost Modeling, **PO** = Physical Optimization. Median slowdowns are computed over the 54 issues with measured paired execution times, excluding the two pure cardinality/statistics defects MySQL `#120902` and TiDB `#69931`.)*

---

### 1. MySQL (15 Reported, 15 Confirmed)

| # | Bug ID & Link | Title | Variant | Instance Size (Rows) | Slowdown | Status |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | [MySQL #120772](https://bugs.mysql.com/bug.php?id=120772) | UPDATE HISTOGRAM causes optimizer to use stale cardinalities and miss faster join order | Hint | 338 | **65.9×** | Confirmed |
| 2 | [MySQL #120773](https://bugs.mysql.com/bug.php?id=120773) | Hash join misses DISTINCT ORDER BY LIMIT early termination | Hint | 157 | **537.5×** | Confirmed |
| 3 | [MySQL #120793](https://bugs.mysql.com/bug.php?id=120793) | Default heuristic pruning misses join-connected prefix in 3-table query, causing ~40x slower plan | Hint | 7,648 | **2,907.0×** | Confirmed |
| 4 | [MySQL #120797](https://bugs.mysql.com/bug.php?id=120797) | Poor plan for DISTINCT Cartesian product: hash join materializes full product while NO_BNL is about 50x faster | Hint | 321 | **50.4×** | Confirmed |
| 5 | [MySQL #120809](https://bugs.mysql.com/bug.php?id=120809) | Optimizer misses DISTINCT early-out and chooses 40M-row hash join Cartesian product | Hint | 22,000 | **141.9×** | Confirmed |
| 6 | [MySQL #120830](https://bugs.mysql.com/bug.php?id=120830) | Optimizer chooses 2800x slower join order despite lower-cost JOIN_PREFIX plan | Hint | 7,648 | **2,879.8×** | Confirmed |
| 7 | [MySQL #120850](https://bugs.mysql.com/bug.php?id=120850) | Optimizer misses impossible INNER JOIN condition with IS NULL and normal equality | Hint | 104,000 | **5.8×** | Confirmed |
| 8 | [MySQL #120861](https://bugs.mysql.com/bug.php?id=120861) | SELECT DISTINCT inner join expands duplicate-insensitive fanout instead of using semijoin/existence semantics | Rewrite | 410 | **14.3×** | Confirmed |
| 9 | [MySQL #120868](https://bugs.mysql.com/bug.php?id=120868) | Optimizer misses ORDER BY LIMIT optimization when ordering key is equivalent through join equality | Rewrite | 120,000 | **20.9×** | Confirmed |
| 10 | [MySQL #120869](https://bugs.mysql.com/bug.php?id=120869) | Optimizer chooses 26x slower join order due to fanout misestimation despite histograms | Hint | 1,320,015 | **26.3×** | Confirmed |
| 11 | [MySQL #120870](https://bugs.mysql.com/bug.php?id=120870) | Hypergraph misses BETWEEN equality, causing 100x slowdown | Rewrite | 18,000 | **98.5×** | Confirmed |
| 12 | [MySQL #120902](https://bugs.mysql.com/bug.php?id=120902) | Hash join AccessPath double-applies probe-side condition filtering | Hint | 234 | *Plan/Cost* | Confirmed |
| 13 | [MySQL #121266](https://bugs.mysql.com/bug.php?id=121266) | Optimizer misses cheaper join order when range predicate is only available through join equality | Rewrite | 3,164 | **124.6×** | Confirmed |
| 14 | [MySQL #121271](https://bugs.mysql.com/bug.php?id=121271) | Equivalent range rewrite exposes ORDER BY cost inversion and slower access path | Rewrite | 3,064 | **2.85×** | Confirmed |
| 15 | [MySQL #121401](https://bugs.mysql.com/bug.php?id=121401) | Optimizer join-order / access-path cost misestimation on compact multi-table join | Hint | 3,000 | **230.0×** | Confirmed |

---

### 2. MariaDB (10 Reported, 10 Confirmed)

| # | Issue ID & Link | Title | Variant | Instance Size (Rows) | Slowdown | Status |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | [MDEV-40238](https://jira.mariadb.org/browse/MDEV-40238) | SELECT DISTINCT inner join is not optimized as semijoin, causing duplicate-insensitive fanout expansion | Rewrite | 7,640 | **15.0×** | Confirmed |
| 2 | [MDEV-40241](https://jira.mariadb.org/browse/MDEV-40241) | Optimizer misses impossible INNER JOIN condition with IS NULL and normal equality | Hint | 104,000 | **4.3×** | Confirmed |
| 3 | [MDEV-40261](https://jira.mariadb.org/browse/MDEV-40261) | Cost model chooses much slower lower-cost join order over faster higher-cost plans when optimizer_prune_level=0 | Hint | 17,000 | **2,193.0×** | Confirmed |
| 4 | [MDEV-40262](https://jira.mariadb.org/browse/MDEV-40262) | Cost model chooses slower join order for GROUP BY/ORDER BY LIMIT query with highly selective predicate on joined table | Hint | 400,000 | **2.5×** | Confirmed |
| 5 | [MDEV-40263](https://jira.mariadb.org/browse/MDEV-40263) | Default join_cache_level=2 misses ORDER BY LIMIT early-stop plan; range-checked plan does 8800 full scans | Hint | 25,900 | **8,007.1×** | Confirmed |
| 6 | [MDEV-40264](https://jira.mariadb.org/browse/MDEV-40264) | ORDER BY LIMIT cost model misses faster join order when ordering key is equivalent through join equality | Hint | 120,000 | **40.0×** | Confirmed |
| 7 | [MDEV-40269](https://jira.mariadb.org/browse/MDEV-40269) | Optimizer chooses 30x slower join order due to equality-join fanout misestimation | Hint | 1,320,015 | **32.1×** | Confirmed |
| 8 | [MDEV-40273](https://jira.mariadb.org/browse/MDEV-40273) | Optimizer misses equality from x >= y AND x <= y join predicate | Rewrite | 3,000 | **3,642.6×** | Confirmed |
| 9 | [MDEV-40370](https://jira.mariadb.org/browse/MDEV-40370) | optimizer_join_limit_pref_ratio applies shortcut cost to BNL plan that still requires full join and filesort | Hint | 320 | **212.7×** | Confirmed |
| 10 | [MDEV-41159](https://jira.mariadb.org/browse/MDEV-41159) | Equivalent query rewrite reveals missed join / predicate optimization opportunity | Rewrite | 5,341 | **8.1×** | Confirmed |

---

### 3. TiDB (18 Reported, 17 Confirmed, 1 Fixed)

| # | Issue ID & Link | Title | Variant | Instance Size (Rows) | Slowdown | Status |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | [TiDB #69556](https://github.com/pingcap/tidb/issues/69556) | planner: HashJoin plan is estimated 325x more costly but runs 3x faster than the chosen MergeJoin under LIMIT | Hint | 512 | **3.0×** | Confirmed |
| 2 | [TiDB #69564](https://github.com/pingcap/tidb/issues/69564) | planner: similar estimated cost but 3x runtime gap for HashJoin join orders under TopN | Hint | 3,430 | **3.4×** | Confirmed |
| 3 | [TiDB #69915](https://github.com/pingcap/tidb/issues/69915) | planner: unreferenced Cartesian inputs prevent DISTINCT ORDER BY LIMIT early termination | Rewrite | 100,020 | **485.7×** | Confirmed |
| 4 | [TiDB #69917](https://github.com/pingcap/tidb/issues/69917) | planner: plain DISTINCT materializes unreferenced Cartesian inputs instead of using existence checks | Rewrite | 321 | **90.7×** | Confirmed |
| 5 | [TiDB #69918](https://github.com/pingcap/tidb/issues/69918) | planner: DISTINCT materializes all predicate matches instead of using per-row semijoin/FirstMatch | Rewrite | 22,000 | **1.6×** | Confirmed |
| 6 | [TiDB #69922](https://github.com/pingcap/tidb/issues/69922) | planner: contradictory IS NULL and derived IS NOT NULL predicates are not folded to TableDual | Rewrite | 1,200,000 | **43,083.0×** | Confirmed |
| 7 | [TiDB #69931](https://github.com/pingcap/tidb/issues/69931) | statistics: ANALYZE shortly after committed bulk INSERT can publish incomplete statistics as healthy | Hint | 1,200,000 | *Stats Bug* | Confirmed |
| 8 | [TiDB #69932](https://github.com/pingcap/tidb/issues/69932) | planner: DISTINCT equality join expands duplicate matches instead of using an available Merge Semi Join | Rewrite | 6,906 | **12.9×** | Confirmed |
| 9 | [TiDB #69933](https://github.com/pingcap/tidb/issues/69933) | planner: NOT (x <> y) is not normalized to an equi-join key, causing a Cartesian join and about 247x slowdown | Rewrite | 18,000 | **301.7×** | **Fixed** |
| 10 | [TiDB #69935](https://github.com/pingcap/tidb/issues/69935) | planner: x BETWEEN y AND y and x >= y AND x <= y cause large performance regression due to missing equi-join extraction | Rewrite | 18,000 | **273.5×** | Confirmed |
| 11 | [TiDB #69950](https://github.com/pingcap/tidb/issues/69950) | planner: ORDER BY on an equivalent join key misses LIMIT early stop and causes a ~120x slowdown | Rewrite | 120,000 | **120.5×** | Confirmed |
| 12 | [TiDB #69963](https://github.com/pingcap/tidb/issues/69963) | planner: LIMIT cost model chooses a 42x slower HashJoin build side for a Cartesian join with a cross-table residual predicate | Hint | 6,741 | **41.9×** | Confirmed |
| 13 | [TiDB #69989](https://github.com/pingcap/tidb/issues/69989) | planner: A MergeJoin estimated 13.7x more expensive runs 20.8x faster for LEFT JOIN with LIMIT 1 | Hint | 8,998 | **20.8×** | Confirmed |
| 14 | [TiDB #71319](https://github.com/pingcap/tidb/issues/71319) | 3-table pre-join sort regression (`3.14 s` vs `437.0 ms`) | Rewrite | 5,341 | **7.2×** | Confirmed |
| 15 | [TiDB #73610](https://github.com/pingcap/tidb/issues/73610) | planner: optimizer drops lower-cost ordered join plan under ORDER BY LIMIT | Hint | 6,300 | **95.4×** | Confirmed |
| 16 | [TiDB #73755](https://github.com/pingcap/tidb/issues/73755) | planner: greedy join reorder expands 25.7M rows before a selective join; DP is 225x faster on three small tables | Hint | 26,893 | **314.7×** | Confirmed |
| 17 | [TiDB #73756](https://github.com/pingcap/tidb/issues/73756) | planner: TopN(1) has zero comparison CPU cost, favoring a 2.6M-row HashJoin over a faster MIN early-stop plan | Hint | 11,140 | **28.1×** | Confirmed |
| 18 | [TiDB #73785](https://github.com/pingcap/tidb/issues/73785) | planner: impossible NULLIF(0, expr) filter is not folded to TableDual, executing a 12.5M-pair join | Rewrite | 20,000 | **1,294.1×** | Confirmed |

---

### 4. CockroachDB (7 Reported, 7 Confirmed)

| # | Issue ID & Link | Title | Variant | Instance Size (Rows) | Slowdown | Status |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | [CRDB-65623 / #172387](https://github.com/cockroachdb/cockroach/issues/172387) | sql/opt: optimizer misses early-stop plan for DISTINCT ORDER BY LIMIT over CROSS JOIN | Rewrite | 1,600 | **4,359.1×** | Confirmed |
| 2 | [CRDB-65625 / #172389](https://github.com/cockroachdb/cockroach/issues/172389) | sql/opt: optimizer materializes unused CROSS JOIN inputs under DISTINCT instead of using existence checks | Rewrite | 321 | **118.2×** | Confirmed |
| 3 | [CRDB-65632 / #172396](https://github.com/cockroachdb/cockroach/issues/172396) | sql/opt: Optimizer misses impossible condition for NULL-constrained equality join | Rewrite | 104,000 | **67.8×** | Confirmed |
| 4 | [CRDB-65633 / #172397](https://github.com/cockroachdb/cockroach/issues/172397) | opt/sql: DISTINCT inner join misses semi-join reduction when joined table contributes no output columns | Rewrite | 6,700 | **2,541.7×** | Confirmed |
| 5 | [CRDB-65646 / #172410](https://github.com/cockroachdb/cockroach/issues/172410) | sql/opt: Optimizer switches from sort-before-lookup early-stop to full join + top-k at LIMIT 80, causing ~25x slowdown | Hint | 120,000 | **32.5×** | Confirmed |
| 6 | [CRDB-65647 / #172411](https://github.com/cockroachdb/cockroach/issues/172411) | optimizer: equality-equivalent BETWEEN and >=/<= join predicates produce cross joins and ~31x slowdown | Rewrite | 18,000 | **31.5×** | Confirmed |
| 7 | [CRDB-65648 / #172412](https://github.com/cockroachdb/cockroach/issues/172412) | sql/opt: join cardinality overestimation for nearly disjoint skewed keys causes a ~26x slower full-scan hash-join plan | Rewrite | 1,320,015 | **25.7×** | Confirmed |

---

### 5. OceanBase (6 Reported, 2 Confirmed, 4 Submitted)

| # | Issue ID & Link | Title | Variant | Instance Size (Rows) | Slowdown | Status |
| :-: | :--- | :--- | :---: | :---: | :---: | :---: |
| 1 | [OceanBase #2423](https://github.com/oceanbase/oceanbase/issues/2423) | [Performance Bug] DISTINCT prevents LIMIT pushdown to an unreferenced Cartesian input, causing a 381x slowdown | Rewrite | 1,000,100 | **381.2×** | Confirmed |
| 2 | [OceanBase #2424](https://github.com/oceanbase/oceanbase/issues/2424) | [Bug] Performance Bug: DISTINCT join misses correlated EXISTS early-out, causing a 202x slowdown | Rewrite | 22,000 | **202.2×** | Confirmed |
| 3 | [OceanBase #2425](https://github.com/oceanbase/oceanbase/issues/2425) | [Bug] Performance Bug: DISTINCT inner join expands duplicate matches before deduplication, causing a 72x slowdown | Rewrite | 15,567 | **72.0×** | Submitted |
| 4 | [OceanBase #2436](https://github.com/oceanbase/oceanbase/issues/2436) | [Performance Bug] Join cardinality overestimation makes a ~3.3x faster hybrid plan appear ~22x slower | Hint | 350,015 | **3.3×** | Submitted |
| 5 | [OceanBase #2437](https://github.com/oceanbase/oceanbase/issues/2437) | [Performance Bug] Paired `>=` and `<=` join predicates are not recognized as equality, causing a 54.8x slowdown | Rewrite | 18,000 | **54.8×** | Submitted |
| 6 | [OceanBase #2438](https://github.com/oceanbase/oceanbase/issues/2438) | [Performance Bug] `BETWEEN x AND x` join predicates are not recognized as equality, causing a 93.8x slowdown | Rewrite | 18,000 | **93.8×** | Submitted |

---

## Acknowledgments & License

PUFFER is developed on top of [SQLancer](https://github.com/sqlancer/sqlancer) and incorporates static equivalence rules adapted from [WeTune](https://github.com/VgSg/wetune). This project is released under the [MIT License](LICENSE.md).
