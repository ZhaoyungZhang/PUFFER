# WeTune Static Worker

This Java 17 worker exposes the pinned WeTune static rewrite rule bank over a lightweight `stdin`/`stdout` JSONL protocol for **PUFFER**. It performs fast, semantics-preserving algebraic query rewrites without requiring database connections, cost profiling, or Z3 theorem proving at runtime.

## Self-Contained Build (Recommended)

To ensure out-of-the-box reproducibility without requiring access to external GitLab servers, this repository includes the prebuilt WeTune core library JARs (`common-0.1.jar`, `sql-0.1.jar`, `stmt-0.1.jar`, `spes-0.1.jar`, `superopt-0.1.jar`, and `z3-4.8.9.jar`) under `third_party/wetune/lib/` and the pinned static rule bank under `third_party/wetune/wtune_data/prepared/rules.txt`.

You can build the worker directly with Java 17+:

```bash
cd tools/wetune-static-worker
./gradlew --no-daemon installDist
```

Verify the worker binary:

```bash
./build/install/wetune-static-worker/bin/wetune-static-worker < /dev/null
# Expected output:
# {"protocolVersion":1,"status":"ready","upstreamCommit":"3e36def4ae6a8a1630eeb83e6fde2f727715297e","rulesSha256":"9f69a8f32afc70812df5e118f0bc6bb299e502984f1eda6969e7f9aac3bdd4fb"}
```

## Provenance & Licenses

See [`../../third_party/wetune/README.md`](../../third_party/wetune/README.md) for upstream provenance and license details of the bundled WeTune Apache-2.0 core JARs, Z3 MIT bindings, and the 650-rule static equivalence bank. (If a full upstream WeTune checkout with `settings.gradle` is placed at `third_party/wetune/`, `settings.gradle` also supports composite source builds via `includeBuild('../../third_party/wetune')`.)

## Usage with PUFFER

Pass the built worker binary to PUFFER's `PERFORMANCE_HINT` oracle on MySQL, MariaDB, TiDB, CockroachDB, or OceanBase:

```text
--perf-query-rewrite-provider=wetune-static
--perf-wetune-static-worker=/absolute/path/to/tools/wetune-static-worker/build/install/wetune-static-worker/bin/wetune-static-worker
--perf-counterfactual-hint-probability=0.5
```
