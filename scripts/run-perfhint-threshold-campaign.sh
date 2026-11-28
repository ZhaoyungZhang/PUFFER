#!/usr/bin/env bash

set -u

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

source "$REPO_ROOT/scripts/perfhint_properties.sh"
CONFIG_PATH=""
perfhint_take_config_argument CONFIG_PATH "$@"
set -- "${PERFHINT_REMAINING_ARGS[@]}"
if [ -n "$CONFIG_PATH" ]; then
    perfhint_load_properties "$CONFIG_PATH"
fi

DURATION_SECONDS="${DURATION_SECONDS:-28800}"
MAX_CAMPAIGN_DURATION_SECONDS="${MAX_CAMPAIGN_DURATION_SECONDS:-604800}"
MAX_SEED_SECONDS="${MAX_SEED_SECONDS:-0}"
LEGACY_THRESHOLD="${THRESHOLD:-}"
COST_THRESHOLD="${COST_THRESHOLD:-${LEGACY_THRESHOLD:-10.0}}"
RUNTIME_THRESHOLD="${RUNTIME_THRESHOLD:-${LEGACY_THRESHOLD:-10.0}}"
REPEAT_COUNT="${REPEAT_COUNT:-5}"
QUERY_TIMEOUT_SECONDS="${QUERY_TIMEOUT_SECONDS:-5}"
STATEMENT_KIND_RETRIES="${STATEMENT_KIND_RETRIES:-0}"
PER_RUN_TIMEOUT="${PER_RUN_TIMEOUT:-600}"
NUM_QUERIES="${NUM_QUERIES:-64}"
WORKERS="${WORKERS:-8}"
TRIES="${TRIES:-$WORKERS}"
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-34971}"
USERNAME="${USERNAME:-sqlancer}"
PASSWORD="${PASSWORD-sqlancer}"
DB_PREFIX_BASE="${DB_PREFIX_BASE:-perfhint8h}"
DBMS="${DBMS:-mysql}"
USE_EXPLAIN_ANALYZE="${USE_EXPLAIN_ANALYZE:-0}"
START_SEED="${START_SEED:-$(python3 -c 'import secrets; print(secrets.randbelow(2147483647))')}"
SCHEMA_MANIFEST_PATH="${SCHEMA_MANIFEST_PATH:-}"
LOG_DIR="${LOG_DIR:-${CAMPAIGN_LOG_DIR:-$REPO_ROOT/logs/perfhint_campaign}}"
MASTER_LOG="${MASTER_LOG:-$LOG_DIR/perfhint8h-master.log}"
LAUNCH_LOG="${LAUNCH_LOG:-$LOG_DIR/perfhint8h-launch.log}"
PID_FILE="${PID_FILE:-$LOG_DIR/perfhint8h.pid}"
BUG_REPORT_ROOT="${SQLANCER_BUG_REPORT_DIR:-${REPORT_ROOT:-$REPO_ROOT/bug_reports}}"
SQLANCER_LOG_ROOT="${SQLANCER_LOG_DIR:-$REPO_ROOT/logs}"
DATA_GENERATOR_MODE="${DATA_GENERATOR_MODE:-model-v1}"
DATA_ABLATION_PROFILE="${DATA_ABLATION_PROFILE:-none}"
MIN_TABLES="${MIN_TABLES:-1}"
MAX_TABLES="${MAX_TABLES:-5}"
MIN_ROWS_PER_TABLE="${MIN_ROWS_PER_TABLE:-100}"
MAX_ROWS_PER_TABLE="${MAX_ROWS_PER_TABLE:-1000}"
PERF_INITIAL_SCALE="${PERF_INITIAL_SCALE:-1}"
PERF_ALLOW_BKA_HINT="${PERF_ALLOW_BKA_HINT:-0}"
MIN_COLUMNS="${MIN_COLUMNS:-3}"
MAX_COLUMNS="${MAX_COLUMNS:-6}"
MAX_INDEXES_PER_TABLE="${MAX_INDEXES_PER_TABLE:-3}"
ENABLE_HISTOGRAM_REFRESH="${ENABLE_HISTOGRAM_REFRESH:-1}"
MAX_HISTOGRAM_COLUMNS_PER_TABLE="${MAX_HISTOGRAM_COLUMNS_PER_TABLE:-2}"
SINGLE_COLUMN_MODEL_PROBABILITY="${SINGLE_COLUMN_MODEL_PROBABILITY:-0.8}"
MULTI_COLUMN_MODEL_PROBABILITY="${MULTI_COLUMN_MODEL_PROBABILITY:-0.6}"
INTER_TABLE_MODEL_PROBABILITY="${INTER_TABLE_MODEL_PROBABILITY:-0.8}"
FEEDBACK_STATE_PATH="${FEEDBACK_STATE_PATH:-$BUG_REPORT_ROOT/feedback_state}"
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
QPG_ENABLE="${QPG_ENABLE:-0}"
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
SUMMARY_TXT="${SUMMARY_TXT:-$LOG_DIR/perfhint-summary.txt}"
SUMMARY_JSON="${SUMMARY_JSON:-$LOG_DIR/perfhint-summary.json}"
DETACH=0

case "$DATA_ABLATION_PROFILE" in
    none|random|column|column-intra|column-inter|full) ;;
    *) echo "DATA_ABLATION_PROFILE must be none, random, column, column-intra, column-inter, or full" >&2; exit 1 ;;
esac
if [ "$DATA_ABLATION_PROFILE" != "none" ]; then
    if { [ "$DBMS" != "mysql" ] && [ "$DBMS" != "mariadb" ] && [ "$DBMS" != "tidb" ] && [ "$DBMS" != "cockroachdb" ] && [ "$DBMS" != "oceanbase" ]; } || [ "$DATA_GENERATOR_MODE" != "model-v1" ]; then
        echo "DATA_ABLATION_PROFILE requires DBMS=mysql, mariadb, tidb, cockroachdb or oceanbase and DATA_GENERATOR_MODE=model-v1" >&2
        exit 1
    fi
fi

case "$DURATION_SECONDS" in
    ''|*[!0-9]*)
        echo "DURATION_SECONDS and MAX_CAMPAIGN_DURATION_SECONDS must be positive integers" >&2
        exit 1
        ;;
esac
case "$MAX_CAMPAIGN_DURATION_SECONDS" in
    ''|*[!0-9]*)
        echo "DURATION_SECONDS and MAX_CAMPAIGN_DURATION_SECONDS must be positive integers" >&2
        exit 1
        ;;
esac
if [ "$DURATION_SECONDS" -le 0 ] || [ "$MAX_CAMPAIGN_DURATION_SECONDS" -le 0 ]; then
    echo "DURATION_SECONDS and MAX_CAMPAIGN_DURATION_SECONDS must be positive integers" >&2
    exit 1
fi
if [ "$DURATION_SECONDS" -gt "$MAX_CAMPAIGN_DURATION_SECONDS" ]; then
    echo "refusing campaign duration ${DURATION_SECONDS}s: maximum is ${MAX_CAMPAIGN_DURATION_SECONDS}s" >&2
    exit 1
fi
case "$WORKERS:$TRIES" in
    *[!0-9:]*|:*|*:)
        echo "WORKERS and TRIES must be positive integers" >&2
        exit 1
        ;;
esac
if [ "$WORKERS" -le 0 ] || [ "$TRIES" -le 0 ]; then
    echo "WORKERS and TRIES must be positive integers" >&2
    exit 1
fi
if [ "$(printf '%s' "$PERF_QUERY_REWRITE_PROVIDER" | tr '[:upper:]' '[:lower:]')" = "wetune-static" ] \
        && [ "$DBMS" != "mysql" ] && [ "$DBMS" != "mariadb" ] && [ "$DBMS" != "tidb" ] \
        && [ "$DBMS" != "oceanbase" ] && [ "$DBMS" != "cockroachdb" ]; then
    echo "wetune-static is supported only by DBMS=mysql, DBMS=mariadb, DBMS=tidb, DBMS=oceanbase, or DBMS=cockroachdb" >&2
    exit 1
fi
perfhint_prepare_wetune_static_worker "$REPO_ROOT"
export PERF_HINT_TELEMETRY_FLUSH_SECONDS

while [ "$#" -gt 0 ]; do
    case "$1" in
        --detach)
            DETACH=1
            ;;
        --foreground)
            DETACH=0
            ;;
        *)
            echo "unknown argument: $1" >&2
            exit 1
            ;;
    esac
    shift
done

mkdir -p "$LOG_DIR"

