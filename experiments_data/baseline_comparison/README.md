# RQ2: End-to-End Comparison with State-of-the-Art Baselines

This directory contains the experimental datasets, per-family audit records, discovery timelines, pattern-overlap matrices, and code-coverage summaries comparing **PUFFER** against five state-of-the-art black-box DBMS performance testing tools over 8-hour budgets across MySQL 9.7.1, MariaDB 12.3.2, TiDB v8.5.0, and CockroachDB v26.2.2 (Section 6.3, RQ2: Table 5 and Figure 7).

---

## 1. Directory Contents

- [`TABLE5_SUMMARY.csv`](TABLE5_SUMMARY.csv): Exact machine-readable summary of **Table 5** (base-table scale, test cases/h, reported PPIs, unique root causes, >= 10× slowdowns, >= 100× slowdowns, and pooled median slowdowns across MySQL, MariaDB, TiDB, CockroachDB, and Overall).
- [`STAGE2_SUMMARY.csv`](STAGE2_SUMMARY.csv): Stage II L0 -> L1 -> L2 consolidation summary across the 13 execution-based baseline campaigns (118 L2 families; combined with the 3 CERT campaigns / 54 L2 families in [`CERT_FAMILY_AUDIT.csv`](CERT_FAMILY_AUDIT.csv) to form all 16 baseline campaigns and 172 L2 families in Table 8).
- [`RQ2_FAMILY_AUDIT.csv`](RQ2_FAMILY_AUDIT.csv) & [`CERT_FAMILY_AUDIT.csv`](CERT_FAMILY_AUDIT.csv): Per-family audit records and 5-round replay measurements for AMOEBA, PUPPY, HULK, SCor (`RQ2_FAMILY_AUDIT.csv`, 118 L2 families) and CERT (`CERT_FAMILY_AUDIT.csv`, 54 L2 families audited under CERT cardinality-estimation semantics).
- [`PUFFER_ROOT_CAUSE_CATALOG.csv`](PUFFER_ROOT_CAUSE_CATALOG.csv) & [`PUFFER_FAMILY_AUDIT.csv`](PUFFER_FAMILY_AUDIT.csv): Complete catalog of PUFFER's **37** unique optimizer root causes and per-family audit of all **411** L3-validated L2 families (`37` `VALID_UNIQUE` representatives + `351` `MERGE_DUP` families mapped via `merged_into_family_id` + `23` `FALSE_POSITIVE` families: `13` dual-plan 10× timeouts and `10` boolean-coercion rewrites).
- [`RQ2_DISCOVERY_TIMELINE.csv`](RQ2_DISCOVERY_TIMELINE.csv) & [`PUFFER_DISCOVERY_TIMELINE.csv`](PUFFER_DISCOVERY_TIMELINE.csv): Exact discovery timestamps (`elapsed_sec`, `elapsed_min`) and slowdown factors for all unique issues across the 8-hour campaigns, backing **Figure 7(b)** (slowdown severity) and **Figure 7(c)** (cumulative unique issue discovery over 8 hours).
- [`RQ2_CROSS_TOOL_OVERLAP.csv`](RQ2_CROSS_TOOL_OVERLAP.csv) & [`RQ2_PATTERN_OVERLAP_AUDIT.csv`](RQ2_PATTERN_OVERLAP_AUDIT.csv): Cross-tool overlap breakdown across all **71** distinct optimizer-issue patterns and audited Tier A (exact bug) / Tier B (shared mechanism) intersections, backing **Figure 7(a)**.
- [`RQ2_COVERAGE_SUMMARY.csv`](RQ2_COVERAGE_SUMMARY.csv) & [`RQ2_COVERAGE_8H.json`](RQ2_COVERAGE_8H.json): 8-hour query-optimizer and execution-engine code coverage trajectories (GCC branch coverage on MySQL/MariaDB; Go basic-block coverage on TiDB/CockroachDB) and 8-hour exclusive union coverage shares, backing **Figure 7(d)** and **Figure 7(e)**.

---

## 2. End-to-End Comparison Across the Four Overlapping DBMSs (Table 5)

