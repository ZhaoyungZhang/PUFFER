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

CREDENTIALS_FILE="${OB45_CREDENTIALS_FILE:-}"
REPORT_ROOT="${REPORT_ROOT:-$REPO_ROOT/evaluation/oceanbase_4_5_0}"
RUN_LOG_DIR="${RUN_LOG_DIR:-$REPORT_ROOT/logs}"
CAMPAIGN_LOG_DIR="${CAMPAIGN_LOG_DIR:-$REPORT_ROOT/campaign_logs}"
SQLANCER_LOG_DIR="${SQLANCER_LOG_DIR:-$REPORT_ROOT/sqlancer_logs}"
HOST="${HOST:-}"
PORT="${PORT:-}"
USERNAME="${USERNAME:-}"
PASSWORD="${PASSWORD:-}"
EXPECTED_VERSION_PREFIX="${EXPECTED_VERSION_PREFIX:-${OB45_EXPECTED_VERSION_PREFIX:-5.7.25-OceanBase_CE-v4.5.0}}"
MYSQL_CLIENT_BIN="${MYSQL_CLIENT_BIN:-}"
JAR_PATH="${JAR_PATH:-}"
SEED="${SEED:-450000}"
START_SEED="${START_SEED:-0}"
WORKERS="${WORKERS:-1}"
TRIES="${TRIES:-$WORKERS}"
NUM_QUERIES="${NUM_QUERIES:-64}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-600}"
QUERY_TIMEOUT_SECONDS="${QUERY_TIMEOUT_SECONDS:-5}"
MIN_TABLES="${MIN_TABLES:-2}"
MAX_TABLES="${MAX_TABLES:-4}"
MIN_ROWS_PER_TABLE="${MIN_ROWS_PER_TABLE:-${ROWS_PER_TABLE:-100}}"
MAX_ROWS_PER_TABLE="${MAX_ROWS_PER_TABLE:-${ROWS_PER_TABLE:-1000}}"
PERF_INITIAL_SCALE="${PERF_INITIAL_SCALE:-1}"
MIN_COLUMNS="${MIN_COLUMNS:-3}"
MAX_COLUMNS="${MAX_COLUMNS:-8}"
MAX_INDEXES_PER_TABLE="${MAX_INDEXES_PER_TABLE:-2}"
SINGLE_COLUMN_MODEL_PROBABILITY="${SINGLE_COLUMN_MODEL_PROBABILITY:-0.8}"
MULTI_COLUMN_MODEL_PROBABILITY="${MULTI_COLUMN_MODEL_PROBABILITY:-0.6}"
INTER_TABLE_MODEL_PROBABILITY="${INTER_TABLE_MODEL_PROBABILITY:-0.8}"
REPEAT_COUNT="${REPEAT_COUNT:-5}"
DATABASE_PREFIX="${DATABASE_PREFIX:-ob45ph}"
DATA_GENERATOR_MODE="${DATA_GENERATOR_MODE:-model-v1}"
DATA_ABLATION_PROFILE="${DATA_ABLATION_PROFILE:-none}"
COST_THRESHOLD="${COST_THRESHOLD:-10.0}"
RUNTIME_THRESHOLD="${RUNTIME_THRESHOLD:-10.0}"
STATEMENT_KIND_RETRIES="${STATEMENT_KIND_RETRIES:-0}"
DURATION_SECONDS="${DURATION_SECONDS:-28800}"
MAX_SEED_SECONDS="${MAX_SEED_SECONDS:-0}"
PID_FILE="${PID_FILE:-$CAMPAIGN_LOG_DIR/perfhint_oceanbase.pid}"
QPG_PID_FILE="${QPG_PID_FILE:-$CAMPAIGN_LOG_DIR/qpg_oceanbase.pid}"
FEEDBACK_STATE_PATH="${FEEDBACK_STATE_PATH:-$REPORT_ROOT/feedback_state}"
PERF_HINT_INCLUDE_TYPES="${PERF_HINT_INCLUDE_TYPES:-}"
PERF_HINT_EXCLUDE_TYPES="${PERF_HINT_EXCLUDE_TYPES:-}"
PERF_HINT_FAMILY_QUOTA="${PERF_HINT_FAMILY_QUOTA:-8}"
PERF_HINT_FAMILY_WEIGHT_MODE="${PERF_HINT_FAMILY_WEIGHT_MODE:-uniform}"
PERF_HINT_MAX_CANDIDATES_PER_FAMILY="${PERF_HINT_MAX_CANDIDATES_PER_FAMILY:-8}"
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
PERF_TAXONOMY_COST_RATIO_LOWER_BOUND="${PERF_TAXONOMY_COST_RATIO_LOWER_BOUND:-0.9}"
PERF_TAXONOMY_COST_RATIO_UPPER_BOUND="${PERF_TAXONOMY_COST_RATIO_UPPER_BOUND:-1.1}"
QPG_LOG_QUERY_PLAN="${QPG_LOG_QUERY_PLAN:-1}"
QPG_MAX_INTERVAL="${QPG_MAX_INTERVAL:-1000}"
QPG_REWARD_WEIGHT="${QPG_REWARD_WEIGHT:-0.25}"
QPG_SELECTION_PROBABILITY="${QPG_SELECTION_PROBABILITY:-0.7}"
QPG_STALE_WINDOW_SIZE="${QPG_STALE_WINDOW_SIZE:-0}"
QPG_STALE_WINDOW_MAX_NEW_PLANS="${QPG_STALE_WINDOW_MAX_NEW_PLANS:--1}"
QPG_STALE_WINDOW_MAX_POOL_GROWTH="${QPG_STALE_WINDOW_MAX_POOL_GROWTH:--1}"
QPG_STALE_REWARD_LOOKBACK="${QPG_STALE_REWARD_LOOKBACK:-0}"
QPG_STALE_MAX_RECENT_REWARD="${QPG_STALE_MAX_RECENT_REWARD:--1}"
SUMMARY_INTERVAL_SECONDS="${SUMMARY_INTERVAL_SECONDS:-300}"
SUMMARY_NICE_LEVEL="${SUMMARY_NICE_LEVEL:-19}"
SUMMARY_TELEMETRY_PER_SECOND="${SUMMARY_TELEMETRY_PER_SECOND:-0}"
PERF_HINT_TELEMETRY_FLUSH_SECONDS="${PERF_HINT_TELEMETRY_FLUSH_SECONDS:-60}"
PERF_CONTINUE_AFTER_FORMAL_WITNESS="${PERF_CONTINUE_AFTER_FORMAL_WITNESS:-0}"
PERF_ASYNC_REPORT_WRITES="${PERF_ASYNC_REPORT_WRITES:-0}"
PERF_REPORT_QUEUE_CAPACITY="${PERF_REPORT_QUEUE_CAPACITY:-256}"
PERF_REPORT_QUEUE_OFFER_MS="${PERF_REPORT_QUEUE_OFFER_MS:-50}"
PERF_INSERT_BATCH_SIZE="${PERF_INSERT_BATCH_SIZE:-25}"

