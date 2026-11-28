#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

source "$SCRIPT_DIR/perfhint_properties.sh"
CONFIG_PATH=""
perfhint_take_config_argument CONFIG_PATH "$@"
set -- "${PERFHINT_REMAINING_ARGS[@]}"
if [ -n "$CONFIG_PATH" ]; then
    perfhint_load_properties "$CONFIG_PATH"
fi

REPORT_ROOT="${REPORT_ROOT:-$REPO_ROOT/evaluation/cockroachdb_26_2_2}"
RUN_LOG_DIR="${RUN_LOG_DIR:-$REPORT_ROOT/logs}"
CAMPAIGN_LOG_DIR="${CAMPAIGN_LOG_DIR:-$REPORT_ROOT/campaign_logs}"
SQLANCER_LOG_DIR="${SQLANCER_LOG_DIR:-$REPORT_ROOT/sqlancer_logs}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-26257}"
USERNAME="${USERNAME:-root}"
PASSWORD="${PASSWORD:-}"
DB_PREFIX_BASE="${DB_PREFIX_BASE:-cockroach262ph}"
SEED="${SEED:-0}"
START_SEED="${START_SEED:-0}"
NUM_QUERIES="${NUM_QUERIES:-64}"
WORKERS="${WORKERS:-1}"
TRIES="${TRIES:-$WORKERS}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-2400}"
REPEAT_COUNT="${REPEAT_COUNT:-3}"
COST_THRESHOLD="${COST_THRESHOLD:-1.5}"
RUNTIME_THRESHOLD="${RUNTIME_THRESHOLD:-4.0}"
QUERY_TIMEOUT_SECONDS="${QUERY_TIMEOUT_SECONDS:-5}"
STATEMENT_KIND_RETRIES="${STATEMENT_KIND_RETRIES:-0}"
DURATION_SECONDS="${DURATION_SECONDS:-75600}"
MAX_CAMPAIGN_DURATION_SECONDS="${MAX_CAMPAIGN_DURATION_SECONDS:-604800}"
MAX_SEED_SECONDS="${MAX_SEED_SECONDS:-1800}"
SUMMARY_INTERVAL_SECONDS="${SUMMARY_INTERVAL_SECONDS:-60}"
JAR_PATH="${JAR_PATH:-}"
COCKROACH_EXPECTED_VERSION_PREFIX="${COCKROACH_EXPECTED_VERSION_PREFIX:-v26.2.2}"
COCKROACH_BIN="${COCKROACH_BIN:-$(command -v cockroach || echo cockroach)}"
COCKROACH_SQL_EXTRA_ARGS="${COCKROACH_SQL_EXTRA_ARGS:---insecure}"
PID_FILE="${PID_FILE:-$CAMPAIGN_LOG_DIR/perfhint_cockroachdb.pid}"
QPG_PID_FILE="${QPG_PID_FILE:-$CAMPAIGN_LOG_DIR/qpg_cockroachdb.pid}"
DATA_GENERATOR_MODE="${DATA_GENERATOR_MODE:-model-v1}"
DATA_ABLATION_PROFILE="${DATA_ABLATION_PROFILE:-none}"
MIN_TABLES="${MIN_TABLES:-2}"
MAX_TABLES="${MAX_TABLES:-4}"
MIN_ROWS_PER_TABLE="${MIN_ROWS_PER_TABLE:-100}"
MAX_ROWS_PER_TABLE="${MAX_ROWS_PER_TABLE:-1000}"
PERF_INITIAL_SCALE="${PERF_INITIAL_SCALE:-1}"
MIN_COLUMNS="${MIN_COLUMNS:-3}"
MAX_COLUMNS="${MAX_COLUMNS:-8}"
MAX_INDEXES_PER_TABLE="${MAX_INDEXES_PER_TABLE:-2}"
SINGLE_COLUMN_MODEL_PROBABILITY="${SINGLE_COLUMN_MODEL_PROBABILITY:-0.8}"
MULTI_COLUMN_MODEL_PROBABILITY="${MULTI_COLUMN_MODEL_PROBABILITY:-0.6}"
INTER_TABLE_MODEL_PROBABILITY="${INTER_TABLE_MODEL_PROBABILITY:-0.8}"
FEEDBACK_STATE_PATH="${FEEDBACK_STATE_PATH:-$REPORT_ROOT/feedback_state}"
PERF_HINT_INCLUDE_TYPES="${PERF_HINT_INCLUDE_TYPES:-}"
PERF_HINT_EXCLUDE_TYPES="${PERF_HINT_EXCLUDE_TYPES:-}"
PERF_HINT_FAMILY_QUOTA="${PERF_HINT_FAMILY_QUOTA:-4}"
PERF_HINT_FAMILY_WEIGHT_MODE="${PERF_HINT_FAMILY_WEIGHT_MODE:-uniform}"
PERF_HINT_MAX_CANDIDATES_PER_FAMILY="${PERF_HINT_MAX_CANDIDATES_PER_FAMILY:-4}"
PERF_COUNTERFACTUAL_HINT_PROBABILITY="${PERF_COUNTERFACTUAL_HINT_PROBABILITY:-0.5}"
PERF_PREDICATE_BINDING_PROBABILITY="${PERF_PREDICATE_BINDING_PROBABILITY:-0.5}"
PERF_QUERY_GENERATOR_MODE="${PERF_QUERY_GENERATOR_MODE:-mixed}"
case "$PERF_QUERY_GENERATOR_MODE" in
    structured|expanded|mixed) ;;
    *) echo "PERF_QUERY_GENERATOR_MODE must be structured, expanded, or mixed" >&2; exit 1 ;;
