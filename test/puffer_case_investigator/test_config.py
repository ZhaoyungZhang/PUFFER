from __future__ import annotations

import pytest

from puffer_case_investigator.config import ProfileSettings
from puffer_case_investigator.db import DatabaseProfile, InvestigatorDBError


def test_profile_does_not_supply_a_default_password(monkeypatch) -> None:
    variable = "PUFFER_TEST_PASSWORD"
    monkeypatch.delenv(variable, raising=False)
    profile = ProfileSettings(
        name="mysql-local",
        dbms="mysql",
        host="127.0.0.1",
        port=3306,
        user="sqlancer",
        password_env=variable,
        expected_version_prefix="9.7.1",
    )
    assert profile.password is None
    with pytest.raises(InvestigatorDBError, match=variable):
        DatabaseProfile.from_settings(profile).resolved_password()


def test_profile_reads_password_only_from_its_environment_variable(monkeypatch) -> None:
    variable = "PUFFER_TEST_PASSWORD"
    monkeypatch.setenv(variable, "configured-for-test")
    profile = ProfileSettings(
        name="mariadb-local",
        dbms="mariadb",
        host="127.0.0.1",
        port=3306,
        user="sqlancer",
        password_env=variable,
        expected_version_prefix="12.3.2",
    )
    assert profile.password == "configured-for-test"
    assert DatabaseProfile.from_settings(profile).resolved_password() == "configured-for-test"
