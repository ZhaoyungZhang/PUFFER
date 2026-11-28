"""Configuration loading for the PUFFER Case Investigator command line."""

from __future__ import annotations

import os
try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - Python < 3.11
    import tomli as tomllib  # type: ignore[no-redef]
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Mapping, Optional

from .backends import BACKENDS


@dataclass(frozen=True)
class ProfileSettings:
    name: str
    dbms: str
    host: str
    port: int
    user: str
    password_env: str
    expected_version_prefix: str

    @property
    def password(self) -> Optional[str]:
        """Return the environment-provided password without an implicit default."""

        return os.environ.get(self.password_env)


@dataclass(frozen=True)
class InvestigatorSettings:
    output_root: Path
    local_index_path: Path
    case_roots: tuple[Path, ...]
    max_rows_per_table: int = 300_000
    max_amplification_trials: int = 12
    max_total_minutes: int = 60
    statement_timeout_seconds: int = 30
    max_result_rows: int = 5_000_000
    max_result_bytes: int = 2 * 1024 * 1024 * 1024
    profiles: tuple[ProfileSettings, ...] = ()

    def profile(self, name: str) -> ProfileSettings:
        for profile in self.profiles:
            if profile.name == name:
                return profile
        available = ", ".join(profile.name for profile in self.profiles)
        raise KeyError(f"unknown profile {name!r}; available: {available}")


DEFAULT_PROFILES = tuple(
    ProfileSettings(name=f"{dbms}-local", dbms=dbms, host="127.0.0.1", port=backend.port,
                    user="sqlancer", password_env=f"PUFFER_{dbms.upper()}_PASSWORD",
                    expected_version_prefix=backend.version)
    for dbms, backend in BACKENDS.items()
)


def default_settings(repo_root: Path) -> InvestigatorSettings:
    return InvestigatorSettings(
        output_root=repo_root / "evaluation" / "bug_analysis",
        local_index_path=repo_root / "evaluation" / "bug_analysis" / "case_index.sqlite3",
        case_roots=(repo_root / "evaluation", repo_root / "experiments_data"),
        profiles=DEFAULT_PROFILES,
    )


def load_settings(repo_root: Path, config_path: Path | None = None) -> InvestigatorSettings:
    """Load a small TOML override while retaining portable local defaults."""
    settings = default_settings(repo_root)
    selected = config_path or repo_root / "configs" / "puffer-case-investigator-v1.toml"
    if not selected.is_file():
        return settings
    try:
        payload = tomllib.loads(selected.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ValueError(f"cannot parse investigator config {selected}: {exc}") from exc
    if not isinstance(payload, Mapping):
        raise ValueError("investigator config must contain a TOML object")

    limits = payload.get("limits", {})
    paths = payload.get("paths", {})
    if not isinstance(limits, Mapping) or not isinstance(paths, Mapping):
        raise ValueError("[limits] and [paths] must be TOML tables when present")

    def integer(key: str, default: int) -> int:
        raw = limits.get(key, default)
        if not isinstance(raw, int) or raw <= 0:
            raise ValueError(f"limits.{key} must be a positive integer")
        return raw

    def rooted(raw: Any, default: Path) -> Path:
        if raw is None:
            return default
        value = Path(str(raw)).expanduser()
        return value if value.is_absolute() else repo_root / value

    profiles_by_name = {profile.name: profile for profile in settings.profiles}
    for raw_profile in payload.get("profile", []):
        if not isinstance(raw_profile, Mapping):
            raise ValueError("each [[profile]] entry must be a TOML table")
        name = str(raw_profile.get("name", "")).strip()
        if not name or name not in profiles_by_name:
            raise ValueError("[[profile]] must name " + ", ".join(profiles_by_name))
        base = profiles_by_name[name]
        try:
            profiles_by_name[name] = replace(
                base,
                host=str(raw_profile.get("host", base.host)),
                port=int(raw_profile.get("port", base.port)),
                user=str(raw_profile.get("user", base.user)),
                password_env=str(raw_profile.get("password_env", base.password_env)),
                expected_version_prefix=str(
                    raw_profile.get("expected_version_prefix", base.expected_version_prefix)
                ),
            )
        except (TypeError, ValueError) as exc:
            raise ValueError(f"invalid profile {name}: {exc}") from exc

    raw_roots = paths.get("case_roots")
    if raw_roots is None:
        case_roots = settings.case_roots
    elif isinstance(raw_roots, list) and raw_roots:
        case_roots = tuple(rooted(value, repo_root) for value in raw_roots)
    else:
        raise ValueError("paths.case_roots must be a non-empty array")

    return InvestigatorSettings(
        output_root=rooted(paths.get("output_root"), settings.output_root),
        local_index_path=rooted(paths.get("local_index_path"), settings.local_index_path),
        case_roots=case_roots,
        max_rows_per_table=integer("max_rows_per_table", settings.max_rows_per_table),
        max_amplification_trials=integer(
            "max_amplification_trials", settings.max_amplification_trials
        ),
        max_total_minutes=integer("max_total_minutes", settings.max_total_minutes),
        statement_timeout_seconds=integer(
            "statement_timeout_seconds", settings.statement_timeout_seconds
        ),
        max_result_rows=integer("max_result_rows", settings.max_result_rows),
        max_result_bytes=integer("max_result_bytes", settings.max_result_bytes),
        profiles=tuple(profiles_by_name.values()),
    )
