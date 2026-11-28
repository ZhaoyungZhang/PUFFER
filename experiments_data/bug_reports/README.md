# RQ1: Real-World Optimizer Performance Issue Catalog

This directory provides machine-readable summary tables for the **56 optimizer-induced performance issues** uncovered by **PUFFER** across five production relational and distributed DBMSs (Section 6.2, RQ1: Tables 2–4 and Figure 6).

Each of the **56** reported issues links directly to its public, login-free official issue tracker entry in the root repository [`README.md`](../../README.md#detected-performance-issues-56-bug-catalog), where the self-contained reproduction SQL, paired query plans, runtime measurements, and developer confirmation records are archived.

- **MySQL 9.7.1**: 15 reported issues (15 Confirmed; S5 Performance)
- **MariaDB 12.3.2**: 10 reported issues (10 Confirmed; 9 Major, 1 New Feature)
- **TiDB v8.5.0**: 18 reported issues (18 Confirmed/Fixed: 17 Confirmed, 1 Fixed; Moderate)
- **CockroachDB v26.2.2**: 7 reported issues (7 Confirmed; Enhancement)
- **OceanBase v4.5.0.0**: 6 reported issues (2 Confirmed, 4 Submitted; 2 Enhancement, 4 Pending)
- [`issue_data_volumes.csv`](issue_data_volumes.csv): Machine-readable table of all 56 reported issues (DBMS, issue ID, public tracker URL, validation database row count, and measured slowdown factor).
- [`issue_component_counts.csv`](issue_component_counts.csv): Per-DBMS optimizer component breakdown (`LO`, `CE`, `CM`, `PO`).

---

## Summary by DBMS, Optimizer Component, Size, and Slowdown (Tables 2 & 4)

| DBMS | All | Severity / Type | Status | LO (Logical Opt.) | CE (Cardinality Est.) | CM (Cost Model) | PO (Physical Opt.) | Median Size (Rows) | Median Slowdown |
| :--- | :---: | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **MySQL (9.7.1)** | 15 | S5 Performance (15) | Confirmed (15) | 3 | 3 | 2 | 7 | 3,164 | **82.2×** |
| **MariaDB (12.3.2)** | 10 | Major (9), New Feature (1) | Confirmed (10) | 3 | 1 | 5 | 1 | 21,450 | **36.1×** |
| **TiDB (v8.5.0)** | 18 | Moderate (18) | Confirmed (17), Fixed (1) | 8 | 1 | 5 | 4 | 14,570 | **90.7×** |
| **CockroachDB (v26.2.2)** | 7 | Enhancement (7) | Confirmed (7) | 5 | 1 | 0 | 1 | 18,000 | **67.8×** |
| **OceanBase (v4.5.0.0)** | 6 | Enhancement (2), Pending (4) | Confirmed (2), Submitted (4) | 5 | 1 | 0 | 0 | 20,000 | **82.9×** |
| **Total / Overall** | **56** | **Performance (42), Enhancement (10), Pending (4)** | **Confirmed (51), Fixed (1), Submitted (4)** | **24** | **7** | **12** | **13** | **17,500** | **69.9×** |

- **Small data, big slowdowns (Figure 6)**: Across all 56 reports, the median validation database contains **17,500 rows** (44.6%, 25/56 use <= 10,000 rows). Across the 54 issues with measured paired runtimes (excluding two pure cardinality/statistics defects, MySQL `#120902` and TiDB `#69931`), the median slowdown reaches **69.9×**: 81.5% (44/54) exceed 10×, 40.7% (22/54) exceed 100×, and 16.7% (9/54) surpass 1,000×, peaking at 43,083× in TiDB (`#69922`) and reaching 4,359.1× on only 1,600 rows in CockroachDB (`CRDB-65623`).

---

## Published Report Outcomes vs. Prior Work (Table 3)

| Work | Confirmed or Fixed / All | Confirmation Rate |
| :--- | :---: | :---: |
| AMOEBA (ICSE 2022) | 6 / 25 | 24.0% |
| CERT (ICSE 2024) | 11 / 13 | 84.6% |
| PUPPY (ICSE 2025) | 22 / 24 | 91.7% |
| SCor (PACMMOD 2026) | 70 / 91 | 76.9% |
| **PUFFER (Ours)** | **52 / 56** | **92.9%** |

See the main repository [`README.md`](../../README.md#detected-performance-issues-56-bug-catalog) for the full 56-bug catalog with direct links to official issue trackers, optimizer components, Stage I oracle classes, validation database row counts, and measured slowdowns.
