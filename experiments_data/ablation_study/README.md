# RQ3 & RQ4: Ablation Study Data (Data-Aware Generation & Two-Stage Performance Oracle)

This directory contains the summary CSV tables for **Section 6.4 (RQ3: Data-Aware Test-Case Generation)** and **Section 6.5 (RQ4: Two-Stage Performance Oracle)** across all five target DBMSs: MySQL 9.7.1, MariaDB 12.3.2, TiDB v8.5.0, CockroachDB v26.2.2, and OceanBase v4.5.0.0.

---

## 1. Directory Contents

- [`TABLE6_RQ3_DATAGEN_ABLATION.csv`](TABLE6_RQ3_DATAGEN_ABLATION.csv): Multi-granularity database generation ablation (**Table 6**: `Random`, `Column`, `+Intra`, `+Inter`, `Full` at $p_{\mathrm{bind}}=0.5$), including validated PPIs, PPI per 1,000 generated base queries (`PPI/kQ`), L2 families, plan diversity ($\Delta(P_{\mathrm{d}}, P')$ signatures), empty-join rates, cross-DBMS geometric mean yield, >= 100× PPIs, and > 20 s timeouts.
- [`FIG8_RQ3_PBIND_ABLATION.csv`](FIG8_RQ3_PBIND_ABLATION.csv): Data-aware predicate-binding probability sweep (**Figure 8**: $p_{\mathrm{bind}} \in \{0.0, 0.25, 0.5, 0.75, 1.0\}$ under `Full` data), including pooled PPIs, 5-DBMS mean normalized PPI, >= 100× PPIs, `With WHERE` vs. `No WHERE` split, per-DBMS and mean generated empty-result rates, and predicate-binding latency overhead.
- [`RQ3_QUERY_QUALITY_1H.csv`](RQ3_QUERY_QUALITY_1H.csv): Full 50-run (5 DBMSs × 10 slots) 1-hour online query quality and Stage I/II funnel metrics (generated base queries, variant `EXPLAIN` counts, empty-join and empty-result rates, binding overhead, L0/L1/L2/PPI counts).
- [`FIG9_RQ4_ORACLE_BREAKDOWN.csv`](FIG9_RQ4_ORACLE_BREAKDOWN.csv): Per-DBMS and 5-DBMS aggregate breakdown backing **Figure 9(a)** (`Large-only` 10× vs. `Selective` 1×+10×), **Figure 9(b)** (Stage I single-signal cost-only vs. runtime-only miss rates), and **Figure 9(c)** (Stage II L3 validation time without L2 vs. with L2).
- [`TABLE7_RQ4_ORACLE_STRATEGY.csv`](TABLE7_RQ4_ORACLE_STRATEGY.csv): End-to-end test oracle strategy ablation (**Table 7**: `Large-only`, `Without L2`, `Without L3`, and `PUFFER (Full)` across 5 DBMSs: reported PPIs, >= 100× PPIs, L3 validation time, family recall, and audited precision).
- [`TABLE8_RQ4_EXTERNAL_TRANSFER.csv`](TABLE8_RQ4_EXTERNAL_TRANSFER.csv): Transfer of PUFFER's unmodified Stage II consolidation (`L1`–`L2`) and validation (`L3`) pipeline to the raw reports of the five RQ2 external baselines (**Table 8**: PUPPY, HULK, AMOEBA, SCor, CERT).

---

## 2. RQ3: Data-Aware Test-Case Generation (Table 6 & Figure 8)

### 2.1 Multi-Granularity Database Generation Ablation — Table 6

Comparison across five data-generation profiles at $p_{\mathrm{bind}} = 0.5$ (`PPI (PPI/kQ)`):

| DBMS / Metric | Random (SQLancer) | Column | +Intra | +Inter | Full | Max Gain |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **MySQL** | 11 (0.10) | 39 (0.49) | 64 (0.41) | 68 (0.57) | **78 (0.63)** | +609% (540%) |
| **MariaDB** | 53 (0.53) | 89 (**1.40**) | 106 (1.40) | 118 (1.13) | **121** (0.89) | +128% (167%) |
| **TiDB** | 14 (0.56) | 16 (0.81) | 17 (0.81) | 16 (0.62) | **26 (1.09)** | +86% (92%) |
| **CockroachDB** | 18 (3.77) | 18 (1.52) | 21 (**4.09**) | 22 (2.33) | **23** (2.08) | +28% (9%) |
| **OceanBase** | 3 (0.14) | 5 (0.17) | 6 (0.29) | 6 (**0.32**) | **8** (0.27) | +167% (129%) |
| **Pooled PPIs (L2 Families)** | 99 (380) | 167 (566) | 214 (495) | 230 (639) | **256 (748)** | **+159% (+97%)** |
| **GeoMean** | 1.00× | 1.63× | 2.01× | 2.07× | **2.52×** | **+152%** |
| **>= 100× PPIs** | 19 | 44 | 45 | 68 | **74** | **+289%** |
| **> 20 s Timeout** | 9 | 38 | 32 | 61 | **63** | **+600%** |
| **Plan Diversity** | 91 | 157 | 192 | 204 | **230** | **+153%** |
| **Mean Empty-Join Rate** | 30.5% | 44.9% | 46.0% | 35.3% | **35.1%** | – |

### 2.2 Data-Aware Predicate Binding Sweep ($p_{\mathrm{bind}}$) — Figure 8

Effect of varying $p_{\mathrm{bind}} \in \{0.0, 0.25, 0.5, 0.75, 1.0\}$ under `Full` data across all five DBMSs:

| $p_{\mathrm{bind}}$ | Pooled PPIs | Mean Rel. PPI | >= 100× PPIs | `With WHERE` PPIs | `No WHERE` PPIs | Pooled L2 | Mean Empty-Result Rate | Per-DBMS Empty-Result Rate (MySQL / MariaDB / TiDB / CRDB / OB) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **0.0** | 126 | 1.00× | 41 | 72 | 54 | 345 | 55.5% | 68.1% / 50.3% / 57.8% / 42.9% / 58.3% |
| **0.25** | 227 | 1.86× | 68 | 152 | 75 | 654 | 46.5% | 56.2% / 43.5% / 47.9% / 37.1% / 47.8% |
| **0.5 (Default)** | **256** | **2.29×** | **74** | **199** (2.76×) | 57 | **748** | **37.6%** | **44.3% / 36.3% / 38.2% / 32.5% / 36.7%** |
| **0.75** | 214 | 1.84× | 66 | 178 | 36 | 638 | 28.4% | 33.1% / 29.0% / 28.8% / 24.8% / 26.4% |
| **1.0** | 191 | 1.73× | 52 | 191 | 0 | 627 | **19.3%** | 20.8% / 20.0% / 19.9% / 20.9% / 15.1% |

- At $p_{\mathrm{bind}} = 0.5$, in-memory predicate binding averages **0.19 ms** per bound base query across the five engines (under **0.15%** of total testing time).

---

## 3. RQ4: Two-Stage Performance Oracle (Figure 9, Table 7, & Table 8)

### 3.1 Per-DBMS Two-Stage Oracle Breakdown — Figure 9(a)–(c)

| DBMS | `Large-only` (10×) Variants / L2 / PPIs (>= 100×) | `Selective` (1×+10×) Variants / L2 / PPIs (>= 100×) | Stage I Cells (`A / B / C / D / G`) | Cost-Only Missed (`D+G`) | Runtime-Only Missed | L3 Time Without L2 -> With L2 (Saved) | 1× -> 10× Median Slowdown |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **MySQL** | 81.1K / 27 / 15 (1) | **542.8K / 120 / 78 (31)** (+420%) | `18 / 1 / 0 / 8 / 51` | 59 / 78 (**75.6%**) | 35 / 78 (**44.9%**) | 59.2 m -> **29.9 m** (-49.5%) | 7.2× -> **59.8×** (8.3×) |
| **MariaDB** | 18.0K / 37 / 13 (7) | **1,071.9K / 279 / 121 (40)** (+831%) | `49 / 10 / 4 / 23 / 35` | 58 / 121 (**47.9%**) | 39 / 121 (**32.2%**) | 293.4 m -> **67.3 m** (-77.1%) | 12.8× -> **36.4×** (2.8×) |
| **TiDB** | 4.7K / 30 / 8 (0) | **107.9K / 119 / 26 (0)** (+225%) | `3 / 1 / 0 / 3 / 19` | 22 / 26 (**84.6%**) | 6 / 26 (**23.1%**) | 40.9 m -> **23.2 m** (-43.3%) | 4.0× -> **9.4×** (2.3×) |
| **CockroachDB** | 7.4K / 48 / 26 (0) | **114.5K / 209 / 23 (0)** (4.35× L2) | `0 / 0 / 0 / 1 / 22` | 23 / 23 (**100.0%**) | 4 / 23 (**17.4%**) | 129.9 m -> **45.0 m** (-65.3%) | 5.3× -> **6.4×** (1.2×) |
| **OceanBase** | 3.8K / 3 / 1 (0) | **124.8K / 21 / 8 (3)** (+700%) | `4 / 1 / 0 / 1 / 2` | 3 / 8 (**37.5%**) | 1 / 8 (**12.5%**) | 12.8 m -> **11.2 m** (-12.6%) | 9.0× -> **24.9×** (2.8×) |
| **Total (5 DBMSs)** | **115.1K / 145 / 63 (8)** | **1,961.9K (17.0×) / 748 / 256 (74)** | **`74 / 13 / 4 / 36 / 129`** | **165 / 256 (64.5%)** | **85 / 256 (33.2%)** | **536.1 m -> 176.6 m (-67.1%)** | **1.2×–8.3× lift** |

- Across all 5 engines, Stage I emits **6,653** raw candidates (`L0`), which L1 plan-pair deduplication (**2,980** groups) and L2 plan-delta clustering condense by **88.8%** into **748** L2 families.
- L3 two-scale replay filters **492** spurious families, emitting **256** validated PPIs (**179 `Amplified`**, **10 `Activated`**, and **67 `Sustained`**), while more than doubling >= 100× issues from **35** (at 1×) to **74** (at 10×) and increasing >= 10× issues by 4.61× over `Large-only` (**166 vs. 36**).
- *(Rounding note for Figure 9(c))*: Unrounded per-DBMS `Without L2` L3 times sum to **536.1 min** (`536.2 min` if summing the 1-decimal display values).

### 3.2 Test Oracle Strategy Ablation — Table 7

| Oracle Variant | Reported PPIs | >= 100× | L3 Validation Time (min) | Family Recall | Audited Precision | True Positives |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Large-only (10×)** | 63 | 8 | 39.7 | 19.0% | 98.4% | 62 |
| **Without L2 (all L1)** | 332 (1,060 L1 passes) | – | 536.1 | 100.0% | 94.3% | 313 |
| **Without L3 (Stage I + L2)** | 748 | – | 0.0 | 75.3% | 32.4% | 242 |
| **PUFFER (Full)** | **256** | **74** | **176.6** | **75.3%** | **94.5%** | **242** |

- **Exact false-positive breakdown & timeout handling (Table 7)**:
  - `PUFFER (Full)`: **256** emitted PPIs = **242** true positives (**94.5%**) + **14** strict false positives (**8** dual-plan 10× timeouts `SCALED_BOTH_PLANS_TIMEOUT_MANUAL_REVIEW` + **6** non-boolean truthiness-coercion rewrites `UNSOUND_REWRITE_BOOL_COERCION`). Dual-plan timeouts have empty `ten_x_ratio` and **never enter the `>= 10×` (166) or `>= 100×` (74) severity counts**.
  - `Without L2 (all L1)`: **332** distinct passing L2 families (from 1,060 passing L1 groups) = **313** true positives (**94.3%**) + **19** strict false positives (**14** dual-plan timeouts + **5** boolean-coercion rewrites).
  - `Large-only (10×)`: **63** emitted PPIs = **62** true positives (**98.4%**) + **1** boolean-coercion rewrite (**0** dual-plan timeouts).
  - `Family Recall`: Reports L2 top-1 family retention relative to `Without L2` (`250 / 332 = 75.3%`; strict true-positive family recall is `242 / 313 = 77.3%`).

### 3.3 Transferring Stage II to External Baselines — Table 8

| External Tool | Raw Reports | L1 Groups | L2 Families (Reduction %) | Top-1 Family Recall | Stage II Impact |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **PUPPY (4 DBMSs)** | 302 | 29 | 25 (91.7%) | 100.0% (25 / 25) | 12.1× cut |
| **HULK (4 DBMSs)** | 103 | 65 | 52 (49.5%) | 97.3% (36 / 37) | 2.0× cut |
| **AMOEBA (CRDB)** | 41 | 24 | 22 (46.3%) | 88.9% (8 / 9) | 1.9× cut |
| **SCor (4 DBMSs)** | 3,417 | 495 | 19 (99.4%) | 78.9% (15 / 19) | 180× cut |
| **CERT (3 DBMSs)** | 751 | 83 | 54 (92.8%) | 75.0% (6 / 8) | 84.8% FP cut (39 / 46 CE FPs) |
| **Total (5 Tools)** | **4,614** | **696** | **172 (96.3%)** | **91.8% (90 / 98)** | **26.8× cut** |

- On plan-comparing tools (**PUPPY, HULK, AMOEBA**), Stage II L1–L2 consolidation compresses **446** raw reports into **99** L2 families while achieving **97.2%** top-1 family recall (**69 / 71**) and **88.7%** distinct root-cause recall (**55 / 62**).
- Per-campaign L0/L1/L2 consolidation counts and per-family audit verdicts backing Table 8 are in [`../baseline_comparison/STAGE2_SUMMARY.csv`](../baseline_comparison/STAGE2_SUMMARY.csv) (118 L2 families across PUPPY, HULK, AMOEBA, and SCor), [`../baseline_comparison/RQ2_FAMILY_AUDIT.csv`](../baseline_comparison/RQ2_FAMILY_AUDIT.csv), and [`../baseline_comparison/CERT_FAMILY_AUDIT.csv`](../baseline_comparison/CERT_FAMILY_AUDIT.csv) (54 L2 families on CERT; `118 + 54 = 172` total L2 families).
