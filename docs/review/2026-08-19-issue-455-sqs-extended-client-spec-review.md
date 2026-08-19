# Issue #455 SQS Extended Client 설계 통합 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-19-issue-455-sqs-extended-client-design.md`
- 기준: `develop` `81a77815c971d2b0d5bc9306aca15b3245949b41`
- 방식: 성능·안정성·보안·운영·개발자/API·사용자/문서의 독립 read-only review
- 변경 경계: spec과 review artifact만 허용. production/test/GitHub/build 변경 없음.
- 1인 개발자 저장소이므로 human review gate는 N/A이며, 설계·계획·CI·exact-head
  merge gate는 별도로 유지한다.

## 독립 review 결과

| 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 3 | 3 | 0 | BLOCK |
| 안정성 | 0 | 5 | 3 | 0 | BLOCK |
| 보안 | 0 | 6 | 2 | 0 | BLOCK |
| 운영 | 0 | 7 + 조건부 1 | 3 | 1 | BLOCK |
| 개발자/API | 1 | 8 | 3 | 0 | BLOCK |
| 사용자/문서 | 0 | 5 | 4 | 0 | BLOCK |

### 공통 차단 사유

1. 기존 spec의 pointer parser와 `cleanup(pointer)`는 queue policy와 무결성에
   묶이지 않아 foreign S3 object read/delete가 가능했다.
2. SQS send 불확실성, cancellation, S3 delete failure의 caller-visible result와
   cleanup 권한이 서로 모순됐다.
3. `defaultPolicy` 전체 queue 적용, policy 없는 queue의 pointer parsing,
   mixed consumer rollout, FIFO/idempotency, visibility·payload 상한이 고정되지
   않았다.
4. public model의 generated `toString`, raw SDK cause/stack trace, strict UTF-8,
   Spring auto-configuration imports/order, canonical manual/example acceptance가
   명확하지 않았다.

## 반영한 설계 수리

- pointer envelope를 version `2` authenticated HMAC으로 바꾸고 queue URL,
  bucket, key, content type, encrypted flag, canonical encryption context를
  signature 대상에 포함했다. receive/cleanup 직전에 exact policy와
  normalized keyPrefix를 재검증한다.
- public `cleanup(pointer)`를 제거하고 SQS delete 성공 뒤에만 발급하는 opaque
  `SqsExtendedCleanupHandle`로 제한했다. ambiguous send/cancellation은
  lifecycle-only orphan으로 남긴다.
- offload에는 필수 `idempotencyKey`와 deterministic object key를 요구하고,
  `maxOffloadPayloadBytes=64 MiB`, extended `maxMessages=1`, strict UTF-8,
  `Dispatchers.Default` 암복호화 경계를 추가했다.
- `defaultQueueUrls` exact allowlist, logical-name map 규칙,
  `orphanRetentionHours`, signing key validation을 추가했다.
- `CancellationException` 재전파와 ACK 이후 `NonCancellable` cleanup,
  redacted typed exception·bounded diagnostic code·low-cardinality metrics를
  고정했다.
- producer disable → consumer drain/redrive → extended consumer 배포 → producer
  enable 순서, rollback, S3 lifecycle/IAM/KMS 조건, README·manual EN/KO·runnable
  example acceptance를 추가했다.
- stacked train 명칭을 SQS-5a/SQS-5b/SQS-5c로 정정했다.

## 남은 검토 게이트

## r2 fresh review 결과

material한 API·wire·운영 계약 수리 후 동일 6개 관점의 독립 review를 다시
수행했으며, helper receipt sequence `70`까지 완료 증거를 기록했다.

| 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 4 | 2 | 0 | BLOCK |
| 안정성 | 0 | 2 | 0 | 0 | BLOCK |
| 보안 | 0 | 3 | 1 | 0 | BLOCK |
| 운영 | 0 | 6 | 1 | 0 | BLOCK |
| 개발자/API | 0 | 5 | 3 | 0 | BLOCK |
| 사용자/문서 | 1 | 2 | 2 | 0 | BLOCK |

반복해서 확인된 수리 요구는 다음과 같다.

