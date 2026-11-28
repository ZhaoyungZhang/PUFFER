"""Small shared backend descriptions; no database connections or driver imports."""

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class Backend:
    label: str
    port: int
    version: str
    schema_prefix: str
    plan_format: str = "json"


BACKENDS = {
    "oceanbase": Backend("OceanBase", 12881, "4.5.0", "ob450abl", "oceanbase-json"),
    "mysql": Backend("MySQL", 34971, "9.7.1", "mysql971abl"),
    "mariadb": Backend("MariaDB", 32332, "12.3.2", "mariadb1232abl"),
    "cockroachdb": Backend("CockroachDB", 26257, "v26.2.2", "crdb262abl", "cockroach-opt"),
    "tidb": Backend("TiDB", 4000, "8.5.0", "tidb850abl", "tidb-verbose"),
}


def matches_version(dbms: str, version: str, expected: str) -> bool:
    if dbms == "oceanbase":
        match = re.search(r"OceanBase(?:_CE)?-v(\d+\.\d+\.\d+(?:[._-][A-Za-z0-9]+)*)", version, re.IGNORECASE)
        if not match:
            return False
        if "oceanbase" in expected.lower():
            return version.startswith(expected)
        return not expected or bool(re.match(re.escape(expected) + r"(?:$|[._-])", match.group(1)))
    if dbms == "cockroachdb":
        return bool(re.search(r"\bCockroachDB\b.*?" + re.escape(expected), version))
    if dbms == "tidb":
        match = re.search(r"(?:^|[-\s])TiDB-v?(\d+\.\d+\.\d+[^\s]*)", version, re.IGNORECASE)
        return bool(match and (not expected or match.group(1).startswith(expected)))
    return not expected or version.startswith(expected)
