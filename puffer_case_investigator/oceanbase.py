"""MySQL-mode OceanBase adapter; replay, timing and scaling stay shared."""
from __future__ import annotations

from typing import Any

import pymysql

from .db import AnalyzeResult, BaseDatabaseAdapter, ExplainResult, InvestigatorDBError
from .oceanbase_plan import parse_plan


class OceanBaseAdapter(BaseDatabaseAdapter):
    analyze_prefix = "EXPLAIN ANALYZE "

    def __init__(self, profile: Any, **kwargs: Any) -> None:
        if profile.dbms != "oceanbase":
            raise ValueError("OceanBaseAdapter requires an oceanbase profile")
        tenant = profile.user.rsplit("@", 1)[-1].split("#", 1)[0].lower()
        if profile.user.lower() == "root" or tenant == "sys":
            raise ValueError("OceanBase ablation requires a MySQL-mode test tenant, not sys/root")
        super().__init__(profile, **kwargs)
        self._supports_explain_analyze: bool | None = None

    def _connect(self, database: str | None = None) -> Any:
        connection = super()._connect(database)
        if database is None:
            try:
                self._configure_query_timeout(connection)
            except Exception:
                connection.close()
                raise
        return connection

    def _configure_query_timeout(self, connection: Any) -> None:
        with connection.cursor() as cursor:
            cursor.execute(f"SET SESSION ob_query_timeout={self.profile.query_timeout_s * 1_000_000}")
            cursor.execute(f"SET SESSION ob_trx_timeout={max(600, self.profile.query_timeout_s) * 1_000_000}")
            cursor.execute("SET SESSION ob_enable_plan_cache = 0")

    def validate_expected_version(self) -> str:
        version = super().validate_expected_version()
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT @@ob_compatibility_mode")
                row = cursor.fetchone()
                if not row or str(row[0]).upper() != "MYSQL":
                    raise InvestigatorDBError("OceanBase ablation requires a MySQL-mode test tenant")
        finally:
            connection.close()
        return version

    def explain(self, sandbox_name: str, query: str) -> ExplainResult:
        raw, parsed = self._read_plan(sandbox_name, query, self.explain_prefix)
        plan = parse_plan(parsed)
        return ExplainResult(raw=raw, parsed=parsed, estimated_cost=plan["estimated_cost"], structure=plan["root"])

    def explain_analyze(self, sandbox_name: str, query: str) -> AnalyzeResult:
        if self._supports_explain_analyze is not False:
            try:
                result = super().explain_analyze(sandbox_name, query)
                self._supports_explain_analyze = True
                return result
            except (pymysql.err.ProgrammingError, pymysql.err.OperationalError) as error:
                if not error.args or error.args[0] not in (1064, 1235):
                    raise
                self._supports_explain_analyze = False
        raw, parsed = self._read_plan(sandbox_name, query, "EXPLAIN EXTENDED ", runtime_plan=True)
        return AnalyzeResult(raw="EXPLAIN EXTENDED (runtime plan unavailable)\n" + raw, parsed=parsed)

    @staticmethod
    def is_statement_timeout(error: BaseException) -> bool:
        # 4012 also covers transaction timeouts. Bare 'Timeout' is ambiguous.
        message = str(error).lower()
        return (isinstance(error, pymysql.err.OperationalError) and bool(error.args)
                and error.args[0] in (4012, -4012) and "transaction" not in message
                and any(token in message for token in ("query timeout", "query execution timeout", "ob_query_timeout")))