- full-request delegate capability를 additive marker, Micrometer/custom
  wrapper 전파, auto-config detection, inherited-default negative test까지
  실제 API 계약으로 닫는다.
- queue-specific/default allowlist resolution table, acknowledgement token의
  forged-copy/foreign-message 재검증, public exception/result hierarchy와
  cleanup failure outcome을 하나의 observable contract로 고정한다.
- Java serialization은 지원하지 않는 대신 현재 `compileOnly` Jackson 3
  경계의 explicit redaction module/DTO를 사용하고, raw AWS model·secret이
  supported serializer에 나오지 않음을 검증한다.
- producer/consumer rollback은 legacy pointer auto-ACK를 막는
  stop→drain/quarantine→pointer=0·inFlight=0 확인→redrive 상태 기계여야
  하며, shutdown drain ownership·timeout·close 순서를 실행 가능한 테스트와
  manual로 증명한다.
- marker/tombstone, retention/visibility 관계, bounded payload materialization,
  encrypted send/receive dispatcher, 고정 metric schema, CMK/context identity,
  Floci capability evidence를 acceptance에 포함한다.

현재 r2 findings는 명세에 반영 중이며, fresh r3 review에서 P0=0/P1=0이
확인되기 전에는 설계 PASS나 계획 단계로 승격하지 않는다. P2(외부 publisher
latency/cleanup telemetry, 실제 heap·throughput 측정)는 후속 Issue #515
범위로 유지하되, 이번 구현에 필요한 low-cardinality failure/offload metrics는
포함한다.

## r3 사용자/문서 review와 보완 상태

r3 사용자/문서 lane은 다음 차단을 발견했다. 첫 판정은
P0=1/P1=2/P2=2/P3=0 BLOCK이었다.

- mixed-consumer rollback에서 quarantine pointer를 native redrive하면 legacy
  `@SqsListener`가 pointer를 성공 처리할 수 있으므로, `QUARANTINE_REHYDRATING`
  단계에서 extended consumer가 payload를 inline으로 재발행한 뒤에만 원본을
  삭제해야 한다.
- `receiveFlow` collect admission과 counter 종료, drain 이후 새 collect의
  `SQS_EXT_DRAIN_002`가 명시되어야 한다.
- `docs/manual/en|ko/modules/aws-spring-boot-sqs-examples.md`, 정확한
  `aws-spring-boot/README.ko.md`, example configuration 경로,
  EN/KO semantic parity와 releaseRef/source-link/compile/smoke 산출물이
  acceptance에 포함되어야 한다.

위 세 범주의 수리는 spec에 반영했다. rollback은 publish-before-delete,
rehydrated/destination/pointer count gate, visibility-window quiescence,
idempotency 및 native-redrive rejection을 추가했고, flow는 collect 시점
admission·`finally` 감소·post-drain 거부를 추가했다. 문서는 exact manual 및
`examples/aws-spring-boot-sqs-examples/src/test/resources/application-extended.yml`
경로, semantic parity matrix와 다섯 개 실행 명령/산출물 매핑을 추가했다.
이후 API·운영·성능·안정성·보안 r3 재검토가 모두 끝날 때까지 이 artifact의
최종 상태는 BLOCK이다.

## r3 운영/성능 재검토에서 추가된 수리

r3 운영 lane은 policy fingerprint canonicalization, marker metadata capability,
marker conditional-create, retention evidence, encrypted smoke 범위와 docs
mapping을 추가로 요구했다. 성능/사용자 lane은 encrypted `ByteArray`의
bounded-read capability, raw rollback probe의 deadline, Spring lifecycle
timeout의 동기 blocking 상한을 추가로 요구했다.

최신 spec은 다음으로 수리했다.

- `policyFingerprint`는 고정 domain·ordered length-prefixed tuple·SHA-256
  unpadded base64url로 계산하고 field mutation/map reorder 테스트를 요구한다.
- `S3ObjectMetadataOperations`와 `S3BoundedEncryptedReadOperations`를
  additive capability로 두고, `deleteOnAck`/encryption policy가 capability
  없으면 fail closed한다. marker는 HEAD 비교·conditional create·metadata
  mismatch 시 payload delete 금지를 고정한다.
