#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: run-gradle-with-classified-retry.sh [--fallback-emulator NAME] -- COMMAND [ARG ...]

Run a Gradle command once, retrying only known emulator or infrastructure
failures. The first failure log and test reports are copied to the configured
artifact directory before any retry or emulator fallback.
USAGE
}

fallback_emulator=""
while (($# > 0)); do
  case "$1" in
    --fallback-emulator)
      (($# >= 2)) || { usage >&2; exit 2; }
      fallback_emulator="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

(($# > 0)) || { usage >&2; exit 2; }

artifact_dir="${BLUETAPE4K_RETRY_ARTIFACT_DIR:-ci-retry-artifacts}"
job_name="${BLUETAPE4K_RETRY_JOB:-gradle}"
max_attempts="${BLUETAPE4K_RETRY_MAX_ATTEMPTS:-2}"
delay_seconds="${BLUETAPE4K_RETRY_DELAY_SECONDS:-15}"

[[ "$max_attempts" =~ ^[1-2]$ ]] || {
  printf 'BLUETAPE4K_RETRY_MAX_ATTEMPTS must be 1 or 2\n' >&2
  exit 2
}
[[ "$delay_seconds" =~ ^[0-9]+$ ]] || {
  printf 'BLUETAPE4K_RETRY_DELAY_SECONDS must be a non-negative integer\n' >&2
  exit 2
}

mkdir -p "$artifact_dir"

record_classification() {
  local classification="$1"
  printf '%s\n' "$classification" > "$artifact_dir/classification.txt"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      printf '### Classified retry: %s\n' "$job_name"
      printf -- '- classification: `%s`\n' "$classification"
      printf -- '- max attempts: `%s`\n' "$max_attempts"
      printf -- '- delay seconds: `%s`\n' "$delay_seconds"
      printf -- '- first failure evidence: `%s/first-failure/`\n' "$artifact_dir"
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

snapshot_test_artifacts() {
  local destination="$1"
  mkdir -p "$destination"
  while IFS= read -r -d '' file; do
    local relative_path="${file#./}"
    local target="$destination/$relative_path"
    mkdir -p "$(dirname "$target")"
    cp "$file" "$target"
  done < <(
    find . -type f \( \
      -path './build/test-results/test/*' -o \
      -path './*/build/test-results/test/*' -o \
      -path './build/reports/tests/*' -o \
      -path './*/build/reports/tests/*' \
    \) -print0
  )
}

is_known_infrastructure_failure() {
  local log_file="$1"
  grep -Eiq \
    'Could not start container|ContainerLaunchException|Could not connect to Ryuk|DockerClientException|Error response from daemon|Cannot connect to the Docker daemon|operation not supported|Wait strategy[^[:cntrl:]]*(failed|timed out)|timed out waiting for|Connection (refused|reset)|Failed to connect to|Network is unreachable|Temporary failure in name resolution|Could not resolve host|Could not GET|Read timed out|HTTP (429|502|503|504)' \
    "$log_file"
}

run_attempt() {
  local label="$1"
  shift
  local log_file="$artifact_dir/${label}.log"
  printf 'Running %s: ' "$label"
  printf '%q ' "$@"
  printf '\n'
  set +e
  "$@" 2>&1 | tee "$log_file"
  local status="${PIPESTATUS[0]}"
  set -e
  return "$status"
}

command=("$@")
if run_attempt attempt-1 "${command[@]}"; then
  first_failure_status=0
  record_classification pass
  exit 0
else
  first_failure_status="$?"
fi
snapshot_test_artifacts "$artifact_dir/first-failure"

if ! is_known_infrastructure_failure "$artifact_dir/attempt-1.log"; then
  record_classification non-infra-failure
  exit "$first_failure_status"
fi

if [[ -n "$fallback_emulator" ]]; then
  fallback_command=("${command[@]}")
  replaced_emulator=false
  for index in "${!fallback_command[@]}"; do
    if [[ "${fallback_command[$index]}" == -Dbluetape4k.aws.emulator=* ]]; then
      fallback_command[$index]="-Dbluetape4k.aws.emulator=$fallback_emulator"
      replaced_emulator=true
    fi
  done
  if [[ "$replaced_emulator" != true ]]; then
    printf 'fallback emulator requested but command has no emulator property\n' >&2
    record_classification invalid-fallback-command
    exit 2
  fi
  if ((delay_seconds > 0)); then
    sleep "$delay_seconds"
  fi
  if run_attempt fallback "${fallback_command[@]}"; then
    fallback_status=0
    record_classification infra-fallback-pass
    exit 0
  else
    fallback_status="$?"
  fi
  snapshot_test_artifacts "$artifact_dir/fallback-failure"
  record_classification infra-fallback-failure
  exit "$fallback_status"
fi

if ((max_attempts < 2)); then
  record_classification infra-no-retry
  exit "$first_failure_status"
fi

if ((delay_seconds > 0)); then
  sleep "$delay_seconds"
fi
if run_attempt attempt-2 "${command[@]}"; then
  retry_status=0
  record_classification infra-retry-pass
  exit 0
else
  retry_status="$?"
fi
snapshot_test_artifacts "$artifact_dir/retry-failure"
record_classification infra-retry-failure
exit "$retry_status"