if [ "$DETACH" -eq 1 ] && [ "${PERFHINT_NOHUP_LAUNCHED:-0}" != "1" ]; then
    nohup env PERFHINT_NOHUP_LAUNCHED=1 \
        DURATION_SECONDS="$DURATION_SECONDS" \
        MAX_CAMPAIGN_DURATION_SECONDS="$MAX_CAMPAIGN_DURATION_SECONDS" \
        MAX_SEED_SECONDS="$MAX_SEED_SECONDS" \
        THRESHOLD="$LEGACY_THRESHOLD" \
        COST_THRESHOLD="$COST_THRESHOLD" \
        RUNTIME_THRESHOLD="$RUNTIME_THRESHOLD" \
        REPEAT_COUNT="$REPEAT_COUNT" \
        QUERY_TIMEOUT_SECONDS="$QUERY_TIMEOUT_SECONDS" \
        PER_RUN_TIMEOUT="$PER_RUN_TIMEOUT" \
        NUM_QUERIES="$NUM_QUERIES" \
        WORKERS="$WORKERS" \
        TRIES="$TRIES" \
        HOST="$HOST" \
        PORT="$PORT" \
        USERNAME="$USERNAME" \
        PASSWORD="$PASSWORD" \
        DB_PREFIX_BASE="$DB_PREFIX_BASE" \
        DBMS="$DBMS" \
        USE_EXPLAIN_ANALYZE="$USE_EXPLAIN_ANALYZE" \
        START_SEED="$START_SEED" \
        SCHEMA_MANIFEST_PATH="$SCHEMA_MANIFEST_PATH" \
        STATEMENT_KIND_RETRIES="$STATEMENT_KIND_RETRIES" \
        DATA_GENERATOR_MODE="$DATA_GENERATOR_MODE" \
        DATA_ABLATION_PROFILE="$DATA_ABLATION_PROFILE" \
        MIN_TABLES="$MIN_TABLES" \
        MAX_TABLES="$MAX_TABLES" \
        MIN_ROWS_PER_TABLE="$MIN_ROWS_PER_TABLE" \
        MAX_ROWS_PER_TABLE="$MAX_ROWS_PER_TABLE" \
        PERF_INITIAL_SCALE="$PERF_INITIAL_SCALE" \
        PERF_ALLOW_BKA_HINT="$PERF_ALLOW_BKA_HINT" \
        MIN_COLUMNS="$MIN_COLUMNS" \
        MAX_COLUMNS="$MAX_COLUMNS" \
        MAX_INDEXES_PER_TABLE="$MAX_INDEXES_PER_TABLE" \
        ENABLE_HISTOGRAM_REFRESH="$ENABLE_HISTOGRAM_REFRESH" \
        MAX_HISTOGRAM_COLUMNS_PER_TABLE="$MAX_HISTOGRAM_COLUMNS_PER_TABLE" \
        SINGLE_COLUMN_MODEL_PROBABILITY="$SINGLE_COLUMN_MODEL_PROBABILITY" \
        MULTI_COLUMN_MODEL_PROBABILITY="$MULTI_COLUMN_MODEL_PROBABILITY" \
        INTER_TABLE_MODEL_PROBABILITY="$INTER_TABLE_MODEL_PROBABILITY" \
        FEEDBACK_STATE_PATH="$FEEDBACK_STATE_PATH" \
        PERF_HINT_INCLUDE_TYPES="$PERF_HINT_INCLUDE_TYPES" \
        PERF_HINT_EXCLUDE_TYPES="$PERF_HINT_EXCLUDE_TYPES" \
        PERF_HINT_FAMILY_QUOTA="$PERF_HINT_FAMILY_QUOTA" \
        PERF_HINT_FAMILY_WEIGHT_MODE="$PERF_HINT_FAMILY_WEIGHT_MODE" \
        PERF_HINT_MAX_CANDIDATES_PER_FAMILY="$PERF_HINT_MAX_CANDIDATES_PER_FAMILY" \
        PERF_COUNTERFACTUAL_HINT_PROBABILITY="$PERF_COUNTERFACTUAL_HINT_PROBABILITY" \
        PERF_PREDICATE_BINDING_PROBABILITY="$PERF_PREDICATE_BINDING_PROBABILITY" \
        PERF_QUERY_GENERATOR_MODE="$PERF_QUERY_GENERATOR_MODE" \
        PERF_QUERY_REWRITE_PROVIDER="$PERF_QUERY_REWRITE_PROVIDER" \
        PERF_WETUNE_STATIC_WORKER="$PERF_WETUNE_STATIC_WORKER" \
        PERF_WETUNE_STATIC_TIMEOUT_MS="$PERF_WETUNE_STATIC_TIMEOUT_MS" \
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
        QPG_ENABLE="$QPG_ENABLE" \
        QPG_LOG_QUERY_PLAN="$QPG_LOG_QUERY_PLAN" \
        QPG_MAX_INTERVAL="$QPG_MAX_INTERVAL" \
        QPG_REWARD_WEIGHT="$QPG_REWARD_WEIGHT" \
        QPG_SELECTION_PROBABILITY="$QPG_SELECTION_PROBABILITY" \
        QPG_STALE_WINDOW_SIZE="$QPG_STALE_WINDOW_SIZE" \
        QPG_STALE_WINDOW_MAX_NEW_PLANS="$QPG_STALE_WINDOW_MAX_NEW_PLANS" \
        QPG_STALE_WINDOW_MAX_POOL_GROWTH="$QPG_STALE_WINDOW_MAX_POOL_GROWTH" \
        QPG_STALE_REWARD_LOOKBACK="$QPG_STALE_REWARD_LOOKBACK" \
        QPG_STALE_MAX_RECENT_REWARD="$QPG_STALE_MAX_RECENT_REWARD" \
        SUMMARY_INTERVAL_SECONDS="$SUMMARY_INTERVAL_SECONDS" \
        SUMMARY_NICE_LEVEL="$SUMMARY_NICE_LEVEL" \
        SUMMARY_TELEMETRY_PER_SECOND="$SUMMARY_TELEMETRY_PER_SECOND" \
        PERF_HINT_TELEMETRY_FLUSH_SECONDS="$PERF_HINT_TELEMETRY_FLUSH_SECONDS" \
        PERF_CONTINUE_AFTER_FORMAL_WITNESS="$PERF_CONTINUE_AFTER_FORMAL_WITNESS" \
        PERF_ASYNC_REPORT_WRITES="$PERF_ASYNC_REPORT_WRITES" \
        PERF_REPORT_QUEUE_CAPACITY="$PERF_REPORT_QUEUE_CAPACITY" \
        PERF_REPORT_QUEUE_OFFER_MS="$PERF_REPORT_QUEUE_OFFER_MS" \
        PERF_INSERT_BATCH_SIZE="$PERF_INSERT_BATCH_SIZE" \
        SUMMARY_TXT="$SUMMARY_TXT" \
        SUMMARY_JSON="$SUMMARY_JSON" \
        SQLANCER_BUG_REPORT_DIR="$BUG_REPORT_ROOT" \
        SQLANCER_LOG_DIR="$SQLANCER_LOG_ROOT" \
        LOG_DIR="$LOG_DIR" \
        MASTER_LOG="$MASTER_LOG" \
        LAUNCH_LOG="$LAUNCH_LOG" \
        PID_FILE="$PID_FILE" \
        "${BASH_SOURCE[0]}" --foreground >> "$LAUNCH_LOG" 2>&1 < /dev/null &
    detached_pid="$!"
    echo "$detached_pid" > "$PID_FILE"
    echo "detached_pid=$detached_pid"
    echo "pid_file=$PID_FILE"
    echo "launch_log=$LAUNCH_LOG"
    echo "master_log=$MASTER_LOG"
    exit 0
fi

printf '%s\n' "$$" > "$PID_FILE"
start_ts="$(date +%s)"
end_ts="$((start_ts + DURATION_SECONDS))"
seed="$START_SEED"
current_db_prefix=""
current_run_log=""
current_cur_log=""
current_run_started_at=""
current_run_start_ts=0
current_run_stop_reason=""
heartbeat_pid=""
active_sqlancer_pid=""

log_master() {
    local message="$1"
    printf '%s\n' "$message" | tee -a "$MASTER_LOG"
}

json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

json_number() {
    local value="${1:-0}"
    case "$value" in
        .*) printf '0%s' "$value" ;;
        -.*) printf '%s' "-0${value#-}" ;;
        *) printf '%s' "$value" ;;
    esac
}

extract_oceanbase_feedback_value() {
    local key="$1"
    local feedback_state="$FEEDBACK_STATE_PATH/feedback-state.tsv"
    if [ ! -f "$feedback_state" ]; then
        printf '0'
        return
    fi
    awk -F '\t' -v key="$key" '
        $1 == "S"  { total_seen += $3; unique_skeletons++ }
        $1 == "F"  { unique_families++ }
        $1 == "SO" { unique_signatures++ }
        $1 == "SR" { total_written += $3 }
        $1 == "RO" { total_root_cause_observations += $3; unique_root_causes++ }
        $1 == "RR" { total_root_cause_reports += $3 }
        END {
            total_suppressed = total_seen - total_written
            if (total_suppressed < 0) total_suppressed = 0
            root_cause_suppressions = total_root_cause_observations - total_root_cause_reports
            if (root_cause_suppressions < 0) root_cause_suppressions = 0
            if (key == "totalWitnessesSeen") print total_seen
            else if (key == "totalWitnessesWritten") print total_written
            else if (key == "totalWitnessesSuppressed") print total_suppressed
            else if (key == "totalRootCauseSuppressions") print root_cause_suppressions
            else if (key == "uniqueRootCauseCount") print unique_root_causes
            else if (key == "uniquePlanSignatureCount") print unique_signatures
            else if (key == "uniqueFamilyCount") print unique_families
            else if (key == "uniqueSkeletonCount") print unique_skeletons
            else print 0
        }
    ' "$feedback_state"
}

extract_feedback_value() {
    local key="$1"
    if [ "$DBMS" = "oceanbase" ]; then
        extract_oceanbase_feedback_value "$key"
        return
    fi
    local feedback_summary="$FEEDBACK_STATE_PATH/perfhint_feedback_summary.json"
    if [ ! -f "$feedback_summary" ]; then
        printf '0'
        return
    fi
    grep -m1 "\"$key\"" "$feedback_summary" | sed -E 's/.*: ([0-9]+).*/\1/' || printf '0'
}