normalize_bool_env() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on) printf '%s\n' true ;;
        0|false|no|off|'') printf '%s\n' false ;;
        *) echo "expected a boolean value, got: $1" >&2; exit 1 ;;
    esac
}

usage() {
    cat <<'EOF'
Usage: scripts/run_oceanbase_4_5_sqlancer.sh [--config <properties-path>] <command> [OceanBase options...]

Commands:
  build  Build SQLancer.
  smoke  Run the OceanBase PERFORMANCE_HINT oracle against CE 4.5.
  qpg    Run QPG-guided OceanBase PERFORMANCE_HINT once.
  campaign      Run a rolling-seed OceanBase PERFORMANCE_HINT campaign.
  qpg-campaign  Run a rolling-seed QPG-guided OceanBase PERFORMANCE_HINT campaign.

Provide credentials via OB45_CREDENTIALS_FILE or set HOST, PORT, USERNAME, and PASSWORD explicitly.

The PERFORMANCE_HINT smoke defaults match the MySQL/MariaDB campaign shape: 2-4 tables,
50-220 rows per table, 3-8 columns, and at most two indexes per table. Set the corresponding
MIN_*/MAX_* environment variables to override them; ROWS_PER_TABLE remains a compatibility shortcut.
EOF
}

load_credentials() {
    if [[ -r "$CREDENTIALS_FILE" ]]; then
        # The deployment helper writes this file with mode 0600. It contains only local test-tenant credentials.
        # shellcheck disable=SC1090
        source "$CREDENTIALS_FILE"
    fi
    HOST="${HOST:-${OB45_SQLANCER_HOST:-127.0.0.1}}"
    PORT="${PORT:-${OB45_SQLANCER_PORT:-12881}}"
    USERNAME="${USERNAME:-${OB45_SQLANCER_USER:-}}"
    PASSWORD="${PASSWORD:-${OB45_SQLANCER_PASSWORD:-}}"
    if [[ -z "$USERNAME" || -z "$PASSWORD" ]]; then
        echo "OceanBase test credentials are unavailable; set OB45_CREDENTIALS_FILE or HOST/PORT/USERNAME/PASSWORD." >&2
        exit 1
    fi
}

