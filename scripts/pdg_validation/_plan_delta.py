from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Mapping

from puffer_case_investigator.tidb_plan import parse_plan

from ._common import ValidationInputError, canonical_json, phenotype, read_json, sha256_text
from ._pdg import extract_pdg_primitives, load_pdg


IR_VERSION = "pdg-plan-delta/v1"


def _node(kind: str, *, relation: str | None = None, access: str | None = None,
          algorithm: str | None = None, flags: Iterable[str] = (), children: Iterable[dict[str, Any]] = ()) -> dict[str, Any]:
    return {
        "kind": kind,
        "relation": relation,
        "access": access,
        "algorithm": algorithm,
        "flags": sorted(set(flags)),
        "children": list(children),
    }


def normalize_mysql(document: Any, source: Path) -> dict[str, Any]:
    if not isinstance(document, Mapping) or document.get("json_schema_version") != "2.0":
        raise ValidationInputError(f"{source}: expected MySQL JSON schema 2.0")
    root = document.get("query_plan")
    if not isinstance(root, Mapping):
        raise ValidationInputError(f"{source}: missing query_plan")

    def visit(raw: Any) -> dict[str, Any]:
        if not isinstance(raw, Mapping):
            raise ValidationInputError(f"{source}: plan node is not an object")
        access = str(raw.get("access_type", "")).lower()
        children = [visit(child) for child in raw.get("inputs", [])]
        if access in {"table", "index"}:
            flags = []
            if raw.get("covering"):
                flags.append("covering")
            if raw.get("reverse"):
                flags.append("reverse")
            return _node("SCAN", relation=str(raw.get("table_name", "?")).lower(),
                         access=str(raw.get("index_access_type") or access).upper(), flags=flags, children=children)
        kind = {
            "join": "JOIN", "filter": "FILTER", "sort": "SORT", "limit": "LIMIT",
            "stream": "STREAM", "materialize": "MATERIALIZE", "aggregate": "AGGREGATE",
        }.get(access, str(raw.get("operation", access or "OTHER")).split(" ", 1)[0].upper())
        flags = []
        if raw.get("deduplication"):
            flags.append("deduplication")
        if raw.get("temp_table"):
            flags.append("temporary")
        return _node(kind, access=access.upper() or None, algorithm=str(raw.get("join_algorithm", "")).upper() or None,
                     flags=flags, children=children)

    return visit(root)


def normalize_mariadb(document: Any, source: Path) -> dict[str, Any]:
    if not isinstance(document, Mapping) or not isinstance(document.get("query_block"), Mapping):
        raise ValidationInputError(f"{source}: expected MariaDB query_block plan")

    def visit(raw: Any) -> dict[str, Any]:
        if not isinstance(raw, Mapping):
            raise ValidationInputError(f"{source}: MariaDB plan node is not an object")
        if "table" in raw and isinstance(raw["table"], Mapping):
            table = raw["table"]
            flags = ["using_index"] if table.get("using_index") else []
            return _node("SCAN", relation=str(table.get("table_name", "?")).lower(),
                         access=str(table.get("access_type", "UNKNOWN")).upper(), flags=flags)
        if "block-nl-join" in raw and isinstance(raw["block-nl-join"], Mapping):
            block = raw["block-nl-join"]
            child = visit({"table": block.get("table", {})})
            return _node("JOIN_INPUT", algorithm=str(block.get("join_type", "BNL")).upper(), children=[child])
        if "nested_loop" in raw and isinstance(raw["nested_loop"], list):
            children = [visit(child) for child in raw["nested_loop"]]
            algorithm = next((child.get("algorithm") for child in children if child.get("algorithm")), "NESTED_LOOP")
            return _node("JOIN", algorithm=algorithm, children=children)
        for key, kind, flags in (
            ("filesort", "SORT", ()),
            ("temporary_table", "MATERIALIZE", ("temporary",)),
            ("duplicates_removal", "DEDUP", ("deduplication",)),
            ("grouping_operation", "AGGREGATE", ()),
        ):
            if key in raw and isinstance(raw[key], Mapping):
                return _node(kind, flags=flags, children=[visit(raw[key])])
        if "query_block" in raw:
            return visit(raw["query_block"])
        structural = [(key, value) for key, value in raw.items()
                      if key not in {"select_id", "cost", "sort_key", "attached_condition"}
                      and isinstance(value, (dict, list))]
        if len(structural) == 1:
            key, value = structural[0]
            if isinstance(value, dict):
                return _node(key.upper(), children=[visit(value)])
        raise ValidationInputError(f"{source}: unsupported MariaDB plan keys {sorted(raw)}")

    return visit(document["query_block"])


