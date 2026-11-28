"""Complete-result normalization and comparison.

Rows are represented with explicit type tags so NULL, bytes, booleans, numeric
values, timestamps, and duplicate rows cannot collapse into one another.
"""

from __future__ import annotations

import base64
import datetime as dt
import decimal
import hashlib
import json
import math
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence, Tuple

from .models import ResultComparison, ResultDigest


DEFAULT_MAX_ROWS = 5_000_000
DEFAULT_MAX_BYTES = 2 * 1024 * 1024 * 1024


class ResultLimitExceeded(RuntimeError):
    pass


def canonicalize_value(value: Any) -> Any:
    """Return a JSON-safe, type-tagged representation of a DB value."""

    if value is None:
        return ("null",)
    if isinstance(value, bool):
        return ("bool", value)
    if isinstance(value, int):
        return ("int", str(value))
    if isinstance(value, decimal.Decimal):
        if not value.is_finite():
            return ("decimal", str(value))
        return ("decimal", _canonical_decimal(value))
    if isinstance(value, float):
        if math.isnan(value):
            return ("float", "nan")
        if math.isinf(value):
            return ("float", "+inf" if value > 0 else "-inf")
        if value == 0.0:
            value = 0.0
        return ("float", value.hex())
    if isinstance(value, str):
        return ("str", value)
    if isinstance(value, (bytes, bytearray, memoryview)):
        raw = bytes(value)
        return ("bytes", base64.b64encode(raw).decode("ascii"))
    if isinstance(value, dt.datetime):
        return ("datetime", value.isoformat(timespec="microseconds"))
    if isinstance(value, dt.date):
        return ("date", value.isoformat())
    if isinstance(value, dt.time):
        return ("time", value.isoformat(timespec="microseconds"))
    if isinstance(value, dt.timedelta):
        return ("timedelta_us", str(round(value.total_seconds() * 1_000_000)))
    if isinstance(value, Mapping):
        return (
            "mapping",
            tuple(
                sorted(
                    (str(key), canonicalize_value(item)) for key, item in value.items()
                )
            ),
        )
    if isinstance(value, (list, tuple)):
        return ("sequence", tuple(canonicalize_value(item) for item in value))
    return (
        "object",
        f"{type(value).__module__}.{type(value).__qualname__}",
        repr(value),
    )


def canonicalize_row(row: Any) -> Tuple[Any, ...]:
    if isinstance(row, Sequence) and not isinstance(row, (str, bytes, bytearray, memoryview)):
        return tuple(canonicalize_value(value) for value in row)
    return (canonicalize_value(row),)


def digest_rows(
    rows: Iterable[Any],
    ordered: bool,
    max_rows: int = DEFAULT_MAX_ROWS,
    max_bytes: int = DEFAULT_MAX_BYTES,
) -> ResultDigest:
    if max_rows < 0 or max_bytes < 0:
        raise ValueError("result limits must be non-negative")
    row_count = 0
    encoded_bytes = 0
    ordered_digest = hashlib.sha256()
    unordered_rows: Counter[bytes] = Counter()

    for row in rows:
        row_count += 1
        if row_count > max_rows:
            raise ResultLimitExceeded(f"result exceeds row limit {max_rows}")
        encoded = _encode_row(canonicalize_row(row))
        encoded_bytes += len(encoded)
        if encoded_bytes > max_bytes:
            raise ResultLimitExceeded(f"result exceeds encoded byte limit {max_bytes}")
        row_hash = hashlib.sha256(encoded).digest()
        if ordered:
            ordered_digest.update(len(encoded).to_bytes(8, "big"))
            ordered_digest.update(encoded)
        else:
            unordered_rows[row_hash] += 1

    if ordered:
        digest = ordered_digest.hexdigest()
    else:
        unordered_digest = hashlib.sha256()
        for row_hash, count in sorted(unordered_rows.items()):
            unordered_digest.update(row_hash)
            unordered_digest.update(count.to_bytes(8, "big"))
        digest = unordered_digest.hexdigest()
    return ResultDigest(
        ordered=ordered,
        row_count=row_count,
        encoded_bytes=encoded_bytes,
        digest_sha256=digest,
    )


def compare_results(
    left_rows: Iterable[Any],
    right_rows: Iterable[Any],
    ordered: bool,
    max_rows: int = DEFAULT_MAX_ROWS,
    max_bytes: int = DEFAULT_MAX_BYTES,
) -> ResultComparison:
    left = digest_rows(left_rows, ordered, max_rows, max_bytes)
    right = digest_rows(right_rows, ordered, max_rows, max_bytes)
    if left.row_count != right.row_count:
        return ResultComparison(False, left, right, "row_count_mismatch")
    if left.digest_sha256 != right.digest_sha256:
        reason = "ordered_rows_differ" if ordered else "row_multisets_differ"
        return ResultComparison(False, left, right, reason)
    return ResultComparison(True, left, right, "complete_results_equal")


def _encode_row(row: Tuple[Any, ...]) -> bytes:
    return json.dumps(row, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _canonical_decimal(value: decimal.Decimal) -> str:
    normalized = value.normalize()
    if normalized == 0:
        return "0"
    rendered = format(normalized, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return rendered


__all__ = [
    "DEFAULT_MAX_BYTES",
    "DEFAULT_MAX_ROWS",
    "ResultLimitExceeded",
    "canonicalize_row",
    "canonicalize_value",
    "compare_results",
    "digest_rows",
]