collect_qpg_metrics() {
    local qpg_dir="$SQLANCER_LOG_ROOT/$DBMS"
    local summary_path="$LOG_DIR/perfhint-qpg-summary.json"
    python3 - "$qpg_dir" "$summary_path" <<'PY'
import json
import sys
from pathlib import Path

qpg_dir = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
checkpoint_path = summary_path.with_name("perfhint-qpg-aggregate-state.json")


def load_checkpoint():
    try:
        checkpoint = json.loads(checkpoint_path.read_text())
        if checkpoint.get("schemaVersion") != 1:
            raise ValueError("unsupported checkpoint")
        return checkpoint
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return {"schemaVersion": 1, "events": {}, "pools": {}, "counts": {},
                "maxPoolSize": 0, "maxExistingStreak": 0, "maxMutationSequence": 0,
                "lastSelected": {}, "lastReward": {}}


checkpoint = load_checkpoint()
events = checkpoint.get("events", {})
pools = checkpoint.get("pools", {})
counts = checkpoint.get("counts", {})
if not isinstance(events, dict):
    events = {}
if not isinstance(pools, dict):
    pools = {}
if not isinstance(counts, dict):
    counts = {}
max_pool_size = int(checkpoint.get("maxPoolSize", 0))
max_existing_streak = int(checkpoint.get("maxExistingStreak", 0))
max_mutation_sequence = int(checkpoint.get("maxMutationSequence", 0))
last_selected = checkpoint.get("lastSelected", {})
last_reward = checkpoint.get("lastReward", {})

event_files = sorted(qpg_dir.glob("*-qpg-events.ndjson"), key=lambda path: path.name) if qpg_dir.is_dir() else []
pool_files = sorted(qpg_dir.glob("*-qpg-pool.json"), key=lambda path: path.name) if qpg_dir.is_dir() else []

# Process only bytes appended since the prior summary. The per-file streak is
# checkpointed too, so splitting a streak across heartbeat boundaries does not
# lose information.
for path in event_files:
    source_key = str(path.resolve())
    state = events.get(source_key, {})
    try:
        file_size = path.stat().st_size
        offset = int(state.get("offset", 0))
        if offset < 0 or offset > file_size:
            offset = 0
        with path.open("rb") as source:
            source.seek(offset)
            payload = source.read()
    except OSError:
        continue
    newline = payload.rfind(b"\n")
    if newline < 0:
        continue
    complete = payload[:newline + 1]
    current_existing_streak = int(state.get("existingStreak", 0))
    for raw_line in complete.splitlines():
        try:
            event = json.loads(raw_line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        event_type = event.get("type")
        if not isinstance(event_type, str) or not event_type:
            continue
        counts[event_type] = int(counts.get(event_type, 0)) + 1
        pool_size = event.get("poolSize")
        if isinstance(pool_size, int) and not isinstance(pool_size, bool):
            max_pool_size = max(max_pool_size, pool_size)
        mutation_sequence = event.get("mutationSequence")
        if isinstance(mutation_sequence, int) and not isinstance(mutation_sequence, bool):
            max_mutation_sequence = max(max_mutation_sequence, mutation_sequence)
        if event_type == "existing_plan_seen":
            current_existing_streak += 1
            max_existing_streak = max(max_existing_streak, current_existing_streak)
        elif event_type == "new_plan_accepted":
            current_existing_streak = 0
        if event_type == "mutation_selected":
            last_selected = event
        elif event_type == "mutation_reward_updated":
            last_reward = event
    events[source_key] = {"offset": offset + len(complete), "existingStreak": current_existing_streak}

# Pool files are snapshots. Read a file only after it changes, retaining the
# aggregate maxima in the checkpoint instead of rescanning every seed's pool.
for path in pool_files:
    source_key = str(path.resolve())
    try:
        stat = path.stat()
    except OSError:
        continue
    marker = {"size": stat.st_size, "mtimeNs": stat.st_mtime_ns}
    if pools.get(source_key) == marker:
        continue
    try:
        pool = json.loads(path.read_text(errors="ignore"))
    except (OSError, json.JSONDecodeError):
        continue
    pool_size = pool.get("poolSize")
    if isinstance(pool_size, int) and not isinstance(pool_size, bool):
        max_pool_size = max(max_pool_size, pool_size)
    mutation_sequence = pool.get("mutationSequence")
    if isinstance(mutation_sequence, int) and not isinstance(mutation_sequence, bool):
        max_mutation_sequence = max(max_mutation_sequence, mutation_sequence)
    pools[source_key] = marker

new_plan_events = counts.get("new_plan_accepted", 0)
existing_plan_events = counts.get("existing_plan_seen", 0)
mutation_selected_events = counts.get("mutation_selected", 0)
mutation_reward_events = counts.get("mutation_reward_updated", 0)
total_plan_events = new_plan_events + existing_plan_events
new_plan_ratio = (new_plan_events / total_plan_events) if total_plan_events else 0.0

def emit(key, value):
    print(f"{key}={value}")


summary = {"schemaVersion": 1, "eventFiles": len(event_files), "poolFiles": len(pool_files),
           "counts": counts, "maxPoolSize": max_pool_size, "maxExistingStreak": max_existing_streak,
           "maxMutationSequence": max_mutation_sequence, "lastSelected": last_selected,
           "lastReward": last_reward}
summary_path.parent.mkdir(parents=True, exist_ok=True)
summary_tmp = summary_path.with_name(summary_path.name + ".tmp")
summary_tmp.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
summary_tmp.replace(summary_path)
checkpoint_tmp = checkpoint_path.with_name(checkpoint_path.name + ".tmp")
checkpoint_tmp.write_text(json.dumps({"schemaVersion": 1, "events": events, "pools": pools,
                                      "counts": counts, "maxPoolSize": max_pool_size,
                                      "maxExistingStreak": max_existing_streak,
                                      "maxMutationSequence": max_mutation_sequence,
                                      "lastSelected": last_selected, "lastReward": last_reward},
                                     sort_keys=True) + "\n")
checkpoint_tmp.replace(checkpoint_path)

emit("qpg_event_files", len(event_files))
emit("qpg_pool_files", len(pool_files))
emit("qpg_total_plan_events", total_plan_events)
emit("qpg_new_plan_events", new_plan_events)
emit("qpg_existing_plan_events", existing_plan_events)
emit("qpg_new_plan_ratio", f"{new_plan_ratio:.6f}")
emit("qpg_max_pool_size", max_pool_size)
emit("qpg_max_existing_streak", max_existing_streak)
emit("qpg_mutation_selected_events", mutation_selected_events)
emit("qpg_mutation_reward_events", mutation_reward_events)
emit("qpg_max_mutation_sequence", max_mutation_sequence)
emit("qpg_last_mutation_action", last_selected.get("actionName", ""))
emit("qpg_last_mutation_selection_mode", last_selected.get("selectionMode", ""))
emit("qpg_last_stale_trigger", last_selected.get("staleTrigger", ""))
emit("qpg_last_existing_plan_streak", last_selected.get("existingPlanStreak", ""))
emit("qpg_last_stale_window_size", last_selected.get("windowSize", ""))
emit("qpg_last_stale_window_new_plans", last_selected.get("windowNewPlans", ""))
emit("qpg_last_stale_window_pool_growth", last_selected.get("windowPoolGrowth", ""))
emit("qpg_last_recent_reward_window_size", last_selected.get("recentRewardWindowSize", ""))
emit("qpg_last_recent_reward_max", last_selected.get("recentRewardMax", ""))
emit("qpg_last_mutation_reward_before", last_selected.get("rewardBefore", ""))
emit("qpg_last_reward_action", last_reward.get("actionName", ""))
emit("qpg_last_reward_delta_plans", last_reward.get("rewardDeltaPlans", ""))
emit("qpg_last_reward_normalized", last_reward.get("normalizedReward", ""))
emit("qpg_last_reward_after", last_reward.get("rewardAfter", ""))
PY
}

collect_perfhint_telemetry() {
    local telemetry_dir="$FEEDBACK_STATE_PATH"
    local summary_path="$LOG_DIR/perfhint-telemetry-summary.json"
    local per_second_path="$LOG_DIR/perfhint-telemetry-per-second.ndjson"
    python3 - "$telemetry_dir" "$summary_path" "$per_second_path" "$SUMMARY_TELEMETRY_PER_SECOND" <<'PY'
import collections
import json
import os
import sys
from pathlib import Path

telemetry_dir = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
per_second_path = Path(sys.argv[3])
write_per_second = sys.argv[4].strip().lower() in {"1", "true", "yes", "on"}
checkpoint_path = summary_path.with_name("perfhint-telemetry-aggregate-state.json")


def load_checkpoint():
    try:
        loaded = json.loads(checkpoint_path.read_text())
        if not isinstance(loaded, dict) or loaded.get("schemaVersion") not in {2, 3}:
            raise ValueError("unsupported checkpoint")
        return loaded
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return {"schemaVersion": 3, "files": {}, "counters": {}, "snapshots": 0, "observedMillis": 0}


checkpoint = load_checkpoint()
offsets = checkpoint.get("files", {})
if not isinstance(offsets, dict):
    offsets = {}
totals = collections.Counter()
for key, value in checkpoint.get("counters", {}).items():
    if isinstance(key, str) and isinstance(value, int) and not isinstance(value, bool):
        totals[key] = value
snapshots = int(checkpoint.get("snapshots", 0))
# Schema v2 counted telemetry records as seconds. Schema v3 stores elapsed
# time explicitly so a diagnostic flush period other than one second does not
# distort rates. A v2 checkpoint has no exact interval history; preserve the
# prior approximation only for its already-consumed records.
if checkpoint.get("schemaVersion") == 2:
    observed_millis = int(checkpoint.get("observedSeconds", 0)) * 1000
else:
    observed_millis = int(checkpoint.get("observedMillis", 0))
new_records = []
files = sorted(telemetry_dir.glob("perfhint_telemetry-*.ndjson"),
               key=lambda path: (path.stat().st_mtime_ns, path.name)) if telemetry_dir.is_dir() else []

# Each telemetry JVM appends one complete JSON line per second. Remember byte offsets per source file so
# heartbeat summaries read only newly appended bytes; historic telemetry is never reparsed during a campaign.
for source_path in files:
    source_key = str(source_path.resolve())
    try:
        file_size = source_path.stat().st_size
        offset = int(offsets.get(source_key, 0))
        if offset < 0 or offset > file_size:
            offset = 0
        with source_path.open("rb") as source:
            source.seek(offset)
            payload = source.read()
    except OSError:
        continue
    newline = payload.rfind(b"\n")
    if newline < 0:
        continue
    complete = payload[:newline + 1]
    offsets[source_key] = offset + len(complete)
    for raw_bytes in complete.splitlines():
        try:
            event = json.loads(raw_bytes.decode("utf-8"))
            timestamp_ms = int(event["timestampEpochMillis"])
            delta = event.get("delta", {})
            interval_millis = int(event.get("intervalMillis", 1000))
        except (UnicodeDecodeError, ValueError, TypeError, KeyError, json.JSONDecodeError):
            continue
        if not isinstance(delta, dict) or interval_millis <= 0:
            continue
        snapshots += 1
        second = timestamp_ms // 1000
        normalized_delta = {}
        for key, value in delta.items():
            if isinstance(value, int) and not isinstance(value, bool):
                totals[key] += value
                normalized_delta[key] = value
        if write_per_second:
            # Detailed telemetry is an explicit short-run diagnostic option;
            # unattended campaigns retain only the compact aggregate state.
            new_records.append(json.dumps({"epochSecond": second, "source": source_path.name,
                                           "counters": dict(sorted(normalized_delta.items()))}, sort_keys=True))
        observed_millis += interval_millis

observed_seconds = observed_millis / 1000.0

for key in (
    "base_queries.generated",
    "base_explains.succeeded",
    "pairs.checked",
    "pairs.scheduled",
    "pairs.counterfactual_explain_succeeded",
    "pairs.plan_different",
    "pairs.presumed_equivalent_plan_different",
    "post_refresh.base_explains_succeeded",
    "pairs.post_refresh_counterfactual_explain_succeeded",
    "pairs.post_refresh_plan_different",
    "pairs.post_refresh_qualified",
    "pairs.runtime_measured",
    "pairs.nine_cell_classified",
    "pairs.nine_cell_reportable",
    "result_equivalence.deferred_confirmed",
    "result_equivalence.deferred_rejected",
    "pairs.oracle_qualified",
    "reports.formal_written",
    "reports.observation_written",
    "reports.diagnostic_written",
    "reports.suppressed",
    "timeout_artifacts.written",
    "rewrite_provider.requests",
    "rewrite_candidates.admitted",
    "pairs.fallback_query_rewrite_pool_empty",
    "nine_cell.a",
    "nine_cell.b",
    "nine_cell.c",
    "nine_cell.d",
    "nine_cell.e",
    "nine_cell.f",
    "nine_cell.g",
    "nine_cell.h",
    "nine_cell.i",
):
    totals.setdefault(key, 0)

summary = {
    "schemaVersion": 2,
    "telemetryFiles": len(files),
    "snapshots": snapshots,
    "observedSeconds": observed_seconds,
    "counters": dict(sorted(totals.items())),
}
summary_path.parent.mkdir(parents=True, exist_ok=True)
if new_records:
    with per_second_path.open("a") as output:
        output.write("\n".join(new_records) + "\n")
summary_tmp = summary_path.with_name(summary_path.name + ".tmp")
summary_tmp.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
os.replace(summary_tmp, summary_path)
checkpoint_tmp = checkpoint_path.with_name(checkpoint_path.name + ".tmp")
checkpoint_tmp.write_text(json.dumps({"schemaVersion": 3, "files": offsets,
                                      "counters": dict(sorted(totals.items())), "snapshots": snapshots,
                                      "observedMillis": observed_millis}, sort_keys=True) + "\n")
os.replace(checkpoint_tmp, checkpoint_path)

def emit(key, value):
    print(f"{key}={value}")

emit("telemetry_files", len(files))
emit("telemetry_snapshots", snapshots)
emit("telemetry_observed_seconds", observed_seconds)
emit("telemetry_base_queries_generated", totals["base_queries.generated"])
emit("telemetry_base_explains_succeeded", totals["base_explains.succeeded"])
emit("telemetry_pairs_scheduled", totals["pairs.scheduled"])
emit("telemetry_pairs_checked", totals["pairs.checked"])
emit("telemetry_pairs_counterfactual_explain_succeeded", totals["pairs.counterfactual_explain_succeeded"])
emit("telemetry_pairs_plan_different", totals["pairs.plan_different"])
emit("telemetry_pairs_presumed_equivalent_plan_different", totals["pairs.presumed_equivalent_plan_different"])
emit("telemetry_post_refresh_base_explains_succeeded", totals["post_refresh.base_explains_succeeded"])
emit("telemetry_post_refresh_counterfactual_explain_succeeded", totals["pairs.post_refresh_counterfactual_explain_succeeded"])
emit("telemetry_pairs_post_refresh_plan_different", totals["pairs.post_refresh_plan_different"])
emit("telemetry_pairs_post_refresh_qualified", totals["pairs.post_refresh_qualified"])
emit("telemetry_pairs_runtime_measured", totals["pairs.runtime_measured"])
emit("telemetry_pairs_nine_cell_classified", totals["pairs.nine_cell_classified"])
emit("telemetry_pairs_nine_cell_reportable", totals["pairs.nine_cell_reportable"])
emit("telemetry_result_equivalence_confirmed", totals["result_equivalence.deferred_confirmed"])
emit("telemetry_result_equivalence_rejected", totals["result_equivalence.deferred_rejected"])
emit("telemetry_pairs_oracle_qualified", totals["pairs.oracle_qualified"])
emit("telemetry_reports_formal_written", totals["reports.formal_written"])
emit("telemetry_reports_observation_written", totals["reports.observation_written"])
emit("telemetry_reports_diagnostic_written", totals["reports.diagnostic_written"])
emit("telemetry_reports_suppressed", totals["reports.suppressed"])
emit("telemetry_timeout_artifacts_written", totals["timeout_artifacts.written"])
emit("telemetry_rewrite_requests", totals["rewrite_provider.requests"])
emit("telemetry_rewrite_candidates_admitted", totals["rewrite_candidates.admitted"])
emit("telemetry_rewrite_pool_empty_fallbacks", totals["pairs.fallback_query_rewrite_pool_empty"])
for code in "abcdefghi":
    emit("telemetry_nine_cell_" + code, totals["nine_cell." + code])
PY
}

write_campaign_summary() {
    local status="$1"
    local now_human now_epoch completed_seeds case_count formal_case_count observation_case_count timeout_case_count
    local latest_case latest_timeout_case
    local current_run_elapsed elapsed_seconds total_runtime_seconds avg_seconds_per_seed
    local total_witnesses_seen total_witnesses_written total_witnesses_suppressed total_root_cause_suppressions
    local unique_root_causes unique_plan_signatures unique_families unique_skeletons
    local feedback_parser
    local qpg_event_files qpg_pool_files qpg_total_plan_events qpg_new_plan_events qpg_existing_plan_events
    local qpg_new_plan_ratio qpg_max_pool_size qpg_max_existing_streak qpg_required_stale_streak
    local qpg_mutation_selected_events qpg_mutation_reward_events qpg_max_mutation_sequence
    local qpg_last_stale_trigger qpg_last_existing_plan_streak qpg_last_stale_window_size
    local qpg_last_stale_window_new_plans qpg_last_stale_window_pool_growth
    local qpg_last_recent_reward_window_size qpg_last_recent_reward_max
    local qpg_last_mutation_action qpg_last_mutation_selection_mode qpg_last_mutation_reward_before
    local qpg_last_reward_action qpg_last_reward_delta_plans qpg_last_reward_normalized qpg_last_reward_after
    local telemetry_files telemetry_snapshots telemetry_observed_seconds telemetry_base_queries_generated telemetry_base_explains_succeeded
    local telemetry_pairs_scheduled telemetry_pairs_checked telemetry_pairs_counterfactual_explain_succeeded
    local telemetry_pairs_plan_different telemetry_pairs_presumed_equivalent_plan_different
    local telemetry_post_refresh_base_explains_succeeded telemetry_post_refresh_counterfactual_explain_succeeded telemetry_pairs_post_refresh_plan_different
    local telemetry_pairs_post_refresh_qualified telemetry_pairs_runtime_measured telemetry_pairs_nine_cell_classified telemetry_pairs_nine_cell_reportable
    local telemetry_result_equivalence_confirmed telemetry_result_equivalence_rejected telemetry_pairs_oracle_qualified
    local telemetry_reports_formal_written telemetry_reports_observation_written telemetry_reports_diagnostic_written telemetry_reports_suppressed telemetry_timeout_artifacts_written
    local telemetry_rewrite_requests telemetry_rewrite_candidates_admitted telemetry_rewrite_pool_empty_fallbacks
    local telemetry_nine_cell_a telemetry_nine_cell_b telemetry_nine_cell_c telemetry_nine_cell_d telemetry_nine_cell_e
    local telemetry_nine_cell_f telemetry_nine_cell_g telemetry_nine_cell_h telemetry_nine_cell_i
    local tmp_txt tmp_json
    now_human="$(date '+%Y-%m-%d %H:%M:%S')"
    now_epoch="$(date +%s)"
    completed_seeds="$((seed - START_SEED))"
    # The heartbeat must not recursively scan an ever-growing report tree.
    # Written-report counters come from the incremental telemetry below.
    case_count=0
    formal_case_count=0
    observation_case_count=0
    timeout_case_count=0
    latest_case=""
    latest_timeout_case=""
    elapsed_seconds="$((now_epoch - start_ts))"
    if [ "$current_run_start_ts" -gt 0 ]; then
        current_run_elapsed="$((now_epoch - current_run_start_ts))"
    else
        current_run_elapsed=0
    fi
    if [ "$completed_seeds" -gt 0 ]; then
        total_runtime_seconds="$((now_epoch - start_ts))"
        avg_seconds_per_seed="$((total_runtime_seconds / completed_seeds))"
    else
        avg_seconds_per_seed=0
    fi
    total_witnesses_seen="$(extract_feedback_value totalWitnessesSeen)"
    total_witnesses_written="$(extract_feedback_value totalWitnessesWritten)"
    total_witnesses_suppressed="$(extract_feedback_value totalWitnessesSuppressed)"
    total_root_cause_suppressions="$(extract_feedback_value totalRootCauseSuppressions)"
    unique_root_causes="$(extract_feedback_value uniqueRootCauseCount)"
    unique_plan_signatures="$(extract_feedback_value uniquePlanSignatureCount)"
    unique_families="$(extract_feedback_value uniqueFamilyCount)"
    unique_skeletons="$(extract_feedback_value uniqueSkeletonCount)"
    if [ "$DBMS" = "oceanbase" ]; then
        feedback_parser="oceanbase_feedback_state_tsv"
    else
        feedback_parser="perfhint_feedback_summary_json"
    fi
    qpg_event_files=0
    qpg_pool_files=0
    qpg_total_plan_events=0
    qpg_new_plan_events=0
    qpg_existing_plan_events=0
    qpg_new_plan_ratio=0.000000
    qpg_max_pool_size=0
    qpg_max_existing_streak=0
    qpg_required_stale_streak="$QPG_MAX_INTERVAL"
    qpg_mutation_selected_events=0
    qpg_mutation_reward_events=0
    qpg_max_mutation_sequence=0
    qpg_last_stale_trigger=""
    qpg_last_existing_plan_streak=""
    qpg_last_stale_window_size=""
    qpg_last_stale_window_new_plans=""
    qpg_last_stale_window_pool_growth=""
    qpg_last_recent_reward_window_size=""
    qpg_last_recent_reward_max=""
    qpg_last_mutation_action=""
    qpg_last_mutation_selection_mode=""
    qpg_last_mutation_reward_before=""
    qpg_last_reward_action=""
    qpg_last_reward_delta_plans=""
    qpg_last_reward_normalized=""
    qpg_last_reward_after=""
    telemetry_files=0
    telemetry_snapshots=0
    telemetry_observed_seconds=0
    telemetry_base_queries_generated=0
    telemetry_base_explains_succeeded=0
    telemetry_pairs_scheduled=0
    telemetry_pairs_checked=0
    telemetry_pairs_counterfactual_explain_succeeded=0
    telemetry_pairs_plan_different=0
    telemetry_pairs_presumed_equivalent_plan_different=0
    telemetry_post_refresh_base_explains_succeeded=0
    telemetry_post_refresh_counterfactual_explain_succeeded=0
    telemetry_pairs_post_refresh_plan_different=0
    telemetry_pairs_post_refresh_qualified=0
    telemetry_pairs_runtime_measured=0
    telemetry_pairs_nine_cell_classified=0
    telemetry_pairs_nine_cell_reportable=0
    telemetry_result_equivalence_confirmed=0
    telemetry_result_equivalence_rejected=0
    telemetry_pairs_oracle_qualified=0
    telemetry_reports_formal_written=0
    telemetry_reports_observation_written=0
    telemetry_reports_diagnostic_written=0
    telemetry_reports_suppressed=0
    telemetry_timeout_artifacts_written=0
    telemetry_rewrite_requests=0
    telemetry_rewrite_candidates_admitted=0
    telemetry_rewrite_pool_empty_fallbacks=0
    telemetry_nine_cell_a=0
    telemetry_nine_cell_b=0
    telemetry_nine_cell_c=0
    telemetry_nine_cell_d=0
    telemetry_nine_cell_e=0
    telemetry_nine_cell_f=0
    telemetry_nine_cell_g=0
    telemetry_nine_cell_h=0
    telemetry_nine_cell_i=0
    if [ "$QPG_ENABLE" = "1" ]; then
        while IFS='=' read -r key value; do
            case "$key" in
            qpg_event_files) qpg_event_files="$value" ;;
            qpg_pool_files) qpg_pool_files="$value" ;;
            qpg_total_plan_events) qpg_total_plan_events="$value" ;;
            qpg_new_plan_events) qpg_new_plan_events="$value" ;;
            qpg_existing_plan_events) qpg_existing_plan_events="$value" ;;
            qpg_new_plan_ratio) qpg_new_plan_ratio="$value" ;;
            qpg_max_pool_size) qpg_max_pool_size="$value" ;;
            qpg_max_existing_streak) qpg_max_existing_streak="$value" ;;
            qpg_mutation_selected_events) qpg_mutation_selected_events="$value" ;;
            qpg_mutation_reward_events) qpg_mutation_reward_events="$value" ;;
            qpg_max_mutation_sequence) qpg_max_mutation_sequence="$value" ;;
            qpg_last_stale_trigger) qpg_last_stale_trigger="$value" ;;
            qpg_last_existing_plan_streak) qpg_last_existing_plan_streak="$value" ;;
            qpg_last_stale_window_size) qpg_last_stale_window_size="$value" ;;
            qpg_last_stale_window_new_plans) qpg_last_stale_window_new_plans="$value" ;;
            qpg_last_stale_window_pool_growth) qpg_last_stale_window_pool_growth="$value" ;;
            qpg_last_recent_reward_window_size) qpg_last_recent_reward_window_size="$value" ;;
            qpg_last_recent_reward_max) qpg_last_recent_reward_max="$value" ;;
            qpg_last_mutation_action) qpg_last_mutation_action="$value" ;;
            qpg_last_mutation_selection_mode) qpg_last_mutation_selection_mode="$value" ;;
            qpg_last_mutation_reward_before) qpg_last_mutation_reward_before="$value" ;;
            qpg_last_reward_action) qpg_last_reward_action="$value" ;;
            qpg_last_reward_delta_plans) qpg_last_reward_delta_plans="$value" ;;
            qpg_last_reward_normalized) qpg_last_reward_normalized="$value" ;;
                qpg_last_reward_after) qpg_last_reward_after="$value" ;;
            esac
        done < <(collect_qpg_metrics)
    fi
    while IFS='=' read -r key value; do
        case "$key" in
            telemetry_files) telemetry_files="$value" ;;
            telemetry_snapshots) telemetry_snapshots="$value" ;;
            telemetry_observed_seconds) telemetry_observed_seconds="$value" ;;
            telemetry_base_queries_generated) telemetry_base_queries_generated="$value" ;;
            telemetry_base_explains_succeeded) telemetry_base_explains_succeeded="$value" ;;
            telemetry_pairs_scheduled) telemetry_pairs_scheduled="$value" ;;
            telemetry_pairs_checked) telemetry_pairs_checked="$value" ;;
            telemetry_pairs_counterfactual_explain_succeeded) telemetry_pairs_counterfactual_explain_succeeded="$value" ;;
            telemetry_pairs_plan_different) telemetry_pairs_plan_different="$value" ;;
            telemetry_pairs_presumed_equivalent_plan_different) telemetry_pairs_presumed_equivalent_plan_different="$value" ;;
            telemetry_post_refresh_base_explains_succeeded) telemetry_post_refresh_base_explains_succeeded="$value" ;;
            telemetry_post_refresh_counterfactual_explain_succeeded) telemetry_post_refresh_counterfactual_explain_succeeded="$value" ;;
            telemetry_pairs_post_refresh_plan_different) telemetry_pairs_post_refresh_plan_different="$value" ;;
            telemetry_pairs_post_refresh_qualified) telemetry_pairs_post_refresh_qualified="$value" ;;
            telemetry_pairs_runtime_measured) telemetry_pairs_runtime_measured="$value" ;;
            telemetry_pairs_nine_cell_classified) telemetry_pairs_nine_cell_classified="$value" ;;
            telemetry_pairs_nine_cell_reportable) telemetry_pairs_nine_cell_reportable="$value" ;;
            telemetry_result_equivalence_confirmed) telemetry_result_equivalence_confirmed="$value" ;;
            telemetry_result_equivalence_rejected) telemetry_result_equivalence_rejected="$value" ;;
            telemetry_pairs_oracle_qualified) telemetry_pairs_oracle_qualified="$value" ;;
            telemetry_reports_formal_written) telemetry_reports_formal_written="$value" ;;
            telemetry_reports_observation_written) telemetry_reports_observation_written="$value" ;;
            telemetry_reports_diagnostic_written) telemetry_reports_diagnostic_written="$value" ;;
            telemetry_reports_suppressed) telemetry_reports_suppressed="$value" ;;
            telemetry_timeout_artifacts_written) telemetry_timeout_artifacts_written="$value" ;;
            telemetry_rewrite_requests) telemetry_rewrite_requests="$value" ;;
            telemetry_rewrite_candidates_admitted) telemetry_rewrite_candidates_admitted="$value" ;;
            telemetry_rewrite_pool_empty_fallbacks) telemetry_rewrite_pool_empty_fallbacks="$value" ;;
            telemetry_nine_cell_a) telemetry_nine_cell_a="$value" ;;
            telemetry_nine_cell_b) telemetry_nine_cell_b="$value" ;;
            telemetry_nine_cell_c) telemetry_nine_cell_c="$value" ;;
            telemetry_nine_cell_d) telemetry_nine_cell_d="$value" ;;
            telemetry_nine_cell_e) telemetry_nine_cell_e="$value" ;;
            telemetry_nine_cell_f) telemetry_nine_cell_f="$value" ;;
            telemetry_nine_cell_g) telemetry_nine_cell_g="$value" ;;
            telemetry_nine_cell_h) telemetry_nine_cell_h="$value" ;;
            telemetry_nine_cell_i) telemetry_nine_cell_i="$value" ;;
        esac
    done < <(collect_perfhint_telemetry)

    formal_case_count="$telemetry_reports_formal_written"
    observation_case_count="$telemetry_reports_observation_written"
    case_count="$((formal_case_count + observation_case_count))"
    timeout_case_count="$telemetry_timeout_artifacts_written"

    tmp_txt="${SUMMARY_TXT}.tmp.$$.$RANDOM"
    {
        printf 'Campaign summary\n'
        printf 'updated_at=%s\n' "$now_human"
        printf 'status=%s\n' "$status"
        printf 'dbms=%s host=%s port=%s\n' "$DBMS" "$HOST" "$PORT"
        printf 'data_generator_mode=%s\n' "$DATA_GENERATOR_MODE"
        printf 'data_ablation_profile=%s\n' "$DATA_ABLATION_PROFILE"
        printf 'report_root=%s\n' "$BUG_REPORT_ROOT"
        printf 'campaign_start=%s\n' "$(date -d "@$start_ts" '+%Y-%m-%d %H:%M:%S')"
        printf 'elapsed_seconds=%s\n' "$elapsed_seconds"
        printf 'completed_seeds=%s\n' "$completed_seeds"
        printf 'current_seed=%s\n' "$seed"
        printf 'current_db_prefix=%s\n' "$current_db_prefix"
        printf 'current_run_started_at=%s\n' "$current_run_started_at"
        printf 'current_run_elapsed_seconds=%s\n' "$current_run_elapsed"
        printf 'current_run_log=%s\n' "$current_run_log"
        printf 'current_cur_log=%s\n' "$current_cur_log"
        printf 'avg_seconds_per_seed=%s\n' "$avg_seconds_per_seed"
        printf 'case_count=%s\n' "$case_count"
        printf 'latest_case=<not_scanned_in_live_summary>\n'
        printf 'formal_case_count=%s\n' "$formal_case_count"
        printf 'observation_case_count=%s\n' "$observation_case_count"
        printf 'timeout_case_count=%s\n' "$timeout_case_count"
        printf 'latest_timeout_case=<not_scanned_in_live_summary>\n'
        printf 'feedback_total_witnesses_seen=%s\n' "$total_witnesses_seen"
        printf 'feedback_total_witnesses_written=%s\n' "$total_witnesses_written"
        printf 'feedback_total_witnesses_suppressed=%s\n' "$total_witnesses_suppressed"
        printf 'feedback_total_root_cause_suppressions=%s\n' "$total_root_cause_suppressions"
        printf 'feedback_parser=%s\n' "$feedback_parser"
        printf 'feedback_unique_root_causes=%s\n' "$unique_root_causes"
        printf 'feedback_unique_plan_signatures=%s\n' "$unique_plan_signatures"
        printf 'feedback_unique_families=%s\n' "$unique_families"
        printf 'feedback_unique_skeletons=%s\n' "$unique_skeletons"
        printf 'nine_cell_cost_ratio_interval=[%s,%s]\n' "$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND" "$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND"
        printf 'nine_cell_runtime_ratio_interval=[%s,%s]\n' "$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND" "$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND"
        printf 'report_harmful_runtime_ratio=%s\n' "$PERF_REPORT_HARMFUL_RUNTIME_RATIO"
        printf 'report_cost_anomaly_ratio=%s\n' "$PERF_REPORT_COST_ANOMALY_RATIO"
        printf 'nine_cell_report_cells=%s\n' "$PERF_TAXONOMY_REPORT_CELLS"
        printf 'telemetry_files=%s\n' "$telemetry_files"
        printf 'telemetry_snapshots=%s\n' "$telemetry_snapshots"
        printf 'telemetry_observed_seconds=%s\n' "$telemetry_observed_seconds"
        printf 'queries=%s\n' "$telemetry_base_queries_generated"
        printf 'scheduled_pairs=%s\n' "$telemetry_pairs_scheduled"
        printf 'base_explains_succeeded=%s\n' "$telemetry_base_explains_succeeded"
        printf 'counterfactual_explains_succeeded=%s\n' "$telemetry_pairs_counterfactual_explain_succeeded"
        printf 'initial_plan_different=%s\n' "$telemetry_pairs_plan_different"
        printf 'post_refresh_base_explains_succeeded=%s\n' "$telemetry_post_refresh_base_explains_succeeded"
        printf 'post_refresh_counterfactual_explains_succeeded=%s\n' "$telemetry_post_refresh_counterfactual_explain_succeeded"
        printf 'post_refresh_plan_different=%s\n' "$telemetry_pairs_post_refresh_plan_different"
        printf 'runtime_measured=%s\n' "$telemetry_pairs_runtime_measured"
        printf 'nine_cell_A=%s\n' "$telemetry_nine_cell_a"
        printf 'nine_cell_B=%s\n' "$telemetry_nine_cell_b"
        printf 'nine_cell_C=%s\n' "$telemetry_nine_cell_c"
        printf 'nine_cell_D=%s\n' "$telemetry_nine_cell_d"
        printf 'nine_cell_E=%s\n' "$telemetry_nine_cell_e"
        printf 'nine_cell_F=%s\n' "$telemetry_nine_cell_f"
        printf 'nine_cell_G=%s\n' "$telemetry_nine_cell_g"
        printf 'nine_cell_H=%s\n' "$telemetry_nine_cell_h"
        printf 'nine_cell_I=%s\n' "$telemetry_nine_cell_i"
        printf 'report_candidates=%s\n' "$telemetry_pairs_nine_cell_reportable"
        printf 'result_equivalence_confirmed=%s\n' "$telemetry_result_equivalence_confirmed"
        printf 'result_equivalence_rejected=%s\n' "$telemetry_result_equivalence_rejected"
        printf 'formal_cases=%s\n' "$formal_case_count"
        printf 'observation_cases=%s\n' "$observation_case_count"
        printf 'suppressed_cases=%s\n' "$telemetry_reports_suppressed"
        printf 'timeout_observations=%s\n' "$telemetry_timeout_artifacts_written"
        printf 'telemetry_summary_json=%s\n' "$LOG_DIR/perfhint-telemetry-summary.json"
        printf 'telemetry_per_second_jsonl=%s\n' "$LOG_DIR/perfhint-telemetry-per-second.ndjson"
        printf 'qpg_event_files=%s\n' "$qpg_event_files"
        printf 'qpg_pool_files=%s\n' "$qpg_pool_files"
        printf 'qpg_total_plan_events=%s\n' "$qpg_total_plan_events"
        printf 'qpg_new_plan_events=%s\n' "$qpg_new_plan_events"
        printf 'qpg_existing_plan_events=%s\n' "$qpg_existing_plan_events"
        printf 'qpg_new_plan_ratio=%s\n' "$qpg_new_plan_ratio"
        printf 'qpg_max_pool_size=%s\n' "$qpg_max_pool_size"
        printf 'qpg_max_existing_streak=%s\n' "$qpg_max_existing_streak"
        printf 'qpg_required_stale_streak=%s\n' "$qpg_required_stale_streak"
        printf 'qpg_mutation_selected_events=%s\n' "$qpg_mutation_selected_events"
        printf 'qpg_mutation_reward_events=%s\n' "$qpg_mutation_reward_events"
        printf 'qpg_max_mutation_sequence=%s\n' "$qpg_max_mutation_sequence"
        printf 'qpg_window_size=%s\n' "$QPG_STALE_WINDOW_SIZE"
        printf 'qpg_window_max_new_plans=%s\n' "$QPG_STALE_WINDOW_MAX_NEW_PLANS"
        printf 'qpg_window_max_pool_growth=%s\n' "$QPG_STALE_WINDOW_MAX_POOL_GROWTH"
        printf 'qpg_reward_lookback=%s\n' "$QPG_STALE_REWARD_LOOKBACK"
        printf 'qpg_max_recent_reward=%s\n' "$QPG_STALE_MAX_RECENT_REWARD"
        printf 'qpg_last_stale_trigger=%s\n' "${qpg_last_stale_trigger:-<none>}"
        printf 'qpg_last_existing_plan_streak=%s\n' "${qpg_last_existing_plan_streak:-<none>}"
        printf 'qpg_last_stale_window_size=%s\n' "${qpg_last_stale_window_size:-<none>}"
        printf 'qpg_last_stale_window_new_plans=%s\n' "${qpg_last_stale_window_new_plans:-<none>}"
        printf 'qpg_last_stale_window_pool_growth=%s\n' "${qpg_last_stale_window_pool_growth:-<none>}"
        printf 'qpg_last_recent_reward_window_size=%s\n' "${qpg_last_recent_reward_window_size:-<none>}"
        printf 'qpg_last_recent_reward_max=%s\n' "${qpg_last_recent_reward_max:-<none>}"
        printf 'qpg_last_mutation_action=%s\n' "${qpg_last_mutation_action:-<none>}"
        printf 'qpg_last_mutation_selection_mode=%s\n' "${qpg_last_mutation_selection_mode:-<none>}"
        printf 'qpg_last_mutation_reward_before=%s\n' "${qpg_last_mutation_reward_before:-<none>}"
        printf 'qpg_last_reward_action=%s\n' "${qpg_last_reward_action:-<none>}"
        printf 'qpg_last_reward_delta_plans=%s\n' "${qpg_last_reward_delta_plans:-<none>}"
        printf 'qpg_last_reward_normalized=%s\n' "${qpg_last_reward_normalized:-<none>}"
        printf 'qpg_last_reward_after=%s\n' "${qpg_last_reward_after:-<none>}"
        printf 'recent_cases=<not_scanned_in_live_summary>\n'
    } > "$tmp_txt"
    mv "$tmp_txt" "$SUMMARY_TXT"

    tmp_json="${SUMMARY_JSON}.tmp.$$.$RANDOM"
    {
        printf '{\n'
        printf '  "updatedAt": "%s",\n' "$(json_escape "$now_human")"
        printf '  "status": "%s",\n' "$(json_escape "$status")"
        printf '  "dbms": "%s",\n' "$(json_escape "$DBMS")"
        printf '  "dataGeneratorMode": "%s",\n' "$(json_escape "$DATA_GENERATOR_MODE")"
        printf '  "dataAblationProfile": "%s",\n' "$(json_escape "$DATA_ABLATION_PROFILE")"
        printf '  "host": "%s",\n' "$(json_escape "$HOST")"
        printf '  "port": %s,\n' "$PORT"
        printf '  "reportRoot": "%s",\n' "$(json_escape "$BUG_REPORT_ROOT")"
        printf '  "campaignStart": "%s",\n' "$(date -d "@$start_ts" '+%Y-%m-%d %H:%M:%S')"
        printf '  "elapsedSeconds": %s,\n' "$elapsed_seconds"
        printf '  "completedSeeds": %s,\n' "$completed_seeds"
        printf '  "currentSeed": %s,\n' "$seed"
        printf '  "currentDbPrefix": "%s",\n' "$(json_escape "$current_db_prefix")"
        printf '  "currentRunStartedAt": "%s",\n' "$(json_escape "$current_run_started_at")"
        printf '  "currentRunElapsedSeconds": %s,\n' "$current_run_elapsed"
        printf '  "currentRunLog": "%s",\n' "$(json_escape "$current_run_log")"
        printf '  "currentCurLog": "%s",\n' "$(json_escape "$current_cur_log")"
        printf '  "avgSecondsPerSeed": %s,\n' "$avg_seconds_per_seed"
        printf '  "caseCount": %s,\n' "$case_count"
        printf '  "latestCase": "",\n'
        printf '  "formalCaseCount": %s,\n' "$formal_case_count"
        printf '  "observationCaseCount": %s,\n' "$observation_case_count"
        printf '  "timeoutCaseCount": %s,\n' "$timeout_case_count"
        printf '  "latestTimeoutCase": "",\n'
        printf '  "feedback": {\n'
        printf '    "parser": "%s",\n' "$(json_escape "$feedback_parser")"
        printf '    "totalWitnessesSeen": %s,\n' "$total_witnesses_seen"
        printf '    "totalWitnessesWritten": %s,\n' "$total_witnesses_written"
        printf '    "totalWitnessesSuppressed": %s,\n' "$total_witnesses_suppressed"
        printf '    "totalRootCauseSuppressions": %s,\n' "$total_root_cause_suppressions"
        printf '    "uniqueRootCauses": %s,\n' "$unique_root_causes"
        printf '    "uniquePlanSignatures": %s,\n' "$unique_plan_signatures"
        printf '    "uniqueFamilies": %s,\n' "$unique_families"
        printf '    "uniqueSkeletons": %s\n' "$unique_skeletons"
        printf '  },\n'
        printf '  "nineCellConfiguration": {\n'
        printf '    "costRatioLowerBound": %s,\n' "$(json_number "$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND")"
        printf '    "costRatioUpperBound": %s,\n' "$(json_number "$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND")"
        printf '    "runtimeRatioLowerBound": %s,\n' "$(json_number "$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND")"
        printf '    "runtimeRatioUpperBound": %s,\n' "$(json_number "$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND")"
        printf '    "harmfulRuntimeRatio": %s,\n' "$(json_number "$PERF_REPORT_HARMFUL_RUNTIME_RATIO")"
        printf '    "costAnomalyRatio": %s,\n' "$(json_number "$PERF_REPORT_COST_ANOMALY_RATIO")"
        printf '    "reportCells": "%s"\n' "$(json_escape "$PERF_TAXONOMY_REPORT_CELLS")"
        printf '  },\n'
        printf '  "telemetry": {\n'
        printf '    "summaryPath": "%s",\n' "$(json_escape "$LOG_DIR/perfhint-telemetry-summary.json")"
        printf '    "perSecondPath": "%s",\n' "$(json_escape "$LOG_DIR/perfhint-telemetry-per-second.ndjson")"
        printf '    "files": %s,\n' "$telemetry_files"
        printf '    "snapshots": %s,\n' "$telemetry_snapshots"
        printf '    "observedSeconds": %s,\n' "$telemetry_observed_seconds"
        printf '    "queries": %s,\n' "$telemetry_base_queries_generated"
        printf '    "scheduledPairs": %s,\n' "$telemetry_pairs_scheduled"
        printf '    "baseExplainsSucceeded": %s,\n' "$telemetry_base_explains_succeeded"
        printf '    "counterfactualExplainsSucceeded": %s,\n' "$telemetry_pairs_counterfactual_explain_succeeded"
        printf '    "initialPlanDifferent": %s,\n' "$telemetry_pairs_plan_different"
        printf '    "postRefreshBaseExplainsSucceeded": %s,\n' "$telemetry_post_refresh_base_explains_succeeded"
        printf '    "postRefreshCounterfactualExplainsSucceeded": %s,\n' "$telemetry_post_refresh_counterfactual_explain_succeeded"
        printf '    "postRefreshPlanDifferent": %s,\n' "$telemetry_pairs_post_refresh_plan_different"
        printf '    "runtimeMeasured": %s,\n' "$telemetry_pairs_runtime_measured"
        printf '    "nineCell": {\n'
        printf '      "A": %s,\n' "$telemetry_nine_cell_a"
        printf '      "B": %s,\n' "$telemetry_nine_cell_b"
        printf '      "C": %s,\n' "$telemetry_nine_cell_c"
        printf '      "D": %s,\n' "$telemetry_nine_cell_d"
        printf '      "E": %s,\n' "$telemetry_nine_cell_e"
        printf '      "F": %s,\n' "$telemetry_nine_cell_f"
        printf '      "G": %s,\n' "$telemetry_nine_cell_g"
        printf '      "H": %s,\n' "$telemetry_nine_cell_h"
        printf '      "I": %s\n' "$telemetry_nine_cell_i"
        printf '    },\n'
        printf '    "reportCandidates": %s,\n' "$telemetry_pairs_nine_cell_reportable"
        printf '    "resultEquivalenceConfirmed": %s,\n' "$telemetry_result_equivalence_confirmed"
        printf '    "resultEquivalenceRejected": %s,\n' "$telemetry_result_equivalence_rejected"
        printf '    "formalCases": %s,\n' "$formal_case_count"
        printf '    "observationCases": %s,\n' "$observation_case_count"
        printf '    "suppressedCases": %s,\n' "$telemetry_reports_suppressed"
        printf '    "timeoutObservations": %s,\n' "$telemetry_timeout_artifacts_written"
        printf '    "rewriteRequests": %s,\n' "$telemetry_rewrite_requests"
        printf '    "rewriteCandidatesAdmitted": %s,\n' "$telemetry_rewrite_candidates_admitted"
        printf '    "rewritePoolEmptyFallbacks": %s\n' "$telemetry_rewrite_pool_empty_fallbacks"
        printf '  },\n'
        printf '  "qpg": {\n'
        printf '    "eventFiles": %s,\n' "$qpg_event_files"
        printf '    "poolFiles": %s,\n' "$qpg_pool_files"
        printf '    "totalPlanEvents": %s,\n' "$qpg_total_plan_events"
        printf '    "newPlanEvents": %s,\n' "$qpg_new_plan_events"
        printf '    "existingPlanEvents": %s,\n' "$qpg_existing_plan_events"
        printf '    "newPlanRatio": %s,\n' "$qpg_new_plan_ratio"
        printf '    "maxPoolSize": %s,\n' "$qpg_max_pool_size"
        printf '    "maxExistingStreak": %s,\n' "$qpg_max_existing_streak"
        printf '    "requiredStaleStreak": %s,\n' "$qpg_required_stale_streak"
        printf '    "mutationSelectedEvents": %s,\n' "$qpg_mutation_selected_events"
        printf '    "mutationRewardEvents": %s,\n' "$qpg_mutation_reward_events"
        printf '    "maxMutationSequence": %s,\n' "$qpg_max_mutation_sequence"
        printf '    "windowSize": %s,\n' "$QPG_STALE_WINDOW_SIZE"
        printf '    "windowMaxNewPlans": %s,\n' "$QPG_STALE_WINDOW_MAX_NEW_PLANS"
        printf '    "windowMaxPoolGrowth": %s,\n' "$QPG_STALE_WINDOW_MAX_POOL_GROWTH"
        printf '    "rewardLookback": %s,\n' "$QPG_STALE_REWARD_LOOKBACK"
        printf '    "maxRecentReward": %s,\n' "$(json_number "$QPG_STALE_MAX_RECENT_REWARD")"
        printf '    "lastStaleTrigger": "%s",\n' "$(json_escape "$qpg_last_stale_trigger")"
        printf '    "lastExistingPlanStreak": %s,\n' "${qpg_last_existing_plan_streak:-0}"
        printf '    "lastStaleWindowSize": %s,\n' "${qpg_last_stale_window_size:-0}"
        printf '    "lastStaleWindowNewPlans": %s,\n' "${qpg_last_stale_window_new_plans:-0}"
        printf '    "lastStaleWindowPoolGrowth": %s,\n' "${qpg_last_stale_window_pool_growth:-0}"
        printf '    "lastRecentRewardWindowSize": %s,\n' "${qpg_last_recent_reward_window_size:-0}"
        printf '    "lastRecentRewardMax": %s,\n' "$(json_number "${qpg_last_recent_reward_max:--1}")"
        printf '    "lastMutationAction": "%s",\n' "$(json_escape "$qpg_last_mutation_action")"
        printf '    "lastMutationSelectionMode": "%s",\n' "$(json_escape "$qpg_last_mutation_selection_mode")"
        printf '    "lastMutationRewardBefore": "%s",\n' "$(json_escape "$qpg_last_mutation_reward_before")"
        printf '    "lastRewardAction": "%s",\n' "$(json_escape "$qpg_last_reward_action")"
        printf '    "lastRewardDeltaPlans": "%s",\n' "$(json_escape "$qpg_last_reward_delta_plans")"
        printf '    "lastRewardNormalized": "%s",\n' "$(json_escape "$qpg_last_reward_normalized")"
        printf '    "lastRewardAfter": "%s"\n' "$(json_escape "$qpg_last_reward_after")"
        printf '  },\n'
        printf '  "recentCases": []\n'
        printf '}\n'
    } > "$tmp_json"
    mv "$tmp_json" "$SUMMARY_JSON"
}

