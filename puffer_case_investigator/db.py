"""Controlled MySQL/MariaDB execution for PUFFER case investigation.

The adapter deliberately exposes operations rather than an unrestricted SQL
tool.  Case setup is replayed only after its database lifecycle has been
rewritten to an owned ``puffer_ci_*`` sandbox, while candidate queries are
restricted to one read-only SELECT statement.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import random
import re
import statistics
import time
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from typing import Any, Callable, Dict, Iterator, List, Mapping, Optional, Sequence, Tuple

import pymysql

from .backends import BACKENDS, matches_version
from .tidb_plan import format_table, parse_plan


SANDBOX_PREFIX = "puffer_ci_"
MAX_RESULT_ROWS = 5_000_000
MAX_RESULT_BYTES = 2 * 1024 * 1024 * 1024
_IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_SANDBOX_RE = re.compile(r"^puffer_ci_[A-Za-z0-9_]{1,48}$")
_LIFECYCLE_RE = re.compile(
    r"^\s*(DROP\s+DATABASE(?:\s+IF\s+EXISTS)?|"
    r"CREATE\s+DATABASE(?:\s+IF\s+NOT\s+EXISTS)?|USE)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*$",
    re.IGNORECASE | re.DOTALL,
)
_LIFECYCLE_PREFIX_RE = re.compile(
    r"^\s*(?:DROP\s+DATABASE|CREATE\s+DATABASE|USE)\b", re.IGNORECASE
)
_PROHIBITED_SETUP_RE = re.compile(
    r"^\s*(GRANT|REVOKE|CREATE\s+USER|ALTER\s+USER|DROP\s+USER|"
    r"CREATE\s+ROLE|DROP\s+ROLE|INSTALL|UNINSTALL|SHUTDOWN|KILL|"
    r"LOAD\s+DATA|LOAD_FILE|SOURCE|DELIMITER|CREATE\s+(?:PROCEDURE|FUNCTION|"
    r"TRIGGER|EVENT)|ALTER\s+INSTANCE)\b",
    re.IGNORECASE | re.DOTALL,
)
_OBJECT_REFERENCE_RE = re.compile(
    # ON is deliberately excluded: ``JOIN t1 ON t0.c0 = t1.c0`` contains
    # ordinary column qualifications, not an external schema reference.
    r"\b(?:INTO|UPDATE|TABLE|REFERENCES|FROM|JOIN)\s+"
    r"(?P<first>`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*"
    r"(?P<second>`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)",
    re.IGNORECASE,
)


class InvestigatorDBError(RuntimeError):
    """Base error raised by the controlled database layer."""


class SetupValidationError(InvestigatorDBError):
    """The case setup cannot be safely scoped to an investigator sandbox."""


class QueryValidationError(InvestigatorDBError):
    """A candidate query is not one read-only SELECT statement."""


class QueryLimitExceeded(InvestigatorDBError):
    """A query result exceeded a configured row or byte limit."""


def is_statement_timeout(error: BaseException) -> bool:
    """Only server-enforced statement deadlines are runtime lower bounds.

    Connection loss, socket timeouts, lock waits and result-size limits must not
    be converted to successful timing observations.
    """
    if getattr(error, "pgcode", None) == "57014":
        return "statement timeout" in str(error).lower()
    return isinstance(error, pymysql.err.OperationalError) and bool(error.args) and error.args[0] in (3024, 1969)


class AmplificationRefused(InvestigatorDBError):
    """A table cannot be amplified by empirical row replication."""


@dataclass(frozen=True)
class DatabaseProfile:
    """Connection settings without embedding a password in persisted output."""

    name: str
    dbms: str
    host: str = "127.0.0.1"
    port: int = 3306
    user: str = "sqlancer"
    password_env: str = "SQLANCER_DB_PASSWORD"
    expected_version_prefix: str = ""
    connect_timeout_s: int = 5
    query_timeout_s: int = 30
    charset: str = "utf8mb4"
    password: Optional[str] = field(default=None, repr=False, compare=False)

    def __post_init__(self) -> None:
        normalized = self.dbms.lower()
        if normalized not in BACKENDS:
            raise ValueError(f"unsupported DBMS for V1: {self.dbms}")
        object.__setattr__(self, "dbms", normalized)
        if not (1 <= self.port <= 65535):
            raise ValueError("port must be between 1 and 65535")
        if self.connect_timeout_s <= 0 or self.query_timeout_s <= 0:
            raise ValueError("connection and query timeouts must be positive")

    def resolved_password(self) -> str:
        value = os.environ.get(self.password_env)
        if value is not None:
            return value
        if self.password is not None:
            return self.password
        raise InvestigatorDBError(
            f"database password is unavailable; set environment variable {self.password_env}"
        )

    def public_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload.pop("password", None)
        payload["password_configured"] = self.password_env in os.environ or self.password is not None
        return payload

    @classmethod
    def from_settings(
        cls,
        settings: Any,
        *,
        query_timeout_s: int = 30,
        password: Optional[str] = None,
    ) -> "DatabaseProfile":
        """Adapt ``config.ProfileSettings`` without importing the CLI layer."""

        return cls(
            name=str(settings.name),
            dbms=str(settings.dbms),
            host=str(settings.host),
            port=int(settings.port),
            user=str(settings.user),
            password_env=str(settings.password_env),
            expected_version_prefix=str(settings.expected_version_prefix),
            query_timeout_s=query_timeout_s,
            password=password,
        )


@dataclass(frozen=True)
class ExplainResult:
    raw: str
    parsed: Optional[Any]
    estimated_cost: Optional[float]
    structure: Optional[Any] = None


@dataclass(frozen=True)
class AnalyzeResult:
    raw: str
    parsed: Optional[Any]


@dataclass(frozen=True)
class FetchedResult:
    columns: Tuple[str, ...]
    rows: Tuple[Tuple[Any, ...], ...]
    row_count: int
    approximate_bytes: int


@dataclass(frozen=True)
class PairRound:
    round_index: int
    order: str
    default_runtime_ms: float
    counterfactual_runtime_ms: float
    default_timeout_bound: bool = False
    counterfactual_timeout_bound: bool = False

    @property
    def counterfactual_won(self) -> bool:
        return self.default_runtime_ms > self.counterfactual_runtime_ms


@dataclass(frozen=True)
class RuntimeSummary:
    samples_ms: Tuple[float, ...]
    median_ms: float
    mean_ms: float
    min_ms: float
    max_ms: float


@dataclass(frozen=True)
class MeasurementResult:
    warmups_per_query: int
    rounds: Tuple[PairRound, ...]
    default: RuntimeSummary
    counterfactual: RuntimeSummary
    speedup: float
    absolute_gap_ms: float
    speedup_bootstrap_95_lower: Optional[float]
    counterfactual_wins: int
    timeout_observations: Tuple[Mapping[str, Any], ...] = ()
    early_stopped: bool = False
    early_stop_reason: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "warmups_per_query": self.warmups_per_query,
            "rounds": [asdict(item) for item in self.rounds],
            "default": asdict(self.default),
            "counterfactual": asdict(self.counterfactual),
            "speedup": self.speedup,
            "absolute_gap_ms": self.absolute_gap_ms,
            "speedup_bootstrap_95_lower": self.speedup_bootstrap_95_lower,
            "counterfactual_wins": self.counterfactual_wins,
            "timeout_observations": [dict(item) for item in self.timeout_observations],
            "early_stopped": self.early_stopped,
            "early_stop_reason": self.early_stop_reason,
        }


@dataclass(frozen=True)
class ConstraintInfo:
    name: str
    kind: str
    columns: Tuple[str, ...] = ()


@dataclass(frozen=True)
class ColumnInfo:
    name: str
    data_type: str
    column_type: str
    nullable: bool


@dataclass(frozen=True)
class ColumnProfile:
    name: str
    null_count: int
    distinct_count: int
    minimum: Any
    maximum: Any
    top_values: Tuple[Tuple[Any, int], ...]


@dataclass(frozen=True)
class TableProfile:
    name: str
    row_count: int
    create_sql: str
    columns: Tuple[ColumnInfo, ...]
    constraints: Tuple[ConstraintInfo, ...]
    column_profiles: Tuple[ColumnProfile, ...]
    data_bytes: int = 0
    index_bytes: int = 0

    @property
    def amplification_blockers(self) -> Tuple[str, ...]:
        blocked = {"PRIMARY KEY", "UNIQUE", "FOREIGN KEY"}
        return tuple(sorted({item.kind for item in self.constraints if item.kind.upper() in blocked}))

    def to_dict(self) -> Dict[str, Any]:
        return _json_ready(asdict(self))


@dataclass(frozen=True)
class ReplayResult:
    sandbox_name: str
    server_version: str
    statistics_refreshed: bool
    ordered_results: bool
    default_explain: ExplainResult
    counterfactual_explain: ExplainResult
    default_analyze: AnalyzeResult
    counterfactual_analyze: AnalyzeResult
    plan_changed: bool
    result_comparison: Any
    runtime: Optional[MeasurementResult]
    classification_metrics: Mapping[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        comparison = (
            self.result_comparison.to_dict()
            if hasattr(self.result_comparison, "to_dict")
            else _json_ready(self.result_comparison)
        )
        return {
            "sandbox_name": self.sandbox_name,
            "server_version": self.server_version,
            "statistics_refreshed": self.statistics_refreshed,
            "ordered_results": self.ordered_results,
            "default_explain": _json_ready(asdict(self.default_explain)),
            "counterfactual_explain": _json_ready(asdict(self.counterfactual_explain)),
            "default_analyze": _json_ready(asdict(self.default_analyze)),
            "counterfactual_analyze": _json_ready(asdict(self.counterfactual_analyze)),
            "plan_changed": self.plan_changed,
            "result_comparison": comparison,
            "runtime": self.runtime.to_dict() if self.runtime is not None else None,
            "classification_metrics": _json_ready(dict(self.classification_metrics)),
        }


def _json_ready(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, bytes):
        return {"encoding": "hex", "value": value.hex()}
    if isinstance(value, Mapping):
        return {str(key): _json_ready(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_ready(item) for item in value]
    return str(value)


def quote_identifier(name: str) -> str:
    """Quote a validated MySQL-family identifier."""

    if not _IDENTIFIER_RE.fullmatch(name):
        raise ValueError(f"invalid SQL identifier: {name!r}")
    return f"`{name}`"


def validate_sandbox_name(name: str) -> str:
    if not _SANDBOX_RE.fullmatch(name):
        raise SetupValidationError(
            "sandbox name must begin with 'puffer_ci_' and contain at most 48 suffix characters"
        )
    return name


def make_sandbox_name(dbms: str, case_id: str, run_id: str) -> str:
    """Build a stable, bounded sandbox name from untrusted display strings."""

    digest = hashlib.sha256(f"{dbms}\0{case_id}\0{run_id}".encode("utf-8")).hexdigest()[:16]
    dbms_part = re.sub(r"[^a-z0-9_]", "_", dbms.lower())[:10]
    return validate_sandbox_name(f"{SANDBOX_PREFIX}{dbms_part}_{digest}")


def split_sql_statements(sql: str) -> List[str]:
    """Split SQL on top-level semicolons while preserving quoted content.

    SQLancer setup files do not use stored-program delimiters.  Detecting and
    rejecting those constructs is preferable to accidentally executing a
    semicolon from a routine body as a separate statement.
    """

    statements: List[str] = []
    start = 0
    index = 0
    quote: Optional[str] = None
    line_comment = False
    block_comment = False
    while index < len(sql):
        char = sql[index]
        nxt = sql[index + 1] if index + 1 < len(sql) else ""
        if line_comment:
            if char in "\r\n":
                line_comment = False
            index += 1
            continue
        if block_comment:
            if char == "*" and nxt == "/":
                block_comment = False
                index += 2
            else:
                index += 1
            continue
        if quote:
            if char == "\\" and quote in {"'", '"'}:
                index += 2
                continue
            if char == quote:
                if index + 1 < len(sql) and sql[index + 1] == quote:
                    index += 2
                    continue
                quote = None
            index += 1
            continue
        if char in {"'", '"', "`"}:
            quote = char
            index += 1
            continue
        if char == "#":
            line_comment = True
            index += 1
            continue
        if char == "-" and nxt == "-" and (
            index + 2 >= len(sql) or sql[index + 2].isspace()
        ):
            line_comment = True
            index += 2
            continue
        if char == "/" and nxt == "*":
            block_comment = True
            index += 2
            continue
        if char == ";":
            statement = sql[start:index].strip()
            if statement:
                statements.append(statement)
            start = index + 1
        index += 1
    if quote:
        raise SetupValidationError("unterminated quoted value in SQL")
    if block_comment:
        raise SetupValidationError("unterminated block comment in SQL")
    tail = sql[start:].strip()
    if tail:
        statements.append(tail)
    return statements


def _unquote_identifier(value: str) -> str:
    value = value.strip()
    return value[1:-1].replace("``", "`") if value.startswith("`") else value


def _strip_leading_comments(statement: str) -> str:
    return re.sub(
        r"\A(?:\s|/\*.*?\*/|--[^\r\n]*(?:\r?\n|\Z)|\#[^\r\n]*(?:\r?\n|\Z))*",
        "",
        statement,
        flags=re.DOTALL,
    )


def _replace_database_qualifier(sql: str, source: Optional[str], target: str) -> str:
    """Replace source DB qualifiers outside quoted strings/comments."""

    if not source or source.lower() == target.lower():
        return sql
    source_lower = source.lower()
    out: List[str] = []
    index = 0
    while index < len(sql):
        char = sql[index]
        if char in {"'", '"'}:
            quote = char
            end = index + 1
            while end < len(sql):
                if sql[end] == "\\":
                    end += 2
                    continue
                if sql[end] == quote:
                    if end + 1 < len(sql) and sql[end + 1] == quote:
                        end += 2
                        continue
                    end += 1
                    break
                end += 1
            out.append(sql[index:end])
            index = end
            continue
        if char == "`":
            end = sql.find("`", index + 1)
            if end == -1:
                raise SetupValidationError("unterminated quoted identifier in setup")
            token = sql[index + 1 : end].replace("``", "`")
            suffix = sql[end + 1 :]
            qualifier = re.match(r"\s*\.", suffix)
            if token.lower() == source_lower and qualifier:
                out.append(quote_identifier(target))
            else:
                out.append(sql[index : end + 1])
            index = end + 1
            continue
        identifier = re.match(r"[A-Za-z_][A-Za-z0-9_]*", sql[index:])
        if identifier:
            token = identifier.group(0)
            end = index + len(token)
            if token.lower() == source_lower and re.match(r"\s*\.", sql[end:]):
                out.append(quote_identifier(target))
            else:
                out.append(token)
            index = end
            continue
        out.append(char)
        index += 1
    return "".join(out)


def rewrite_setup_sql(setup_sql: str, sandbox_name: str) -> List[str]:
    """Return setup statements scoped to exactly one owned sandbox database."""

    validate_sandbox_name(sandbox_name)
    statements = split_sql_statements(setup_sql)
    if not statements:
        raise SetupValidationError("setup.sql is empty")

    statements = [effective for statement in statements if (effective := _strip_leading_comments(statement))]
    prefix = [_LIFECYCLE_RE.fullmatch(statement) for statement in statements[:4]]
    # SQLancer first selects its bootstrap database, then recreates the dataset.
    # Ignore only this exact preamble; later switches and multiple datasets must
    # still fail, and no statement referencing the bootstrap database is replayed.
    if (len(prefix) == 4 and all(prefix)
            and tuple(match.group(1).split()[0].upper() for match in prefix) == ("USE", "DROP", "CREATE", "USE")
            and _unquote_identifier(prefix[0].group(2)).lower() == "test"
            and len({_unquote_identifier(match.group(2)).lower() for match in prefix[1:]}) == 1):
        statements = statements[1:]

    source_databases: set[str] = set()
    non_lifecycle: List[str] = []
    for statement in statements:
        effective_statement = _strip_leading_comments(statement)
        lifecycle = _LIFECYCLE_RE.fullmatch(effective_statement)
        if lifecycle:
            source_databases.add(_unquote_identifier(lifecycle.group(2)))
            continue
        if _LIFECYCLE_PREFIX_RE.match(effective_statement):
            raise SetupValidationError(
                "database lifecycle statement has unsupported trailing clauses"
            )
        if _PROHIBITED_SETUP_RE.match(effective_statement):
            raise SetupValidationError(
                f"unsupported setup statement: {effective_statement.split(None, 3)[0:3]}"
            )
        non_lifecycle.append(effective_statement)

    if len({item.lower() for item in source_databases}) > 1:
        raise SetupValidationError(
            f"setup references multiple database lifecycles: {sorted(source_databases)}"
        )
    source = next(iter(source_databases), None)
    rewritten: List[str] = [
        f"DROP DATABASE IF EXISTS {quote_identifier(sandbox_name)}",
        f"CREATE DATABASE {quote_identifier(sandbox_name)}",
        f"USE {quote_identifier(sandbox_name)}",
    ]
    for statement in non_lifecycle:
        for match in _OBJECT_REFERENCE_RE.finditer(statement):
            referenced = _unquote_identifier(match.group("first"))
            if source and referenced.lower() == source.lower():
                continue
            if referenced.lower() == sandbox_name.lower():
                continue
            raise SetupValidationError(
                f"setup contains an external qualified object reference: {match.group(0)!r}"
            )
        rewritten.append(_replace_database_qualifier(statement, source, sandbox_name))
    return rewritten


def validate_read_only_query(query: str) -> str:
    statements = split_sql_statements(query)
    if len(statements) != 1:
        raise QueryValidationError("candidate must contain exactly one SQL statement")
    normalized = statements[0].strip()
    without_leading_comments = re.sub(
        r"\A(?:\s|/\*.*?\*/|--[^\r\n]*(?:\r?\n|\Z)|\#[^\r\n]*(?:\r?\n|\Z))*",
        "",
        normalized,
        flags=re.DOTALL,
    )
    if not re.match(r"^(?:SELECT|WITH)\b", without_leading_comments, re.IGNORECASE):
        raise QueryValidationError("candidate query must be a SELECT or WITH query")
    if re.search(
        r"\b(?:INTO\s+(?:OUTFILE|DUMPFILE)|FOR\s+UPDATE|LOCK\s+IN\s+SHARE\s+MODE)\b",
        without_leading_comments,
        re.IGNORECASE,
    ):
        raise QueryValidationError("candidate query contains a write or locking clause")
    return normalized


def validate_sandbox_query(query: str, sandbox_name: str) -> str:
    """Validate a candidate and confine qualified table references to its sandbox."""

    validate_sandbox_name(sandbox_name)
    normalized = validate_read_only_query(query)
    for match in _OBJECT_REFERENCE_RE.finditer(normalized):
        referenced = _unquote_identifier(match.group("first"))
        if referenced.lower() != sandbox_name.lower():
            raise QueryValidationError(
                "candidate query references an external qualified object: "
                f"{match.group(0)!r}"
            )
    return normalized


def _approximate_value_bytes(value: Any) -> int:
    if value is None:
        return 4
    if isinstance(value, bytes):
        return len(value)
    if isinstance(value, str):
        return len(value.encode("utf-8", errors="surrogatepass"))
    return len(str(value).encode("utf-8", errors="replace"))


def _runtime_summary(samples: Sequence[float]) -> RuntimeSummary:
    if not samples:
        raise ValueError("at least one runtime sample is required")
    return RuntimeSummary(
        samples_ms=tuple(samples),
        median_ms=statistics.median(samples),
        mean_ms=statistics.fmean(samples),
        min_ms=min(samples),
        max_ms=max(samples),
    )


def _percentile(sorted_values: Sequence[float], probability: float) -> float:
    if not sorted_values:
        return math.nan
    position = probability * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[lower]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def paired_bootstrap_speedup_lower_bound(
    rounds: Sequence[PairRound], *, seed: int = 0, iterations: int = 10_000
) -> float:
    if not rounds:
        raise ValueError("at least one paired round is required")
    if iterations < 100:
        raise ValueError("bootstrap iterations must be at least 100")
    rng = random.Random(seed)
    ratios: List[float] = []
    count = len(rounds)
    for _ in range(iterations):
        sampled = [rounds[rng.randrange(count)] for _ in range(count)]
        default_median = statistics.median(item.default_runtime_ms for item in sampled)
        counterfactual_median = statistics.median(
            item.counterfactual_runtime_ms for item in sampled
        )
        ratios.append(
            default_median / counterfactual_median
            if counterfactual_median > 0.0
            else math.inf
        )
    ratios.sort()
    return _percentile(ratios, 0.05)


def extract_estimated_cost(plan: Any) -> Optional[float]:
    """Extract a comparable optimizer cost from MySQL-family JSON plans."""

    preferred: List[float] = []
    fallback: List[float] = []

    def visit(value: Any) -> None:
        if isinstance(value, Mapping):
            for key, child in value.items():
                if key.lower() in {"query_cost", "total_cost", "estimated_total_cost"}:
                    try:
                        number = float(child)
                    except (TypeError, ValueError):
                        pass
                    else:
                        if math.isfinite(number) and number >= 0:
                            preferred.append(number)
                elif key.lower() in {"cost", "read_cost", "eval_cost", "prefix_cost"}:
                    try:
                        number = float(child)
                    except (TypeError, ValueError):
                        pass
                    else:
                        if math.isfinite(number) and number >= 0:
                            fallback.append(number)
                visit(child)
        elif isinstance(value, (list, tuple)):
            for child in value:
                visit(child)

    visit(plan)
    if preferred:
        return preferred[0]
    if fallback:
        return max(fallback)
    return None


def _top_level_sql_text(sql: str) -> str:
    """Mask nested and quoted text, retaining only top-level SQL tokens."""

    output: List[str] = []
    depth = 0
    quote: Optional[str] = None
    index = 0
    while index < len(sql):
        char = sql[index]
        nxt = sql[index + 1] if index + 1 < len(sql) else ""
        if quote:
            if char == "\\" and quote in {"'", '"'}:
                index += 2
                continue
            if char == quote:
                if nxt == quote:
                    index += 2
                    continue
                quote = None
            index += 1
            continue
        if char in {"'", '"', "`"}:
            quote = char
            output.append(" ")
        elif char == "/" and nxt == "*":
            end = sql.find("*/", index + 2)
            index = len(sql) if end < 0 else end + 2
            output.append(" ")
            continue
        elif char == "#" or (char == "-" and nxt == "-"):
            end = sql.find("\n", index + 1)
            index = len(sql) if end < 0 else end + 1
            output.append(" ")
            continue
        elif char == "(":
            depth += 1
            output.append(" ")
        elif char == ")":
            depth = max(0, depth - 1)
            output.append(" ")
        elif depth == 0:
            output.append(char)
        else:
            output.append(" ")
        index += 1
    return "".join(output)


def _query_requires_order(query: str) -> bool:
    return bool(re.search(r"\bORDER\s+BY\b", _top_level_sql_text(query), re.IGNORECASE))


def _stable_plan_digest(explain: ExplainResult) -> str:
    value = explain.structure
    if value is None:
        value = _plan_shape(explain.parsed) if explain.parsed is not None else explain.raw
    encoded = json.dumps(
        _json_ready(value), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


_PLAN_SHAPE_KEYS = {
    "access_type",
    "join_type",
    "join_algorithm",
    "operation",
    "table_name",
    "key",
    "key_name",
    "index_name",
    "using_filesort",
    "using_temporary_table",
    "using_join_buffer",
    "covering",
    "dependent",
    "cacheable",
}


def _plan_shape(value: Any) -> Any:
    """Project a JSON plan to operator/access-path structure, excluding costs."""

    if isinstance(value, Mapping):
        projected: Dict[str, Any] = {}
        for raw_key, child in value.items():
            key = str(raw_key).lower()
            if key in _PLAN_SHAPE_KEYS and not isinstance(child, (Mapping, list, tuple)):
                if key == "operation":
                    projected[key] = _operation_family(str(child))
                else:
                    projected[key] = _json_ready(child)
            elif isinstance(child, (Mapping, list, tuple)):
                nested = _plan_shape(child)
                if nested not in ({}, [], None):
                    projected[key] = nested
        return projected
    if isinstance(value, (list, tuple)):
        return [nested for child in value if (nested := _plan_shape(child)) not in ({}, [], None)]
    return None


def _operation_family(operation: str) -> str:
    normalized = re.sub(r"\s+", " ", operation.lower().replace("`", "")).strip()
    normalized = normalized.split(":", 1)[0]
    normalized = re.sub(r"\s+on\s+.*$", "", normalized)
    normalized = re.sub(r"\s+using\s+.*$", "", normalized)
    return normalized


ConnectionFactory = Callable[..., Any]


def inline_initial_indexes(statements: Sequence[str]) -> List[str]:
    """Fold SQLancer's initial nonunique indexes into table creation.

    TiDB otherwise executes one online DDL job per index. Restrict this rewrite
    to generated tN/iN/cN names before the first SET/ANALYZE; later index
    changes and all other SQL remain verbatim.
    """
    prefix_end = next((i for i, sql in enumerate(statements) if re.match(r"\s*(?:SET|ANALYZE)\b", sql, re.I)), len(statements))
    tables: Dict[str, Tuple[int, re.Match]] = {}
    indexes: Dict[str, List[str]] = {}
    consumed = set()
    for i, sql in enumerate(statements[:prefix_end]):
        table = re.fullmatch(r"CREATE TABLE (t[0-9]+)\((.*)\)", sql, re.I | re.S)
        index = re.fullmatch(r"CREATE INDEX (i[0-9]+) ON (t[0-9]+)\((c[0-9]+(?:\s*,\s*c[0-9]+)*)\)", sql, re.I)
        if table:
            if table[1] in tables:
                return list(statements)
            tables[table[1]] = (i, table)
        elif index and index[2] in tables:
            indexes.setdefault(index[2], []).append(f"INDEX {index[1]} ({index[3]})")
            consumed.add(i)
        elif not (_LIFECYCLE_RE.fullmatch(sql) or re.match(r"INSERT INTO t[0-9]+(?:\s|\()", sql, re.I)):
            return list(statements)
    result = list(statements)
    for name, definitions in indexes.items():
        i, match = tables[name]
        result[i] = f"CREATE TABLE {name}({match[2]}, {', '.join(definitions)})"
    return [sql for i, sql in enumerate(result) if i not in consumed]


class BaseDatabaseAdapter:
    """Controlled operations shared by MySQL and MariaDB."""

    explain_prefix = "EXPLAIN FORMAT=JSON "
    analyze_prefix = ""
    statistics_suffix = ""

    def __init__(
        self,
        profile: DatabaseProfile,
        *,
        connection_factory: ConnectionFactory = pymysql.connect,
        password: Optional[str] = None,
    ) -> None:
        self.profile = profile
        self._connection_factory = connection_factory
        self._password_override = password
        self._active_sandbox: Optional[str] = None
        self._active_version: Optional[str] = None

    def _connect(self, database: Optional[str] = None) -> Any:
        password = (
            self._password_override
            if self._password_override is not None
            else self.profile.resolved_password()
        )
        kwargs: Dict[str, Any] = {
            "host": self.profile.host,
            "port": self.profile.port,
            "user": self.profile.user,
            "password": password,
            "autocommit": True,
            "charset": self.profile.charset,
            "connect_timeout": self.profile.connect_timeout_s,
            "read_timeout": self.profile.query_timeout_s + 5,
            "write_timeout": self.profile.query_timeout_s + 5,
            # Stream potentially large SELECT results instead of letting the
            # driver buffer an unbounded rowset before our limits can run.
            "cursorclass": pymysql.cursors.SSCursor,
        }
        if database:
            kwargs["database"] = database
        connection = self._connection_factory(**kwargs)
        if database:
            self._configure_query_timeout(connection)
        return connection

    quote_identifier = staticmethod(quote_identifier)
    is_statement_timeout = staticmethod(is_statement_timeout)

    @staticmethod
    def begin_data_transaction(connection: Any) -> None:
        connection.autocommit(False)

    def _configure_query_timeout(self, connection: Any) -> None:
        raise NotImplementedError

    def server_version(self) -> str:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT VERSION()")
                row = cursor.fetchone()
                return str(row[0]) if row else ""
        finally:
            connection.close()

    def validate_expected_version(self) -> str:
        version = self.server_version()
        expected = self.profile.expected_version_prefix
        if not matches_version(self.profile.dbms, version, expected):
            raise InvestigatorDBError(
                f"server version {version!r} does not start with expected prefix {expected!r}"
            )
        return version

    def create_sandbox(self, sandbox_name: str) -> None:
        validate_sandbox_name(sandbox_name)
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS {quote_identifier(sandbox_name)}")
                cursor.execute(f"CREATE DATABASE {quote_identifier(sandbox_name)}")
        finally:
            connection.close()

    def drop_sandbox(self, sandbox_name: str) -> None:
        validate_sandbox_name(sandbox_name)
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS {quote_identifier(sandbox_name)}")
        finally:
            connection.close()

    def load_setup(self, setup_sql: str, sandbox_name: str) -> List[str]:
        """Rewrite and execute setup from a server-level connection."""

        statements = rewrite_setup_sql(setup_sql, sandbox_name)
        if self.profile.dbms == "tidb":
            statements = inline_initial_indexes(statements)
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                for statement in statements:
                    cursor.execute(statement)
                    if cursor.description is not None:
                        cursor.fetchall()
        except Exception:
            try:
                self.drop_sandbox(sandbox_name)
            finally:
                raise
        finally:
            connection.close()
        return statements

    @contextmanager
    def sandbox(
        self, sandbox_name: str, setup_sql: str, *, keep: bool = False
    ) -> Iterator[str]:
        self.load_setup(setup_sql, sandbox_name)
        try:
            yield sandbox_name
        finally:
            if not keep:
                self.drop_sandbox(sandbox_name)

    def prepare_sandbox(self, case: Any, db_name: str) -> Dict[str, Any]:
        """Validate a parsed case and load it into an isolated database."""

        validate_sandbox_name(db_name)
        case_dbms = getattr(getattr(case, "dbms", None), "value", getattr(case, "dbms", None))
        if str(case_dbms).lower() != self.profile.dbms:
            raise InvestigatorDBError(
                f"case DBMS {case_dbms!r} does not match profile DBMS {self.profile.dbms!r}"
            )
        version = self.validate_expected_version()
        case_version = str(getattr(case, "database_version", ""))
        if case_version and not matches_version(
                self.profile.dbms, case_version, self.profile.expected_version_prefix):
            raise InvestigatorDBError(
                f"case version {case_version!r} does not match expected prefix "
                f"{self.profile.expected_version_prefix!r}"
            )
        statements = self.load_setup(str(case.setup_sql), db_name)
        self._active_sandbox = db_name
        self._active_version = version
        return {
            "sandbox_name": db_name,
            "server_version": version,
            "statement_count": len(statements),
            "tables": list(self.list_tables(db_name)),
        }

    def replay(
        self,
        case: Any,
        db_name: Optional[str] = None,
        *,
        ordered: Optional[bool] = None,
        warmups_per_query: int = 2,
        rounds: int = 9,
        seed: int = 0,
        max_rows: int = MAX_RESULT_ROWS,
        max_bytes: int = MAX_RESULT_BYTES,
        bootstrap_iterations: int = 10_000,
        timeout_as_limit: bool = False,
        continue_on_result_mismatch: bool = False,
        scaled_default_timeout_pass: bool = False,
        collect_analyze: bool = True,
        timeout_early_stop: bool = False,
        early_stop_min_speedup: float = 1.30,
        early_stop_fast_alt_ms: float = 100.0,
        early_stop_min_gap_ms: float = 5.0,
    ) -> ReplayResult:
        """Refresh, re-plan, compare complete results, and measure a case."""

        sandbox_name = db_name or self._active_sandbox
        if sandbox_name is None:
            raise InvestigatorDBError("prepare_sandbox must run before replay")
        validate_sandbox_name(sandbox_name)
        default_query = str(case.original_query)
        counterfactual_query = str(case.counterfactual_query)
        validate_sandbox_query(default_query, sandbox_name)
        validate_sandbox_query(counterfactual_query, sandbox_name)
        if ordered is None:
            ordered = _query_requires_order(default_query) or _query_requires_order(
                counterfactual_query
            )

        self.refresh_statistics(sandbox_name)
        default_explain = self.explain(sandbox_name, default_query)
        counterfactual_explain = self.explain(sandbox_name, counterfactual_query)
        timeout_observations: List[Mapping[str, Any]] = []

        def analyze(label: str, query: str) -> AnalyzeResult:
            if not collect_analyze:
                return AnalyzeResult(raw="Skipped during scaled validation", parsed=None)
            started = time.monotonic()
            try:
                res = self.explain_analyze(sandbox_name, query)
                # MySQL EXPLAIN ANALYZE can silently hit max_execution_time and emit
                # "(never executed)" without raising OperationalError 3024.
                if (timeout_as_limit
                        and (time.monotonic() - started) >= self.profile.query_timeout_s * 0.95
                        and "(never executed)" in res.raw):
                    timeout_observations.append({"side": label, "stage": "EXPLAIN_ANALYZE",
                                                 "limit_ms": self.profile.query_timeout_s * 1000})
                return res
            except Exception as error:
                if not timeout_as_limit or not self.is_statement_timeout(error):
                    raise
                timeout_observations.append({"side": label, "stage": "EXPLAIN_ANALYZE",
                                             "limit_ms": self.profile.query_timeout_s * 1000})
                return AnalyzeResult(raw="Statement deadline exceeded; execution plan unavailable", parsed=None)

        default_analyze = analyze("default", default_query)
        counterfactual_analyze = analyze("counterfactual", counterfactual_query)
        default_timed_out = lambda: any(item["side"] == "default" for item in timeout_observations)
        if scaled_default_timeout_pass and default_timed_out():
            comparison = {"equivalent": None, "reason": "DEFAULT_TIMEOUT_REQUIRES_MANUAL_REVIEW"}
        else:
            comparison_options: Dict[str, Any] = (
                {"timeout_as_limit": True, "timeout_observations": timeout_observations,
                 "known_timeout_sides": [str(item["side"]) for item in timeout_observations]}
                if timeout_as_limit else {})
            comparison = self.compare_query_results(
                sandbox_name,
                default_query,
                counterfactual_query,
                ordered=ordered,
                max_rows=max_rows,
                max_bytes=max_bytes,
                **comparison_options,
            )
        equivalent = comparison.get("equivalent") if isinstance(comparison, Mapping) else comparison.equivalent
        runtime: Optional[MeasurementResult] = None
        if (equivalent or continue_on_result_mismatch or (timeout_as_limit and equivalent is None)) and not (
                scaled_default_timeout_pass and default_timed_out()):
            timing_options: Dict[str, Any] = {}
            if timeout_as_limit:
                timing_options["timeout_as_limit"] = True
                timing_options["known_timeout_sides"] = list(dict.fromkeys(
                    str(item["side"]) for item in timeout_observations))
                if timeout_early_stop:
                    timing_options.update(
                        timeout_early_stop=True,
                        early_stop_min_speedup=early_stop_min_speedup,
                        early_stop_fast_alt_ms=early_stop_fast_alt_ms,
                        early_stop_min_gap_ms=early_stop_min_gap_ms,
                    )
            runtime = self.measure_pair(
                sandbox_name,
                default_query,
                counterfactual_query,
                warmups_per_query=warmups_per_query,
                rounds=rounds,
                seed=seed,
                max_rows=max_rows,
                bootstrap_iterations=bootstrap_iterations,
                **timing_options,
            )
            timeout_observations.extend(runtime.timeout_observations)
        default_cost = default_explain.estimated_cost
        counterfactual_cost = counterfactual_explain.estimated_cost
        cost_ratio = (
            default_cost / counterfactual_cost
            if default_cost is not None
            and counterfactual_cost is not None
            and counterfactual_cost > 0.0
            else None
        )
        metrics: Dict[str, Any] = {
            "defaultEstimatedCost": default_cost,
            "counterfactualEstimatedCost": counterfactual_cost,
            "costRatio": cost_ratio,
            "resultEquivalent": equivalent,
            "runtimeValidated": runtime is not None,
            "timeoutObserved": bool(timeout_observations),
            "timeoutObservations": timeout_observations,
            "timeoutLimitMs": self.profile.query_timeout_s * 1000 if timeout_observations else None,
            "runtimeCensored": runtime is not None and any(
                item.default_timeout_bound or item.counterfactual_timeout_bound for item in runtime.rounds),
            "timeoutEarlyStopped": bool(getattr(runtime, "early_stopped", False)) if runtime is not None else False,
            "timeoutEarlyStopReason": getattr(runtime, "early_stop_reason", None) if runtime is not None else None,
        }
        if runtime is not None:
            metrics.update(
                {
                    "defaultRuntimeMedianMs": runtime.default.median_ms,
                    "counterfactualRuntimeMedianMs": runtime.counterfactual.median_ms,
                    "runtimeSpeedup": runtime.speedup,
                    "runtimeAbsGapMs": runtime.absolute_gap_ms,
                }
            )
        plan_changed = _stable_plan_digest(default_explain) != _stable_plan_digest(
            counterfactual_explain
        )
        return ReplayResult(
            sandbox_name=sandbox_name,
            server_version=self._active_version or self.server_version(),
            statistics_refreshed=True,
            ordered_results=ordered,
            default_explain=default_explain,
            counterfactual_explain=counterfactual_explain,
            default_analyze=default_analyze,
            counterfactual_analyze=counterfactual_analyze,
            plan_changed=plan_changed,
            result_comparison=comparison,
            runtime=runtime,
            classification_metrics=metrics,
        )

    def profile_trigger(
        self, case: Any = None, db_name: Optional[str] = None, *, top_k: int = 10
    ) -> Dict[str, Any]:
        """Collect deterministic schema/distribution evidence for amplification."""

        sandbox_name = db_name or self._active_sandbox
        if sandbox_name is None:
            raise InvestigatorDBError("prepare_sandbox must run before profile_trigger")
        profiles = self.profile_database(sandbox_name, top_k=top_k)
        query_text = ""
        if case is not None:
            query_text = f"{case.original_query}\n{case.counterfactual_query}"
        query_tables = [
            profile.name
            for profile in profiles
            if re.search(
                rf"(?<![A-Za-z0-9_$])`?{re.escape(profile.name)}`?(?![A-Za-z0-9_$])",
                query_text,
                re.IGNORECASE,
            )
        ]
        return {
            "sandbox_name": sandbox_name,
            "tables": [profile.to_dict() for profile in profiles],
            "query_tables": query_tables,
        }

    def cleanup(self, db_name: Optional[str] = None) -> None:
        sandbox_name = db_name or self._active_sandbox
        if sandbox_name is None:
            return
        self.drop_sandbox(sandbox_name)
        if sandbox_name == self._active_sandbox:
            self._active_sandbox = None
            self._active_version = None

    def _with_database(self, sandbox_name: str) -> Any:
        validate_sandbox_name(sandbox_name)
        return self._connect(sandbox_name)

    def _read_plan(self, sandbox_name: str, query: str, prefix: str,
                   *, runtime_plan: bool = False) -> Tuple[str, Any]:
        query = validate_read_only_query(query)
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(prefix + query)
                columns = [column[0] for column in cursor.description]
                rows = cursor.fetchall()
            if BACKENDS[self.profile.dbms].plan_format == "tidb-verbose":
                raw = format_table(columns, rows)
                return raw, parse_plan(raw, require_cost=not runtime_plan)
            raw = "\n".join(str(row[0]) for row in rows)
            try:
                parsed = json.loads(raw)
            except (json.JSONDecodeError, TypeError):
                parsed = None
            return raw, parsed
        finally:
            connection.close()

    def explain(self, sandbox_name: str, query: str) -> ExplainResult:
        raw, parsed = self._read_plan(sandbox_name, query, self.explain_prefix)
        cost = (parsed["estimated_cost"] if BACKENDS[self.profile.dbms].plan_format == "tidb-verbose"
                else extract_estimated_cost(parsed))
        return ExplainResult(raw=raw, parsed=parsed, estimated_cost=cost)

    def explain_analyze(self, sandbox_name: str, query: str) -> AnalyzeResult:
        raw, parsed = self._read_plan(sandbox_name, query, self.analyze_prefix, runtime_plan=True)
        return AnalyzeResult(raw=raw, parsed=parsed)

    def fetch_complete_result(
        self,
        sandbox_name: str,
        query: str,
        *,
        max_rows: int = MAX_RESULT_ROWS,
        max_bytes: int = MAX_RESULT_BYTES,
        batch_size: int = 2_048,
    ) -> FetchedResult:
        query = validate_read_only_query(query)
        if max_rows <= 0 or max_bytes <= 0 or batch_size <= 0:
            raise ValueError("result limits and batch size must be positive")
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(query)
                columns = tuple(str(item[0]) for item in (cursor.description or ()))
                rows: List[Tuple[Any, ...]] = []
                approximate_bytes = 0
                while True:
                    batch = cursor.fetchmany(batch_size)
                    if not batch:
                        break
                    for raw_row in batch:
                        row = tuple(raw_row)
                        approximate_bytes += sum(_approximate_value_bytes(value) for value in row)
                        rows.append(row)
                        if len(rows) > max_rows:
                            raise QueryLimitExceeded(
                                f"query result exceeds {max_rows:,} rows"
                            )
                        if approximate_bytes > max_bytes:
                            raise QueryLimitExceeded(
                                f"query result exceeds {max_bytes:,} approximate bytes"
                            )
            return FetchedResult(
                columns=columns,
                rows=tuple(rows),
                row_count=len(rows),
                approximate_bytes=approximate_bytes,
            )
        finally:
            connection.close()

    def result_digest(
        self,
        sandbox_name: str,
        query: str,
        *,
        ordered: bool,
        max_rows: int = MAX_RESULT_ROWS,
        max_bytes: int = MAX_RESULT_BYTES,
    ) -> Any:
        """Capture a complete result using the shared exact-comparison model."""

        query = validate_read_only_query(query)
        from .results import ResultLimitExceeded, digest_rows

        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(query)

                def rows() -> Iterator[Tuple[Any, ...]]:
                    while True:
                        batch = cursor.fetchmany(2_048)
                        if not batch:
                            return
                        for row in batch:
                            yield tuple(row)

                try:
                    return digest_rows(rows(), ordered, max_rows, max_bytes)
                except ResultLimitExceeded as error:
                    raise QueryLimitExceeded(str(error)) from error
        finally:
            connection.close()

    def compare_query_results(
        self,
        sandbox_name: str,
        default_query: str,
        counterfactual_query: str,
        *,
        ordered: bool,
        max_rows: int = MAX_RESULT_ROWS,
        max_bytes: int = MAX_RESULT_BYTES,
        timeout_as_limit: bool = False,
        timeout_observations: Optional[List[Mapping[str, Any]]] = None,
        known_timeout_sides: Sequence[str] = (),
    ) -> Any:
        from .models import ResultComparison

        known = set(known_timeout_sides) if timeout_as_limit else set()
        digests = {}
        for label, query in (("default", default_query), ("counterfactual", counterfactual_query)):
            if label in known:
                continue
            try:
                digests[label] = self.result_digest(
                    sandbox_name, query, ordered=ordered, max_rows=max_rows, max_bytes=max_bytes)
            except Exception as error:
                if not timeout_as_limit or not self.is_statement_timeout(error):
                    raise
                if timeout_observations is not None:
                    timeout_observations.append({"side": label, "stage": "RESULT_COMPARISON",
                                                 "limit_ms": self.profile.query_timeout_s * 1000})
        if len(digests) != 2:
            return {"equivalent": None, "reason": "STATEMENT_TIMEOUT_EQUIVALENCE_UNVERIFIED",
                    "left": digests["default"].to_dict() if "default" in digests else None,
                    "right": digests["counterfactual"].to_dict() if "counterfactual" in digests else None}
        default_result = digests["default"]
        counterfactual_result = digests["counterfactual"]
        if default_result.row_count != counterfactual_result.row_count:
            return ResultComparison(False, default_result, counterfactual_result, "row_count_mismatch")
        if default_result.digest_sha256 != counterfactual_result.digest_sha256:
            reason = "ordered_rows_differ" if ordered else "row_multisets_differ"
            return ResultComparison(False, default_result, counterfactual_result, reason)
        return ResultComparison(True, default_result, counterfactual_result, "complete_results_equal")

    def _consume_query(self, connection: Any, query: str, max_rows: int) -> int:
        with connection.cursor() as cursor:
            cursor.execute(query)
            row_count = 0
            while True:
                batch = cursor.fetchmany(2_048)
                if not batch:
                    return row_count
                row_count += len(batch)
                if row_count > max_rows:
                    raise QueryLimitExceeded(
                        f"timed query result exceeds {max_rows:,} rows"
                    )

    def measure_pair(
        self,
        sandbox_name: str,
        default_query: str,
        counterfactual_query: str,
        *,
        warmups_per_query: int = 2,
        rounds: int = 9,
        seed: int = 0,
        max_rows: int = MAX_RESULT_ROWS,
        bootstrap_iterations: int = 10_000,
        timeout_as_limit: bool = False,
        known_timeout_sides: Sequence[str] = (),
        timeout_early_stop: bool = False,
        early_stop_min_speedup: float = 1.30,
        early_stop_fast_alt_ms: float = 100.0,
        early_stop_min_gap_ms: float = 5.0,
    ) -> MeasurementResult:
        """Measure a Q/Q' pair in deterministic, alternating AB/BA order."""

        default_query = validate_read_only_query(default_query)
        counterfactual_query = validate_read_only_query(counterfactual_query)
        if warmups_per_query < 0 or rounds <= 0:
            raise ValueError("warmups must be non-negative and rounds must be positive")
        connection = self._with_database(sandbox_name)
        censored = set(known_timeout_sides) if timeout_as_limit else set()
        observations: List[Mapping[str, Any]] = []
        early_stopped = False
        early_stop_reason: Optional[str] = None

        def execute(label: str, query: str, phase: str, index: int) -> tuple[float, bool]:
            nonlocal connection
            if label in censored:
                return float(self.profile.query_timeout_s * 1000), True
            started = time.perf_counter_ns()
            try:
                self._consume_query(connection, query, max_rows)
            except Exception as error:
                if not timeout_as_limit or not self.is_statement_timeout(error):
                    raise
                censored.add(label)
                observations.append({"side": label, "stage": phase, "round_index": index,
                                     "limit_ms": self.profile.query_timeout_s * 1000})
                # Reuse the observed bound for later rounds rather than waiting
                # for the same deadline repeatedly; round flags expose reuse.
                connection.close()
                connection = self._with_database(sandbox_name)
                return float(self.profile.query_timeout_s * 1000), True
            return (time.perf_counter_ns() - started) / 1_000_000.0, False

        try:
            warmup_order = (("default", default_query), ("counterfactual", counterfactual_query))
            for warmup_index in range(warmups_per_query):
                ordered = warmup_order if (warmup_index + seed) % 2 == 0 else tuple(reversed(warmup_order))
                for label, query in ordered:
                    execute(label, query, "WARMUP", warmup_index)

            measured: List[PairRound] = []
            for round_index in range(rounds):
                default_first = (round_index + seed) % 2 == 0
                labels_and_queries = (
                    (("default", default_query), ("counterfactual", counterfactual_query))
                    if default_first
                    else (("counterfactual", counterfactual_query), ("default", default_query))
                )
                times: Dict[str, float] = {}
                bounds: Dict[str, bool] = {}
                for label, query in labels_and_queries:
                    times[label], bounds[label] = execute(label, query, "MEASUREMENT", round_index)
                measured.append(
                    PairRound(
                        round_index=round_index,
                        order="AB" if default_first else "BA",
                        default_runtime_ms=times["default"],
                        counterfactual_runtime_ms=times["counterfactual"],
                        default_timeout_bound=bounds["default"],
                        counterfactual_timeout_bound=bounds["counterfactual"],
                    )
                )
                if timeout_as_limit and timeout_early_stop and (bounds["default"] or bounds["counterfactual"]):
                    if bounds["default"] and bounds["counterfactual"]:
                        early_stopped = True
                        early_stop_reason = "BOTH_SIDES_TIMEOUT"
                        break
                    if bounds["counterfactual"] and not bounds["default"]:
                        early_stopped = True
                        early_stop_reason = "COUNTERFACTUAL_TIMEOUT"
                        break
                    alt_ms = times["counterfactual"]
                    limit_ms = times["default"]
                    if (alt_ms > 0.0
                            and (alt_ms < early_stop_fast_alt_ms
                                 or (limit_ms / alt_ms) >= max(early_stop_min_speedup * 2.0, 2.0))
                            and (limit_ms - alt_ms) >= early_stop_min_gap_ms):
                        early_stopped = True
                        early_stop_reason = "DEFAULT_TIMEOUT_FAST_COUNTERFACTUAL"
                        break
        finally:
            connection.close()

        default_summary = _runtime_summary([item.default_runtime_ms for item in measured])
        counterfactual_summary = _runtime_summary(
            [item.counterfactual_runtime_ms for item in measured]
        )
        # Reuse the shared runtime implementation so the controller and the
        # database layer cannot drift on bootstrap semantics.
        from .runtime import summarize_paired_runtimes

        shared_stats = summarize_paired_runtimes(
            [item.default_runtime_ms for item in measured],
            [item.counterfactual_runtime_ms for item in measured],
            bootstrap_iterations=bootstrap_iterations,
            bootstrap_seed=seed,
        )
        return MeasurementResult(
            warmups_per_query=warmups_per_query,
            rounds=tuple(measured),
            default=default_summary,
            counterfactual=counterfactual_summary,
            speedup=shared_stats.median_speedup,
            absolute_gap_ms=shared_stats.median_absolute_gap_ms,
            speedup_bootstrap_95_lower=None if censored else shared_stats.bootstrap_speedup_lower_bound,
            counterfactual_wins=shared_stats.counterfactual_wins,
            timeout_observations=tuple(observations),
            early_stopped=early_stopped,
            early_stop_reason=early_stop_reason,
        )

    def list_tables(self, sandbox_name: str) -> Tuple[str, ...]:
        validate_sandbox_name(sandbox_name)
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT TABLE_NAME FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",
                    (sandbox_name,),
                )
                return tuple(str(row[0]) for row in cursor.fetchall())
        finally:
            connection.close()

    def row_count(self, sandbox_name: str, table: str, *, connection: Any = None) -> int:
        validate_sandbox_name(sandbox_name)
        quoted = self.quote_identifier(table)
        owned = connection is None
        connection = connection or self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"SELECT COUNT(*) FROM {quoted}")
                row = cursor.fetchone()
                return int(row[0]) if row else 0
        finally:
            if owned:
                connection.close()

    def _columns(self, connection: Any, sandbox_name: str, table: str) -> Tuple[ColumnInfo, ...]:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE "
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s "
                "ORDER BY ORDINAL_POSITION",
                (sandbox_name, table),
            )
            return tuple(
                ColumnInfo(
                    name=str(row[0]),
                    data_type=str(row[1]),
                    column_type=str(row[2]),
                    nullable=str(row[3]).upper() == "YES",
                )
                for row in cursor.fetchall()
            )

    def _constraints(
        self, connection: Any, sandbox_name: str, table: str
    ) -> Tuple[ConstraintInfo, ...]:
        constraints: Dict[Tuple[str, str], List[str]] = {}
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE, kcu.COLUMN_NAME "
                "FROM information_schema.TABLE_CONSTRAINTS tc "
                "LEFT JOIN information_schema.KEY_COLUMN_USAGE kcu "
                "ON tc.CONSTRAINT_SCHEMA=kcu.CONSTRAINT_SCHEMA "
                "AND tc.TABLE_NAME=kcu.TABLE_NAME "
                "AND tc.CONSTRAINT_NAME=kcu.CONSTRAINT_NAME "
                "WHERE tc.CONSTRAINT_SCHEMA=%s AND tc.TABLE_NAME=%s "
                "ORDER BY tc.CONSTRAINT_NAME, kcu.ORDINAL_POSITION",
                (sandbox_name, table),
            )
            for row in cursor.fetchall():
                key = (str(row[0]), str(row[1]).upper())
                if row[2] is not None:
                    constraints.setdefault(key, []).append(str(row[2]))
                else:
                    constraints.setdefault(key, [])

            # Some servers expose a unique index without a TABLE_CONSTRAINTS row.
            cursor.execute(f"SHOW INDEX FROM {quote_identifier(table)}")
            description = [str(item[0]).lower() for item in (cursor.description or ())]
            for row in cursor.fetchall():
                record = dict(zip(description, row))
                if int(record.get("non_unique", 1)) == 0:
                    name = str(record.get("key_name", "UNNAMED_UNIQUE"))
                    kind = "PRIMARY KEY" if name.upper() == "PRIMARY" else "UNIQUE"
                    key = (name, kind)
                    column = record.get("column_name")
                    if column is not None and str(column) not in constraints.setdefault(key, []):
                        constraints[key].append(str(column))
        return tuple(
            ConstraintInfo(name=name, kind=kind, columns=tuple(columns))
            for (name, kind), columns in sorted(constraints.items())
        )

    def table_profile(
        self, sandbox_name: str, table: str, *, top_k: int = 10
    ) -> TableProfile:
        validate_sandbox_name(sandbox_name)
        if top_k <= 0:
            raise ValueError("top_k must be positive")
        table_q = quote_identifier(table)
        connection = self._with_database(sandbox_name)
        try:
            columns = self._columns(connection, sandbox_name, table)
            if not columns:
                raise InvestigatorDBError(f"table does not exist in sandbox: {table}")
            constraints = self._constraints(connection, sandbox_name, table)
            row_count = self.row_count(sandbox_name, table, connection=connection)
            with connection.cursor() as cursor:
                cursor.execute(f"SHOW CREATE TABLE {table_q}")
                create_row = cursor.fetchone()
                create_sql = str(create_row[1]) if create_row and len(create_row) > 1 else ""
                cursor.execute(
                    "SELECT COALESCE(DATA_LENGTH,0), COALESCE(INDEX_LENGTH,0) "
                    "FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s",
                    (sandbox_name, table),
                )
                size_row = cursor.fetchone()
                data_bytes = int(size_row[0]) if size_row else 0
                index_bytes = int(size_row[1]) if size_row else 0

                column_profiles: List[ColumnProfile] = []
                for column in columns:
                    column_q = quote_identifier(column.name)
                    cursor.execute(
                        f"SELECT SUM({column_q} IS NULL), COUNT(DISTINCT {column_q}), "
                        f"MIN({column_q}), MAX({column_q}) FROM {table_q}"
                    )
                    summary = cursor.fetchone() or (0, 0, None, None)
                    cursor.execute(
                        f"SELECT {column_q}, COUNT(*) AS frequency FROM {table_q} "
                        f"GROUP BY {column_q} ORDER BY frequency DESC, {column_q} LIMIT {int(top_k)}"
                    )
                    top_values = tuple((row[0], int(row[1])) for row in cursor.fetchall())
                    column_profiles.append(
                        ColumnProfile(
                            name=column.name,
                            null_count=int(summary[0] or 0),
                            distinct_count=int(summary[1] or 0),
                            minimum=summary[2],
                            maximum=summary[3],
                            top_values=top_values,
                        )
                    )
            return TableProfile(
                name=table,
                row_count=row_count,
                create_sql=create_sql,
                columns=columns,
                constraints=constraints,
                column_profiles=tuple(column_profiles),
                data_bytes=data_bytes,
                index_bytes=index_bytes,
            )
        finally:
            connection.close()

    def profile_database(self, sandbox_name: str, *, top_k: int = 10) -> Tuple[TableProfile, ...]:
        return tuple(
            self.table_profile(sandbox_name, table, top_k=top_k)
            for table in self.list_tables(sandbox_name)
        )

    def table_constraints(self, sandbox_name: str, table: str) -> Tuple[ConstraintInfo, ...]:
        validate_sandbox_name(sandbox_name)
        quote_identifier(table)
        connection = self._with_database(sandbox_name)
        try:
            constraints = self._constraints(connection, sandbox_name, table)
            # An empty column list indicates that INFORMATION_SCHEMA found no
            # matching table; make that distinction explicit.
            if not self._columns(connection, sandbox_name, table):
                raise InvestigatorDBError(f"table does not exist in sandbox: {table}")
            return constraints
        finally:
            connection.close()

    def refresh_statistics(self, sandbox_name: str, tables: Optional[Sequence[str]] = None) -> None:
        selected = tuple(tables or self.list_tables(sandbox_name))
        if not selected:
            return
        table_list = ", ".join(quote_identifier(table) for table in selected)
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"ANALYZE TABLE {table_list}{self.statistics_suffix}")
                cursor.fetchall()
        finally:
            connection.close()

    def amplification_blockers(self, sandbox_name: str, table: str) -> Tuple[str, ...]:
        blocked = {"PRIMARY KEY", "UNIQUE", "FOREIGN KEY"}
        constraints = self.table_constraints(sandbox_name, table)
        return tuple(
            sorted({item.kind for item in constraints if item.kind.upper() in blocked})
        )

    def amplify_table(
        self,
        sandbox_name: str,
        table: str,
        target_rows: int,
        *,
        seed: int = 0,
        hard_cap_rows: int = 300_000,
    ) -> int:
        """Grow one unconstrained table by deterministic whole-row replication."""

        result = self.amplify_tables(
            sandbox_name,
            {table: target_rows},
            seed=seed,
            hard_cap_rows=hard_cap_rows,
        )
        return result[table]

    def amplify_tables(
        self,
        sandbox_name: str,
        target_rows: Mapping[str, int],
        *,
        seed: int = 0,
        hard_cap_rows: int = 300_000,
    ) -> Dict[str, int]:
        """Atomically grow multiple unconstrained tables to exact row targets."""

        validate_sandbox_name(sandbox_name)
        if not target_rows:
            raise AmplificationRefused("no tables selected for amplification")
        quoted: Dict[str, str] = {}
        for table, target in target_rows.items():
            quoted[table] = quote_identifier(table)
            if int(target) < 0 or int(target) > hard_cap_rows:
                raise AmplificationRefused(
                    f"target {int(target):,} for {table} is outside the "
                    f"0..{hard_cap_rows:,} row budget"
                )
            blockers = self.amplification_blockers(sandbox_name, table)
            if blockers:
                raise AmplificationRefused(
                    f"table {table} has unsupported constraints: {', '.join(blockers)}"
                )

        connection = self._with_database(sandbox_name)
        try:
            connection.autocommit(False)
            current = {
                table: self.row_count(sandbox_name, table, connection=connection)
                for table in target_rows
            }
            for offset, (table, target_value) in enumerate(target_rows.items()):
                target = int(target_value)
                table_q = quoted[table]
                if current[table] == 0 and target > 0:
                    raise AmplificationRefused(
                        f"cannot empirically amplify empty table {table}"
                    )
                while current[table] < target:
                    addition = min(current[table], target - current[table])
                    if addition == current[table]:
                        sql = f"INSERT INTO {table_q} SELECT * FROM {table_q}"
                    else:
                        # A seeded whole-row sample preserves intra-row correlations.
                        sql = (
                            f"INSERT INTO {table_q} SELECT * FROM {table_q} "
                            f"ORDER BY RAND({int(seed + offset)}) LIMIT {int(addition)}"
                        )
                    with connection.cursor() as cursor:
                        cursor.execute(sql)
                    current[table] += addition
            connection.commit()
            return current
        except Exception:
            connection.rollback()
            raise
        finally:
            try:
                connection.autocommit(True)
            except Exception:
                pass
            connection.close()


