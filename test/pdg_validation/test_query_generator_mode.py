"""The same query mode reaches Java regardless of the data ablation profile."""
import copy
from pathlib import Path

import pytest

from scripts import run_ablation_experiment as campaign
from scripts.pdg_validation._common import ValidationInputError


@pytest.mark.parametrize("mode", ["structured", "expanded", "mixed"])
@pytest.mark.parametrize("profile", campaign.PROFILES)
def test_mode_is_independent_of_data_profile(mode, profile):
    config = copy.deepcopy(campaign.DEFAULTS)
    config["java"]["query_generator_mode"] = mode
    config["data_profiles"] = [profile]
    campaign.validate_config(config)
    assert campaign.ENV_FIELDS["java"]["query_generator_mode"] == "PERF_QUERY_GENERATOR_MODE"


def test_bad_mode_fails_before_any_database_action():
    config = copy.deepcopy(campaign.DEFAULTS)
    config["java"]["query_generator_mode"] = "bad"
    with pytest.raises(ValidationInputError, match="query_generator_mode"):
        campaign.validate_config(config)


def test_wrappers_forward_the_mode():
    root = Path(__file__).resolve().parents[2]
    for relative in ("scripts/run-perfhint-threshold-campaign.sh", "scripts/run_mysql_9_7_1_sqlancer.sh",
                     "scripts/run_mariadb_12_3_2_sqlancer.sh", "scripts/run_tidb_8_5_0_sqlancer.sh",
                     "scripts/run_cockroachdb_26_2_sqlancer.sh", "scripts/run_oceanbase_4_5_sqlancer.sh"):
        content = (root / relative).read_text()
        assert '--perf-query-generator-mode "$PERF_QUERY_GENERATOR_MODE"' in content
        assert 'PERF_QUERY_GENERATOR_MODE="${PERF_QUERY_GENERATOR_MODE:-mixed}"' in content


