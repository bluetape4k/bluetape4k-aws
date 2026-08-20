# Issue #455 SQS Extended Client 구현 review

## 범위

`aws-spring-boot`의 SQS Extended Client, S3 bounded/metadata capability,
auto-configuration, lifecycle/rollback, Jackson 3 safe module, Micrometer,
ABI fixture, README/manual 및 예제를 검토했다.

## 확인 결과

- 기본값은 disabled이며 producer/consumer gate와 queue URL allowlist가
  fail-closed다.
- `256 KiB` threshold 이하 payload는 SQS body를 그대로 사용하고, 초과
  payload는 strict UTF-8·idempotency key·policy fingerprint·HMAC pointer를
  거쳐 S3 upload 후 SQS send 순서를 지킨다.
- receive는 `maxMessages=1`, wait/visibility 범위를 외부 호출 전에 검증하고
  bounded plaintext/encrypted capability가 없으면 복원을 거부한다.
- SQS delete 성공 이후에만 marker를 확인하고 payload를 삭제한다. marker
  mismatch와 cleanup failure는 payload를 보존하고 retryable opaque handle을
  반환한다.
- `SqsExtendedClientJacksonModule`은 safe DTO만 등록하며 raw AWS model, body,
  pointer, receipt handle과 Java serialization은 경계 밖으로 둔다.
- Micrometer tag는 저카디널리티 enum만 사용하고 queue URL, bucket, key,
  payload, diagnostic code를 tag로 기록하지 않는다.
- rollback은 producer 중지 → legacy consumer 중지 → drain → visibility
  quiescence → rehydrate → legacy 재시작 순서를 상태 evidence로 보존하며,
  deadline/redrive budget 실패 시 legacy 재시작을 금지한다.
- 기존 `SqsOperations`와 `S3Operations` public ABI는 pre-change source,
  bytecode checksum 및 normalized `javap` signature fixture로 검증한다.

## 검증 근거

| 검증 | 결과 |
| --- | --- |
| `:bluetape4k-aws-spring-boot:test` | 500 passing |
| Jackson 3 auto-config targeted test | 4 passing |
| pointer/bounded capability targeted test | 6 passing |
| `detekt` | BUILD SUCCESSFUL |
| `verifySqsExtendedLegacyAbi verifyS3ExtendedLegacyAbi` | BUILD SUCCESSFUL |
| manual inventory/contract/manifest | 모두 통과 |
| `git diff --check` | 통과 |

## 보류 및 위험

- 실제 외부 publisher latency/cleanup telemetry와 heap·throughput 수치는
  Issue [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)의
  재현 가능한 benchmark 범위로 분리했다. 이번 구현의 완료 근거로 절대 성능
  수치를 주장하지 않는다.
- Floci 전체 SQS Extended Client round-trip smoke는 현재 capability 범위와
  별도 emulator acceptance gate로 유지한다.
- human review gate는 1인 개발자 저장소 정책에 따라 N/A이며, 설계·계획 승인,
  required CI, exact-head merge 승인은 별도 gate다.

Final status: PASS — 구현 범위의 정적·단위·module·ABI·문서 계약 검증 완료.