esac
PERF_QUERY_REWRITE_PROVIDER="${PERF_QUERY_REWRITE_PROVIDER:-none}"
PERF_WETUNE_STATIC_WORKER="${PERF_WETUNE_STATIC_WORKER:-}"
PERF_WETUNE_STATIC_TIMEOUT_MS="${PERF_WETUNE_STATIC_TIMEOUT_MS:-100}"
PERF_TAXONOMY_ENABLE="${PERF_TAXONOMY_ENABLE:-1}"
PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS="${PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS:-0.2}"
PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS="${PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS:-0.5}"
PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND="${PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND:-0.9}"
PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND="${PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND:-1.3}"
PERF_REPORT_HARMFUL_RUNTIME_RATIO="${PERF_REPORT_HARMFUL_RUNTIME_RATIO:-3.0}"
PERF_REPORT_COST_ANOMALY_RATIO="${PERF_REPORT_COST_ANOMALY_RATIO:-10.0}"
PERF_TAXONOMY_REPORT_CELLS="${PERF_TAXONOMY_REPORT_CELLS:-A,B,C,D,G}"
PERF_TAXONOMY_COST_RATIO_LOWER_BOUND="${PERF_TAXONOMY_COST_RATIO_LOWER_BOUND:-0.95}"
PERF_TAXONOMY_COST_RATIO_UPPER_BOUND="${PERF_TAXONOMY_COST_RATIO_UPPER_BOUND:-1.05}"
QPG_LOG_QUERY_PLAN="${QPG_LOG_QUERY_PLAN:-1}"
QPG_MAX_INTERVAL="${QPG_MAX_INTERVAL:-0}"
QPG_REWARD_WEIGHT="${QPG_REWARD_WEIGHT:-0.25}"
QPG_SELECTION_PROBABILITY="${QPG_SELECTION_PROBABILITY:-0.45}"
QPG_STALE_WINDOW_SIZE="${QPG_STALE_WINDOW_SIZE:-12}"
QPG_STALE_WINDOW_MAX_NEW_PLANS="${QPG_STALE_WINDOW_MAX_NEW_PLANS:-4}"
QPG_STALE_WINDOW_MAX_POOL_GROWTH="${QPG_STALE_WINDOW_MAX_POOL_GROWTH:-3}"
QPG_STALE_REWARD_LOOKBACK="${QPG_STALE_REWARD_LOOKBACK:-4}"
QPG_STALE_MAX_RECENT_REWARD="${QPG_STALE_MAX_RECENT_REWARD:-0.05}"
PERF_CONTINUE_AFTER_FORMAL_WITNESS="${PERF_CONTINUE_AFTER_FORMAL_WITNESS:-0}"
PERF_ASYNC_REPORT_WRITES="${PERF_ASYNC_REPORT_WRITES:-0}"
PERF_REPORT_QUEUE_CAPACITY="${PERF_REPORT_QUEUE_CAPACITY:-256}"
PERF_REPORT_QUEUE_OFFER_MS="${PERF_REPORT_QUEUE_OFFER_MS:-50}"
PERF_INSERT_BATCH_SIZE="${PERF_INSERT_BATCH_SIZE:-25}"