start_summary_heartbeat() {
    local child_pid="$1"
    if [ "$SUMMARY_INTERVAL_SECONDS" -le 0 ]; then
        return
    fi
    (
        # The summary worker is deliberately best-effort and must yield CPU and
        # I/O to SQLancer/MySQL. Its aggregation is incremental, and these
        # priorities keep even an unexpectedly slow filesystem scan off the
        # measurement critical path.
        if [ "$SUMMARY_NICE_LEVEL" -gt 0 ]; then
            renice -n "$SUMMARY_NICE_LEVEL" -p "$BASHPID" >/dev/null 2>&1 || true
        fi
        if command -v ionice >/dev/null 2>&1; then
            ionice -c3 -p "$BASHPID" >/dev/null 2>&1 || true
        fi
        while kill -0 "$child_pid" 2>/dev/null; do
            sleep "$SUMMARY_INTERVAL_SECONDS" || break
            if kill -0 "$child_pid" 2>/dev/null; then
                write_campaign_summary "RUNNING"
            fi
        done
    ) &
    heartbeat_pid="$!"
}

stop_summary_heartbeat() {
    if [ -n "$heartbeat_pid" ]; then
        kill "$heartbeat_pid" 2>/dev/null || true
        wait "$heartbeat_pid" 2>/dev/null || true
        heartbeat_pid=""
    fi
}