resolve_jar() {
    if [[ -n "$JAR_PATH" ]]; then
        printf '%s\n' "$JAR_PATH"
        return
    fi
    find "$REPO_ROOT/target" -maxdepth 1 -type f -name 'sqlancer-*.jar' ! -name 'original-*' | sort | head -n 1
}

resolve_mysql_client() {
    if [[ -n "$MYSQL_CLIENT_BIN" ]]; then
        printf '%s\n' "$MYSQL_CLIENT_BIN"
        return
    fi
    command -v mysql || true
}

verify_target() {
    local mysql_bin
    local version
    mysql_bin="$(resolve_mysql_client)"
    if [[ -z "$mysql_bin" ]]; then
        echo "mysql client not found; set MYSQL_CLIENT_BIN to validate the OceanBase target." >&2
        exit 1
    fi
    version="$(MYSQL_PWD="$PASSWORD" "$mysql_bin" --protocol=TCP --default-character-set=utf8mb4 -h "$HOST" -P "$PORT" -u "$USERNAME" -Nse 'SELECT VERSION()' 2>/dev/null || true)"
    if [[ "$version" != "$EXPECTED_VERSION_PREFIX"* ]]; then
        echo "expected OceanBase $EXPECTED_VERSION_PREFIX at $HOST:$PORT, got '${version:-unreachable}'." >&2
        exit 1
    fi
    printf 'verified_target_version=%s\n' "$version"
}

ensure_dirs() {
    mkdir -p "$REPORT_ROOT" "$RUN_LOG_DIR" "$CAMPAIGN_LOG_DIR" "$SQLANCER_LOG_DIR"
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
    export SUMMARY_INTERVAL_SECONDS SUMMARY_NICE_LEVEL SUMMARY_TELEMETRY_PER_SECOND
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
    command+=(
        --perf-cost-ratio-threshold "$COST_THRESHOLD" \
        --perf-runtime-ratio-threshold "$RUNTIME_THRESHOLD" \
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
    )
    if [ -n "$PERF_WETUNE_STATIC_WORKER" ]; then
        command+=(--perf-wetune-static-worker "$PERF_WETUNE_STATIC_WORKER")
    fi
    if [ -n "$PERF_HINT_INCLUDE_TYPES" ]; then
        command+=(--perf-hint-include-types "$PERF_HINT_INCLUDE_TYPES")
    fi
    if [ -n "$PERF_HINT_EXCLUDE_TYPES" ]; then
        command+=(--perf-hint-exclude-types "$PERF_HINT_EXCLUDE_TYPES")
    fi
}

