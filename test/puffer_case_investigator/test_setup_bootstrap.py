from __future__ import annotations

import pytest

from puffer_case_investigator.db import SetupValidationError, rewrite_setup_sql


@pytest.mark.parametrize("prefix", [
    "USE test; DROP DATABASE IF EXISTS generated; CREATE DATABASE generated; USE generated;",
    "-- SQLancer bootstrap\nUsE `test`; DROP DATABASE `generated`; /* dataset */ CREATE DATABASE `GENERATED`; USE generated;",
])
def test_sqlancer_bootstrap_context_is_not_an_additional_dataset(prefix):
    result = rewrite_setup_sql(prefix + "CREATE TABLE generated.t0(c0 VARCHAR(30)); "
        "INSERT INTO generated.t0 VALUES ('test.t0');", "puffer_ci_bootstrap")
    assert result == [
        "DROP DATABASE IF EXISTS `puffer_ci_bootstrap`",
        "CREATE DATABASE `puffer_ci_bootstrap`",
        "USE `puffer_ci_bootstrap`",
        "CREATE TABLE `puffer_ci_bootstrap`.t0(c0 VARCHAR(30))",
        "INSERT INTO `puffer_ci_bootstrap`.t0 VALUES ('test.t0')",
    ]


@pytest.mark.parametrize("sql", [
    "USE test; CREATE TABLE t0(c0 INT); DROP DATABASE generated; CREATE DATABASE generated; USE generated;",
    "USE test; DROP DATABASE victim; CREATE DATABASE generated; USE generated; CREATE TABLE t0(c0 INT);",
    "USE test; DROP DATABASE generated; CREATE DATABASE generated; USE generated; USE test; CREATE TABLE t0(c0 INT);",
    "USE test; DROP DATABASE generated; CREATE DATABASE generated; USE generated; CREATE TABLE test.t0(c0 INT);",
    "USE test; DROP DATABASE generated; CREATE DATABASE generated; USE generated; DROP DATABASE victim;",
    "USE business; DROP DATABASE generated; CREATE DATABASE generated; USE generated; CREATE TABLE t0(c0 INT);",
    "USE test; DROP DATABASE generated; CREATE DATABASE generated; CREATE TABLE t0(c0 INT);",
])
def test_bootstrap_exception_does_not_allow_other_database_access(sql):
    with pytest.raises(SetupValidationError):
        rewrite_setup_sql(sql, "puffer_ci_bootstrap")


def test_single_database_and_setups_without_lifecycle_keep_working():
    assert rewrite_setup_sql("USE generated; CREATE TABLE t0(c0 INT)", "puffer_ci_bootstrap")[-1] == "CREATE TABLE t0(c0 INT)"
    assert rewrite_setup_sql("CREATE TABLE t0(c0 INT)", "puffer_ci_bootstrap")[-1] == "CREATE TABLE t0(c0 INT)"
