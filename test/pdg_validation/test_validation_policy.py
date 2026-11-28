"""Regression coverage for the requested ablation validation policy."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace

import pymysql
import pytest

from puffer_case_investigator.db import AnalyzeResult, DatabaseProfile, ExplainResult, MySQLAdapter
from puffer_case_investigator.models import ResultComparison
from puffer_case_investigator.results import digest_rows

ROOT = Path(__file__).resolve().parents[2]


def measurement():
    return SimpleNamespace(timeout_observations=(), rounds=(), default=SimpleNamespace(median_ms=10),
                           counterfactual=SimpleNamespace(median_ms=1), speedup=10, absolute_gap_ms=9,
                           to_dict=lambda: {})


def stage():
    spec = importlib.util.spec_from_file_location('policy_scale', ROOT / 'scripts/pdg_validation/03_scale_validate.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replay(default, alt, equivalent=True, sides=()):
    return {'result_comparison': {'equivalent': equivalent},
            'runtime': {'default': {'median_ms': default}, 'counterfactual': {'median_ms': alt},
                        'rounds': [{'default_runtime_ms': default, 'counterfactual_runtime_ms': alt}] * 5},
            'classification_metrics': {'timeoutObserved': bool(sides), 'timeoutLimitMs': 120000,
                                      'timeoutObservations': [{'side': side, 'stage': 'EXPLAIN_ANALYZE',
                                                               'limit_ms': 120000} for side in sides]}}


@pytest.mark.parametrize('one,ten,equivalent,sides,status,review', [
    ((30, 10), (100, 10), True, (), 'AMPLIFIED', 0),
    ((19, 10), (100, 10), True, (), 'DISAPPEARED', 0),
    ((30, 10), (40, 10), True, (), 'DISAPPEARED', 0),
    ((30, 10), (100, 10), False, (), 'AMPLIFIED', 0),
    ((30, 10), (40, 10), False, (), 'DISAPPEARED', 0),
    ((30, 10), (120000, 30000), None, ('default',), 'SUSTAINED', 1),
    ((30, 10), (120000, 120000), None, ('default', 'counterfactual'), 'SUSTAINED', 1),
    ((30, 10), (10, 120000), True, ('counterfactual',), 'DISAPPEARED', 0),
])
def test_policy_classification_and_counts(tmp_path, monkeypatch, one, ten, equivalent, sides, status, review):
    module = stage()
    args = module.parse_args(['--dbms', 'mysql', '--representatives', str(tmp_path/'reps.tsv'),
                              '--output-dir', str(tmp_path), '--min-speedup', '5', '--min-gap-ms', '0.5',
                              '--seed', '7', '--allow-result-mismatch', '--scaled-timeout-pass'])
    row = {'dbms': 'mysql', 'cell': 'G', 'case_id': 'case1', 'family_id': 'family1', 'case_path': str(tmp_path)}
    monkeypatch.setattr(module, 'load_pdg', lambda _: {'dbms': 'mysql'})
    monkeypatch.setattr('puffer_case_investigator.case_parser.parse_case', lambda *_, **__: object())
    monkeypatch.setattr(module, 'extend_database', lambda *_, **__: {'t0': 1000})
    replies = iter([replay(*one), replay(*ten, equivalent=equivalent, sides=sides)])
    monkeypatch.setattr(module, 'replay_phase', lambda *_, **__: next(replies))
    result = module.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert result['status'] == status
    summary = module.write_progress(tmp_path, args, [row], [result], 'COMPLETE')
    assert summary['manual_review_timeout_count'] == review
    assert summary['validated_performance_candidates'] == (status in ('AMPLIFIED', 'SUSTAINED', 'ACTIVATED'))
    if equivalent is False:
        assert result['equivalence_verified'] is False
        assert summary['result_mismatch_count'] == 1
        assert summary['passed_without_equivalence_count'] == (status == 'AMPLIFIED')


@pytest.mark.parametrize('sides', [('default',), ('default', 'counterfactual'), ('counterfactual',)])
def test_adapter_captures_actual_timeout_sides(tmp_path, monkeypatch, sides):
    db = MySQLAdapter(DatabaseProfile(name='test', dbms='mysql', host='localhost', port=3306,
                                      user='test', password_env='TEST_PASSWORD', query_timeout_s=120))
    db._active_version = '9.7.1'
    monkeypatch.setattr(db, 'refresh_statistics', lambda _: None)
    monkeypatch.setattr(db, 'explain', lambda *_: ExplainResult('{}', {}, 1.0))
    def analyze(_, query):
        if ('default' if query == 'SELECT 1' else 'counterfactual') in sides:
            raise pymysql.err.OperationalError(3024, 'deadline')
        return AnalyzeResult('{}', {})
    monkeypatch.setattr(db, 'explain_analyze', analyze)
    digest = digest_rows([(1,)], ordered=False)
    monkeypatch.setattr(db, 'compare_query_results', lambda *_, **__: ResultComparison(True, digest, digest, 'equal'))
    measured = []
    monkeypatch.setattr(db, 'measure_pair', lambda *_, **__: (measured.append(True), measurement())[1])
    result = db.replay(SimpleNamespace(original_query='SELECT 1', counterfactual_query='SELECT 2'),
                       'puffer_ci_test', timeout_as_limit=True, scaled_default_timeout_pass=True).to_dict()
    assert {item['side'] for item in result['classification_metrics']['timeoutObservations']} == set(sides)
    assert bool(measured) == ('default' not in sides)
    if 'default' in sides:
        assert result['result_comparison']['equivalent'] is None
        assert result['runtime'] is None


def test_adapter_measures_mismatched_results(monkeypatch):
    db = MySQLAdapter(DatabaseProfile(name='test', dbms='mysql', host='localhost', port=3306,
                                      user='test', password_env='TEST_PASSWORD', query_timeout_s=120))
    db._active_version = '9.7.1'
    monkeypatch.setattr(db, 'refresh_statistics', lambda _: None)
    monkeypatch.setattr(db, 'explain', lambda *_: ExplainResult('{}', {}, 1.0))
    monkeypatch.setattr(db, 'explain_analyze', lambda *_: AnalyzeResult('{}', {}))
    monkeypatch.setattr(db, 'compare_query_results', lambda *_, **__: {'equivalent': False})
    calls = []
    monkeypatch.setattr(db, 'measure_pair', lambda *_, **__: (calls.append(True), measurement())[1])
    result = db.replay(SimpleNamespace(original_query='SELECT 1', counterfactual_query='SELECT 2'),
                       'puffer_ci_test', continue_on_result_mismatch=True).to_dict()
    assert calls == [True]
    assert result['result_comparison']['equivalent'] is False


def test_socket_timeout_does_not_become_a_policy_pass(tmp_path, monkeypatch):
    module = stage()
    args = module.parse_args(['--dbms', 'mysql', '--representatives', str(tmp_path/'reps.tsv'),
                              '--output-dir', str(tmp_path), '--min-speedup', '5', '--min-gap-ms', '0.5',
                              '--seed', '7', '--allow-result-mismatch', '--scaled-timeout-pass'])
    row = {'dbms': 'mysql', 'cell': 'G', 'case_id': 'case1', 'family_id': 'family1', 'case_path': str(tmp_path)}
    monkeypatch.setattr(module, 'load_pdg', lambda _: {'dbms': 'mysql'})
    monkeypatch.setattr('puffer_case_investigator.case_parser.parse_case', lambda *_, **__: object())
    monkeypatch.setattr(module, 'extend_database', lambda *_, **__: {'t0': 1000})
    def phase(*_, scaled=False, **__):
        if scaled:
            raise TimeoutError('socket timeout')
        return replay(30, 10)
    monkeypatch.setattr(module, 'replay_phase', phase)
    result = module.validate_one(row, SimpleNamespace(prepare_sandbox=lambda *_: None, cleanup=lambda *_: None), args)
    assert result['status'] == 'FAILED'
    assert not result.get('timeout_pass', False)

