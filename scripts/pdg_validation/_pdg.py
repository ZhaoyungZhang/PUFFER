from __future__ import annotations

import bisect
import math
import random
import re
from pathlib import Path
from typing import Any, Mapping, Sequence

import yaml

from ._common import ValidationInputError


IR_VERSION = "pdg-ir/v1"
SCALE_VERSION = "pdg-scale/v1"
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*$")


def load_pdg(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise ValidationInputError(f"cannot load PDG-IR {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValidationInputError(f"PDG-IR root is not a mapping: {path}")
    if value.get("pdg_ir_version") != IR_VERSION:
        raise ValidationInputError(f"unsupported pdg_ir_version {value.get('pdg_ir_version')!r}")
    if value.get("scale_semantics_version") != SCALE_VERSION:
        raise ValidationInputError(f"unsupported scale_semantics_version {value.get('scale_semantics_version')!r}")
    if value.get("dbms") not in {"mysql", "mariadb", "tidb", "oceanbase", "cockroachdb"}:
        raise ValidationInputError(f"unsupported PDG DBMS {value.get('dbms')!r}")
    tables = value.get("tables")
    if not isinstance(tables, list) or not tables:
        raise ValidationInputError("PDG-IR contains no tables")
    seen: set[str] = set()
    for table in tables:
        if not isinstance(table, dict) or not isinstance(table.get("name"), str):
            raise ValidationInputError("malformed PDG table")
        name = table["name"]
        if name in seen or not IDENTIFIER.fullmatch(name):
            raise ValidationInputError(f"invalid or duplicate table name {name!r}")
        seen.add(name)
        if not isinstance(table.get("base_rows"), int) or table["base_rows"] < 0:
            raise ValidationInputError(f"invalid base_rows for {name}")
        columns = table.get("columns")
        if not isinstance(columns, list) or not columns:
            raise ValidationInputError(f"table {name} contains no columns")
        column_names: set[str] = set()
        for column in columns:
            if not isinstance(column, dict) or not isinstance(column.get("name"), str):
                raise ValidationInputError(f"malformed column in {name}")
            column_name = column["name"]
            if column_name in column_names or not IDENTIFIER.fullmatch(column_name):
                raise ValidationInputError(f"invalid or duplicate column {name}.{column_name}")
            column_names.add(column_name)
            producer = column.get("producer")
            if not isinstance(producer, dict) or not isinstance(producer.get("parameters"), dict):
                raise ValidationInputError(f"column {name}.{column_name} has no complete producer")
    return value


def extract_pdg_primitives(document: Mapping[str, Any]) -> list[str]:
    """Return the sorted, deduplicated list of active PDG-IR primitives."""
    primitives: set[str] = set()
    for table in document.get("tables", ()):
        if not isinstance(table, Mapping):
            continue
        for column in table.get("columns", ()):
            if not isinstance(column, Mapping):
                continue
            producer = column.get("producer")
            if not isinstance(producer, Mapping):
                continue
            kind = str(producer.get("kind", "")).upper()
            model = str(producer.get("model", "")).strip().upper()
            if kind == "INDEPENDENT" and model and model != "UNIFORM":
                primitives.add(model)
    for relationship in document.get("relationships", ()):
        if not isinstance(relationship, Mapping):
            continue
        rel_type = str(relationship.get("type", "")).strip().upper()
        if not rel_type:
            continue
        params = relationship.get("parameters")
        fanout_type = params.get("fanout_type") if isinstance(params, Mapping) else None
        if rel_type == "JOIN_FANOUT" and fanout_type:
            primitives.add(f"{rel_type}:{str(fanout_type).strip().upper()}")
        else:
            primitives.add(rel_type)
    if not primitives:
        primitives.add("UNIFORM")
    return sorted(primitives)


def _mix(value: int) -> int:
    value &= (1 << 64) - 1
    value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & ((1 << 64) - 1)
    value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & ((1 << 64) - 1)
    return value ^ (value >> 31)


def _signed_long(value: int) -> int:
    """Match the Java materializer's signed 64-bit arithmetic, including wraparound."""
    return ((value + (1 << 63)) & ((1 << 64) - 1)) - (1 << 63)


CANONICAL_SCALE_POLICIES = frozenset({
    "SCALE_FREQ",
    "SCALE_DOMAIN",
    "SCALE_DEP",
    "SCALE_OVERLAP",
})
LEGACY_SCALE_POLICIES = frozenset({
    "FIXED_DISTRIBUTION_EXTEND_SAMPLES",
    "EXTEND_SEQUENCE",
    "FIXED_MAPPING",
    "FIXED_SHARED_DOMAIN_GROW_FREQUENCY",
    "PRESERVE_FANOUT",
    "EXTEND_PRIVATE_KEYS",
})
VALID_SCALE_POLICIES = CANONICAL_SCALE_POLICIES | LEGACY_SCALE_POLICIES


def _java_random_bits(seed: int, row_index: int, stream: int) -> int:
    mask = (1 << 64) - 1
    mixed = (
        (seed & mask)
        ^ ((0x9E3779B97F4A7C15 * (row_index + 1)) & mask)
        ^ ((0xBF58476D1CE4E5B9 * (stream + 1)) & mask)
    )
    return _mix(mixed)


def _java_unit_double(bits: int) -> float:
    return ((bits & ((1 << 64) - 1)) >> 11) * (2.0 ** -53)


def _java_bounded_long(bits: int, minimum: int, maximum: int) -> int:
    span = maximum - minimum + 1
    return minimum + (_signed_long(bits) % span)


class PdgExtender:
    def __init__(self, document: Mapping[str, Any], scale: int) -> None:
        if scale < 2:
            raise ValidationInputError("scale must be at least 2")
        self.document = document
        self.scale = scale
        self.tables = list(document["tables"])
        self.table_by_name = {table["name"]: table for table in self.tables}
        self.relationships = {item["id"]: item for item in document.get("relationships", [])}
        self.target_rows = {table["name"]: int(table["base_rows"]) * scale for table in self.tables}
        self._zipf_cdf_cache: dict[tuple[int, float], list[float]] = {}

    def additional_rows(self, table_name: str) -> list[tuple[Any, ...]]:
        table = self.table_by_name[table_name]
        start = int(table["base_rows"])
        raw_rows = [list(self.row(table, index)) for index in range(start, self.target_rows[table_name])]
        self._apply_rank_coupling(table, raw_rows, start)
        return [tuple(r) for r in raw_rows]

    def _apply_rank_coupling(self, table: Mapping[str, Any], rows: list[list[Any]], start_row: int) -> None:
        base_rows = max(1, int(table["base_rows"]))
        if not rows or base_rows <= 1:
            return
        for col_idx, column in enumerate(table["columns"]):
            producer = column["producer"]
            if producer.get("kind") != "INTRA_TABLE_DERIVED":
                continue
            relationship = self._relationship(producer)
            if relationship["type"] != "RANK_COUPLE":
                continue
            source_name = str(relationship["left"]).split(".", 1)[1]
            source_idx = next(i for i, item in enumerate(table["columns"]) if item["name"] == source_name)
            params = relationship["parameters"]
            rho = float(params.get("rho", 0.8))
            seed = int(params["value_seed"])
            minimum = int(params.get("minimum", -50000))
            maximum = int(params.get("maximum", 50000))
            is_double = column["sql_type"].upper().startswith(("DOUBLE", "FLOAT", "DECIMAL"))

            for offset in range(0, len(rows), base_rows):
                batch = rows[offset:offset + base_rows]
                n = len(batch)
                if n <= 1:
                    continue
                batch_start = start_row + offset
                marginal = sorted(
                    _java_bounded_long(_java_random_bits(seed, batch_start + i, 0), minimum, maximum)
                    for i in range(n)
                )
                src_order = sorted(
                    range(n),
                    key=lambda idx: (
                        0 if batch[idx][source_idx] is None else 1,
                        0.0 if batch[idx][source_idx] is None else float(batch[idx][source_idx]),
                        idx,
                    ),
                )
                source_rank = [0] * n
                for rank_pos, row_pos in enumerate(src_order):
                    source_rank[row_pos] = rank_pos
                noise_weight = math.sqrt(max(0.0, 1.0 - rho * rho))
                denom = max(1, n - 1)
                scores = [
                    rho * (source_rank[i] / denom)
                    + noise_weight * (_java_unit_double(_java_random_bits(seed, batch_start + i, 1)) - 0.5)
                    for i in range(n)
                ]
                target_order = sorted(range(n), key=lambda idx: (scores[idx], idx))
                for rank_pos, row_pos in enumerate(target_order):
                    val = marginal[rank_pos]
                    batch[row_pos][col_idx] = float(val) if is_double else int(val)

    def row(self, table: Mapping[str, Any], row_index: int) -> tuple[Any, ...]:
        values: list[Any] = [None] * len(table["columns"])
        complete = [False] * len(values)
        active = [False] * len(values)

        def cell(column_index: int) -> Any:
            if complete[column_index]:
                return values[column_index]
            if active[column_index]:
                raise ValidationInputError(f"cyclic producer in {table['name']}")
            active[column_index] = True
            column = table["columns"][column_index]
            producer = column["producer"]
            policy = producer.get("scale_policy")
            if policy is not None and policy not in VALID_SCALE_POLICIES:
                raise ValidationInputError(f"unsupported scale_policy {policy!r}")
            kind = producer["kind"]
            if kind == "INDEPENDENT":
                value = self._independent(producer, column["sql_type"], table, column_index, row_index)
            elif kind == "INTRA_TABLE_DERIVED":
                relationship = self._relationship(producer)
                source_name = str(relationship["left"]).split(".", 1)[1]
                source_index = next(index for index, item in enumerate(table["columns"]) if item["name"] == source_name)
                source = cell(source_index)
                rel_type = relationship["type"]
                params = relationship["parameters"]
                if source is None:
                    value = None
                elif rel_type in {"FUNCTIONAL_DEPENDENCY", "FUNC_MAP"}:
                    mapping_kind = str(params.get("mapping_kind", "LINEAR"))
                    multiplier = int(params.get("multiplier", 3))
                    offset = int(params.get("offset", 7))
                    src_val = int(source)
                    if mapping_kind == "MODULO":
                        modulus = int(params.get("modulus", 16))
                        value = _signed_long((src_val % modulus) * multiplier + offset)
                    elif mapping_kind == "DIVIDE":
                        divisor = int(params.get("divisor", 8))
                        value = _signed_long((src_val // divisor) * multiplier + offset)
                    else:
                        value = _signed_long(src_val * multiplier + offset)
                elif rel_type == "ANTI_CORRELATION":
                    value = _signed_long(int(params["constant"]) - int(source))
                elif rel_type == "COND_PATTERN":
                    mod = int(params.get("trigger_modulus", 10))
                    rem = int(params.get("trigger_remainder", 2))
                    seed = int(params["value_seed"])
                    if (int(source) % mod) < rem:
                        c_min = int(params.get("cond_min", 900000))
                        c_max = int(params.get("cond_max", 900050))
                        value = _java_bounded_long(_java_random_bits(seed, row_index, 0), c_min, c_max)
                    else:
                        b_min = int(params.get("bg_min", -100000))
                        b_max = int(params.get("bg_max", 100000))
                        value = _java_bounded_long(_java_random_bits(seed, row_index, 1), b_min, b_max)
                elif rel_type == "RANK_COUPLE":
                    seed = int(params["value_seed"])
                    minimum = int(params.get("minimum", -50000))
                    maximum = int(params.get("maximum", 50000))
                    value = _java_bounded_long(_java_random_bits(seed, row_index, 0), minimum, maximum)
                else:
                    raise ValidationInputError(f"unsupported derived relationship {rel_type}")
                if value is not None and column["sql_type"].upper().startswith(("DOUBLE", "FLOAT", "DECIMAL")):
                    value = float(value)
            elif kind == "INTER_TABLE_ENDPOINT":
                value = self._endpoint(producer, row_index)
            else:
                raise ValidationInputError(f"unknown producer kind {kind!r}")
            values[column_index] = value
            complete[column_index] = True
            active[column_index] = False
            return value

        for index in range(len(values)):
            cell(index)
        return tuple(values)

    def _relationship(self, producer: Mapping[str, Any]) -> Mapping[str, Any]:
        identifier = producer["parameters"].get("relationship_id")
        relationship = self.relationships.get(identifier)
        if not isinstance(relationship, Mapping):
            raise ValidationInputError(f"unknown relationship {identifier!r}")
        return relationship

    def _zipf_cdf(self, size: int, exponent: float) -> list[float]:
        key = (size, exponent)
        cached = self._zipf_cdf_cache.get(key)
        if cached is not None:
            return cached
        cdf: list[float] = []
        total = 0.0
        for rank in range(1, size + 1):
            total += 1.0 / math.pow(rank, exponent)
            cdf.append(total)
        cdf = [x / total for x in cdf]
        cdf[-1] = 1.0
        self._zipf_cdf_cache[key] = cdf
        return cdf

    def _parent_key(self, relationship: Mapping[str, Any], row_index: int) -> int:
        params = relationship["parameters"]
        parent_start = int(params.get("parent_key_start", 1_000_000))
        mode = str(params.get("parent_key_mode", "SEQUENTIAL"))
        if mode == "POINT_SPIKE":
            period = int(params.get("parent_spike_period", 25))
            return parent_start if (row_index % period == 0) else parent_start + 1 + row_index
        if mode == "ZIPF_HOT":
            hot_period = int(params.get("parent_hot_period", 10))
            hot_count = int(params.get("parent_hot_count", 2))
            hot_domain = int(params.get("parent_hot_domain_size", 12))
            if row_index % hot_period < hot_count:
                seed = int(params["parent_value_seed"])
                exponent = float(params.get("parent_zipf_exponent", 1.1))
                cdf = self._zipf_cdf(hot_domain, exponent)
                sample = _java_unit_double(_java_random_bits(seed, row_index, 0))
                return parent_start + bisect.bisect_left(cdf, sample)
            return parent_start + hot_domain + row_index
        return parent_start + row_index

    def _endpoint(self, producer: Mapping[str, Any], row_index: int) -> int:
        relationship = self._relationship(producer)
        params = relationship["parameters"]
        role = producer["parameters"].get("role")
        parent = role == "parent" or role == str(relationship["left"]).split(".", 1)[0]
        relationship_type = relationship["type"]
        if relationship_type in {"REFERENTIAL_CORRELATION", "PARTIAL_REF"}:
            if parent:
                return self._parent_key(relationship, row_index)
            period = int(params["match_period"])
            match_count = int(params.get("match_count", period - 1))
            if row_index % period >= match_count:
                return int(params["private_key_start"]) + row_index
            parent_table = str(relationship["left"]).split(".", 1)[0]
            child_table = str(relationship["right"]).split(".", 1)[0]
            parent_base_rows = int(self.table_by_name[parent_table]["base_rows"])
            child_base_rows = int(self.table_by_name[child_table]["base_rows"])
            if row_index < child_base_rows:
                return self._parent_key(relationship, row_index % max(1, parent_base_rows))
            extra_parent_rows = max(1, self.target_rows[parent_table] - parent_base_rows)
            parent_offset = parent_base_rows + ((row_index - child_base_rows) % extra_parent_rows)
            return self._parent_key(relationship, parent_offset)
        if relationship_type == "SKEWED_FANOUT":
            start = int(params["shared_domain_start"])
            size = int(params["shared_domain_size"])
            exponent = float(params.get("exponent", 1.2))
            seed_key = "parent_value_seed" if parent else "child_value_seed"
            seed = int(params.get(seed_key, 17))
            sample = _java_unit_double(_java_random_bits(seed, row_index, 0))
            cdf = self._zipf_cdf(size, exponent)
            return start + bisect.bisect_left(cdf, sample)
        if relationship_type == "DISJOINT_RANGE":
            start = int(params["range_start"])
            width = int(params["range_width"]) * self.scale
            shift = 0 if parent else int(params["range_shift"]) * self.scale
            return start + shift + (row_index % max(1, width))
        if relationship_type not in {"JOIN_FANOUT", "PARENT_CHILD", "MANY_TO_MANY"}:
            raise ValidationInputError(f"unsupported endpoint relationship {relationship_type}")
        fanout_type = "MANY_TO_MANY" if relationship_type == "MANY_TO_MANY" else params.get("fanout_type", "ONE_TO_MANY")
        if fanout_type == "MANY_TO_MANY":
            return int(params["shared_domain_start"]) + row_index % int(params["shared_domain_size"])
        if parent:
            return self._parent_key(relationship, row_index)
        parent_index = row_index // int(params["fanout"]) if fanout_type == "ONE_TO_MANY" else row_index
        parent_table = str(relationship["left"]).split(".", 1)[0]
        if parent_index >= self.target_rows[parent_table]:
            return int(params["private_key_start"]) + row_index
        return self._parent_key(relationship, parent_index)

    def _independent(self, producer: Mapping[str, Any], sql_type: str, table: Mapping[str, Any],
                     column_index: int, row_index: int) -> Any:
        params = producer["parameters"]
        seed = int(params["value_seed"])
        rng = random.Random(_mix(seed ^ _mix(row_index + 0x9E3779B97F4A7C15)))
        model = producer["model"]
        if model == "NULL_HEAVY" and rng.random() < float(params["null_probability"]):
            return None
        if model == "ZIPF":
            size, exponent = int(params["domain_size"]), float(params["exponent"])
            weights = [1.0 / math.pow(rank, exponent) for rank in range(1, size + 1)]
            target, cumulative = rng.random() * sum(weights), 0.0
            numeric = int(params["domain_start"]) + size - 1
            for offset, weight in enumerate(weights):
                cumulative += weight
                if cumulative >= target:
                    numeric = int(params["domain_start"]) + offset
                    break
        elif model == "GAUSSIAN":
            numeric = rng.gauss(float(params["mean"]), float(params["standard_deviation"]))
        elif model == "LOW_NDV":
            numeric = int(params["domain_start"]) + row_index % int(params["domain_size"])
        elif model == "MONOTONIC_ASC":
            numeric = int(params["start"]) + row_index * int(params["step"])
        elif model == "MONOTONIC_DESC":
            numeric = int(params["start"]) + (int(table["base_rows"]) - row_index) * int(params["step"])
        elif model == "POINT_SPIKE":
            period = int(params.get("spike_period", 25))
            if row_index % period == 0:
                numeric = int(params["spike_value"])
            else:
                numeric = _java_bounded_long(
                    _java_random_bits(seed, row_index, 0),
                    int(params.get("minimum", -100000)),
                    int(params.get("maximum", 100000)),
                )
        elif model in {"UNIFORM", "NULL_HEAVY"}:
            numeric = rng.randint(int(params["minimum"]), int(params["maximum"]))
        else:
            raise ValidationInputError(f"unsupported independent model {model!r}")
        normalized_type = sql_type.upper()
        if normalized_type.startswith("VARCHAR"):
            table_index = self.tables.index(table)
            return f"{model.lower()}_{table_index}_{column_index}_{round(numeric)}"
        if normalized_type.startswith("DOUBLE") or normalized_type.startswith("FLOAT") or normalized_type.startswith("DECIMAL"):
            return float(numeric)
        return round(numeric)


def quote_identifier(identifier: str) -> str:
    if not IDENTIFIER.fullmatch(identifier):
        raise ValidationInputError(f"unsafe SQL identifier {identifier!r}")
    return f"`{identifier}`"


def extend_database(adapter: Any, sandbox: str, document: Mapping[str, Any], *, scale: int,
                    hard_cap_rows: int = 300_000, batch_size: int = 500) -> dict[str, int]:
    extender = PdgExtender(document, scale)
    actual_tables = set(adapter.list_tables(sandbox))
    if actual_tables != set(extender.table_by_name):
        raise ValidationInputError(f"PDG/setup table mismatch: pdg={sorted(extender.table_by_name)}, actual={sorted(actual_tables)}")
    for table, target in extender.target_rows.items():
        if target > hard_cap_rows:
            raise ValidationInputError(f"scaled table {table} exceeds row cap {hard_cap_rows}")
        if int(adapter.row_count(sandbox, table)) != int(extender.table_by_name[table]["base_rows"]):
            raise ValidationInputError(f"setup row count does not match PDG base_rows for {table}")
    connection = adapter._with_database(sandbox)
    try:
        begin_tx = getattr(adapter, "begin_data_transaction", None)
        if callable(begin_tx):
            begin_tx(connection)
        elif callable(getattr(connection, "autocommit", None)):
            connection.autocommit(False)
        else:
            connection.autocommit = False
        quote = getattr(adapter, "quote_identifier", quote_identifier)
        for table in extender.tables:
            table_name = table["name"]
            columns = [column["name"] for column in table["columns"]]
            rows = extender.additional_rows(table_name)
            sql = (f"INSERT INTO {quote(table_name)} ("
                   + ", ".join(quote(column) for column in columns)
                   + ") VALUES (" + ", ".join(["%s"] * len(columns)) + ")")
            with connection.cursor() as cursor:
                for offset in range(0, len(rows), batch_size):
                    cursor.executemany(sql, rows[offset:offset + batch_size])
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    final = {table: int(adapter.row_count(sandbox, table)) for table in extender.target_rows}
    if final != extender.target_rows:
        raise ValidationInputError(f"scaled row-count invariant failed: expected={extender.target_rows}, actual={final}")
    return final
