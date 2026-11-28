"""Thin PostgreSQL-protocol adapter for generated CRDB ablation cases.

Timing, equivalence checks and verdicts are inherited from the shared adapter.
The setup dialect intentionally accepts only SQLancer's generated data setup.
"""
from __future__ import annotations

import re
from typing import Any

from .cockroach_plan import parse_plan
from .db import (BaseDatabaseAdapter, ColumnInfo, ExplainResult, SetupValidationError,
                 quote_identifier, split_sql_statements, validate_sandbox_name, validate_read_only_query,
                 _strip_leading_comments)


class CockroachDBAdapter(BaseDatabaseAdapter):
    explain_prefix = "EXPLAIN (OPT, VERBOSE) "
    analyze_prefix = "EXPLAIN ANALYZE "

    def __init__(self, profile: Any, *, connection_factory: Any = None, password: Any = None) -> None:
        if profile.dbms != "cockroachdb":
            raise ValueError("CockroachDBAdapter requires a cockroachdb profile")
        if connection_factory is None:
            import psycopg2
            connection_factory = psycopg2.connect
        super().__init__(profile, connection_factory=connection_factory, password=password)

    @staticmethod
    def quote_identifier(name: str) -> str:
        return quote_identifier(name).replace("`", '"')

    @staticmethod
    def begin_data_transaction(connection: Any) -> None:
        connection.autocommit = False

    def _connect(self, database: str | None = None) -> Any:
        password = self._password_override if self._password_override is not None else self.profile.resolved_password()
        connection = self._connection_factory(host=self.profile.host, port=self.profile.port,
            user=self.profile.user, password=password, dbname=database or "defaultdb", sslmode="disable",
            connect_timeout=self.profile.connect_timeout_s)
        connection.autocommit = True
        self._configure_query_timeout(connection)
        return connection

    def _configure_query_timeout(self, connection: Any) -> None:
        with connection.cursor() as cursor:
            cursor.execute(f"SET statement_timeout = '{int(self.profile.query_timeout_s * 1000)}ms'")

    def drop_sandbox(self, sandbox_name: str) -> None:
        validate_sandbox_name(sandbox_name)
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS {self.quote_identifier(sandbox_name)} CASCADE")
        finally:
            connection.close()

    def create_sandbox(self, sandbox_name: str) -> None:
        self.drop_sandbox(sandbox_name)
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(f"CREATE DATABASE {self.quote_identifier(sandbox_name)}")
        finally:
            connection.close()

    def load_setup(self, setup_sql: str, sandbox_name: str) -> list[str]:
        validate_sandbox_name(sandbox_name)
        statements = []
        for raw in split_sql_statements(setup_sql):
            statement = _strip_leading_comments(raw).strip()
            if not statement:
                continue
            # Lifecycle is replaced by an owned database and a fresh connection.
            if re.fullmatch(r'(?:USE|CREATE DATABASE(?: IF NOT EXISTS)?|DROP DATABASE(?: IF EXISTS)?)\s+[A-Za-z_][A-Za-z0-9_]*(?:\s+CASCADE)?', statement, re.I):
                continue
            # Online cluster settings are instance-wide, never replay them here.
            if re.match(r"SET\s+CLUSTER\s+SETTING\b", statement, re.I):
                continue
            if not re.match(r"^(?:CREATE\s+(?:TABLE|INDEX|UNIQUE\s+INDEX)|INSERT\s+INTO|ANALYZE|SET\s+(?!DATABASE\b|SEARCH_PATH\b))\b", statement, re.I):
                raise SetupValidationError(f"unsupported CRDB setup statement: {statement[:100]}")
            # Generated setups use unqualified objects. Reject qualified object
            # names instead of guessing how an external database should map.
            if re.search(r'\b(?:TABLE|INTO|ON|REFERENCES|ANALYZE)\s+[\w"]+\s*\.', statement, re.I):
                raise SetupValidationError("qualified CRDB setup objects are unsupported")
            statements.append(statement)
        if not statements:
            raise SetupValidationError("empty CRDB setup")
        self.create_sandbox(sandbox_name)
        connection = None
        try:
            connection = self._with_database(sandbox_name)
            with connection.cursor() as cursor:
                for statement in statements:
                    cursor.execute(statement)
        except Exception:
            self.drop_sandbox(sandbox_name)
            raise
        finally:
            if connection is not None:
                connection.close()
        return statements

    def list_tables(self, sandbox_name: str) -> tuple[str, ...]:
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name")
                return tuple(str(row[0]) for row in cursor.fetchall())
        finally:
            connection.close()

    def _columns(self, connection: Any, sandbox_name: str, table: str) -> tuple[ColumnInfo, ...]:
        with connection.cursor() as cursor:
            cursor.execute("SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position", (table,))
            return tuple(ColumnInfo(str(row[0]), str(row[1]), str(row[1]), row[2] == "YES") for row in cursor.fetchall())

    def refresh_statistics(self, sandbox_name: str, tables: Any = None) -> None:
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                for table in tables or self.list_tables(sandbox_name):
                    cursor.execute(f"ANALYZE {self.quote_identifier(table)}")
        finally:
            connection.close()

    def _read_plan(self, sandbox_name: str, query: str, prefix: str, *, runtime_plan: bool = False) -> tuple[str, Any]:
        connection = self._with_database(sandbox_name)
        try:
            with connection.cursor() as cursor:
                cursor.execute(prefix + validate_read_only_query(query))
                raw = "\n".join(str(row[0]) for row in cursor.fetchall())
            return raw, None if runtime_plan else parse_plan(raw)
        finally:
            connection.close()

    def explain(self, sandbox_name: str, query: str) -> ExplainResult:
        raw, parsed = self._read_plan(sandbox_name, query, self.explain_prefix)
        return ExplainResult(raw=raw, parsed=parsed, estimated_cost=parsed["estimated_cost"])
