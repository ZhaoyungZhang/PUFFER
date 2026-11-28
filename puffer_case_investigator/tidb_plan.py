"""Parse TiDB verbose tabular plans for replay costs and L1 normalization."""

from __future__ import annotations

import math
import re
from typing import Any, Sequence


class TiDBPlanError(ValueError):
    pass


def format_table(columns: Sequence[str], rows: Sequence[Sequence[Any]]) -> str:
    return "\n".join(["\t".join(columns), *[
        "\t".join("" if cell is None else str(cell) for cell in row) for row in rows]])


def parse_plan(text: str, *, require_cost: bool = True) -> dict[str, Any]:
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        raise TiDBPlanError("empty TiDB plan")
    columns = [cell.strip().lower() for cell in lines[0].split("\t")]
    if "id" not in columns or (require_cost and "estcost" not in columns):
        raise TiDBPlanError("TiDB verbose plan requires id and estCost columns")
    nodes: list[dict[str, Any]] = []
    stack: list[tuple[int, dict[str, Any]]] = []
    root_cost = None
    for line in lines[1:]:
        cells = line.split("\t")
        if len(cells) != len(columns):
            raise TiDBPlanError("TiDB plan row does not match its header")
        row = dict(zip(columns, cells))
        identifier = row["id"]
        name = re.search(r"[A-Za-z][A-Za-z0-9_]*", identifier)
        if name is None:
            raise TiDBPlanError("TiDB plan has no operator id")
        depth = name.start()
        operation = re.sub(r"_\d+$", "", name.group()).upper()
        if root_cost is None and not nodes and require_cost:
            try:
                root_cost = float(row["estcost"])
            except (ValueError, TypeError) as error:
                raise TiDBPlanError("TiDB root estCost is not numeric") from error
            if not math.isfinite(root_cost) or root_cost < 0:
                raise TiDBPlanError("TiDB root estCost is invalid")
        access = row.get("access object", "")
        relation = re.search(r"\btable:\s*([^,\s]+)", access, re.IGNORECASE)
        index = re.search(r"\bindex:\s*([^,(\s]+)", access, re.IGNORECASE)
        node = {"operation": operation, "task": row.get("task", "").strip().lower(),
                "relation": relation.group(1).strip("`").lower() if relation else None,
                "index": index.group(1).strip("`").lower() if index else None,
                "operator_info": row.get("operator info", ""), "children": []}
        while stack and stack[-1][0] >= depth:
            stack.pop()
        if stack:
            stack[-1][1]["children"].append(node)
        else:
            nodes.append(node)
        stack.append((depth, node))
    if len(nodes) != 1:
        raise TiDBPlanError("TiDB plan must contain exactly one root")
    return {"root": nodes[0], "estimated_cost": root_cost}