class MySQLAdapter(BaseDatabaseAdapter):
    analyze_prefix = "EXPLAIN ANALYZE "

    def __init__(self, profile: DatabaseProfile, **kwargs: Any) -> None:
        if profile.dbms != "mysql":
            raise ValueError("MySQLAdapter requires a mysql profile")
        super().__init__(profile, **kwargs)

    def _configure_query_timeout(self, connection: Any) -> None:
        with connection.cursor() as cursor:
            cursor.execute(f"SET SESSION max_execution_time={int(self.profile.query_timeout_s * 1000)}")


class MariaDBAdapter(BaseDatabaseAdapter):
    analyze_prefix = "ANALYZE FORMAT=JSON "

    def __init__(self, profile: DatabaseProfile, **kwargs: Any) -> None:
        if profile.dbms != "mariadb":
            raise ValueError("MariaDBAdapter requires a mariadb profile")
        super().__init__(profile, **kwargs)

    def _configure_query_timeout(self, connection: Any) -> None:
        with connection.cursor() as cursor:
            cursor.execute(
                f"SET SESSION max_statement_time={float(self.profile.query_timeout_s):.6f}"
            )


class TiDBAdapter(BaseDatabaseAdapter):
    explain_prefix = "EXPLAIN FORMAT='verbose' "
    analyze_prefix = "EXPLAIN ANALYZE "
    statistics_suffix = " ALL COLUMNS"

    def __init__(self, profile: DatabaseProfile, **kwargs: Any) -> None:
        if profile.dbms != "tidb":
            raise ValueError("TiDBAdapter requires a tidb profile")
        super().__init__(profile, **kwargs)

    def _configure_query_timeout(self, connection: Any) -> None:
        with connection.cursor() as cursor:
            cursor.execute(f"SET SESSION max_execution_time={int(self.profile.query_timeout_s * 1000)}")
            cursor.execute("SET SESSION tidb_enable_prepared_plan_cache = OFF")
            cursor.execute("SET SESSION tidb_enable_non_prepared_plan_cache = OFF")
            cursor.execute("SET SESSION tidb_analyze_version = 2")