usage() {
    cat <<EOF
Usage: $0 [--config <properties-path>] <command>

Commands:
  build-sqlancer    Build the SQLancer jar with Maven
  smoke             Run one PERFORMANCE_HINT smoke test against CockroachDB 26.2.x
  campaign          Run a rolling-seed PERFORMANCE_HINT campaign against CockroachDB 26.2.x
  qpg               Run one QPG-guided PERFORMANCE_HINT smoke test against CockroachDB 26.2.x
  qpg-campaign      Run a rolling-seed QPG-guided PERFORMANCE_HINT campaign against CockroachDB 26.2.x
EOF
}

ensure_dirs() {
    mkdir -p "$REPORT_ROOT" "$RUN_LOG_DIR" "$CAMPAIGN_LOG_DIR" "$SQLANCER_LOG_DIR"
}

resolve_jar() {
    if [ -n "$JAR_PATH" ]; then
        printf '%s\n' "$JAR_PATH"
        return
    fi
    find "$REPO_ROOT/target" -maxdepth 1 -type f -name 'sqlancer-*.jar' ! -name 'original-*' | sort | head -n 1
}

verify_cockroach_target() {
    local version
    version="$("$COCKROACH_BIN" sql $COCKROACH_SQL_EXTRA_ARGS --host="$HOST" --port="$PORT" --user="$USERNAME" \
        -e 'select version()' 2>/dev/null | tail -n 1 | tr -d ' ' || true)"
    if [ -z "$version" ]; then
        echo "failed to query CockroachDB version at $HOST:$PORT with user $USERNAME" >&2
        exit 1
    fi
    if [[ "$version" != *"$COCKROACH_EXPECTED_VERSION_PREFIX"* ]]; then
        echo "refusing to run SQLancer against $HOST:$PORT: expected CockroachDB $COCKROACH_EXPECTED_VERSION_PREFIX, got $version" >&2
        exit 1
    fi
    printf 'verified_target_version=%s\n' "$version"
}

build_sqlancer() {
    mvn -DskipTests package
}

normalize_bool_env() {
    case "${1,,}" in
        1|true|yes|on)
            printf 'true'
            ;;
        0|false|no|off|'')
            printf 'false'
            ;;
        *)
            printf '%s' "$1"
            ;;
    esac
}

prepare_wetune_static() {
    perfhint_prepare_wetune_static_worker "$REPO_ROOT"
    export PERF_QUERY_GENERATOR_MODE PERF_COUNTERFACTUAL_HINT_PROBABILITY PERF_PREDICATE_BINDING_PROBABILITY PERF_QUERY_REWRITE_PROVIDER PERF_WETUNE_STATIC_WORKER
    export PERF_WETUNE_STATIC_TIMEOUT_MS PERF_TAXONOMY_ENABLE PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS
    export PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND
    export PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND PERF_REPORT_HARMFUL_RUNTIME_RATIO
    export PERF_REPORT_COST_ANOMALY_RATIO
    export PERF_TAXONOMY_REPORT_CELLS PERF_TAXONOMY_COST_RATIO_LOWER_BOUND
    export PERF_TAXONOMY_COST_RATIO_UPPER_BOUND
    export SUMMARY_INTERVAL_SECONDS
}