| Tool | Scale | Test cases/h | MySQL (PPIs / Unique / >= 10×) | MariaDB (PPIs / Unique / >= 10×) | TiDB (PPIs / Unique / >= 10×) | CockroachDB (PPIs / Unique / >= 10×) | Overall PPIs | Overall Unique | Overall >= 10× |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **AMOEBA** | 100K | 0.2K | – / – / – | – / – / – | – / – / – | 41 / 4 / 1 | 41 | 4 | 1 |
| **CERT** | 1K | **2.9M** | 472 / 2 / 0 | – / – / – | 248 / 1 / 0 | 31 / 1 / 0 | 751 | 4 | 0 |
| **PUPPY** | 50K | 1.1K | 138 / 5 / 2 | 131 / 2 / 1 | 17 / 1 / 0 | 16 / 3 / 1 | 302 | 11 | 4 |
| **HULK** | 50K | 0.6K | 41 / 5 / 2 | 22 / 2 / 0 | 16 / 3 / 2 | 24 / 3 / 1 | 103 | 13 | 5 |
| **SCor** | 1M | 1.6K | 1,355 / 4 / 4 | 1,082 / 5 / 5 | 697 / 2 / 2 | 283 / 2 / 2 | 3,417 | 13 | 13 |
| **PUFFER** | **1K–10K** | **31.9K** | 165 / **11** / **11** | 146 / **9** / **9** | 38 / **11** / **9** | 62 / **6** / **6** | 411 | **37 (+24)** | **35 (+22)** |

### Accounting Notes for Table 5 and Family Audit Tables
- **Definition of `PPIs` vs. `Unique`**: In Table 5, `PPIs` reports each tool's final emitted reports for triage (raw reports for the five baselines; Stage II L2+L3 validated families for PUFFER). `Unique` and `>= 10×` report *newly generated* unique root causes after excluding built-in regression seeds (`KNOWN_SEED`) shipped inside PUPPY and HULK.
- **Built-in regression seeds (`All` vs. `Net`)**: Including built-in regression seeds (`KNOWN_SEED` in [`RQ2_FAMILY_AUDIT.csv`](RQ2_FAMILY_AUDIT.csv)), PUPPY has 24 total valid families (15 `KNOWN_SEED` + 9 `VALID_UNIQUE` in the full-set L2 clustering) and HULK has 27 total valid families (14 `KNOWN_SEED` + 13 `VALID_UNIQUE`). On CockroachDB, two of PUPPY's `KNOWN_SEED` L2 buckets (`cockroachdb-A-L2-00002` and `cockroachdb-A-L2-00004`, annotated via `net_generated_unique_ids` in `RQ2_FAMILY_AUDIT.csv`) also contain newly generated cases that form 2 additional distinct generated-only Net families (`cockroachdb-NET-gen-1` at 14.34× and `cockroachdb-NET-gen-2` at 3.79× in `RQ2_DISCOVERY_TIMELINE.csv`), yielding `9 + 2 = 11` newly generated unique issues for PUPPY while keeping `RQ2_FAMILY_AUDIT.csv` at exactly 25 PUPPY L2 rows matching Table 8.
- **CERT cardinality-estimation (`CE`) vs. performance (`Perf`) verdicts**: CERT targets cardinality-estimation monotonicity violations rather than paired execution slowdowns. Its 4 unique CE defects (`2` MySQL, `1` TiDB, `1` CockroachDB) are audited in [`CERT_FAMILY_AUDIT.csv`](CERT_FAMILY_AUDIT.csv); none exhibit >= 10× execution slowdown (`0` in the `>= 10×` column).

- **PUFFER family-to-root-cause lineage & timeout fallback (`PUFFER_FAMILY_AUDIT.csv` -> `PUFFER_ROOT_CAUSE_CATALOG.csv`)**: Every row in [`PUFFER_FAMILY_AUDIT.csv`](PUFFER_FAMILY_AUDIT.csv) records `case_id -> family_id -> root_cause_group -> audit_verdict`, reconciling all 411 L2 families (`165` MySQL + `146` MariaDB + `38` TiDB + `62` CockroachDB) directly to the 37 unique root causes in [`PUFFER_ROOT_CAUSE_CATALOG.csv`](PUFFER_ROOT_CAUSE_CATALOG.csv). When a representative's default plan times out at 10× while its 1× slowdown is already verified (`slowdown_source = 1x_verified_10x_default_timeout`, e.g., `TIDB-RC02` at `13.29×`), `effective_slowdown` conservatively retains the measured 1× slowdown rather than extrapolating from the timeout cap.

---

## 3. Key Findings Matching Figure 7(a)–(e)

