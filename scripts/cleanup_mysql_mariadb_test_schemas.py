#!/usr/bin/env python3
"""Inventory and remove local MySQL/MariaDB test schemas.

The default action is a dry run.  Destructive execution requires both
``--execute`` and ``--confirm DROP-TEST-SCHEMAS``.  By default, only schemas
whose names match known automated-test prefixes are selected; descriptive
reproduction schemas such as ``mdev_40273_*`` are left untouched.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from puffer_case_investigator.backends import BACKENDS, matches_version


SYSTEM_SCHEMAS = frozenset({"information_schema", "mysql", "performance_schema", "sys", "defaultdb", "postgres", "system", "oceanbase"})
SAFE_SCHEMA_NAME = re.compile(r"[A-Za-z0-9_$]+\Z")
CONFIRMATION = "DROP-TEST-SCHEMAS"


@dataclass(frozen=True)
class Target:
    name: str
    client: Path
    host: str
    port: int
    user: str
    password_env: str
    default_password: str
    data_dir: Path | None
    expected_version_prefix: str
    generated_patterns: tuple[re.Pattern[str], ...]


TARGETS = {
    "mysql": Target(
        name="mysql",
        client=Path(os.environ.get("MYSQL_CLIENT_BIN") or shutil.which("mysql") or "mysql"),
        host="127.0.0.1",
        port=34971,
        user="sqlancer",
        password_env="MYSQL_CLEANUP_PASSWORD",
        default_password="sqlancer",
        data_dir=Path(os.environ["MYSQL_DATA_DIR"]) if os.environ.get("MYSQL_DATA_DIR") else None,
        expected_version_prefix="9.7.1",
        generated_patterns=(
            re.compile(r"(?:mysql971(?:ph|qpg)|my97)[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),
            re.compile(r"pdg_[A-Za-z0-9_]*mysql[A-Za-z0-9_]*\Z"),
        ),
    ),
    "mariadb": Target(
        name="mariadb",
        client=Path(os.environ.get("MARIADB_CLIENT_BIN") or shutil.which("mariadb") or shutil.which("mysql") or "mariadb"),
        host="127.0.0.1",
        port=32332,
        user="sqlancer",
        password_env="MARIADB_CLEANUP_PASSWORD",
        default_password="sqlancer",
        data_dir=Path(os.environ["MARIADB_DATA_DIR"]) if os.environ.get("MARIADB_DATA_DIR") else None,
        expected_version_prefix="12.3.2",
        generated_patterns=(
            re.compile(r"(?:mariadb1232|mariadb12|ma12)[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),
            re.compile(r"mariadb_batch_[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),
            re.compile(r"(?:codexmdb|mariadbtestx)[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),
            re.compile(r"pdg_[A-Za-z0-9_]*mariadb[A-Za-z0-9_]*\Z"),
        ),
    ),
}

TARGETS["oceanbase"] = Target(
    name="oceanbase", client=TARGETS["mariadb"].client, host="127.0.0.1", port=BACKENDS["oceanbase"].port,
    user="sqlancer@sqlancer_perf", password_env="PUFFER_OCEANBASE_PASSWORD", default_password="",
    data_dir=None, expected_version_prefix=BACKENDS["oceanbase"].version,
    generated_patterns=(re.compile(r"ob450abl[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),),
)

TARGETS["tidb"] = Target(
    name="tidb", client=TARGETS["mariadb"].client, host="127.0.0.1", port=BACKENDS["tidb"].port,
    user="sqlancer", password_env="TIDB_CLEANUP_PASSWORD", default_password="sqlancer",
    data_dir=None, expected_version_prefix=BACKENDS["tidb"].version,
    generated_patterns=(re.compile(r"tidb850(?:ph|qpg|abl)[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),),
)


TARGETS["cockroachdb"] = Target(
    name="cockroachdb", client=Path(os.environ.get("COCKROACH_BIN") or shutil.which("cockroach") or "cockroach"), host="127.0.0.1", port=26257,
    user="root", password_env="PUFFER_COCKROACHDB_PASSWORD", default_password="", data_dir=None,
    expected_version_prefix=BACKENDS["cockroachdb"].version,
    generated_patterns=(re.compile(r"crdb262abl[A-Za-z0-9_]*(?:[0-9]+|connectiontest)\Z"),),
)



def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Safely preview or drop local MySQL/MariaDB test schemas."
    )
    parser.add_argument("--target", choices=(*TARGETS, "both"), default="both")
    parser.add_argument(
        "--mode",
        choices=("generated", "all-non-system", "prefix-only", "names-only"),
        default="generated",
        help="prefix-only ignores default patterns; names-only selects exact names from --names-file",
    )
    parser.add_argument(
        "--mysql-prefix",
        action="append",
        default=[],
        metavar="PREFIX",
        help="also select MySQL schemas beginning with PREFIX (repeatable)",
    )
    parser.add_argument(
        "--mariadb-prefix",
        action="append",
        default=[],
        metavar="PREFIX",
        help="also select MariaDB schemas beginning with PREFIX (repeatable)",
    )
    parser.add_argument("--cockroachdb-prefix", action="append", default=[], metavar="PREFIX")
    parser.add_argument("--tidb-prefix", action="append", default=[], metavar="PREFIX")
    parser.add_argument("--oceanbase-prefix", action="append", default=[], metavar="PREFIX")
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        metavar="SCHEMA",
        help="preserve this exact schema name (repeatable)",
    )
    parser.add_argument(
        "--inventory-source",
        choices=("datadir", "catalog"),
        default="datadir",
        help="datadir is fast for these local instances; catalog uses SHOW DATABASES",
    )
    parser.add_argument("--show-limit", type=int, default=30, help="candidate names printed per target")
    parser.add_argument("--names-out", type=Path, help="write every selected name as target<TAB>schema")
    parser.add_argument("--names-file", type=Path, help="exact allowlist: target<TAB>schema, compatible with --names-out")
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--execute", action="store_true", help="perform DROP DATABASE operations")
    parser.add_argument(
        "--confirm",
        metavar=CONFIRMATION,
        help=f"required with --execute; must equal {CONFIRMATION!r}",
    )
    return parser.parse_args(argv)


def selected_targets(name: str) -> list[Target]:
    if name == "both":
        return [TARGETS["mysql"], TARGETS["mariadb"]]
    return [TARGETS[name]]


def client_command(target: Target) -> list[str]:
    return [
        str(target.client),
        "--protocol=TCP",
        f"--host={target.host}",
        f"--port={target.port}",
        f"--user={target.user}",
        "--connect-timeout=5",
        "--batch",
        "--skip-column-names",
    ]


def client_environment(target: Target) -> dict[str, str]:
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = os.environ.get(target.password_env, target.default_password)
    return environment


def run_sql(target: Target, sql: str, *, force: bool = False) -> subprocess.CompletedProcess[str]:
    if target.name == "cockroachdb":
        import psycopg2
        connection = None
        try:
            connection = psycopg2.connect(host=target.host, port=target.port, user=target.user,
                password=os.environ.get(target.password_env, target.default_password), dbname="defaultdb",
                sslmode="disable", connect_timeout=5)
            connection.autocommit = True
            output = []
            # Execute individually so DDL is never grouped in an implicit transaction.
            from puffer_case_investigator.db import split_sql_statements
            with connection.cursor() as cursor:
                for statement in split_sql_statements(sql):
                    cursor.execute(statement)
                    if cursor.description:
                        output.extend("\t".join(str(value) for value in row) for row in cursor.fetchall())
            return subprocess.CompletedProcess([], 0, "\n".join(output), "")
        except Exception as error:
            return subprocess.CompletedProcess([], 1, "", str(error))
        finally:
            if connection is not None:
                connection.close()
    command = client_command(target)
    if force:
        command.append("--force")
    return subprocess.run(
        command,
        input=sql,
        text=True,
        capture_output=True,
        env=client_environment(target),
        check=False,
    )


def inventory_from_catalog(target: Target) -> list[str]:
    result = run_sql(target, "SHOW DATABASES;\n")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "SHOW DATABASES failed")
    return normalize_inventory(result.stdout.splitlines())


def inventory_from_datadir(target: Target) -> list[str]:
    if target.data_dir is None:
        return inventory_from_catalog(target)
    if not target.data_dir.is_dir():
        raise RuntimeError(f"data directory does not exist: {target.data_dir}")
    names = (entry.name for entry in os.scandir(target.data_dir) if entry.is_dir())
    return normalize_inventory(names)


def normalize_inventory(names: Iterable[str]) -> list[str]:
    # Automated SQLancer names use only this identifier subset.  Ignoring encoded
    # or temporary directory names also prevents a filesystem name from being
    # interpreted as SQL.
    return sorted(
        name
        for raw_name in names
        if (name := raw_name.strip())
        and name not in SYSTEM_SCHEMAS
        and not name.startswith("#")
        and SAFE_SCHEMA_NAME.fullmatch(name)
    )


def select_candidates(
    target: Target,
    inventory: Sequence[str],
    mode: str,
    prefixes: Sequence[str],
    exclusions: set[str],
    names: Sequence[str] | None = None,
) -> list[str]:
    for prefix in prefixes:
        if not prefix or not SAFE_SCHEMA_NAME.fullmatch(prefix):
            raise ValueError(f"unsafe or empty {target.name} prefix: {prefix!r}")
    inventory = normalize_inventory(inventory)
    if mode == "names-only":
        if names is None:
            raise ValueError("names-only requires an explicit schema allowlist")
        for name in names:
            if not SAFE_SCHEMA_NAME.fullmatch(name) or name in SYSTEM_SCHEMAS:
                raise ValueError(f"unsafe allowlist schema: {name!r}")
        allowed = set(names)
        return [name for name in inventory if name in allowed and name not in exclusions
                and (not prefixes or any(name.startswith(prefix) for prefix in prefixes))]
    if mode == "prefix-only" and not prefixes:
        raise ValueError(f"prefix-only requires an explicit {target.name} prefix")
    if mode == "all-non-system":
        return [name for name in inventory if name not in exclusions]
    return [
        name
        for name in inventory
        if name not in exclusions
        and (
            (mode == "generated" and any(pattern.fullmatch(name) for pattern in target.generated_patterns))
            or any(name.startswith(prefix) for prefix in prefixes)
        )
    ]


def read_names_file(path: Path) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {dbms: [] for dbms in TARGETS}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        fields = line.split("\t")
        if (len(fields) != 2 or fields[0] not in TARGETS or not SAFE_SCHEMA_NAME.fullmatch(fields[1])
                or fields[1] in SYSTEM_SCHEMAS):
            raise ValueError(f"invalid schema allowlist at {path}:{number}")
        result[fields[0]].append(fields[1])
    return result


def verify_target(target: Target) -> str:
    if not target.client.is_file():
        raise RuntimeError(f"client does not exist: {target.client}")
    result = run_sql(target, "SELECT VERSION();\n")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "version query failed")
    version = result.stdout.strip().splitlines()[0]
    if not matches_version(target.name, version, target.expected_version_prefix):
        raise RuntimeError(
            f"refusing {target.name} {target.host}:{target.port}: expected "
            f"{target.expected_version_prefix}, got {version}"
        )
    return version


def quote_identifier(name: str) -> str:
    if not SAFE_SCHEMA_NAME.fullmatch(name):
        raise ValueError(f"unsafe schema name: {name!r}")
    return f"`{name}`"


def chunks(items: Sequence[str], size: int) -> Iterable[Sequence[str]]:
    for offset in range(0, len(items), size):
        yield items[offset : offset + size]


def drop_candidates(target: Target, candidates: Sequence[str], batch_size: int) -> tuple[int, list[str]]:
    completed = 0
    errors: list[str] = []
    for batch_number, batch in enumerate(chunks(candidates, batch_size), start=1):
        quote = (lambda name: quote_identifier(name).replace("`", '"')) if target.name == "cockroachdb" else quote_identifier
        suffix = " CASCADE" if target.name == "cockroachdb" else ""
        sql = "".join(f"DROP DATABASE IF EXISTS {quote(name)}{suffix};\n" for name in batch)
        result = run_sql(target, sql, force=True)
        # MariaDB can print a connection WARNING even when every DROP succeeds.
        # Keep treating SQL errors as failures: --force may exit zero after one.
        diagnostic_lines = [line for line in result.stderr.splitlines() if line.strip()]
        failures = [line for line in diagnostic_lines
                    if not re.match(r"^(?:WARNING:|(?:mysql|mariadb):\s*\[Warning\])", line, re.IGNORECASE)]
        if result.returncode != 0 or failures:
            detail = "\n".join(failures) or result.stderr.strip() or f"client exited with status {result.returncode}"
            errors.append(f"batch {batch_number}: {detail}")
        elif diagnostic_lines:
            print(f"[{target.name}] {'; '.join(diagnostic_lines)}", file=sys.stderr, flush=True)
        completed += len(batch)
        print(f"[{target.name}] submitted {completed}/{len(candidates)} DROP statements", flush=True)
    return completed, errors


def print_preview(target: Target, inventory: Sequence[str], candidates: Sequence[str], limit: int) -> None:
    preserved = len(inventory) - len(candidates)
    print(
        f"[{target.name}] non_system={len(inventory)} selected={len(candidates)} preserved={preserved}"
    )
    if limit <= 0 or not candidates:
        return
    shown = candidates[:limit]
    for name in shown:
        print(f"  {name}")
    if len(candidates) > len(shown):
        print(f"  ... {len(candidates) - len(shown)} more (use --names-out for the full list)")


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if args.show_limit < 0:
        raise SystemExit("--show-limit must be non-negative")
    if args.batch_size <= 0:
        raise SystemExit("--batch-size must be positive")
    if args.execute and args.confirm != CONFIRMATION:
        raise SystemExit(f"--execute requires --confirm {CONFIRMATION}")
    if args.mode == "names-only" and args.names_file is None:
        raise SystemExit("--mode names-only requires --names-file")
    if args.names_file is not None and args.mode != "names-only":
        raise SystemExit("--names-file requires --mode names-only (never merge an allowlist into default patterns)")
    names = read_names_file(args.names_file) if args.names_file else {}

    prefixes_by_target = {
        "cockroachdb": args.cockroachdb_prefix,
        "mysql": args.mysql_prefix,
        "mariadb": args.mariadb_prefix,
        "tidb": args.tidb_prefix,
        "oceanbase": args.oceanbase_prefix,
    }
    exclusions = set(args.exclude)
    selections: list[tuple[Target, list[str]]] = []
    output_lines: list[str] = []

    for target in selected_targets(args.target):
        if args.inventory_source == "catalog":
            inventory = inventory_from_catalog(target)
        else:
            inventory = inventory_from_datadir(target)
        candidates = select_candidates(
            target,
            inventory,
            args.mode,
            prefixes_by_target[target.name],
            exclusions,
            names.get(target.name),
        )
        print_preview(target, inventory, candidates, args.show_limit)
        selections.append((target, candidates))
        output_lines.extend(f"{target.name}\t{name}\n" for name in candidates)

    if args.names_out:
        args.names_out.write_text("".join(output_lines), encoding="utf-8")
        print(f"wrote selected names to {args.names_out}")

    if not args.execute:
        print(f"dry run only; execute with --execute --confirm {CONFIRMATION}")
        return 0

    had_errors = False
    for target, candidates in selections:
        if not candidates:
            continue
        version = verify_target(target)
        print(f"[{target.name}] verified version={version}; starting deletion")
        _, errors = drop_candidates(target, candidates, args.batch_size)
        if errors:
            had_errors = True
            print(f"[{target.name}] completed with {len(errors)} batch error(s):", file=sys.stderr)
            for error in errors:
                print(f"  {error}", file=sys.stderr)
        else:
            print(f"[{target.name}] deletion completed without client errors")
    return 1 if had_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