append_qpg_args() {
    qpg_args=(--qpg-enable true)
    if [ "$QPG_LOG_QUERY_PLAN" = "1" ]; then
        qpg_args+=(--qpg-log-query-plan true)
    else
        qpg_args+=(--qpg-log-query-plan false)
    fi
    qpg_args+=(
        --qpg-max-interval "$QPG_MAX_INTERVAL" \
        --qpg-reward-weight "$QPG_REWARD_WEIGHT" \
        --qpg-selection-probability "$QPG_SELECTION_PROBABILITY" \
        --qpg-stale-window-size "$QPG_STALE_WINDOW_SIZE" \
        --qpg-stale-window-max-new-plans "$QPG_STALE_WINDOW_MAX_NEW_PLANS" \
        --qpg-stale-window-max-pool-growth "$QPG_STALE_WINDOW_MAX_POOL_GROWTH" \
        --qpg-stale-reward-lookback "$QPG_STALE_REWARD_LOOKBACK" \
        --qpg-stale-max-recent-reward "$QPG_STALE_MAX_RECENT_REWARD"
    )
}

append_perfhint_args() {
    sqlancer_cmd+=(
        --oracle PERFORMANCE_HINT \
        --perf-cost-ratio-threshold "$COST_THRESHOLD" \
        --perf-enable-runtime-validation \
        --perf-enable-explain-analyze \
        --perf-repeat "$REPEAT_COUNT" \
        --perf-timeout-seconds "$QUERY_TIMEOUT_SECONDS" \
        --perf-runtime-ratio-threshold "$RUNTIME_THRESHOLD" \
        --perf-data-generator-mode "$DATA_GENERATOR_MODE" \
        --perf-min-tables "$MIN_TABLES" \
        --perf-max-tables "$MAX_TABLES" \
        --perf-min-rows-per-table "$MIN_ROWS_PER_TABLE" \
        --perf-max-rows-per-table "$MAX_ROWS_PER_TABLE" \
        --perf-initial-scale "$PERF_INITIAL_SCALE" \
        --perf-min-columns "$MIN_COLUMNS" \
        --perf-max-columns "$MAX_COLUMNS" \
        --perf-max-indexes-per-table "$MAX_INDEXES_PER_TABLE" \
        --perf-model-single-column-probability "$SINGLE_COLUMN_MODEL_PROBABILITY" \
        --perf-model-multi-column-probability "$MULTI_COLUMN_MODEL_PROBABILITY" \
        --perf-model-inter-table-probability "$INTER_TABLE_MODEL_PROBABILITY" \
        --perf-hint-family-quota "$PERF_HINT_FAMILY_QUOTA" \
        --perf-hint-family-weight-mode "$PERF_HINT_FAMILY_WEIGHT_MODE" \
        --perf-hint-max-candidates-per-family "$PERF_HINT_MAX_CANDIDATES_PER_FAMILY" \
        --perf-counterfactual-hint-probability "$PERF_COUNTERFACTUAL_HINT_PROBABILITY" \
        --perf-predicate-binding-probability "$PERF_PREDICATE_BINDING_PROBABILITY" \
        --perf-query-generator-mode "$PERF_QUERY_GENERATOR_MODE" \
        --perf-query-rewrite-provider "$PERF_QUERY_REWRITE_PROVIDER" \
        --perf-wetune-static-timeout-ms "$PERF_WETUNE_STATIC_TIMEOUT_MS" \
        --perf-taxonomy-enable "$(normalize_bool_env "$PERF_TAXONOMY_ENABLE")" \
        --perf-taxonomy-min-runtime-median-ms "$PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS" \
        --perf-taxonomy-min-runtime-abs-gap-ms "$PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS" \
        --perf-taxonomy-runtime-ratio-lower-bound "$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND" \
        --perf-taxonomy-runtime-ratio-upper-bound "$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND" \
        --perf-report-harmful-runtime-ratio "$PERF_REPORT_HARMFUL_RUNTIME_RATIO" \
        --perf-report-cost-anomaly-ratio "$PERF_REPORT_COST_ANOMALY_RATIO" \
        --perf-taxonomy-report-cells "$PERF_TAXONOMY_REPORT_CELLS" \
        --perf-taxonomy-cost-ratio-lower-bound "$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND" \
        --perf-taxonomy-cost-ratio-upper-bound "$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND" \
        --perf-enable-feedback-dedup \
        --perf-feedback-state-path "$FEEDBACK_STATE_PATH" \
        --perf-continue-after-formal-witness "$(normalize_bool_env "$PERF_CONTINUE_AFTER_FORMAL_WITNESS")" \
        --perf-async-report-writes "$(normalize_bool_env "$PERF_ASYNC_REPORT_WRITES")" \
        --perf-report-queue-capacity "$PERF_REPORT_QUEUE_CAPACITY" \
        --perf-report-queue-offer-ms "$PERF_REPORT_QUEUE_OFFER_MS" \
        --perf-insert-batch-size "$PERF_INSERT_BATCH_SIZE"
    )
    if [ -n "$PERF_HINT_INCLUDE_TYPES" ]; then
        sqlancer_cmd+=(--perf-hint-include-types "$PERF_HINT_INCLUDE_TYPES")
    fi
    if [ -n "$PERF_HINT_EXCLUDE_TYPES" ]; then
        sqlancer_cmd+=(--perf-hint-exclude-types "$PERF_HINT_EXCLUDE_TYPES")
    fi
    if [ -n "$PERF_WETUNE_STATIC_WORKER" ]; then
        sqlancer_cmd+=(--perf-wetune-static-worker "$PERF_WETUNE_STATIC_WORKER")
    fi
}