normalize_bool_env() {
    case "$1" in
        1|true|TRUE|True|yes|YES|on|ON)
            printf 'true'
            ;;
        0|false|FALSE|False|no|NO|off|OFF)
            printf 'false'
            ;;
        *)
            echo "invalid boolean value: $1" >&2
            exit 1
            ;;
    esac
}

terminate_seed_process() {
    local child_pid="$1"
    local signal="${2:-TERM}"
    kill "-$signal" "$child_pid" 2>/dev/null || kill "$child_pid" 2>/dev/null || true
    pkill "-$signal" -P "$child_pid" 2>/dev/null || true
}

wait_for_seed() {
    local child_pid="$1"
    local hard_deadline_ts="$2"
    local soft_stop_reason="$3"
    local hard_stop_reason="$4"
    while kill -0 "$child_pid" 2>/dev/null; do
        if [ "$hard_deadline_ts" -gt 0 ] && [ "$(date +%s)" -ge "$hard_deadline_ts" ]; then
            current_run_stop_reason="$soft_stop_reason"
            log_master "run_soft_stop seed=$seed db_prefix=$current_db_prefix reason=$current_run_stop_reason at $(date '+%Y-%m-%d %H:%M:%S')"
            terminate_seed_process "$child_pid" TERM
            for _ in 1 2 3 4 5; do
                sleep 1
                if ! kill -0 "$child_pid" 2>/dev/null; then
                    break
                fi
            done
            if kill -0 "$child_pid" 2>/dev/null; then
                current_run_stop_reason="$hard_stop_reason"
                log_master "run_hard_stop seed=$seed db_prefix=$current_db_prefix reason=$current_run_stop_reason at $(date '+%Y-%m-%d %H:%M:%S')"
                terminate_seed_process "$child_pid" KILL
            fi
            break
        fi
        sleep 1
    done
    wait "$child_pid" || true
}

