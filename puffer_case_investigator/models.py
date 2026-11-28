"""Core data types shared by the PUFFER Case Investigator.

The controller, database adapters, and optional LLM integration exchange these
small, JSON-friendly objects.  Keeping the verdict vocabulary here prevents a
language model or an adapter from inventing terminal states.
"""

from __future__ import annotations

import dataclasses
import enum
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Tuple


class StringEnum(str, enum.Enum):
    """A string enum whose value survives normal JSON encoding."""

    def __str__(self) -> str:
        return self.value


class DBMS(StringEnum):
    OCEANBASE = "oceanbase"
    MYSQL = "mysql"
    MARIADB = "mariadb"
    TIDB = "tidb"
    COCKROACHDB = "cockroachdb"


class Verdict(StringEnum):
    REPORT_READY = "REPORT_READY"
    DO_NOT_REPORT_DUPLICATE = "DO_NOT_REPORT_DUPLICATE"
    DO_NOT_REPORT_INVALID = "DO_NOT_REPORT_INVALID"
    DO_NOT_REPORT_UNSTABLE = "DO_NOT_REPORT_UNSTABLE"
    INCONCLUSIVE = "INCONCLUSIVE"


class NoveltyStatus(StringEnum):
    EXACT_DUPLICATE = "EXACT_DUPLICATE"
    LIKELY_SAME_FAMILY = "LIKELY_SAME_FAMILY"
    NO_CLOSE_MATCH_FOUND = "NO_CLOSE_MATCH_FOUND"
    CHECK_UNAVAILABLE = "CHECK_UNAVAILABLE"


class Stage(StringEnum):
    INGEST = "INGEST"
    STATIC_VALIDATE = "STATIC_VALIDATE"
    LOCAL_DEDUP = "LOCAL_DEDUP"
    CLEAN_REPLAY = "CLEAN_REPLAY"
    COMMUNITY_SEARCH = "COMMUNITY_SEARCH"
    PROFILE_TRIGGER = "PROFILE_TRIGGER"
    OPTIONAL_AMPLIFICATION = "OPTIONAL_AMPLIFICATION"
    FINAL_REPLAY = "FINAL_REPLAY"
    REPORT_READY = "REPORT_READY"
    DO_NOT_REPORT_DUPLICATE = "DO_NOT_REPORT_DUPLICATE"
    DO_NOT_REPORT_INVALID = "DO_NOT_REPORT_INVALID"
    DO_NOT_REPORT_UNSTABLE = "DO_NOT_REPORT_UNSTABLE"
    INCONCLUSIVE = "INCONCLUSIVE"


class StaticValidationStatus(StringEnum):
    EQUIVALENT = "EQUIVALENT"
    NOT_EQUIVALENT = "NOT_EQUIVALENT"
    UNPROVEN = "UNPROVEN"


class NineCellCategory(StringEnum):
    A = "A"
    B = "B"
    C = "C"
    D = "D"
    E = "E"
    F = "F"
    G = "G"
    H = "H"
    I = "I"


@dataclass(frozen=True)
class CaseArtifact:
    """A parsed, immutable view of a persisted PERFORMANCE_HINT case."""

    case_dir: Path
    case_id: str
    dbms: DBMS
    database_version: str
    setup_sql: str
    original_query: str
    counterfactual_query: str
    metrics: Mapping[str, Any]
    signature: Optional[Mapping[str, Any]] = None
    default_explain: Optional[Any] = None
    counterfactual_explain: Optional[Any] = None
    source_files: Mapping[str, Path] = field(default_factory=dict)

    @property
    def original_category(self) -> Optional[NineCellCategory]:
        raw = self.metrics.get("nineCellCategory")
        try:
            return NineCellCategory(str(raw).upper()) if raw is not None else None
        except ValueError:
            return None

    def to_dict(self, include_sql: bool = False) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "case_dir": str(self.case_dir),
            "case_id": self.case_id,
            "dbms": self.dbms.value,
            "database_version": self.database_version,
            "original_category": self.original_category.value if self.original_category else None,
            "source_files": {name: str(path) for name, path in self.source_files.items()},
        }
        if include_sql:
            result.update(
                {
                    "setup_sql": self.setup_sql,
                    "original_query": self.original_query,
                    "counterfactual_query": self.counterfactual_query,
                }
            )
        return result


@dataclass(frozen=True)
class StaticValidationResult:
    status: StaticValidationStatus
    reason: str
    evidence: Tuple[str, ...] = ()

    @property
    def equivalent(self) -> bool:
        return self.status is StaticValidationStatus.EQUIVALENT

    def to_dict(self) -> Dict[str, Any]:
        return _json_ready(dataclasses.asdict(self))


@dataclass(frozen=True)
class RuntimeSeriesSummary:
    samples_ms: Tuple[float, ...]
    median_ms: float
    mean_ms: float
    minimum_ms: float
    maximum_ms: float


@dataclass(frozen=True)
class PairedRuntimeStats:
    default: RuntimeSeriesSummary
    counterfactual: RuntimeSeriesSummary
    pair_count: int
    counterfactual_wins: int
    median_speedup: float
    median_absolute_gap_ms: float
    bootstrap_confidence: float
    bootstrap_speedup_lower_bound: float
    bootstrap_iterations: int
    bootstrap_seed: int

    def to_dict(self) -> Dict[str, Any]:
        return _json_ready(dataclasses.asdict(self))


@dataclass(frozen=True)
class ResultDigest:
    ordered: bool
    row_count: int
    encoded_bytes: int
    digest_sha256: str

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


@dataclass(frozen=True)
class ResultComparison:
    equivalent: bool
    left: ResultDigest
    right: ResultDigest
    reason: str

    def to_dict(self) -> Dict[str, Any]:
        return _json_ready(dataclasses.asdict(self))


@dataclass(frozen=True)
class CaseFingerprint:
    pair_hash: str
    structure_hash: str
    mechanism_hash: str
    normalized_original: str
    normalized_counterfactual: str
    abstract_original: str
    abstract_counterfactual: str
    mechanism_features: Tuple[str, ...]
    plan_features: Tuple[str, ...]
    data_features: Tuple[str, ...]
    metadata_features: Tuple[str, ...]

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


@dataclass(frozen=True)
class SimilarityScore:
    total: float
    sql_structure: float
    plan_delta: float
    provenance: float
    data_trigger: float
    metadata: float

    def to_dict(self) -> Dict[str, Any]:
        return dataclasses.asdict(self)


@dataclass(frozen=True)
class DuplicateMatch:
    case_dir: Path
    case_id: str
    dbms: DBMS
    score: SimilarityScore
    exact_pair: bool

    def to_dict(self) -> Dict[str, Any]:
        result = dataclasses.asdict(self)
        result["case_dir"] = str(self.case_dir)
        result["dbms"] = self.dbms.value
        return _json_ready(result)


@dataclass(frozen=True)
class DuplicateSearchResult:
    novelty_status: NoveltyStatus
    matches: Tuple[DuplicateMatch, ...]
    indexed_cases: int

    def to_dict(self) -> Dict[str, Any]:
        return {
            "novelty_status": self.novelty_status.value,
            "matches": [match.to_dict() for match in self.matches],
            "indexed_cases": self.indexed_cases,
        }


def _json_ready(value: Any) -> Any:
    if isinstance(value, enum.Enum):
        return value.value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _json_ready(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_ready(item) for item in value]
    return value
