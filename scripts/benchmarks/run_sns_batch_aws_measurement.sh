#!/usr/bin/env bash
set -euo pipefail

# 승인된 계정에서만 실제 SNS batch 경로를 순차 측정한다.
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUN_ID=${1:?사용법: run_sns_batch_aws_measurement.sh <run-id>}
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
    printf 'run-id에는 영숫자·점·밑줄·하이픈만 사용할 수 있습니다.\n' >&2
    exit 2
fi

PREFLIGHT="$ROOT_DIR/scripts/benchmarks/sns_aws_measurement_preflight.py"
REDACTION_CHECKER="$ROOT_DIR/scripts/benchmarks/check_sns_measurement_redaction.py"
PARSER="$ROOT_DIR/scripts/benchmarks/parse_sns_batch_benchmark.py"
RESULT_DIR="$ROOT_DIR/.omx/self-improve/tracking/raw/$RUN_ID"

mkdir -p "$RESULT_DIR"
python3 "$PREFLIGHT" --output "$RESULT_DIR/preflight.json"

# Preflight가 endpoint와 자격증명 환경변수를 차단한 뒤에만 실제 AWS identity를 확인한다.
if ! ACCOUNT_ID=$(AWS_PAGER="" aws sts get-caller-identity --query Account --output text 2>/dev/null); then
    printf '승인된 AWS caller identity를 확인하지 못했습니다. 실제 오류와 계정 ID는 출력하지 않습니다.\n' >&2
    exit 2
fi
if [[ ! "$ACCOUNT_ID" =~ ^[0-9]{12}$ || "$ACCOUNT_ID" != "$BLUETAPE4K_AWS_SNS_ACCOUNT_ID" ]]; then
    printf '승인된 AWS account ID와 caller identity가 일치하지 않습니다. 실제 ID는 출력하지 않습니다.\n' >&2
    exit 2
fi

cat >"$RESULT_DIR/command.txt" <<EOF
run_id=${RUN_ID}
backend=aws
matrix=success,transport x entryCount[1,10,11,20,21,100] x maxInFlight[1,2,4]
warmups=1 repetitions=3
credential_source=AWS_PROFILE
endpoint_override=false
identity=aws sts get-caller-identity (account omitted)
EOF

JAVA_TOOL_OPTIONS_VALUE="${JAVA_TOOL_OPTIONS:-} -Dbluetape4k.aws.sns.real-aws-measurement=true -Dbluetape4k.aws.sns.measurement.backend=aws -Dbluetape4k.aws.sns.measurement.output=$RESULT_DIR -Dbluetape4k.aws.sns.measurement.region=$AWS_REGION -Dbluetape4k.aws.sns.measurement.retention-seconds=$BLUETAPE4K_AWS_SNS_RETENTION_SECONDS"
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS_VALUE"
export AWS_PAGER=""

(
    cd "$ROOT_DIR"
    ./gradlew :bluetape4k-aws-spring-boot:test \
        --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateAwsMeasurementTest' \
        --rerun-tasks --no-daemon --max-workers=1 --no-configuration-cache
) >"$RESULT_DIR/measurement.log" 2>&1

for artifact in throughput.json latency.json environment.json summary.json heap-profile.jfr allocation-summary.json retention.json capability.json; do
    if [[ ! -s "$RESULT_DIR/$artifact" ]]; then
        printf '필수 산출물이 없습니다: %s\n' "$artifact" >&2
        exit 2
    fi
done

python3 "$PARSER" \
    --input "$RESULT_DIR/throughput.json" \
    --input "$RESULT_DIR/latency.json" \
    --require-complete-matrix \
    --output "$RESULT_DIR/summary.json"

# HPROF를 만들지 않고 JFR 메타데이터 파싱만 확인한다. 결과 바이너리는 redaction 검사에서 제외한다.
jfr metadata "$RESULT_DIR/heap-profile.jfr" >/dev/null

python3 - "$ROOT_DIR" "$RESULT_DIR/environment.json" "$RUN_ID" <<'PY'
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
path = Path(sys.argv[2])
run_id = sys.argv[3]
payload = json.loads(path.read_text())
payload["account_id_verified"] = True
payload.update(
    {
        "run_id": run_id,
        "commit": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
        "worktree_dirty": bool(subprocess.check_output(["git", "-C", str(root), "status", "--porcelain"], text=True).strip()),
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
            for line in subprocess.check_output(
                [str(root / "gradlew"), "--version"],
                cwd=root,
                env={**os.environ, "JAVA_TOOL_OPTIONS": ""},
                text=True,
            ).splitlines()
            if line.strip().startswith("Gradle ")
        ),
    }
)
path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
PY

python3 - "$RESULT_DIR" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
for name in ("preflight.json", "throughput.json", "latency.json", "environment.json", "summary.json", "allocation-summary.json", "retention.json", "capability.json"):
    json.loads((root / name).read_text())
PY

python3 "$REDACTION_CHECKER" "$RESULT_DIR"
printf 'AWS SNS batch measurement evidence: %s\n' "$RESULT_DIR"