run_smoke() {
    local jar
    local -a sqlancer_cmd
    jar="$(resolve_jar)"
    if [ -z "$jar" ]; then
        echo "SQLancer jar not found; run '$0 build-sqlancer' first" >&2
        exit 1
    fi
    ensure_dirs
    prepare_wetune_static
    verify_cockroach_target
    export SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT"
    export SQLANCER_LOG_DIR
    sqlancer_cmd=(
        java -jar "$jar"
        --random-seed "$SEED" \
        --timeout-seconds "$TIMEOUT_SECONDS" \
        --num-threads "$WORKERS" \
        --num-tries "$WORKERS" \
        --num-queries "$NUM_QUERIES" \
        --num-statement-kind-retries "$STATEMENT_KIND_RETRIES" \
        --host "$HOST" \
        --port "$PORT" \
        --username "$USERNAME" \
        --password "$PASSWORD" \
        --database-prefix "${DB_PREFIX_BASE}${SEED}" \
        cockroachdb
    )
    append_perfhint_args
    "${sqlancer_cmd[@]}" | tee "$RUN_LOG_DIR/smoke-seed-${SEED}.stdout.log"
}

run_qpg() {
    local jar
    local -a sqlancer_cmd
    local -a qpg_args
    jar="$(resolve_jar)"
    if [ -z "$jar" ]; then
        echo "SQLancer jar not found; run '$0 build-sqlancer' first" >&2
        exit 1
    fi
    ensure_dirs
    prepare_wetune_static
    verify_cockroach_target
    export SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT"
    export SQLANCER_LOG_DIR
    append_qpg_args
    sqlancer_cmd=(
        java -jar "$jar"
        --random-seed "$SEED" \
        --timeout-seconds "$TIMEOUT_SECONDS" \
        --num-threads "$WORKERS" \
        --num-tries "$WORKERS" \
        --num-queries "$NUM_QUERIES" \
        --num-statement-kind-retries "$STATEMENT_KIND_RETRIES" \
        --host "$HOST" \
        --port "$PORT" \
        --username "$USERNAME" \
        --password "$PASSWORD" \
        --database-prefix "${DB_PREFIX_BASE}${SEED}" \
        "${qpg_args[@]}" \
        cockroachdb
    )
    append_perfhint_args
    "${sqlancer_cmd[@]}" | tee "$RUN_LOG_DIR/qpg-smoke-seed-${SEED}.stdout.log"
}