run_performance_hint() {
    local jar
    local -a command
    jar="$(resolve_jar)"
    if [[ -z "$jar" ]]; then
        echo "SQLancer jar not found; run '$0 build' first." >&2
        exit 1
    fi
    load_credentials
    verify_target
    ensure_dirs
    prepare_wetune_static
    export SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT"
    export SQLANCER_LOG_DIR
    command=(
        java -jar "$jar"
        --random-seed "$SEED"
        --timeout-seconds "$TIMEOUT_SECONDS"
        --num-threads "$WORKERS"
        --num-tries "$TRIES"
        --max-generated-databases 1
        --num-queries "$NUM_QUERIES"
        --num-statement-kind-retries "$STATEMENT_KIND_RETRIES"
        --host "$HOST"
        --port "$PORT"
        --username "$USERNAME"
        --password "$PASSWORD"
        --database-prefix "${DATABASE_PREFIX}${SEED}"
        --log-each-select true
        --log-execution-time false
        oceanbase
        --oracle PERFORMANCE_HINT
        --perf-data-generator-mode "$DATA_GENERATOR_MODE"
        --perf-min-tables "$MIN_TABLES"
        --perf-max-tables "$MAX_TABLES"
        --perf-min-rows-per-table "$MIN_ROWS_PER_TABLE"
        --perf-max-rows-per-table "$MAX_ROWS_PER_TABLE"
        --perf-initial-scale "$PERF_INITIAL_SCALE"
        --perf-min-columns "$MIN_COLUMNS"
        --perf-max-columns "$MAX_COLUMNS"
        --perf-max-indexes-per-table "$MAX_INDEXES_PER_TABLE"
        --perf-model-single-column-probability "$SINGLE_COLUMN_MODEL_PROBABILITY"
        --perf-model-multi-column-probability "$MULTI_COLUMN_MODEL_PROBABILITY"
        --perf-model-inter-table-probability "$INTER_TABLE_MODEL_PROBABILITY"
        --perf-repeat "$REPEAT_COUNT"
        --perf-timeout-seconds "$QUERY_TIMEOUT_SECONDS"
        --perf-enable-runtime-validation
        --perf-enable-feedback-dedup
        --perf-feedback-state-path "$FEEDBACK_STATE_PATH"
        --perf-continue-after-formal-witness "$(normalize_bool_env "$PERF_CONTINUE_AFTER_FORMAL_WITNESS")"
        --perf-async-report-writes "$(normalize_bool_env "$PERF_ASYNC_REPORT_WRITES")"
        --perf-report-queue-capacity "$PERF_REPORT_QUEUE_CAPACITY"
        --perf-report-queue-offer-ms "$PERF_REPORT_QUEUE_OFFER_MS"
        --perf-insert-batch-size "$PERF_INSERT_BATCH_SIZE"
        --perf-hint-family-quota "$PERF_HINT_FAMILY_QUOTA"
        --perf-hint-family-weight-mode "$PERF_HINT_FAMILY_WEIGHT_MODE"
        --perf-hint-max-candidates-per-family "$PERF_HINT_MAX_CANDIDATES_PER_FAMILY"
    )
    append_perfhint_args
    command+=("$@")
    "${command[@]}"
}