### 3.1 Issue Overlap and Component Coverage — Figure 7(a)
- **37 unique root causes across all 4 optimizer components**: PUFFER uncovers **19** cost-modeling (`CM`), **8** physical-optimization (`PO`), **6** cardinality-estimation (`CE`), and **4** logical-optimization (`LO`) root causes (1.5×–7.0× higher than any baseline on matched targets and 2.8×–3.4× higher across all four shared DBMSs).
- **86.5% exclusivity across 71 distinct patterns**: Across all **71** distinct optimizer-issue patterns found by the six tools combined, PUFFER alone (**37**) nearly matches the five baselines' combined union (**39**). **32 of PUFFER's 37 root causes (86.5%)** are exclusive to PUFFER; **5** share mechanisms with HULK, PUPPY, or SCor (e.g., MySQL `LEFT JOIN` elimination and `ORDER BY LIMIT` costing), and **0** overlap with CERT or AMOEBA.

### 3.2 Slowdown Severity Distribution — Figure 7(b)
- **PUFFER**: **35 / 37** unique issues reach >= 10× slowdowns, **23 / 37** reach >= 30×, and **21 / 37** exceed 100× (more than doubling the **10** >= 100× issues found by all five baselines combined). PUFFER's pooled median slowdown reaches **183.7×** (peaking at **6,993×** on MySQL and **1,713×** on MariaDB).
- **Baselines**: Newly generated queries from PUPPY (median **9.5×**), HULK (**8.2×**), AMOEBA (**5.0×**), and CERT (**1.4×**) produce **0** >= 100× slowdowns. SCor attains a **2,533×** median (**10** >= 100×) by pairing 1M-row tables with 0/1-row tables, but all **13** of its issues are restricted to missing logical short-circuits (`LO`).

### 3.3 Testing Throughput and Discovery Timeline — Figure 7(c)
- Operating on compact 1K–10K-row databases enables PUFFER to evaluate **31.9K base test cases/h** per DBMS (19.5×–154.7× higher than execution-based baselines).
- PUFFER uncovers **16** unique PPIs within **15.5 min** (already exceeding every baseline's 8-hour total), reaches **27** at **1 h** and **33** at **2 h**, and continues discovering new root causes through the seventh hour (**7.15 h**). By contrast, SCor finds **10 / 13** issues within **15.5 min** and saturates, CERT finds all **4** within **27.5 min**, PUPPY and HULK find only **5** and **6** in the first hour, and AMOEBA's first issue arrives at **2.94 h**.

### 3.4 Optimizer and Execution Code Coverage — Figure 7(d) & Figure 7(e)

All values match [`RQ2_COVERAGE_SUMMARY.csv`](RQ2_COVERAGE_SUMMARY.csv) and [`RQ2_COVERAGE_8H.json`](RQ2_COVERAGE_8H.json):

| Tool | Scope | 1 h Mean Cov. (%) | 8 h Mean Cov. (%) | Post-1h Gain (pp) | Mean Unique Share of 8 h Union (%) | Per-DBMS Unique Share (MySQL / MariaDB / TiDB / CRDB) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **PUFFER** | 4 Shared DBMSs | 25.75% | **27.12%** | **+1.37 pp** | **11.64%** | **9.09% / 20.27% / 3.76% / 13.46%** |
| **PUPPY** | 4 Shared DBMSs | 26.18% | 26.54% | +0.36 pp | 0.52% | 0.78% / 0.72% / 0.10% / 0.47% |
| **HULK** | 4 Shared DBMSs | 25.65% | 25.88% | +0.23 pp | 0.14% | 0.02% / 0.04% / 0.06% / 0.44% |
| **SCor** | 4 Shared DBMSs | 18.17% | 18.59% | +0.42 pp | 1.38% | 1.52% / 2.39% / 0.81% / 0.80% |
| **CERT** | 3 DBMSs (vs. PUFFER **23.98%**) | 20.68% | 21.64% | +0.96 pp | 7.90% | 10.25% / – / 8.75% / 4.71% |
| **AMOEBA** | CockroachDB (vs. PUFFER **16.25%**) | 12.69% | 15.13% | +2.44 pp | 8.36% | – / – / – / 8.36% |

### 3.5 Report Precision and Triage Efficiency
- Across the four engines, PUFFER's Stage II consolidation and 10× validation take **22.2–92.5 min** per DBMS, condensing **9,332** raw candidates by **95.6%** into **411** reported L2 families at a **5.6%** L2-family false-positive rate (**23 / 411**).
- By contrast, SCor emits **3,417** PPIs of which **97.3%** (**3,326**) collapse into just **5** L2 families; PUPPY and HULK derive **62.5%** (**15 / 24**) and **51.9%** (**14 / 27**) of their valid L2 families from built-in regression seeds; HULK incurs a **30.8%** (**16 / 52**) L2 false-positive rate due to cross-engine baseline speed gaps; AMOEBA suffers a **63.6%** (**14 / 22**) false-positive rate; and CERT reaches an **85.2%** (**46 / 54**) false-positive rate.
