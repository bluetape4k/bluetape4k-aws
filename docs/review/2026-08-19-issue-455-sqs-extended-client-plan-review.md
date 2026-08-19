# Issue #455 SQS Extended Client 구현 계획 통합 검토

## 검토 범위

- 승인 설계: `docs/superpowers/specs/2026-08-19-issue-455-sqs-extended-client-design.md`
- 구현 계획: `docs/superpowers/plans/2026-08-19-issue-455-sqs-extended-client-plan.md`
- 설계 checkpoint: `048b18b`
- 최종 계획 SHA-256: `f9ed64d5e65bdbef882dc1b8bfc7ff72a4bcd033e3cde72cc62a69554a26233c`
- 최종 계획: 275 lines, Markdown fence 8개/균형, `git diff --no-index --check` 통과
- 검토 범위: API/ABI, 운영/lifecycle/rollback, security/redaction/encryption/IAM, 문서/사용자 계약, 성능·자원 경계와 stacked train traceability
- 실행 범위: read-only 문서·소스 대조. 구현 코드, Gradle build, emulator, AWS, GitHub mutation은 실행하지 않았다.

## 독립 관점 판정

| 관점 | 판정 | 확인 내용 |
|---|---|---|
| API·ABI | PASS | 기존 `SqsOperations`/`S3Operations` 불변, `SqsFullRequestOperations` additive marker, S3 capability 파일별 구현, `MicrometerFullRequestSqsOperations` marker wrapper, root Gradle ABI verify task/fixture 경로, policy fingerprint golden vector와 targeted 명령을 고정했다. |
| 운영·안정성 | PASS | `SmartLifecycle` API/phase/idempotency, timeout retry, drain 순서, rollback state machine, visibility/DLQ probe, `ROLLBACK_BLOCKED`, retention/deadline 교차 검증과 JSON evidence를 RED→GREEN으로 추적한다. |
| 보안·사용자·문서 | PASS | Jackson 3 safe module 경계, 임의 `ObjectMapper` 비보장, Java serialization/raw throwable/CRLF negative test, IAM least-privilege ARN/context, KMS identity mismatch, EN/KO parity와 writer SPW evidence를 명시했다. |
| 성능·자원 | PASS | bounded preflight와 `max+1` read, low-cardinality metrics, background scope 금지, 외부 publisher latency/cleanup telemetry·heap·throughput은 #515 후속으로 분리했다. |
| workflow·stacked train | PASS | SQS-5a→5b→5c 의존 순서, RED→GREEN·exact command, workflow receipt, human review N/A(1인 개발자), issue/PR metadata mirror와 최종 `## DoD Status`를 고정했다. |

## 이전 BLOCK과 수정 결과

1. lifecycle 테스트가 phase만 추적하던 문제를 `isAutoStartup`, `isRunning`, `start`, 두 `stop` overload, callback 1회, timeout running/client 유지와 idempotent retry 테스트명으로 보강했다.
2. runtime rollback을 별도 RED task로 분리하고 quiescence, `ApproximateReceiveCount`/`RedrivePolicy`/DLQ guard, observation/global deadline, rehydration, `ROLLBACK_BLOCKED`, legacy start 금지를 GREEN 구현과 연결했다.
3. retention/release acceptance에 marker/payload 동일 age, `SqsExtendedClientRetentionEvidenceTest`, `validate_release_manuals.rb`, explicit AWS fallback 명령과 `.bluetape/issue-455-docs-acceptance.json` 필드를 추가했다.
4. Jackson/redaction와 IAM/KMS 계약을 safe DTO/raw exclusion, raw cause/suppressed/stack/`CompletionException`/CRLF negative test, queue/bucket/prefix/CMK ARN 및 exact encryption-context 조건으로 구체화했다.
5. SQS/S3 파일 경계를 현재 소스 구조에 맞게 정정하고, `SqsOperations.kt`는 변경하지 않도록 고정했다. `MicrometerFullRequestSqsOperations.kt`와 S3 wrapper 경계를 명시했다.
6. policy fingerprint exact fixture는 `Base58.randomString(16)`과 분리했다. random은 opaque identity에만 사용하고 canonical vector는 고정 상수로 유지한다.

## P0–P3 결과

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 최종 판정: **PASS — 계획 구현 준비 완료**

실제 구현·테스트·emulator·AWS acceptance는 아직 실행하지 않았다. 따라서 이 문서는 계획 gate의 PASS이며 구현 완료나 CI green을 의미하지 않는다. 사용자 계획 승인과 계획/review checkpoint commit 후에만 SQS-5a RED 테스트를 시작한다.

## 고정된 후속 범위

- #514: Spring Cloud AWS식 public `BatchExecutionStrategy`·converter SPI와 일반 retry/backend capability 비교.
- #515: 외부 publisher latency/cleanup telemetry, p50/p95/p99, cleanup age/error rate, allocation/retained root, heap/GC, throughput matrix. #455는 bounded 구조와 호출 순서만 검증하며 이 수치를 완료 조건으로 주장하지 않는다.

## 계획 gate DoD

- [x] 승인 설계·review와 계획의 파일 경계·traceability 대조
- [x] API/ABI·운영·보안/문서·성능 관점 독립 review와 P0–P3 중복 제거
- [x] RED→GREEN, stacked train, rollback, Floci/ABI/manual evidence 명시
- [x] `git diff --check`, fence balance, plan SHA read-back
- [ ] 사용자 계획 승인
- [ ] 계획/review Lore checkpoint commit
- [ ] 구현·테스트·CI·PR/merge DoD
