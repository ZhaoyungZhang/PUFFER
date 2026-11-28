#!/usr/bin/env bash

# Shared, deliberately small .properties reader for the PERFORMANCE_HINT
# campaign wrappers. It is sourced rather than executed.

perfhint_load_properties() {
    local config_path="$1"
    local raw key value

    if [ ! -f "$config_path" ]; then
        echo "PERFORMANCE_HINT config does not exist: $config_path" >&2
        return 1
    fi
    while IFS= read -r raw || [ -n "$raw" ]; do
        raw="${raw%$'\r'}"
        raw="${raw#"${raw%%[![:space:]]*}"}"
        raw="${raw%"${raw##*[![:space:]]}"}"
        case "$raw" in
            ''|'#'*|'!'*)
                continue
                ;;
        esac
        if [[ ! "$raw" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
            echo "invalid PERFORMANCE_HINT property in $config_path: $raw" >&2
            return 1
        fi
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        # A value inherited from the caller is an explicit operational
        # override. Do not source the file: profiles are data, not shell code.
        if [ ! -v "$key" ]; then
            printf -v "$key" '%s' "$value"
        fi
    done < "$config_path"
}

perfhint_take_config_argument() {
    local -n destination="$1"
    shift
    local -a remaining=()
    destination="${PERFHINT_CONFIG:-${MYSQL_PERFHINT_CONFIG:-}}"
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --config)
                if [ "$#" -lt 2 ] || [ -z "${2:-}" ]; then
                    echo "--config requires a .properties path" >&2
                    return 1
                fi
                destination="$2"
                shift 2
                ;;
            *)
                remaining+=("$1")
                shift
                ;;
        esac
    done
    PERFHINT_REMAINING_ARGS=("${remaining[@]}")
}

# Resolve, build, and smoke-check the optional Java 17 WeTune worker before a
# campaign starts. This stays outside the measurement loop: an unavailable
# rewriter must fail fast rather than silently converting rewrite slots into
# HINT fallbacks for an entire campaign.
perfhint_prepare_wetune_static_worker() {
    local repo_root="$1"
    local configured_provider="${PERF_QUERY_REWRITE_PROVIDER:-none}"
    local worker_path java_bin java_version java_major ready_output

    configured_provider="$(printf '%s' "$configured_provider" | tr '[:upper:]' '[:lower:]')"
    case "$configured_provider" in
        ''|none)
            return 0
            ;;
        wetune-static)
            ;;
        *)
            echo "unknown PERFORMANCE_HINT rewrite provider: $PERF_QUERY_REWRITE_PROVIDER" >&2
            return 1
            ;;
    esac

    worker_path="${PERF_WETUNE_STATIC_WORKER:-$repo_root/tools/wetune-static-worker/build/install/wetune-static-worker/bin/wetune-static-worker}"
    if [[ "$worker_path" != /* ]]; then
        worker_path="$repo_root/$worker_path"
    fi
    if [ ! -x "$worker_path" ]; then
        if [ ! -x "$repo_root/tools/wetune-static-worker/gradlew" ]; then
            echo "WeTune worker is missing and its Gradle wrapper is unavailable: $worker_path" >&2
            return 1
        fi
        echo "building WeTune static worker (one-time campaign preflight)"
        (cd "$repo_root/tools/wetune-static-worker" && ./gradlew --no-daemon installDist)
    fi
    if [ ! -x "$worker_path" ]; then
        echo "WeTune worker is not an executable regular file: $worker_path" >&2
        return 1
    fi

    # The Gradle application launcher uses JAVA_HOME when supplied; otherwise
    # it resolves java from PATH. Check the same choice rather than a random
    # JVM that happens to be installed on the host.
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin="$JAVA_HOME/bin/java"
    else
        java_bin="$(command -v java || true)"
    fi
    if [ -z "$java_bin" ]; then
        echo "WeTune static worker requires Java 17 or newer, but no java executable was found" >&2
        return 1
    fi
    java_version="$($java_bin -version 2>&1 | grep -m 1 'version "' || true)"
    java_major="$(printf '%s\n' "$java_version" | sed -nE 's/.*version "([0-9]+)(\.[0-9]+)?.*/\1/p')"
    if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
        echo "WeTune static worker requires Java 17 or newer; detected: ${java_version:-unknown}" >&2
        return 1
    fi

    # Starting the launcher with stdin at EOF loads the pinned rule bank,
    # emits ready, and exits. This verifies the executable, runtime, bundled
    # rules, and protocol before the database campaign begins.
    if ! command -v timeout >/dev/null 2>&1; then
        echo "WeTune worker preflight requires the timeout command" >&2
        return 1
    fi
    if ! ready_output="$(timeout 30s "$worker_path" </dev/null 2>&1)"; then
        echo "WeTune static worker failed its startup preflight: $worker_path" >&2
        printf '%s\n' "$ready_output" >&2
        return 1
    fi
    if ! printf '%s\n' "$ready_output" | grep -Fq '"status":"ready"'; then
        echo "WeTune static worker did not report protocol readiness: $worker_path" >&2
        printf '%s\n' "$ready_output" >&2
        return 1
    fi

    PERF_QUERY_REWRITE_PROVIDER="wetune-static"
    PERF_WETUNE_STATIC_WORKER="$(cd "$(dirname "$worker_path")" && pwd -P)/$(basename "$worker_path")"
    echo "wetune_static_preflight=ok worker=$PERF_WETUNE_STATIC_WORKER java_major=$java_major"
}