run_campaign() {
    ensure_dirs
    prepare_wetune_static
    verify_cockroach_target
    env \
        SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT" \
        SQLANCER_LOG_DIR="$SQLANCER_LOG_DIR" \
        LOG_DIR="$CAMPAIGN_LOG_DIR" \
        MASTER_LOG="$CAMPAIGN_LOG_DIR/perfhint-master.log" \
        LAUNCH_LOG="$CAMPAIGN_LOG_DIR/perfhint-launch.log" \
        PID_FILE="$PID_FILE" \
        DURATION_SECONDS="$DURATION_SECONDS" \
        MAX_CAMPAIGN_DURATION_SECONDS="$MAX_CAMPAIGN_DURATION_SECONDS" \
        MAX_SEED_SECONDS="$MAX_SEED_SECONDS" \
        SUMMARY_INTERVAL_SECONDS="$SUMMARY_INTERVAL_SECONDS" \
        COST_THRESHOLD="$COST_THRESHOLD" \
        RUNTIME_THRESHOLD="$RUNTIME_THRESHOLD" \
        REPEAT_COUNT="$REPEAT_COUNT" \
        QUERY_TIMEOUT_SECONDS="$QUERY_TIMEOUT_SECONDS" \
        STATEMENT_KIND_RETRIES="$STATEMENT_KIND_RETRIES" \
        PER_RUN_TIMEOUT="$TIMEOUT_SECONDS" \
        NUM_QUERIES="$NUM_QUERIES" \
        WORKERS="$WORKERS" \
        TRIES="$TRIES" \
        HOST="$HOST" \
        PORT="$PORT" \
        USERNAME="$USERNAME" \
        PASSWORD="$PASSWORD" \
        DB_PREFIX_BASE="$DB_PREFIX_BASE" \
        DBMS="cockroachdb" \
        USE_EXPLAIN_ANALYZE="1" \
        START_SEED="$START_SEED" \
        DATA_GENERATOR_MODE="$DATA_GENERATOR_MODE" \
        DATA_ABLATION_PROFILE="$DATA_ABLATION_PROFILE" \
        MIN_TABLES="$MIN_TABLES" \
        MAX_TABLES="$MAX_TABLES" \
        MIN_ROWS_PER_TABLE="$MIN_ROWS_PER_TABLE" \
        MAX_ROWS_PER_TABLE="$MAX_ROWS_PER_TABLE" \
        PERF_INITIAL_SCALE="$PERF_INITIAL_SCALE" \
        MIN_COLUMNS="$MIN_COLUMNS" \
        MAX_COLUMNS="$MAX_COLUMNS" \
        MAX_INDEXES_PER_TABLE="$MAX_INDEXES_PER_TABLE" \
        SINGLE_COLUMN_MODEL_PROBABILITY="$SINGLE_COLUMN_MODEL_PROBABILITY" \
        MULTI_COLUMN_MODEL_PROBABILITY="$MULTI_COLUMN_MODEL_PROBABILITY" \
        INTER_TABLE_MODEL_PROBABILITY="$INTER_TABLE_MODEL_PROBABILITY" \
        FEEDBACK_STATE_PATH="$FEEDBACK_STATE_PATH" \
        PERF_CONTINUE_AFTER_FORMAL_WITNESS="$PERF_CONTINUE_AFTER_FORMAL_WITNESS" \
        PERF_ASYNC_REPORT_WRITES="$PERF_ASYNC_REPORT_WRITES" \
        PERF_REPORT_QUEUE_CAPACITY="$PERF_REPORT_QUEUE_CAPACITY" \
        PERF_REPORT_QUEUE_OFFER_MS="$PERF_REPORT_QUEUE_OFFER_MS" \
        PERF_INSERT_BATCH_SIZE="$PERF_INSERT_BATCH_SIZE" \
        PERF_HINT_INCLUDE_TYPES="$PERF_HINT_INCLUDE_TYPES" \
        PERF_HINT_EXCLUDE_TYPES="$PERF_HINT_EXCLUDE_TYPES" \
        PERF_HINT_FAMILY_QUOTA="$PERF_HINT_FAMILY_QUOTA" \
        PERF_HINT_FAMILY_WEIGHT_MODE="$PERF_HINT_FAMILY_WEIGHT_MODE" \
        PERF_HINT_MAX_CANDIDATES_PER_FAMILY="$PERF_HINT_MAX_CANDIDATES_PER_FAMILY" \
        PERF_TAXONOMY_ENABLE="$PERF_TAXONOMY_ENABLE" \
        PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS="$PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS" \
        PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS="$PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS" \
        PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND="$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND" \
        PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND="$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND" \
        PERF_REPORT_HARMFUL_RUNTIME_RATIO="$PERF_REPORT_HARMFUL_RUNTIME_RATIO" \
        PERF_REPORT_COST_ANOMALY_RATIO="$PERF_REPORT_COST_ANOMALY_RATIO" \
        PERF_TAXONOMY_REPORT_CELLS="$PERF_TAXONOMY_REPORT_CELLS" \
        PERF_TAXONOMY_COST_RATIO_LOWER_BOUND="$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND" \
        PERF_TAXONOMY_COST_RATIO_UPPER_BOUND="$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND" \
        "$REPO_ROOT/scripts/run-perfhint-threshold-campaign.sh" "$@"
}

