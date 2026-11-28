"""CRDB OPT VERBOSE physical plan parser, shared by replay and L1."""
from __future__ import annotations

import math
import re
from typing import Any


def parse_plan(text: str) -> dict[str, Any]:
    roots, stack = [], []
    root_cost = None
    for line in text.splitlines():
        match = re.search(r"[A-Za-z]", line)
        if not match:
            continue
        depth, content = match.start(), line[match.start():].strip()
        if content == "info":
            continue
        # Properties belong to their nearest enclosing physical operator.
        if ":" in content:
            if stack:
                prop, value = content.split(":", 1)
                if prop == "cost" and stack[-1][1] is roots[0]:
                    try:
                        root_cost = float(value)
                    except ValueError as error:
                        raise ValueError("invalid CRDB root cost") from error
                elif prop == "table":
                    table = value.strip().split("@", 1)
                    stack[-1][1]["relation"] = table[0].split(".")[-1].strip('"').lower()
                    if len(table) == 2:
                        stack[-1][1]["index"] = table[1].strip('"').lower()
                elif prop == "spans":
                    stack[-1][1]["flags"].append("full-scan" if "FULL SCAN" in value else "bounded-scan")
            continue
        operation, _, detail = content.partition(" ")
        operation = operation.upper().replace("-", "_")
        if operation.startswith("HISTOGRAM(") or operation.endswith("=") or "'" in operation:
            continue
        node = {"operation": operation, "relation": None, "index": None, "flags": [], "children": []}
        if operation in {"SCAN", "REVSCAN", "INDEX_JOIN", "LOOKUP_JOIN", "INVERTED_JOIN"} and detail:
            table = detail.split()[0].split("@", 1)
            node["relation"] = table[0].split(".")[-1].strip('"').lower()
            node["index"] = table[1].strip('"').lower() if len(table) == 2 else None
        if operation.endswith("JOIN") and detail:
            first_token = detail.split()[0].lower()
            if first_token.startswith("("):
                node["flags"].append("join=" + first_token.strip("()"))
        while stack and stack[-1][0] >= depth:
            stack.pop()
        if stack:
            stack[-1][1]["children"].append(node)
        else:
            roots.append(node)
        stack.append((depth, node))
    if len(roots) != 1 or root_cost is None or not math.isfinite(root_cost) or root_cost < 0:
        raise ValueError("CRDB OPT VERBOSE plan requires one root and a finite nonnegative root cost")
    return {"root": roots[0], "estimated_cost": root_cost}