on_exit() {
    local exit_code="$1"
    if [ -n "$active_sqlancer_pid" ] && kill -0 "$active_sqlancer_pid" 2>/dev/null; then
        terminate_seed_process "$active_sqlancer_pid" TERM
    fi
    stop_summary_heartbeat
    log_master "campaign_end=$(date '+%Y-%m-%d %H:%M:%S') total_seeds=$((seed - START_SEED)) next_seed=$seed exit_code=$exit_code"
    write_campaign_summary "EXITED"
    rm -f "$PID_FILE"
}

trap 'on_exit $?' EXIT

log_master "campaign_start=$(date '+%Y-%m-%d %H:%M:%S') pid=$$ repo_root=$REPO_ROOT"
log_master "duration_seconds=$DURATION_SECONDS max_campaign_duration_seconds=$MAX_CAMPAIGN_DURATION_SECONDS max_seed_seconds=$MAX_SEED_SECONDS cost_threshold=$COST_THRESHOLD runtime_threshold=$RUNTIME_THRESHOLD repeat=$REPEAT_COUNT query_timeout_seconds=$QUERY_TIMEOUT_SECONDS statement_kind_retries=$STATEMENT_KIND_RETRIES per_run_timeout=$PER_RUN_TIMEOUT num_queries=$NUM_QUERIES workers=$WORKERS tries=$TRIES start_seed=$START_SEED"
log_master "dbms=$DBMS host=$HOST port=$PORT username=$USERNAME db_prefix_base=$DB_PREFIX_BASE"
log_master "data_generator_mode=$DATA_GENERATOR_MODE data_ablation_profile=$DATA_ABLATION_PROFILE tables=$MIN_TABLES-$MAX_TABLES rows_per_table=$MIN_ROWS_PER_TABLE-$MAX_ROWS_PER_TABLE columns=$MIN_COLUMNS-$MAX_COLUMNS indexes_per_table=0-$MAX_INDEXES_PER_TABLE"
log_master "model_probabilities=single:$SINGLE_COLUMN_MODEL_PROBABILITY,multi:$MULTI_COLUMN_MODEL_PROBABILITY,inter:$INTER_TABLE_MODEL_PROBABILITY feedback_state_path=$FEEDBACK_STATE_PATH histogram_refresh=$ENABLE_HISTOGRAM_REFRESH histogram_columns_per_table=$MAX_HISTOGRAM_COLUMNS_PER_TABLE"
log_master "perf_hint_include_types=${PERF_HINT_INCLUDE_TYPES:-<all>} perf_hint_exclude_types=${PERF_HINT_EXCLUDE_TYPES:-<none>} family_quota=$PERF_HINT_FAMILY_QUOTA family_weight_mode=$PERF_HINT_FAMILY_WEIGHT_MODE max_candidates_per_family=$PERF_HINT_MAX_CANDIDATES_PER_FAMILY use_explain_analyze=$USE_EXPLAIN_ANALYZE"
log_master "counterfactual_hint_probability=$PERF_COUNTERFACTUAL_HINT_PROBABILITY query_rewrite_provider=$PERF_QUERY_REWRITE_PROVIDER wetune_static_worker=${PERF_WETUNE_STATIC_WORKER:-<none>} wetune_static_timeout_ms=$PERF_WETUNE_STATIC_TIMEOUT_MS"
log_master "nine_cell_enabled=$PERF_TAXONOMY_ENABLE cost_ratio_interval=[$PERF_TAXONOMY_COST_RATIO_LOWER_BOUND,$PERF_TAXONOMY_COST_RATIO_UPPER_BOUND] runtime_ratio_interval=[$PERF_TAXONOMY_RUNTIME_RATIO_LOWER_BOUND,$PERF_TAXONOMY_RUNTIME_RATIO_UPPER_BOUND] harmful_runtime_ratio=$PERF_REPORT_HARMFUL_RUNTIME_RATIO cost_anomaly_ratio=$PERF_REPORT_COST_ANOMALY_RATIO min_runtime_median_ms=$PERF_TAXONOMY_MIN_RUNTIME_MEDIAN_MS min_runtime_abs_gap_ms=$PERF_TAXONOMY_MIN_RUNTIME_ABS_GAP_MS report_cells=$PERF_TAXONOMY_REPORT_CELLS"
log_master "summary_interval_seconds=$SUMMARY_INTERVAL_SECONDS summary_nice_level=$SUMMARY_NICE_LEVEL summary_telemetry_per_second=$SUMMARY_TELEMETRY_PER_SECOND telemetry_flush_seconds=$PERF_HINT_TELEMETRY_FLUSH_SECONDS"
log_master "qpg_enable=$QPG_ENABLE qpg_log_query_plan=$QPG_LOG_QUERY_PLAN qpg_max_interval=$QPG_MAX_INTERVAL qpg_reward_weight=$QPG_REWARD_WEIGHT qpg_selection_probability=$QPG_SELECTION_PROBABILITY qpg_stale_window_size=$QPG_STALE_WINDOW_SIZE qpg_stale_window_max_new_plans=$QPG_STALE_WINDOW_MAX_NEW_PLANS qpg_stale_window_max_pool_growth=$QPG_STALE_WINDOW_MAX_POOL_GROWTH qpg_stale_reward_lookback=$QPG_STALE_REWARD_LOOKBACK qpg_stale_max_recent_reward=$QPG_STALE_MAX_RECENT_REWARD"
log_master "launch_log=$LAUNCH_LOG master_log=$MASTER_LOG log_dir=$LOG_DIR"
log_master "bug_report_root=$BUG_REPORT_ROOT"
log_master "sqlancer_log_root=$SQLANCER_LOG_ROOT"
write_campaign_summary "RUNNING"

