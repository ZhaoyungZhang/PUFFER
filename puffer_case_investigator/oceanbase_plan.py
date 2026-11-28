"""OceanBase JSON cost and structure, shared by L1 and clean replay."""
from __future__ import annotations

import json
import math
import re
from typing import Any, Mapping


def parse_plan(document: Any) -> dict[str, Any]:
    if isinstance(document, str):
        document = json.loads(document)
    costs: list[float] = []

    def visit(raw: Any) -> dict[str, Any]:
        if not isinstance(raw, Mapping):
            raise ValueError("OB plan node must be an object")
        operation = raw.get("OPERATOR")
        if not isinstance(operation, str) or not operation.strip():
            raise ValueError("OB plan node requires OPERATOR")
        operation = " ".join(operation.upper().split())
        if "EST.TIME(us)" in raw:
            try:
                if isinstance(raw["EST.TIME(us)"], bool):
                    raise ValueError
                cost = float(raw["EST.TIME(us)"])
            except (ValueError, TypeError) as error:
                raise ValueError("invalid OB EST.TIME(us)") from error
            if not math.isfinite(cost) or cost < 0:
                raise ValueError("OB cost must be finite and nonnegative")
            costs.append(cost)
        children = raw.get("CHILD", [])
        if not isinstance(children, list):
            raise ValueError("OB CHILD must be a list")
        name = raw.get("NAME", "")
        if not isinstance(name, str):
            raise ValueError("OB NAME must be a string")
        relation, flags, algorithm = None, [], None
        kind = operation
        if "JOIN" in operation:
            kind, algorithm = "JOIN", operation
        elif "SCAN" in operation or operation.endswith(" GET"):
            kind = "SCAN"
            access = re.fullmatch(r"([^()]+)(?:\(([^()]+)\))?", name.strip())
            if name and access is None:
                raise ValueError("unsupported OB access name")
            if access:
                relation = access[1].split(".")[-1].strip('`"').lower()
                if access[2]:
                    flags.append("index=" + access[2].strip('`"').lower())
        else:
            kind = next((kind for token, kind in (
                ("AGGREGATE", "AGGREGATE"), ("GROUP BY", "AGGREGATE"),
                ("SORT", "SORT"), ("LIMIT", "LIMIT"), ("FILTER", "FILTER"),
                ("DISTINCT", "DEDUP"), ("MATERIAL", "MATERIALIZE")) if token in operation), operation)
        return {"kind": kind, "relation": relation, "access": operation, "algorithm": algorithm,
                "flags": flags, "children": [visit(child) for child in children]}

    root = visit(document)
    if not costs:
        raise ValueError("OB plan requires EST.TIME(us)")
    # Match the Java oracle's maximum across physical nodes, not a sum.
    return {"root": root, "estimated_cost": max(costs)}