def normalize_tidb(text: str, source: Path) -> dict[str, Any]:
    try:
        document = parse_plan(text)
    except ValueError as error:
        raise ValidationInputError(f"{source}: {error}") from error

    def visit(raw: Mapping[str, Any]) -> dict[str, Any]:
        operation = raw["operation"]
        if "JOIN" in operation:
            kind, algorithm = "JOIN", operation
        else:
            algorithm = None
            kind = next((kind for token, kind in (
                ("SCAN", "SCAN"), ("AGG", "AGGREGATE"), ("SORT", "SORT"),
                ("TOPN", "TOPN"), ("LIMIT", "LIMIT"), ("SELECTION", "FILTER"),
                ("PROJECTION", "PROJECTION")) if token in operation), operation)
        return _node(kind, relation=raw["relation"], access=operation, algorithm=algorithm,
                     flags=["task=" + raw["task"]] if raw["task"] else [],
                     children=[visit(child) for child in raw["children"]])

    return visit(document["root"])


def normalize_cockroachdb(text: str, source: Path) -> dict[str, Any]:
    from puffer_case_investigator.cockroach_plan import parse_plan
    try:
        document = parse_plan(text)
    except ValueError as error:
        raise ValidationInputError(f"{source}: {error}") from error

    def visit(raw: Mapping[str, Any]) -> dict[str, Any]:
        operation = raw["operation"]
        kind = "JOIN" if "JOIN" in operation else ("SCAN" if "SCAN" in operation else operation)
        flags = raw["flags"] + (["index=" + raw["index"]] if raw["index"] else [])
        return _node(kind, relation=raw["relation"], access=operation, flags=flags,
                     children=[visit(child) for child in raw["children"]])

    return visit(document["root"])


def normalize_oceanbase(document: Any, source: Path) -> dict[str, Any]:
    from puffer_case_investigator.oceanbase_plan import parse_plan
    try:
        return parse_plan(document)["root"]
    except ValueError as error:
        raise ValidationInputError(f"{source}: {error}") from error


def facts(root: Mapping[str, Any]) -> Counter[str]:
    result: Counter[str] = Counter()

    def walk(node: Mapping[str, Any]) -> set[str]:
        child_relations: set[str] = set()
        for child in node.get("children", []):
            child_relations.update(walk(child))
        relation = node.get("relation")
        if relation and relation != "<temporary>":
            child_relations.add(str(relation))
        scope = ",".join(sorted(child_relations)) or "NONE"
        token = f"{node.get('kind')}|{node.get('access')}|{node.get('algorithm')}|{'+'.join(node.get('flags', []))}|{scope}"
        result[token] += 1
        return child_relations

    walk(root)
    return result


def observed_delta(base: Mapping[str, Any], alt: Mapping[str, Any]) -> dict[str, Any]:
    base_facts, alt_facts = facts(base), facts(alt)
    removed = sorted((token, count) for token, count in (base_facts - alt_facts).items())
    added = sorted((token, count) for token, count in (alt_facts - base_facts).items())
    return {
        "removed_facts": [{"fact": token, "count": count} for token, count in removed],
        "added_facts": [{"fact": token, "count": count} for token, count in added],
        "tree_changed": canonical_json(base) != canonical_json(alt),
    }


def normalize_fact_for_l2(fact: str) -> str | None:
    """Strip concrete table-name scope, index names, and CRDB metadata artifacts for L2 clustering (Paper Eq. 11).

    Retains normalized physical operator kind, access method, join algorithm,
    and structural flags (abstracting concrete index names like ``index=i3`` to
    ``index`` and keeping only algorithm tags from ``join=(hash)``), while
    dropping non-operator metadata lines (e.g. CRDB ``HISTOGRAM(N)=`` or
    quoted value literals) so identical plan deltas across different table/index
    names cluster into the same family.
    """
    parts = fact.split("|")
    if len(parts) >= 5:
        kind, access, algorithm, raw_flags = parts[0], parts[1], parts[2], parts[3]
        if kind.startswith("HISTOGRAM(") or kind.endswith("=") or "'" in kind:
            return None
        cleaned: set[str] = set()
        for token in raw_flags.split("+"):
            if not token:
                continue
            if token.startswith("index="):
                cleaned.add("index")
            elif token.startswith("join="):
                val = token[5:]
                if val.startswith("("):
                    cleaned.add("join=" + val.strip("()"))
            else:
                cleaned.add(token)
        flags = sorted(cleaned)
        return f"{kind}|{access}|{algorithm}|{'+'.join(flags)}"
    return fact


def extract_relation_order(node: Any) -> list[str]:
    """Return the left-to-right base-relation scan order from a normalized plan tree."""
    if not isinstance(node, Mapping):
        return []
    order: list[str] = []
    relation = node.get("relation")
    if relation and relation != "<temporary>":
        order.append(str(relation))
    for child in node.get("children", ()):
        order.extend(extract_relation_order(child))
    return order