run_qpg_campaign() {
    ensure_dirs
    prepare_wetune_static
    verify_cockroach_target
    env \
        SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT" \
        SQLANCER_LOG_DIR="$SQLANCER_LOG_DIR" \
        LOG_DIR="$CAMPAIGN_LOG_DIR" \
        MASTER_LOG="$CAMPAIGN_LOG_DIR/qpg-master.log" \
        LAUNCH_LOG="$CAMPAIGN_LOG_DIR/qpg-launch.log" \
        PID_FILE="$QPG_PID_FILE" \
        DURATION_SECONDS="$DURATION_SECONDS" \
        MAX_CAMPAIGN_DURATION_SECONDS="$MAX_CAMPAIGN_DURATION_SECONDS" \
        MAX_SEED_SECONDS="$MAX_SEED_SECONDS" \
        SUMMARY_INTERVAL_SECONDS="$SUMMARY_INTERVAL_SECONDS" \
        COST_THRESHOLD="$COST_THRESHOLD" \
        RUNTIME_THRESHOLD="$RUNTIME_THRESHOLD" \
        REPEAT_COUNT="$REPEAT_COUNT" \
        QUERY_TIMEOUT_SECONDS="$QUERY_TIMEOUT_SECONDS" \
        STATEMENT_KIND_RETRIES="$STATEMENT_KIND_RETRIES" \
        PER_RUN_TIMEOUT="$TIMEOUT_SECONDS" \
        NUM_QUERIES="$NUM_QUERIES" \
        WORKERS="$WORKERS" \
        TRIES="$TRIES" \
        HOST="$HOST" \
        PORT="$PORT" \
        USERNAME="$USERNAME" \
        PASSWORD="$PASSWORD" \
        DB_PREFIX_BASE="$DB_PREFIX_BASE" \
        DBMS="cockroachdb" \
        USE_EXPLAIN_ANALYZE="1" \
        START_SEED="$START_SEED" \
        DATA_GENERATOR_MODE="$DATA_GENERATOR_MODE" \
        DATA_ABLATION_PROFILE="$DATA_ABLATION_PROFILE" \
        MIN_TABLES="$MIN_TABLES" \
        MAX_TABLES="$MAX_TABLES" \
        MIN_ROWS_PER_TABLE="$MIN_ROWS_PER_TABLE" \
        MAX_ROWS_PER_TABLE="$MAX_ROWS_PER_TABLE" \
        PERF_INITIAL_SCALE="$PERF_INITIAL_SCALE" \
        MIN_COLUMNS="$MIN_COLUMNS" \
        MAX_COLUMNS="$MAX_COLUMNS" \
        MAX_INDEXES_PER_TABLE="$MAX_INDEXES_PER_TABLE" \
        SINGLE_COLUMN_MODEL_PROBABILITY="$SINGLE_COLUMN_MODEL_PROBABILITY" \
        MULTI_COLUMN_MODEL_PROBABILITY="$MULTI_COLUMN_MODEL_PROBABILITY" \
        INTER_TABLE_MODEL_PROBABILITY="$INTER_TABLE_MODEL_PROBABILITY" \
        FEEDBACK_STATE_PATH="$FEEDBACK_STATE_PATH" \
        PERF_CONTINUE_AFTER_FORMAL_WITNESS="$PERF_CONTINUE_AFTER_FORMAL_WITNESS" \
        PERF_ASYNC_REPORT_WRITES="$PERF_ASYNC_REPORT_WRITES" \
        PERF_REPORT_QUEUE_CAPACITY="$PERF_REPORT_QUEUE_CAPACITY" \
        PERF_REPORT_QUEUE_OFFER_MS="$PERF_REPORT_QUEUE_OFFER_MS" \
        PERF_INSERT_BATCH_SIZE="$PERF_INSERT_BATCH_SIZE" \
        PERF_HINT_INCLUDE_TYPES="$PERF_HINT_INCLUDE_TYPES" \
        PERF_HINT_EXCLUDE_TYPES="$PERF_HINT_EXCLUDE_TYPES" \
        PERF_HINT_FAMILY_QUOTA="$PERF_HINT_FAMILY_QUOTA" \
        PERF_HINT_FAMILY_WEIGHT_MODE="$PERF_HINT_FAMILY_WEIGHT_MODE" \
        PERF_HINT_MAX_CANDIDATES_PER_FAMILY="$PERF_HINT_MAX_CANDIDATES_PER_FAMILY" \
        PERF_TAXONOMY_ENABLE="$PERF_TAXONOMY_ENABLE" \
        PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS="$PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS" \
        PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS="$PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS" \
        PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND="$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND" \
        PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND="$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND" \
        PERF_REPORT_HARMFUL_RUNTIME_RATIO="$PERF_REPORT_HARMFUL_RUNTIME_RATIO" \
        PERF_REPORT_COST_ANOMALY_RATIO="$PERF_REPORT_COST_ANOMALY_RATIO" \
        PERF_TAXONOMY_REPORT_CELLS="$PERF_TAXONOMY_REPORT_CELLS" \
        PERF_TAXONOMY_COST_RATIO_LOWER_BOUND="$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND" \
        PERF_TAXONOMY_COST_RATIO_UPPER_BOUND="$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND" \
        QPG_ENABLE="1" \
        QPG_LOG_QUERY_PLAN="$QPG_LOG_QUERY_PLAN" \
        QPG_MAX_INTERVAL="$QPG_MAX_INTERVAL" \
        QPG_REWARD_WEIGHT="$QPG_REWARD_WEIGHT" \
        QPG_SELECTION_PROBABILITY="$QPG_SELECTION_PROBABILITY" \
        QPG_STALE_WINDOW_SIZE="$QPG_STALE_WINDOW_SIZE" \
        QPG_STALE_WINDOW_MAX_NEW_PLANS="$QPG_STALE_WINDOW_MAX_NEW_PLANS" \
        QPG_STALE_WINDOW_MAX_POOL_GROWTH="$QPG_STALE_WINDOW_MAX_POOL_GROWTH" \
        QPG_STALE_REWARD_LOOKBACK="$QPG_STALE_REWARD_LOOKBACK" \
        QPG_STALE_MAX_RECENT_REWARD="$QPG_STALE_MAX_RECENT_REWARD" \
        "$REPO_ROOT/scripts/run-perfhint-threshold-campaign.sh" "$@"
}

main() {
    if [ "$#" -lt 1 ]; then
        usage
        exit 1
    fi
    command="$1"
    shift || true
    case "$command" in
        build-sqlancer)
            build_sqlancer
            ;;
        smoke)
            run_smoke "$@"
            ;;
        campaign)
            run_campaign "$@"
            ;;
        qpg)
            run_qpg "$@"
            ;;
        qpg-campaign)
            run_qpg_campaign "$@"
            ;;
        *)
            usage
            exit 1
            ;;
    esac
}

main "$@"
