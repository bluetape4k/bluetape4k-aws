#!/usr/bin/env bash
set -euo pipefail

# 동일한 JMH jar와 fake 입력 행렬을 사용해 throughput/latency 원시 결과를 만든다.
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUN_ID=${1:?사용법: run_sns_batch_benchmark.sh <run-id>}
RESULT_DIR="$ROOT_DIR/.omx/self-improve/tracking/raw/$RUN_ID"
JAR_DIR="$ROOT_DIR/aws-spring-boot/build/benchmarks/benchmark/jars"

mkdir -p "$RESULT_DIR"
COMMON_ARGS=(
    'io.bluetape4k.aws.spring.sns.SnsBatchBenchmark.publishBatch'
    -p 'entryCount=1,10,11,20,21,100'
    -p 'maxInFlightBatches=1,2,4'
    -p 'scenario=success,transport'
    -wi 1 -i 3 -f 1 -w 1s -r 1s
    -foe true -rf json
)

# 소스와 JMH jar가 같은 커밋을 가리키도록 매 실행 전에 jar를 재생성한다.
"$ROOT_DIR/gradlew" :bluetape4k-aws-spring-boot:benchmarkBenchmarkJar \
    --no-daemon --max-workers=1 --no-configuration-cache \
    >"$RESULT_DIR/benchmark-build.log" 2>&1
JARS=()
while IFS= read -r jar_path; do
    JARS+=("$jar_path")
done < <(find "$JAR_DIR" -maxdepth 1 -type f -name '*-JMH.jar' -print | sort)
if [[ "${#JARS[@]}" -ne 1 ]]; then
    printf 'JMH jar를 정확히 하나 찾지 못했습니다: %s\n' "${#JARS[@]}" >&2
    exit 2
fi
JAR=${JARS[0]}

java -jar "$JAR" "${COMMON_ARGS[@]}" \
    -prof gc \
    -rff "$RESULT_DIR/throughput.json"

java -jar "$JAR" "${COMMON_ARGS[@]}" \
    -bm avgt \
    -rff "$RESULT_DIR/latency.json"

cat >"$RESULT_DIR/environment.json" <<EOF
{
  "run_id": "${RUN_ID}",
  "commit": "$(git -C "$ROOT_DIR" rev-parse HEAD)",
  "jdk": "$(java -version 2>&1 | head -1)",
  "os": "$(uname -a)",
  "gradle": "$(cd "$ROOT_DIR" && ./gradlew --version | rg '^Gradle ' | head -1)",
  "jar": "${JAR#$ROOT_DIR/}",
  "publisher": "deterministic-fake",
  "matrix": {
    "entryCount": [1, 10, 11, 20, 21, 100],
    "maxInFlightBatches": [1, 2, 4]
  }
}
EOF

python3 "$ROOT_DIR/scripts/benchmarks/parse_sns_batch_benchmark.py" \
    --input "$RESULT_DIR/throughput.json" \
    --input "$RESULT_DIR/latency.json" \
    --require-complete-matrix \
    --output "$RESULT_DIR/summary.json"

printf 'SNS batch benchmark evidence: %s\n' "$RESULT_DIR"
