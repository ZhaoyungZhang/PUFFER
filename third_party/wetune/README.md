# Bundled WeTune Static Rewrite Rule Bank & Core JARs

To keep PUFFER 100% self-contained and reproducible offline without external Git submodule or network dependencies, this directory vendors the prebuilt core libraries and static equivalence rule bank from [WeTune](https://github.com/VgSg/wetune) (Wang et al., SIGMOD 2022):

- `wtune_data/prepared/rules.txt`: Static bank of **650 SMT-verified SQL equivalence rules** (verified by SHA-256 checksum at startup by `tools/wetune-static-worker`).
- `lib/common-0.1.jar`, `lib/sql-0.1.jar`, `lib/stmt-0.1.jar`, `lib/spes-0.1.jar`, `lib/superopt-0.1.jar`: Prebuilt WeTune v0.1 core modules ([Apache License 2.0](https://github.com/VgSg/wetune/blob/main/LICENSE)).
- `lib/z3-4.8.9.jar`: Microsoft Z3 Java bindings ([MIT License](https://github.com/Z3Prover/z3/blob/master/LICENSE.txt)).