run_qpg() {
    local jar
    local -a command
    jar="$(resolve_jar)"
    if [[ -z "$jar" ]]; then
        echo "SQLancer jar not found; run '$0 build' first." >&2
        exit 1
    fi
    load_credentials
    verify_target
    ensure_dirs
    prepare_wetune_static
    export SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT"
    export SQLANCER_LOG_DIR
    append_qpg_args
    command=(
        java -jar "$jar"
        --random-seed "$SEED"
        --timeout-seconds "$TIMEOUT_SECONDS"
        --num-threads "$WORKERS"
        --num-tries "$TRIES"
        --max-generated-databases 1
        --num-queries "$NUM_QUERIES"
        --num-statement-kind-retries "$STATEMENT_KIND_RETRIES"
        --host "$HOST"
        --port "$PORT"
        --username "$USERNAME"
        --password "$PASSWORD"
        --database-prefix "${DATABASE_PREFIX}qpg${SEED}"
        --log-each-select true
        --log-execution-time false
        "${qpg_args[@]}"
        oceanbase
        --oracle PERFORMANCE_HINT
        --perf-data-generator-mode "$DATA_GENERATOR_MODE"
        --perf-min-tables "$MIN_TABLES"
        --perf-max-tables "$MAX_TABLES"
        --perf-min-rows-per-table "$MIN_ROWS_PER_TABLE"
        --perf-max-rows-per-table "$MAX_ROWS_PER_TABLE"
        --perf-initial-scale "$PERF_INITIAL_SCALE"
        --perf-min-columns "$MIN_COLUMNS"
        --perf-max-columns "$MAX_COLUMNS"
        --perf-max-indexes-per-table "$MAX_INDEXES_PER_TABLE"
        --perf-model-single-column-probability "$SINGLE_COLUMN_MODEL_PROBABILITY"
        --perf-model-multi-column-probability "$MULTI_COLUMN_MODEL_PROBABILITY"
        --perf-model-inter-table-probability "$INTER_TABLE_MODEL_PROBABILITY"
        --perf-repeat "$REPEAT_COUNT"
        --perf-timeout-seconds "$QUERY_TIMEOUT_SECONDS"
        --perf-enable-runtime-validation
        --perf-enable-feedback-dedup
        --perf-feedback-state-path "$FEEDBACK_STATE_PATH"
        --perf-continue-after-formal-witness "$(normalize_bool_env "$PERF_CONTINUE_AFTER_FORMAL_WITNESS")"
        --perf-async-report-writes "$(normalize_bool_env "$PERF_ASYNC_REPORT_WRITES")"
        --perf-report-queue-capacity "$PERF_REPORT_QUEUE_CAPACITY"
        --perf-report-queue-offer-ms "$PERF_REPORT_QUEUE_OFFER_MS"
        --perf-insert-batch-size "$PERF_INSERT_BATCH_SIZE"
        --perf-hint-family-quota "$PERF_HINT_FAMILY_QUOTA"
        --perf-hint-family-weight-mode "$PERF_HINT_FAMILY_WEIGHT_MODE"
        --perf-hint-max-candidates-per-family "$PERF_HINT_MAX_CANDIDATES_PER_FAMILY"
    )
    append_perfhint_args
    command+=("$@")
    "${command[@]}"
}

run_campaign_with_mode() {
    local qpg_enabled="$1"
    local master_log
    local launch_log
    local pid_file
    shift
    load_credentials
    verify_target
    ensure_dirs
    prepare_wetune_static
    if [ "$qpg_enabled" = "1" ]; then
        master_log="$CAMPAIGN_LOG_DIR/qpg-master.log"
        launch_log="$CAMPAIGN_LOG_DIR/qpg-launch.log"
        pid_file="$QPG_PID_FILE"
    else
        master_log="$CAMPAIGN_LOG_DIR/perfhint-master.log"
        launch_log="$CAMPAIGN_LOG_DIR/perfhint-launch.log"
        pid_file="$PID_FILE"
    fi
    env \
        SQLANCER_BUG_REPORT_DIR="$REPORT_ROOT" \
        SQLANCER_LOG_DIR="$SQLANCER_LOG_DIR" \
        LOG_DIR="$CAMPAIGN_LOG_DIR" \
        MASTER_LOG="$master_log" \
        LAUNCH_LOG="$launch_log" \
        PID_FILE="$pid_file" \
        DURATION_SECONDS="$DURATION_SECONDS" \
        MAX_CAMPAIGN_DURATION_SECONDS="${MAX_CAMPAIGN_DURATION_SECONDS:-604800}" \
        MAX_SEED_SECONDS="$MAX_SEED_SECONDS" \
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
        DB_PREFIX_BASE="$DATABASE_PREFIX" \
        DBMS=oceanbase \
        USE_EXPLAIN_ANALYZE=0 \
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
        QPG_ENABLE="$qpg_enabled" \
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

run_campaign() {
    run_campaign_with_mode 0 "$@"
}

run_qpg_campaign() {
    run_campaign_with_mode 1 "$@"
}

case "${1:-}" in
    build)
        mvn -DskipTests package
        ;;
    smoke)
        shift
        run_performance_hint "$@"
        ;;
    qpg)
        shift
        run_qpg "$@"
        ;;
    campaign)
        shift
        run_campaign "$@"
        ;;
    qpg-campaign)
        shift
        run_qpg_campaign "$@"
        ;;
    *)
        usage
        exit 1
        ;;
esac