- rollback raw probe는 admission/counter/delete를 건드리지 않으며,
  `clock.now >= probeDeadline` 이후에만 `DRAIN_VERIFIED`가 된다.
- lifecycle drain 기본값은 20초, 최대 25초이며 Spring phase timeout보다 5초
  이상 짧아야 한다. context가 유지되는 동안 retry ownership을 보장하고,
  Spring force-close는 별도 `forcedContextClose` event로 분류한다.
- retention evidence command와 EN/KO `testing-and-operations` parity를
  추가했으며 실제 AWS encrypted smoke는 optional임을 명시했다.

이후 API·안정성·보안 r3 lane의 최신 read-only 판정 및 duplicate/traceability
검증이 남아 있으므로 설계 gate는 계속 BLOCK이다.

## 검증

- `git diff --check` PASS
- `audit-korean-terms.mjs` PASS (`findings=0`)
- production/test/build/GitHub mutation: 미수행
- 최종 상태: **BLOCK — r3 독립 재검토 및 보완 검증 대기**

## r3 최신 보완 라운드

r3 재검토 중 발견된 lifecycle·rollback·API·성능 차단을 최신 spec에
반영했다. 이 라운드에서도 read-only review와 문서 정합성 검증만 수행했으며,
production/test/build/GitHub mutation은 하지 않았다.

- lifecycle bridge는 concrete `SqsExtendedClient`에만 붙고, custom interface
  bean을 오인하지 않는다. `SqsExtendedLifecycleBudgetCondition`과
  `SqsExtendedLifecycleOrderCondition`, 명시적 `PHASE/getPhase`를 함께 사용해
  phase 충돌은 `SQS_EXT_CONFIG_001`로 fail closed한다.
- send 실패는 `upload()`/`inlineSqs()`/`offloadedSqs()` factory throw 계약으로
  통일했고, Micrometer markerless/full wrapper의 template-bound back-off 및
  `@ConditionalOnAwsEnabled` 표현을 로컬 관례와 맞췄다.
- rollback probe는 `ApproximateReceiveCount`, `RedrivePolicy`, DLQ/quarantine
  count를 기록하고, malformed/unknown policy·redrive budget 초과·전체 deadline
  초과를 `ROLLBACK_BLOCKED`로 고정한다. observation window 재시작은 global
  deadline을 늘리지 않는다.
- bounded plaintext read는 `1..67_108_864`, AES-GCM ciphertext read는
  `1..67_108_880`(`+16`)으로 분리하고 `Long` 기반 `max + 1` guard를 사용한다.
  effective rollback deadline은 `orphanRetentionHours * 3_600`보다 짧아야 하며
  이를 넘는 explicit/derived 값은 거부한다.

최신 r3 독립 lane 상태:

| 관점 | 최신 판정 | 근거 |
|---|---|---|
| 안정성 | PASS | explicit lifecycle phase/order, rollback redrive guard/deadline |
| 보안 | PASS | raw AWS/Throwable redaction, Jackson 3, marker/identity tamper boundary |
| 운영 | PASS | retention/Floci/IAM/KMS/metrics/manual acceptance |
| 사용자/문서 | PASS | lifecycle custom-bean gate, bounded capability, EN/KO parity, #515 boundary |
| 개발자/API | PASS | lifecycle contract, drain/result invariants, safe attribute copy, wrapper/condition/factory alignment |
| 성능/리소스 | PASS | plaintext/ciphertext bounds, `Long + 1`, retention-coupled bounded rollback deadline |

최신 설계 기준 데이터(`41d9ab4a49589d38f9e4fc9c11b658a2190a8c726758f8a677dc84c33622fd0b`)
(2,131 lines)에서 여섯 관점 모두 P0=0/P1=0이며 P2/P3도 차단 사유가 없다.
따라서 설계 review gate는 **PASS**로 승격한다. read-only review 단계이므로
Gradle/Floci/AWS/manual acceptance 실행과 runtime evidence JSON은 구현 단계
gate로 남긴다. 외부 publisher latency/cleanup telemetry와 실제 heap·throughput
측정은 Issue #515의 후속 범위이며, 이번 구조적 bounded/counter 계약의 대체
증거로 사용하지 않는다.