while [ "$(date +%s)" -lt "$end_ts" ]; do
    db_prefix="${DB_PREFIX_BASE}${seed}"
    # Optional exact allowlist for a run-scoped cleanup. Write before launch so
    # even an interrupted/failed Java run has a complete list of owned names.
    if [ -n "$SCHEMA_MANIFEST_PATH" ]; then
        mkdir -p "$(dirname -- "$SCHEMA_MANIFEST_PATH")"
        printf '%s\t%sconnectiontest\n' "$DBMS" "$db_prefix" >> "$SCHEMA_MANIFEST_PATH"
        for ((schema_index = 0; schema_index < TRIES; schema_index++)); do
            printf '%s\t%s%s\n' "$DBMS" "$db_prefix" "$schema_index" >> "$SCHEMA_MANIFEST_PATH"
        done
    fi
    run_log="$LOG_DIR/${db_prefix}.stdout.log"
    cur_log="$SQLANCER_LOG_ROOT/$DBMS/${db_prefix}0-cur.log"
    current_db_prefix="$db_prefix"
    current_run_log="$run_log"
    current_cur_log="$cur_log"
    current_run_started_at="$(date '+%Y-%m-%d %H:%M:%S')"
    current_run_start_ts="$(date +%s)"
    current_run_stop_reason=""
    log_master "run_start seed=$seed db_prefix=$db_prefix run_log=$run_log cur_log=$cur_log at $(date '+%Y-%m-%d %H:%M:%S')"
    write_campaign_summary "RUNNING"

    qpg_args=()
    if [ "$QPG_ENABLE" = "1" ]; then
        qpg_args+=(--qpg-enable true)
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
    fi

    sqlancer_cmd=(
        java -jar target/sqlancer-2.0.0.jar
        --random-seed "$seed" \
        --timeout-seconds "$PER_RUN_TIMEOUT" \
        --num-threads "$WORKERS" \
        --num-tries "$TRIES" \
        --num-queries "$NUM_QUERIES" \
        --num-statement-kind-retries "$STATEMENT_KIND_RETRIES" \
        --host "$HOST" \
        --port "$PORT" \
        --username "$USERNAME" \
        --password "$PASSWORD" \
        --database-prefix "$db_prefix" \
        "${qpg_args[@]}" \
        "$DBMS" \
        --oracle PERFORMANCE_HINT \
        --perf-cost-ratio-threshold "$COST_THRESHOLD" \
        --perf-enable-runtime-validation \
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
        --perf-feedback-state-path "$FEEDBACK_STATE_PATH"
    )

    case "$DBMS" in
    mysql|mariadb|tidb|cockroachdb|oceanbase)
        sqlancer_cmd+=(--perf-data-ablation-profile "$DATA_ABLATION_PROFILE")
        ;;
    esac

    case "$DBMS" in
    mysql|mariadb|tidb|cockroachdb|oceanbase)
        sqlancer_cmd+=(
            --perf-continue-after-formal-witness "$(normalize_bool_env "$PERF_CONTINUE_AFTER_FORMAL_WITNESS")" \
            --perf-async-report-writes "$(normalize_bool_env "$PERF_ASYNC_REPORT_WRITES")" \
            --perf-report-queue-capacity "$PERF_REPORT_QUEUE_CAPACITY" \
            --perf-report-queue-offer-ms "$PERF_REPORT_QUEUE_OFFER_MS" \
            --perf-insert-batch-size "$PERF_INSERT_BATCH_SIZE"
        )
        ;;
    esac

    if [ "$USE_EXPLAIN_ANALYZE" = "1" ]; then
        sqlancer_cmd+=(--perf-enable-explain-analyze)
    fi
    if [ -n "$PERF_HINT_INCLUDE_TYPES" ]; then
        sqlancer_cmd+=(--perf-hint-include-types "$PERF_HINT_INCLUDE_TYPES")
    fi
    if [ -n "$PERF_HINT_EXCLUDE_TYPES" ]; then
        sqlancer_cmd+=(--perf-hint-exclude-types "$PERF_HINT_EXCLUDE_TYPES")
    fi
    if [ -n "$PERF_WETUNE_STATIC_WORKER" ]; then
        sqlancer_cmd+=(--perf-wetune-static-worker "$PERF_WETUNE_STATIC_WORKER")
    fi
    if [ "$DBMS" = "mariadb" ] || [ "$DBMS" = "mysql" ]; then
        sqlancer_cmd+=(--perf-allow-bka-hint "$(normalize_bool_env "$PERF_ALLOW_BKA_HINT")")
    fi
    if [ "$DBMS" = "mysql" ]; then
        sqlancer_cmd+=(--perf-max-histogram-columns-per-table "$MAX_HISTOGRAM_COLUMNS_PER_TABLE")
        if [ "$ENABLE_HISTOGRAM_REFRESH" = "1" ]; then
            sqlancer_cmd+=(--perf-enable-histogram-refresh)
        else
            sqlancer_cmd+=(--perf-disable-histogram-refresh)
        fi
    fi

    "${sqlancer_cmd[@]}" > "$run_log" 2>&1 &
    sqlancer_pid="$!"
    active_sqlancer_pid="$sqlancer_pid"
    start_summary_heartbeat "$sqlancer_pid"
    seed_deadline_ts=0
    seed_soft_stop_reason="campaign_duration_reached"
    seed_hard_stop_reason="campaign_duration_force_kill"
    if [ "$MAX_SEED_SECONDS" -gt 0 ]; then
        seed_deadline_ts="$((current_run_start_ts + MAX_SEED_SECONDS))"
        seed_soft_stop_reason="max_seed_seconds_reached"
        seed_hard_stop_reason="max_seed_seconds_force_kill"
        if [ "$end_ts" -lt "$seed_deadline_ts" ]; then
            seed_deadline_ts="$end_ts"
            seed_soft_stop_reason="campaign_duration_reached"
            seed_hard_stop_reason="campaign_duration_force_kill"
        fi
    else
        seed_deadline_ts="$end_ts"
    fi
    wait_for_seed "$sqlancer_pid" "$seed_deadline_ts" "$seed_soft_stop_reason" "$seed_hard_stop_reason"
    stop_summary_heartbeat
    active_sqlancer_pid=""

    newest_case="$(find "$BUG_REPORT_ROOT" -maxdepth 1 -mindepth 1 -type d -name 'case_*' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2- || true)"
    if [ -n "$newest_case" ]; then
        log_master "latest_case=$newest_case after_seed=$seed"
    fi
    if [ -f "$run_log" ]; then
        log_master "run_end seed=$seed stop_reason=${current_run_stop_reason:-completed} run_log_size=$(wc -c < "$run_log") at $(date '+%Y-%m-%d %H:%M:%S')"
    else
        log_master "run_end seed=$seed stop_reason=${current_run_stop_reason:-completed} run_log_missing at $(date '+%Y-%m-%d %H:%M:%S')"
    fi
    write_campaign_summary "RUNNING"

    seed="$((seed + 1))"
done
