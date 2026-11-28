from pathlib import Path

import pytest
import yaml

from scripts import run_ablation_experiment as experiment
from scripts.pdg_validation._common import atomic_json


@pytest.mark.parametrize('enabled', [True, False])
def test_validation_policy_is_loadable_and_reaches_l3(tmp_path, monkeypatch, enabled):
    path=tmp_path/'experiment.yaml'
    path.write_text(yaml.safe_dump({'dbms':'mysql','validation':{
        'baseline_min_speedup':2.0,'allow_result_mismatch':enabled,'scaled_timeout_pass':enabled}}))
    config=experiment.load_config(path)
    assert config['validation']['allow_result_mismatch'] is enabled
    arm=tmp_path/'arm';arm.mkdir()
    meta={'seed':7}
    for stage in ['l1','l2']:
        directory=arm/stage;directory.mkdir();atomic_json(directory/'summary.json',{})
        meta[stage]=str(directory)
    commands=[]
    def command(args, log):
        commands.append(args)
        out=Path(args[args.index('--output-dir')+1]);out.mkdir(parents=True)
        atomic_json(out/'summary.json',{'cleanup_errors':[]})
    monkeypatch.setattr(experiment,'run_command',command)
    experiment.offline(config,arm,meta)
    assert len(commands)==1
    args=commands[0]
    assert args[args.index('--baseline-min-speedup')+1]=='2.0'
    assert ('--allow-result-mismatch' in args) is enabled
    assert ('--scaled-timeout-pass' in args) is enabled
