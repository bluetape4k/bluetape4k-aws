#!/usr/bin/env bash
set -euo pipefail

# 실제 Floci SNS publisher 경로의 36셀 측정과 저카디널리티 telemetry를 보존한다.
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUN_ID=${1:?사용법: run_sns_batch_floci_measurement.sh <run-id>}
RESULT_DIR="$ROOT_DIR/.omx/self-improve/tracking/raw/$RUN_ID"
DEFAULT_DOCKER_SOCKET="$HOME/.colima/default/docker.sock"
DOCKER_SOCKET="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-$DEFAULT_DOCKER_SOCKET}"
if [[ ! -S "$DOCKER_SOCKET" && -S "$DEFAULT_DOCKER_SOCKET" ]]; then
    DOCKER_SOCKET="$DEFAULT_DOCKER_SOCKET"
fi

if [[ ! -S "$DOCKER_SOCKET" ]]; then
    printf 'Colima Docker socket을 찾지 못했습니다: %s\n' "$DOCKER_SOCKET" >&2
    exit 2
fi

mkdir -p "$RESULT_DIR"
printf '%s\n' "실행: $RUN_ID" >"$RESULT_DIR/command.txt"
printf '%s\n' "backend=floci" >>"$RESULT_DIR/command.txt"
printf '%s\n' "matrix=success,transport × entryCount[1,10,11,20,21,100] × maxInFlight[1,2,4]" >>"$RESULT_DIR/command.txt"
printf '%s\n' "warmups=1 repetitions=3" >>"$RESULT_DIR/command.txt"

JAVA_TOOL_OPTIONS_VALUE="${JAVA_TOOL_OPTIONS:-} -Dbluetape4k.aws.sns.real-measurement=true -Dbluetape4k.aws.sns.measurement.output=$RESULT_DIR"
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS_VALUE"
export DOCKER_HOST="unix://$DOCKER_SOCKET"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$DOCKER_SOCKET"
export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
export TESTCONTAINERS_REUSE_ENABLE="${TESTCONTAINERS_REUSE_ENABLE:-true}"

(
    cd "$ROOT_DIR"
    ./gradlew :bluetape4k-aws-spring-boot:test \
        --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateAwsEmulatorTest.measure actual SNS batch publisher and write Floci artifacts' \
        --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache
) >"$RESULT_DIR/measurement.log" 2>&1

python3 "$ROOT_DIR/scripts/benchmarks/parse_sns_batch_benchmark.py" \
    --input "$RESULT_DIR/throughput.json" \
    --input "$RESULT_DIR/latency.json" \
    --require-complete-matrix \
    --output "$RESULT_DIR/summary.json"

python3 - "$RESULT_DIR/environment.json" "$RESULT_DIR" <<'PY'
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

environment_path = Path(sys.argv[1])
result_dir = Path(sys.argv[2])
environment = json.loads(environment_path.read_text())
environment.update(
    {
        "run_id": result_dir.name,
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "worktree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], text=True).strip()),
        "jdk": next(
            line.strip()
            for line in subprocess.check_output(
                ["java", "-version"],
                env={**os.environ, "JAVA_TOOL_OPTIONS": ""},
                stderr=subprocess.STDOUT,
                text=True,
            ).splitlines()
            if "version" in line
        ),
        "os": platform.platform(),
        "gradle": next(
            line.strip()
            for line in subprocess.check_output(["./gradlew", "--version"], text=True).splitlines()
            if line.strip().startswith("Gradle ")
        ),
    }
)
environment_path.write_text(json.dumps(environment, ensure_ascii=False, indent=2) + "\n")
PY

printf 'Floci SNS batch measurement evidence: %s\n' "$RESULT_DIR"