def create_adapter(
    profile: DatabaseProfile,
    *,
    connection_factory: ConnectionFactory = pymysql.connect,
    password: Optional[str] = None,
) -> BaseDatabaseAdapter:
    if profile.dbms == "oceanbase":
        from .oceanbase import OceanBaseAdapter
        return OceanBaseAdapter(profile, connection_factory=connection_factory, password=password)
    if profile.dbms == "cockroachdb":
        from .cockroachdb import CockroachDBAdapter
        options = {"password": password}
        if connection_factory is not pymysql.connect:
            options["connection_factory"] = connection_factory
        return CockroachDBAdapter(profile, **options)
    adapter_type = {"mysql": MySQLAdapter, "mariadb": MariaDBAdapter, "tidb": TiDBAdapter}[profile.dbms]
    return adapter_type(
        profile, connection_factory=connection_factory, password=password
    )


__all__ = [
    "AmplificationRefused",
    "AnalyzeResult",
    "BaseDatabaseAdapter",
    "ColumnInfo",
    "ColumnProfile",
    "ConstraintInfo",
    "DatabaseProfile",
    "ExplainResult",
    "FetchedResult",
    "InvestigatorDBError",
    "MAX_RESULT_BYTES",
    "MAX_RESULT_ROWS",
    "MariaDBAdapter",
    "MeasurementResult",
    "MySQLAdapter",
    "PairRound",
    "QueryLimitExceeded",
    "QueryValidationError",
    "RuntimeSummary",
    "SANDBOX_PREFIX",
    "SetupValidationError",
    "TableProfile",
    "create_adapter",
    "extract_estimated_cost",
    "make_sandbox_name",
    "paired_bootstrap_speedup_lower_bound",
    "quote_identifier",
    "rewrite_setup_sql",
    "split_sql_statements",
    "validate_read_only_query",
    "validate_sandbox_query",
    "validate_sandbox_name",
]