def delta_features_from_record(record: Mapping[str, Any]) -> dict[str, int]:
    """Build canonical sorted L2 fact-delta feature vector (Paper Section 5.2.1 Eq. 11).

    Uses schema-agnostic normalized operator/access multiset differences
    (canceling identical operators moved across tables) plus an explicit
    ``DELTA:JOIN_ORDER_CHANGED`` feature when relation join order permutes.
    """
    delta = record.get("observed_delta")
    if not isinstance(delta, Mapping):
        raise ValidationInputError(f"malformed PlanDeltaIR record {record.get('case_id')}")
    removed_norm: Counter[str] = Counter()
    added_norm: Counter[str] = Counter()
    for bucket, key in ((removed_norm, "removed_facts"), (added_norm, "added_facts")):
        values = delta.get(key, [])
        if not isinstance(values, list):
            raise ValidationInputError(f"malformed {key} for {record.get('case_id')}")
        for item in values:
            if not isinstance(item, Mapping) or not isinstance(item.get("fact"), str):
                raise ValidationInputError(f"malformed fact for {record.get('case_id')}")
            normalized = normalize_fact_for_l2(item["fact"])
            if normalized is not None:
                bucket[normalized] += int(item.get("count", 1))
    counts: Counter[str] = Counter()
    for token, count in (removed_norm - added_norm).items():
        counts[f"REMOVE:{token}"] += count
    for token, count in (added_norm - removed_norm).items():
        counts[f"ADD:{token}"] += count
    plans = record.get("normalized_plans")
    join_order_changed = False
    if isinstance(plans, Mapping):
        base_order = extract_relation_order(plans.get("base"))
        alt_order = extract_relation_order(plans.get("alt"))
        if base_order and alt_order and base_order != alt_order and sorted(base_order) == sorted(alt_order):
            join_order_changed = True
            counts["DELTA:JOIN_ORDER_CHANGED"] += 1
    if not counts and not join_order_changed and delta.get("tree_changed"):
        counts["DELTA:TREE_RESTRUCTURED"] += 1
    return dict(sorted(counts.items()))


def canonical_delta_signature(record: Mapping[str, Any]) -> str:
    """Compute cross-config comparable SHA-256 signature of normalized L2 delta features."""
    payload = {
        "dbms": str(record.get("dbms", "")),
        "features": delta_features_from_record(record),
    }
    return sha256_text(canonical_json(payload))


def analyze_case(case_dir: Path, cell: str, dbms: str) -> dict[str, Any]:
    extension = "txt" if dbms in {"tidb", "cockroachdb"} else "json"
    base_path = case_dir / f"default_explain.{extension}"
    alt_path = case_dir / f"counterfactual_explain.{extension}"
    if not alt_path.is_file():
        alt_path = case_dir / f"hinted_explain.{extension}"
    metrics_path = case_dir / "metrics.json"
    if not base_path.is_file() or not alt_path.is_file():
        raise ValidationInputError("missing base or counterfactual plan")
    metrics = read_json(metrics_path) if metrics_path.is_file() else {}
    if not isinstance(metrics, Mapping):
        raise ValidationInputError("metrics.json is not an object")
    normalizer = {"mysql": normalize_mysql, "mariadb": normalize_mariadb, "tidb": normalize_tidb,
                  "cockroachdb": normalize_cockroachdb, "oceanbase": normalize_oceanbase}[dbms]
    read_plan = (lambda path: path.read_text(encoding="utf-8")) if dbms in {"tidb", "cockroachdb"} else read_json
    base = normalizer(read_plan(base_path), base_path)
    alt = normalizer(read_plan(alt_path), alt_path)
    delta = observed_delta(base, alt)
    signature_payload = {"dbms": dbms, "direction": "BASE_TO_ALT", "delta": delta}
    signature = sha256_text(canonical_json(signature_payload))
    context = {
        "has_distinct": "DISTINCT" in str(metrics.get("baseQuery", "")).upper(),
        "has_order_by": "ORDER BY" in str(metrics.get("baseQuery", "")).upper(),
        "has_limit": "LIMIT" in str(metrics.get("baseQuery", "")).upper(),
    }
    pheno = phenotype(metrics, cell)
    pdg_primitives: list[str] = []
    pdg_path = case_dir / "pdg_ir.yaml"
    if pdg_path.is_file():
        try:
            pdg_primitives = extract_pdg_primitives(load_pdg(pdg_path))
        except Exception:
            pdg_primitives = []
    if not pdg_primitives and isinstance(metrics.get("pdgPrimitives"), list):
        pdg_primitives = sorted({str(item) for item in metrics["pdgPrimitives"] if item})
    record = {
        "ir_version": IR_VERSION,
        "dbms": dbms,
        "cell": cell,
        "stage1_cell": cell,
        "stage1_rho_c": pheno.get("cost_ratio"),
        "stage1_rho_t": pheno.get("runtime_ratio"),
        "case_id": case_dir.name,
        "case_path": str(case_dir.resolve()),
        "direction": "BASE_TO_ALT",
        "source": {"base_plan": base_path.name, "alt_plan": alt_path.name},
        "normalized_plans": {"base": base, "alt": alt},
        "observed_delta": delta,
        "pdg_primitives": pdg_primitives,
        "phenotype": pheno,
        "context": context,
        "strict_signature": {"sha256": signature, "canonical_json": canonical_json(signature_payload)},
    }
    record["delta_features"] = delta_features_from_record(record)
    record["delta_signature"] = canonical_delta_signature(record)
    return record
