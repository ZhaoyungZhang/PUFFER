from puffer_case_investigator.db import inline_initial_indexes


def test_inline_indexes_preserves_columns_index_order_and_insert_statements():
    sql=['USE generated', 'CREATE TABLE t0(c0 BIGINT, c1 VARCHAR(64))', 'CREATE TABLE t1(c0 INT)',
         'CREATE INDEX i0 ON t0(c0)', 'CREATE INDEX i1 ON t0(c0, c1)', 'CREATE INDEX i2 ON t1(c0)',
         "INSERT INTO t0 VALUES (1, 'text')", 'ANALYZE TABLE t0', 'CREATE INDEX i3 ON t0(c1)']
    assert inline_initial_indexes(sql) == ['USE generated',
        'CREATE TABLE t0(c0 BIGINT, c1 VARCHAR(64), INDEX i0 (c0), INDEX i1 (c0, c1))',
        'CREATE TABLE t1(c0 INT, INDEX i2 (c0))', "INSERT INTO t0 VALUES (1, 'text')", 'ANALYZE TABLE t0', 'CREATE INDEX i3 ON t0(c1)']


def test_other_setup_grammar_is_left_verbatim():
    for sql in [
        ['CREATE TABLE t0(c0 INT)', 'CREATE UNIQUE INDEX i0 ON t0(c0)'],
        ['CREATE TABLE user_table(c0 INT)', 'CREATE INDEX i0 ON user_table(c0)'],
        ['CREATE TABLE t0(c0 INT)', 'ALTER TABLE t0 ADD COLUMN c1 INT', 'CREATE INDEX i0 ON t0(c1)'],
        ['CREATE TABLE t0(c0 INT)', 'CREATE INDEX i0 ON t0(c0)', 'CREATE TABLE t0(c0 BIGINT)'],
        ['CREATE TABLE t0(c0 INT)', 'DELETE FROM t0', 'CREATE INDEX i0 ON t0(c0)'],
    ]:
        assert inline_initial_indexes(sql) == sql


def test_indexes_created_after_initial_inserts_can_be_folded():
    sql=['CREATE TABLE t0(c0 INT)', 'INSERT INTO t0 VALUES (1)', 'CREATE INDEX i0 ON t0(c0)', 'ANALYZE TABLE t0']
    assert inline_initial_indexes(sql) == ['CREATE TABLE t0(c0 INT, INDEX i0 (c0))',
                                            'INSERT INTO t0 VALUES (1)', 'ANALYZE TABLE t0']
