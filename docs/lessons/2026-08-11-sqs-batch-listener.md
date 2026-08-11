# #454 SQS batch listener 구현 교훈

## 결정

- 기존 단건 listener는 유지하고 `batch = true` endpoint만 별도 poll 결과 경로로 라우팅한다.
- batch 상태는 `Mutex`로 reservation/외부 AWS 호출/commit을 분리한다. 항목별 성공만 terminal 처리하고 실패·미확인 항목은
  pending으로 되돌린다.
- `SqsOperations`에는 default batch fallback을 두어 pre-change 구현체의 ABI를 보존한다.
- 컨테이너는 `RUNNING -> STOPPING_RECEIVE -> DRAINING -> STOPPED` fence를 사용해 stop 중 새 generation과 새 AWS call을
  막는다.

## 검증 증거

- `SqsBatchAcknowledgementTest`: 전체 성공, partial delete, FIFO predecessor, concurrent duplicate ack, nack/visibility,
  redaction을 검증했다.
- `SqsMessageListenerContainerTest`: injected dispatcher, 한 poll 응답 한 batch invocation, stop drain/timeout을 검증했다.
- `SqsMessageListenerContainerRegistryTest`: asynchronous stop 중 `start(id)`를 `listener is stopping`으로 거부하고
  STOPPED callback 뒤 새 generation을 허용한다.
- `SqsBatchPerformanceTest`: 동일 test-owned dispatcher와 fake AWS boundary에서 warmup 3회/measurement 10회,
  batch size 1/10, Micrometer on/off, optimized batch call 상한을 기록한다.
- `SqsBatchListenerAwsEmulatorTest`: Floci 1.6.0에서 10개 메시지가 유효한 1..10개 batch로 전달되고
  처리 후 삭제되는 실제 SDK 경로를 통과했다. ReceiveMessage는 요청한 최대치보다 적게 반환할 수 있으므로
  단일 poll 호출 횟수는 계약으로 고정하지 않고 전체 메시지·batch 상한·최종 queue empty를 검증한다.
- emulator 실행은 `-PskipAwsEmulatorTests=true` guard를 사용하며, Floci capability 결과는
  `.bluetape/evidence/issue-454/floci/capability-gap.json`과 batch stdout에 authoritative proof로 남긴다.

## 남은 위험과 guard

- SQS는 at-least-once이므로 삭제 응답 유실 시 이미 삭제된 메시지가 다시 보이지 않을 수 있다. consumer side effect는
  idempotent 또는 deduplicated여야 한다.
- batch size 1에서 Micrometer와 container lifecycle overhead가 단건보다 커질 수 있다. 성능 artifact의 p95/allocated
  비교를 release 전 동일 JVM 조건에서 다시 확인한다.
- `SqsBatchDeleteProtocolException`/`SqsBatchVisibilityProtocolException`이 발생하면 항목을 terminal 처리하지 않고
  retry/DLQ 정책으로 보낸다.
- heartbeat/장시간 visibility 연장은 PR #1622의 방향을 참고하되, batch acknowledgement와 섞지 않고 후속 이슈로
  분리한다.

## 운영 rollback

receive 중지 → in-flight drain → `STOPPED` 확인 → 마지막 정상 단건 handler 배포 → DLQ redrive → idempotency 검증 순서를
지킨다. `stopTimeoutMillis=30_000`, partial failure `>1%/5m`, retry exhaustion `>0.1%/5m`, redelivery age p95
`>80% visibility/5m`, DLQ visible `>0/5m`이면 canary를 중단한다. 온콜 owner는 `bluetape4k-sqs-oncall`, 승인자는
`bluetape4k-release-approvers`다.
