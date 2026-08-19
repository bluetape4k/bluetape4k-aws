# #455 SQS Extended Client 구현 계획

> **구현자 지침:** Type A 계획 gate를 통과한 뒤에만 이 계획을 순서대로 실행한다. 각 단계는 RED 테스트를 먼저 고정하고 GREEN 구현으로 전환한다. 모든 public GitHub·문서 prose는 한국어로 작성하며 API, property key, 명령, URL, 숫자와 issue ID는 그대로 보존한다.

## 목표와 완료 기준

`aws-spring-boot`에 opt-in coroutine-native SQS Extended Client를 추가한다. 기본 SQS/S3 동작과 기존 `SqsOperations`/`S3Operations` ABI를 바꾸지 않으면서, `256 KiB` 초과 payload를 authenticated S3 pointer로 offload하고 수신·ACK·cleanup·drain·rollback 계약을 제공한다.

완료는 다음을 모두 fresh evidence로 증명할 때만 선언한다.

- inline 경로는 기존 SQS body와 FIFO/message attributes를 보존하고 S3 호출이 0회다.
- offload 경로는 strict UTF-8, bounded preflight, deterministic idempotency key, pointer HMAC/version/type 검증, S3 upload → SQS send 순서를 지킨다.
- receive는 `maxMessages=1`과 positive visibility를 외부 호출 전에 검증하고 bounded plaintext/encrypted read capability 없이 fail closed한다.
- SQS delete 성공 전 S3 delete를 하지 않으며, cleanup 실패는 retryable opaque handle로 남긴다.
- `SmartLifecycle` bridge가 producer off → drain → consumer off → managed AWS client destroy 순서를 보장하고 timeout에서 close를 진행하지 않는다.
- Jackson 3는 이미 존재하는 compileOnly 경계에서 safe DTO만 선택적으로 지원하고 raw AWS model/pointer/handle은 serialize하지 않는다.
- Floci 우선 unencrypted smoke, module targeted tests, detekt, manual contract/parity, ABI fixture 검증이 모두 통과한다.
- 외부 publisher latency/cleanup telemetry 및 실제 heap·throughput 측정은 구조 검증과 분리된 후속 Issue [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)로 보존한다. 이번 PR에서 측정 수치를 제품 계약으로 승격하지 않는다.

## 설계·운영 고정점

- 승인 설계: `docs/superpowers/specs/2026-08-19-issue-455-sqs-extended-client-design.md`
- 설계 통합 review: `docs/review/2026-08-19-issue-455-sqs-extended-client-spec-review.md`
- 설계 checkpoint commit: `048b18b`
- live issue: [#455](https://github.com/bluetape4k/bluetape4k-aws/issues/455), assignee `debop`, milestone `0.6.0`
- 후속 계기판: [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)는 public `BatchExecutionStrategy`/converter SPI, [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)는 latency/cleanup telemetry·heap·throughput 실측이다.
- SDK/S3/KMS 의존성은 기존 `compileOnly` 선언을 유지하며 dependency catalog/BOM/버전을 수정하지 않는다.
- 기존 `SqsOperations`와 `S3Operations`에 숨은 decorator나 offload를 넣지 않는다. additive marker/capability interface만 사용한다.
- 1인 개발자 저장소이므로 human review gate는 N/A다. 단, 설계 승인, 이 계획 승인, required CI, exact-head merge 승인은 독립 gate로 유지한다.
- 테스트는 `io.bluetape4k.assertions` 관계·문자열 assertion을 사용한다. `shouldBeTrue`/`shouldBeFalse` 직접 사용을 피하고 `shouldBeLessThanOrEqualTo`, `shouldBeEqualTo`, `shouldContain`, `shouldNotContain` 등 기존 API를 사용한다. 임의 식별자는 `Base58.randomString(16)`을 사용한다.

## 변경 경계

### 새 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedOperations.kt`
  - `SqsExtendedClientOperations`, `SqsFullRequestOperations`와 additive public contracts.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedModels.kt`
  - request/received/send/ack/cleanup result, safe attributes, opaque cleanup handle.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedPointer.kt`
  - authenticated pointer envelope, canonical queue/policy binding, strict parser/codec.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedExceptions.kt`
  - bounded failure/diagnostic enum과 redacted typed exceptions.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedClientProperties.kt`
  - disabled-by-default global/producer/consumer gate, queue policy, retention/deadline/security/encryption binding.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedClient.kt`
  - inline/offload send, receive/restore, cold receiveFlow, ACK/cleanup, drain admission/counter.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedClientLifecycle.kt`
  - fixed `SmartLifecycle` phase, `NonCancellable` stop bridge, timeout event.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedClientAutoConfiguration.kt`
  - class/property/capability/lifecycle conditions and user-bean back-off.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsExtendedClientJacksonAutoConfiguration.kt`
  - Jackson 3 optional safe module only.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ExtendedCapabilities.kt`
  - `S3ObjectMetadataOperations`, conditional put-if-absent marker, bounded plaintext/encrypted read capabilities and identity marker.

### 기존 production 파일의 제한적 수정

- `SqsOperations.kt`: 변경하지 않는다. 기존 source/ABI와 default `send(request)`를 그대로 보존한다.
- `SqsCoroutinesTemplate.kt`: SQS `SqsFullRequestOperations` marker만 구현하며 FIFO/group/deduplication/message attributes를 그대로 전달한다.
- `S3CoroutinesTemplate.kt`, `S3ClientSideEncryptionOperations.kt`/`S3ClientSideEncryptionTemplate`, `MicrometerS3Operations.kt`, `S3MicrometerAutoConfiguration.kt`, `SqsMicrometerAutoConfiguration.kt`: metadata/conditional-create/bounded-read/encryption-identity capability를 marker-preserving wrapper로 전달하고 custom delegate에는 auto wrapper를 만들지 않는다.
- `MicrometerSqsOperations.kt`와 새 `MicrometerFullRequestSqsOperations.kt`: template-bound `@Primary` full-request marker wrapper가 FIFO/group/deduplication/message attributes를 보존하고, custom full/markerless delegate context에서는 두 auto wrapper가 모두 back-off되도록 구현한다.
- `SqsAutoConfiguration.kt`, `S3AutoConfiguration.kt`: 기존 client ownership/destroy method를 유지하면서 capability marker 조건만 보강한다.
- `SqsJacksonMessageConverterAutoConfiguration.kt`, `AutoConfiguration.imports`: Jackson 3 extended module과 extended auto-config를 기존 순서 뒤에 additive 등록한다.
- `build.gradle.kts`: 새 dependency를 추가하지 않는다. `aws-spring-boot/src/abi-fixtures/{sqs,s3}-pre-change`의 source/bytecode checksum과 `javap` signature를 비교하는 `verifySqsExtendedLegacyAbi`, `verifyS3ExtendedLegacyAbi` task를 `build/reports/abi/issue-455/` JSON 산출물과 optional SDK isolation을 포함해 등록한다.
- `aws-spring-boot/build.gradle.kts`: compileOnly/testImplementation 상속을 그대로 유지한다.

### 새/수정 test·fixture 파일

- `SqsExtendedClientPropertiesTest.kt`, `SqsExtendedClientPropertiesRedactionTest.kt`
- `SqsExtendedPolicyFingerprintTest.kt` (고정 16-field typed tuple와 exact byte fixture)
- `SqsExtendedClientPointerTest.kt`, `SqsExtendedClientModelsTest.kt`, `SqsExtendedClientExceptionsTest.kt`
- `SqsExtendedClientDelegateCapabilityTest.kt`, `SqsExtendedClientPolicyResolutionTest.kt`
- `SqsExtendedClientTest.kt`, `SqsExtendedClientAcknowledgementTest.kt`, `SqsExtendedClientBoundedReadCapabilityTest.kt`
- `SqsExtendedClientMetadataCapabilityTest.kt`, `SqsExtendedClientEncryptionIdentityTest.kt`
- `SqsExtendedClientRedactionTest.kt`, `SqsExtendedClientSecurityTest.kt`
- `SqsExtendedClientAutoConfigurationTest.kt`, `SqsExtendedClientJacksonAutoConfigurationTest.kt`
- `SqsExtendedClientLifecycleTest.kt`, `SqsExtendedClientRollbackTest.kt`, `SqsExtendedClientMetricsTest.kt`
- `SqsExtendedClientApiCompatibilityTest.kt`와 `src/abi-fixtures/sqs-pre-change`, `src/abi-fixtures/s3-pre-change`
- `SqsExtendedClientAwsEmulatorTest.kt`, `SqsExtendedClientSnippetCompileTest.kt`, `SqsExtendedClientRetentionEvidenceTest.kt`

### 문서·예제 파일

- `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md`와 대응 `ko` 파일
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`와 대응 `ko` 파일
- `docs/manual/en/modules/aws-spring-boot-sqs-examples.md`와 대응 `ko` 파일
- `docs/manual/en/guides/testing-and-operations.md`와 대응 `ko` 파일
- `examples/aws-spring-boot-sqs-examples/README.md`, `README.ko.md`, `src/test/resources/application-extended.yml`, extended smoke/retention test
- `docs/manual/manifest.yaml` 및 generated manifest가 새 manual을 요구할 때만 갱신한다.
- 구현 완료 후 `docs/lessons/2026-08-19-issue-455-sqs-extended-client.md`와 `docs/review/2026-08-19-issue-455-sqs-extended-client-code-review.md`를 생성한다.

변경하지 않는 것: dependency catalog/BOM, 기존 listener container의 hidden wrapping, AWS Java Extended Client library 의존성, 기존 pointer wire-format 상호운용, 자동 orphan batch deletion, Issue #514/#515의 후속 범위.

## 선행 상태·계획 gate

### Task 0 — 계획 입력·baseline·workflow 확인

- [ ] Issue #455, milestone, assignee, labels와 기존 PR을 `gh`로 live read-back한다. PR/issue 본문은 한국어 형식과 `## DoD Status` 계약을 유지한다.
- [ ] `gno search`에서 `bluetape4k-github`, `bluetape4k-docs`, `bluetape4k-wiki`의 issue-455/SQS/extended/stacked 검색을 실행하고, 결과가 sparse하면 live GitHub/source를 우선 근거로 기록한다.
- [ ] `.bluetape` run `20260819T124325Z-a7673ada`의 `resume-check`와 required lane/check 상태를 확인한다. helper receipt/owner JSONL을 직접 수정하지 않는다.
- [ ] `git status --short --branch`, `git fetch origin develop`, `git diff HEAD..origin/develop`로 baseline drift를 확인한다. tracked drift가 있으면 `merge --ff-only` 후 다시 검증한다. root `.lane-inputs/`와 기존 worktree는 보존한다.
- [ ] 구현 전 resolved dependency graph를 다시 고정한다. `:bluetape4k-aws-spring-boot:dependencyInsight`에서 `aws2.sqs`, `aws2.s3`, `aws2.kms`, `bluetape4k.jackson3`, `kotlinx-coroutines-reactive`의 testRuntimeClasspath 결과와 resolved artifact checksum을 `.bluetape/issue-455-dependencies/`에 보존한다. catalog/BOM 변경은 하지 않는다.
- [ ] 설계 checkpoint `048b18b`와 이 계획을 포함한 plan-review artifact가 tracked commit에 들어가기 전에는 production/test Kotlin을 수정하지 않는다.
- [ ] 계획 review가 P0/P1=0이고 사용자가 이 계획을 승인한 뒤에만 SQS-5a 구현을 시작한다.

계획 gate 증거는 `.bluetape/issue-455-plan-inputs.json`에 command, exit status, head, issue metadata, GNO query, baseline와 함께 기록한다.

## Stacked PR train

Epic #499의 Issue #455 train은 다음 의존 순서를 따른다. 각 PR은 이전 PR의 head를 base로 삼고, merge 후 다음 base를 갱신한다. 최종 PR만 `Closes #455`를 사용한다.

| train | PR 범위 | 주요 산출물 | 선행 조건 | 독립 gate |
|---|---|---|---|---|
| SQS-5a | pointer/model/codec, properties, bounded exceptions, S3 capability contracts | public additive API와 RED→GREEN unit tests | 없음 | compileKotlin, model/property/pointer/redaction tests, ABI diff |
| SQS-5b | inline/offload send, bounded receive/restore, cold flow/admission | `SqsExtendedClient` core adapter | SQS-5a merged head | adapter/payload/partial-failure tests, targeted module tests |
| SQS-5c | ACK/cleanup, encryption identity, auto-config/lifecycle, Micrometer, Floci, docs/examples | production-ready opt-in integration | SQS-5b merged head | ack/lifecycle/auto-config, Floci smoke, detekt, manual/manifest, ABI |

각 train PR의 DoD에는 `Stacked train: SQS-5a` 같은 순번, base/head SHA, linked issue, required checks와 N/A human review를 기록한다. green CI는 merge authority가 아니며, 최종 exact head·CI·mergeability·threads를 다시 읽은 뒤 별도 사용자 merge 승인을 받는다.

## TDD 실행 순서

### Task 1 — SQS-5a RED: public model·property·capability 계약

Files: 위 `SqsExtendedModels.kt`, `SqsExtendedPointer.kt`, `SqsExtendedExceptions.kt`, `SqsExtendedClientProperties.kt`, `S3ExtendedCapabilities.kt`의 대응 test 파일.

- [ ] disabled 기본값, `256 KiB` threshold, `1 MiB` inline max, `64 MiB` payload max, threshold/max 관계, exact queue URL allowlist/중복/non-canonical, producer/consumer gate, visibility minimum, shutdown/rollback/retention 교차 검증의 RED 테스트를 먼저 추가한다.
- [ ] public constructor/copy/equals로 raw SQS/AWS payload가 노출되지 않음, `SqsExtendedMessageAttribute`의 printable `dataType`·CR/LF rejection·`binaryValue` clone-on-read를 테스트한다. payload/pointer의 NUL·strict invalid UTF-8·oversize preflight는 별도 테스트로 분리한다.
- [ ] pointer version/type/base64url/HMAC, queue/policy/bucket/key/content-type/encryption binding, signature mismatch/foreign pointer/duplicate marker, deterministic idempotency key fixture를 고정한다.
- [ ] `SqsExtendedPolicyFingerprintTest`는 map reorder와 field mutation을 비교하고, 16-field canonical vector는 `Base58.randomString(16)`이 아닌 고정 golden byte fixture로 identity·byte order·length prefix를 검증한다. random Base58은 opaque key/queue identity 테스트에만 사용한다.
- [ ] `SqsExtendedSendException.upload()`, `inlineSqs()`, `offloadedSqs()`, cancellation/ack/cleanup/payload-read/configuration/drain timeout factory invariant을 identity와 bounded diagnostic code로 검증한다.
- [ ] `S3BoundedObjectReadOperations`(1..67,108,864), encrypted(1..67,108,880), `Long + 1` overflow guard, `S3ObjectMetadataOperations` conditional-create/marker identity를 RED로 고정한다.
- [ ] 모든 새 assertion은 `shouldBeEqualTo`, `shouldBeLessThanOrEqualTo`, `shouldContain`, `shouldNotContain`을 사용한다. opaque queue/key/attribute identity fixture에는 `Base58.randomString(16)`을 사용하되, policy fingerprint canonical vector와 exact byte fixture는 고정 상수로 유지한다.

Expected RED: 타입/팩토리/조건/능력이 아직 없어서 컴파일 또는 assertion이 실패한다. 실패가 녹색이면 test가 잘못된 경계를 바라보는 것이므로 구현으로 진행하지 않고 test를 보정한다.

### Task 2 — SQS-5a GREEN: additive API·모델·codec·properties 구현

- [ ] `SqsExtendedOperations.kt`에 명시적 coroutine API와 `SqsFullRequestOperations : SqsOperations`를 추가한다. 기존 `send(request)` default 구현은 extended path에 사용하지 않으며 full marker 없는 delegate는 fail closed한다.
- [ ] immutable public DTO와 private constructor/internal factory를 구현한다. `SqsExtendedReceivedMessage`는 `data class`/`copy()`/structural equality를 제공하지 않고 raw AWS model·receipt handle·pointer 원문을 외부로 노출하지 않는다.
- [ ] policy resolver가 queue-specific exact URL → default allowlist → policy 없음 순서를 지키도록 구현한다. 정책 없는 queue의 pointer-looking body는 opaque inline으로 위임한다.
- [ ] strict UTF-8 `CharsetEncoder(REPORT)`, bounded preflight, canonical KMS ARN/fingerprint, typed failure와 redacted diagnostics를 구현한다.
- [ ] `S3ExtendedCapabilities.kt`의 marker/metadata/bounded read/identity capability를 additive로 구현한다. 기본 template은 capability를 제공하고 markerless custom delegate는 extended auto-config에서 제외한다.
- [ ] SQS-5a 범위만 compile/test/detekt하고, public ABI diff에서 기존 `SqsOperations`/`S3Operations` 변경이 0임을 확인한다. 구현 전 RED가 먼저 커밋/증거화되어야 한다.

검증 명령:

```bash
./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientPropertiesTest' --tests '*SqsExtendedClientPropertiesRedactionTest' --tests '*SqsExtendedClientPointerTest' --tests '*SqsExtendedClientModelsTest' --tests '*SqsExtendedClientExceptionsTest' --tests '*SqsExtendedPolicyFingerprintTest' --tests '*SqsExtendedClientDelegateCapabilityTest' --tests '*SqsExtendedClientPolicyResolutionTest' --tests '*SqsExtendedClientBoundedReadCapabilityTest' --no-daemon
./gradlew verifySqsExtendedLegacyAbi verifyS3ExtendedLegacyAbi --no-daemon
./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon
```

### Task 3 — SQS-5b RED: inline/offload send·receive·flow

Files: `SqsExtendedClientTest.kt`, `SqsExtendedClientPolicyResolutionTest.kt` 보강, deterministic fake SQS/S3 fixture.

- [ ] threshold `<=` inline/`>` offload, Unicode byte size, required idempotency key, S3 upload 후 pointer send 순서와 inline S3 호출 0회를 고정한다.
- [ ] S3 upload failure, inline SQS failure, offloaded SQS failure, ambiguous SQS failure/cancellation의 typed exception·orphan invariant을 identity로 검증한다. 실패한 SQS send 결과가 불확실하면 pointer object를 자동 삭제하지 않는다.
- [ ] receive pointer parse/HMAC/content type/attributes/system attributes 복원, forged prefix opaque 처리, malformed pointer/S3 read failure, dishonest HEAD와 oversize plaintext/ciphertext stream에서 full materialization/decode 0회를 검증한다.
- [ ] `maxMessages != 1`, null/0 visibility, configured minimum 미만, `maxOffloadPayloadBytes` 초과를 외부 호출 전에 거부한다.
- [ ] cold `receiveFlow` admission counter, cancellation 재전파, drain 이후 새 collect 거부와 in-flight drain 대기를 RED로 고정한다.

Expected RED: core adapter가 아직 없거나 모든 operation이 unsupported이다. `withTimeout`은 외부 publisher latency를 주장하는 증거가 아니라 completion/admission 경계 확인에만 사용한다. 외부 publisher p50/p95/p99는 #515에서 측정한다.

### Task 4 — SQS-5b GREEN: adapter와 bounded restore 구현

- [ ] `SqsExtendedClient.kt`가 기존 Spring-managed SQS/S3 client를 새로 만들거나 close하지 않고, `SqsFullRequestOperations`로 모든 request field를 보존한다.
- [ ] send state machine을 admission → policy resolve → strict preflight → S3 upload/optional encryption → pointer send 순서로 구현한다. `Dispatchers.Default`는 encryption CPU 구간에만 사용하고 blocking AWS 호출은 기존 suspend/IO 경계를 따른다.
- [ ] `S3BoundedObjectReadOperations`/encrypted capability의 `max+1` probe를 사용해 oversize object를 ByteArray/decode 없이 즉시 중단한다. unbounded `downloadBytes`/`downloadEncryptedBytes`는 pointer path에서 호출하지 않는다.
- [ ] receive/flow 결과는 raw AWS model을 노출하지 않는 safe wrapper로 만들고 duplicate marker, content type, attributes, acknowledgement token을 보존한다.
- [ ] operation counter와 drain admission을 lock-free/structured coroutine 방식으로 구현하되 background scope·shutdown hook은 생성하지 않는다. caller scope가 in-flight operation을 소유한다.
- [ ] SQS-5b unit/targeted tests와 detekt를 통과시키고 train PR에 `Stacked train: SQS-5b (base=SQS-5a head)`를 기록한다.

검증 명령:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientTest' --tests '*SqsExtendedClientPolicyResolutionTest' --tests '*SqsExtendedClientBoundedReadCapabilityTest' --tests '*SqsExtendedClientRedactionTest' --no-daemon
./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon
```

### Task 5 — SQS-5c RED: ACK/cleanup·encryption·auto-config·lifecycle·docs

- [ ] SQS delete 성공 전 S3 delete 0회, ACK 이후 `NonCancellable` cleanup, deleteOnAck false, cleanup failure retryable result+opaque handle, handle 없는 조기 cleanup/foreign marker/duplicate cleanup의 RED 테스트를 추가한다.
- [ ] canonical KMS ARN/fingerprint, encrypted bounded read capability, configured key mismatch, encryption context mismatch, deterministic fake encryption round-trip을 고정한다.
- [ ] alias/wildcard/blank KMS key, `bt4k-cek-key-id`, pointer·S3 metadata·current delegate identity·configured `keyFingerprint` 불일치, bounded encrypted read 부재를 crypto 이전에 fail closed하는 RED/GREEN 테스트를 고정한다.
- [ ] `ApplicationContextRunner`로 imports/order, global/service/extended disabled, phase margin 부족, missing full/bounded/metadata/encryption capability, custom markerless/full/`@Primary`, user extended bean back-off와 wrapper ambiguity 0을 검증한다.
- [ ] `SmartLifecycle` phase와 `isAutoStartup`, `isRunning`, `start`, 두 `stop` overload를 각각 검증하는 `lifecycleReportsAutoStartupAndPhase`, `lifecycleStopCallbackRunsOnceAfterDrain`, `lifecycleTimeoutPreservesRunningAndClientForRetry`, `lifecycleStopIsIdempotent` RED 테스트를 추가한다. 성공 callback은 정확히 1회이고 timeout에서는 running/client가 유지되어 재시도할 수 있어야 한다.
- [ ] four low-cardinality metric names/tag enum, redacted payload/URL/key/diagnostic fields를 고정한다. `SqsExtendedClientJacksonModule`은 지원되는 Jackson 3 module 경계에서 safe DTO만 serialize하고, 임의 `ObjectMapper`는 보장하지 않으며, raw AWS request/response/message/pointer/handle과 Java `ObjectOutputStream` serialization은 negative test로 거부한다. exception/structured log에는 raw cause·suppressed·stack·`CompletionException`·CR/LF가 없어야 한다.
- [ ] EN/KO manual parity, canonical snippet compile, example `application-extended.yml`, Floci command/capability gap, retention evidence와 #515 follow-up 문구를 문서 contract RED로 고정한다.

### Task 6 — SQS-5c RED: runtime rollback state machine

- [ ] `RUNNING_EXTENDED → PRODUCER_DISABLED → LEGACY_CONSUMER_STOPPED → EXTENDED_DRAINING → DRAIN_VERIFIED(pointerCount=0,inFlight=0,drained=true) → QUARANTINE_REHYDRATING → LEGACY_REDRIVE_VERIFIED → LEGACY_CONSUMER_STARTED` 전이를 deterministic fake로 고정한다.
- [ ] `rollbackRequiresVisibilityWindowQuiescence`, `rollbackWaitsUntilVisibilityWindowDeadline`, `rollbackProbeGuardsReceiveCountAndDlq`, `rollbackBlocksAfterGlobalDeadline`, `quarantineRehydrationRestoresInlinePayloadBeforeLegacyStart`, `rollbackDeadlineCannotOutliveOrphanRetention` RED 테스트를 추가한다.
- [ ] raw probe가 `ReceiveMessage(max=1, visibility=0, waitTime=0, ApproximateReceiveCount)`만 읽고 delete/visibility/counter를 변경하지 않으며, malformed/unknown `RedrivePolicy`, DLQ count와 pointer/in-flight gate를 guard하는지 검증한다.
- [ ] observation window 재등장 시 window만 재시작하고 global deadline은 늘리지 않으며, `DEADLINE_EXCEEDED`/`REDRIVE_BUDGET_EXHAUSTED`에서는 `ROLLBACK_BLOCKED`로 legacy start를 금지하는 RED를 고정한다.
- [ ] marker/payload가 동일 S3 lifecycle prefix와 `orphanRetentionHours` minimum age를 사용하고, effective rollback deadline이 orphan retention보다 짧아야 함을 properties·runtime test 양쪽에서 검증한다.

Expected RED: rollback coordinator와 probe/rehydration 구현이 없어 state transition과 deadline assertion이 실패한다. visibility window 외부 지연을 heap/throughput 측정으로 해석하지 않는다.

### Task 7 — SQS-5c GREEN: ACK/cleanup·encryption·auto-config·lifecycle·rollback·docs

- [ ] `acknowledge`는 SQS delete 성공 후 marker-aware conditional payload delete만 수행한다. conditional create/HEAD marker fingerprint가 다르면 payload delete 0회이고 retryable cleanup handle을 반환한다.
- [ ] optional encryption은 기존 `S3ClientSideEncryptionOperations`와 bounded encrypted capability/identity를 재사용한다. wire format은 AWS Java Extended Client와 상호운용하지 않음을 문서화한다.
- [ ] `SqsExtendedClientAutoConfiguration` 및 Jackson auto-config를 existing imports 뒤에 additive 등록한다. method-level conditions가 모든 resolved policy capability와 lifecycle budget을 fail closed로 판정한다.
- [ ] `SqsExtendedClientLifecycle`은 fixed phase로 managed AWS client보다 먼저 stop하고, `isAutoStartup=true`, `isRunning` 상태, `start`, `stop()`, `stop(Runnable)`의 idempotent 전이를 구현한다. 정상 drain callback은 한 번만 호출하고 timeout event는 bounded 진단만 남기며 running/client/close를 유지해 명시적 retry를 허용한다.
- [ ] full-request 및 bounded-capability Micrometer wrapper가 template-bound일 때만 생성되고, custom full/markerless delegate에서는 auto wrapper가 0개가 되도록 한다.
- [ ] rollback coordinator가 visibility quiescence와 `ApproximateReceiveCount`/DLQ guard를 통과한 뒤에만 rehydrate와 legacy start를 수행하도록 구현한다. deadline/budget 실패는 `ROLLBACK_BLOCKED`로 남기고 legacy consumer를 시작하지 않는다.
- [ ] manual EN/KO와 README/example은 동일 property/config/source-link/canonical snippet을 사용한다. `@SqsListener` legacy consumer를 extended pointer queue에 연결하지 않는 경고, IAM/KMS, S3 lifecycle age, rollback, Floci fallback을 모두 포함한다.
- [ ] 문서에는 SQS `SendMessage/DeleteMessage/ReceiveMessage`를 대상 queue ARN에, S3 `PutObject/GetObject/DeleteObject`를 bucket/prefix ARN에, KMS `GenerateDataKey/Decrypt`를 exact CMK ARN과 encryption-context 조건에 제한하는 least-privilege 예시를 넣고 wildcard·foreign bucket/key/CMK를 거부하는 acceptance를 남긴다.

검증 명령:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientAcknowledgementTest' --tests '*SqsExtendedClientMetadataCapabilityTest' --tests '*SqsExtendedClientEncryptionIdentityTest' --tests '*SqsExtendedClientAutoConfigurationTest' --tests '*SqsExtendedClientJacksonAutoConfigurationTest' --tests '*SqsExtendedClientLifecycleTest' --tests '*SqsExtendedClientRollbackTest' --tests '*SqsExtendedClientMetricsTest' --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientRedactionTest' --tests '*SqsExtendedClientSecurityTest' --no-daemon
./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon
```

### Task 8 — emulator·ABI·manual acceptance

- [ ] 실행 전 `docker info`, exact Floci image inspect, emulator property와 credentials endpoint를 evidence에 기록한다. Floci unencrypted smoke만 필수이며 capability gap이면 explicit `-Dbluetape4k.aws.emulator=localstack` fallback을 별도 기록한다. 자동 fallback은 금지한다.
- [ ] `SqsExtendedClientAwsEmulatorTest`에서 256 KiB 아래/위 round-trip, ACK 후 S3 delete, content type/attributes 보존을 검증한다.
- [ ] `SqsExtendedClientApiCompatibilityTest`와 `verifySqsExtendedLegacyAbi`, `verifyS3ExtendedLegacyAbi`가 clean checkout pre-change fixture source/bytecode checksum, `javap` signature, optional SDK isolation을 모두 기록하게 한다.
- [ ] `SqsExtendedClientSnippetCompileTest`와 example smoke가 canonical Kotlin snippet와 `application-extended.yml`을 실제 compile/read-back한다.
- [ ] `manual_contract_test.rb`, `export_manifest.rb --check`, `validate_release_manuals.rb`를 모두 실행해 manifest·heading·source-link·peeled release commit과 EN/KO semantic parity를 검증한다.
- [ ] `SqsExtendedClientRetentionEvidenceTest`가 SQS `MessageRetentionPeriod`/`VisibilityTimeout`, S3 lifecycle prefix/minimum age, configured `orphanRetentionHours`/effective rollback deadline과 marker/payload 동일 age를 비교한다. 결과는 `.bluetape/issue-455-docs-acceptance.json`에 `command`, `exit`, `releaseRef`, `releaseCommit`, `retrievedAt`, `redaction`, `prefix`, `minimumAge`, `policyComparison`, `capabilityGap` 필드로 기록한다.
- [ ] emulator가 lifecycle API를 지원하지 않으면 green으로 가장하지 않고 exact capability gap과 다음 AWS 확인 명령을 같은 JSON에 기록한다: `aws sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names MessageRetentionPeriod VisibilityTimeout`, `aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET"`. URL/bucket secret은 redaction한다. LocalStack은 `-Dbluetape4k.aws.emulator=localstack`을 명시적으로 재실행할 때만 허용한다.
- [ ] 실제 AWS encrypted smoke를 실행하지 않으면 deterministic fake/Floci unencrypted 결과로 대체했다고 주장하지 않고, IAM/KMS 권한·비용·capability gap과 미실행 상태를 `.bluetape/issue-455-docs-acceptance.json`에 남긴다. 실행을 선택한 경우에만 `SqsExtendedClientAwsEncryptedSmokeTest`와 raw artifact를 추가한다.

명령:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientAwsEmulatorTest' -Dbluetape4k.aws.emulator=floci --no-daemon
./gradlew :aws-spring-boot-sqs-examples:test --tests '*SqsExtendedClientExampleTest' -Dbluetape4k.aws.emulator=floci --no-daemon
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsExtendedClientSnippetCompileTest' --no-daemon
./gradlew :aws-spring-boot-sqs-examples:test --tests '*SqsExtendedClientRetentionEvidenceTest' -Dbluetape4k.aws.emulator=floci --no-daemon
ruby scripts/manual/manual_contract_test.rb
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
TAG=0.5.0; SHA=$(git rev-parse "$TAG^{}"); ruby scripts/manual/validate_release_manuals.rb "$TAG" "$SHA"
```

### Task 9 — 통합 검증·review·lesson

- [ ] module targeted/full test, `compileKotlin`, `compileTestKotlin`, detekt, static ABI, docs contract, emulator evidence를 `.bluetape/issue-455-*.json`에 command/exit/head/artifact로 보존한다.
- [ ] `git diff --check`, Markdown fence/link/source scan, Korean terminology audit와 `$bluetape-writer` 관점 read-back을 수행한다. EN/KO는 구조뿐 아니라 semantic parity matrix의 모든 행을 PASS해야 한다.
- [ ] writer evidence에 SPW-01(독자·목적·근거·미확정), SPW-02(artifact 구조), SPW-03(한국어 기술 문체·용어), SPW-04(소스/설계/계획 traceability), SPW-05(최종 read-back/checklist)와 Korean naturalness/terminology audit를 각각 기록한다.
- [ ] 독립 code review artifact `docs/review/2026-08-19-issue-455-sqs-extended-client-code-review.md`에서 API, concurrency/lifecycle, security/redaction, performance/resource, ops, docs/ABI를 P0–P3로 판정한다. 1인 개발자이므로 human review는 N/A라고 기록하되 독립 read-only review는 생략하지 않는다.
- [ ] 구현 변경이 승인 spec의 public contract, bounded capability, lifecycle order, rollback state machine, #515 boundary를 바꾸면 구현을 멈추고 spec/review/사용자 승인을 갱신한다.
- [ ] `docs/lessons/2026-08-19-issue-455-sqs-extended-client.md`에 실제 실패·emulator gap·assertion 선택·운영 후속을 한국어로 기록한다.
- [ ] 모든 required check가 fresh green 또는 명시적 N/A이고 P0/P1=0이면 최종 PR DoD를 작성한다. unchecked item은 숨기지 않고 `PENDING`으로 남긴다.
- [ ] PR/Issue live read-back에서 assignee `debop`, milestone `0.6.0`, labels, linked issue를 서로 mirror하고, 최종 PR body의 마지막 heading이 정확히 `## DoD Status`인지 확인한다. DoD에는 reconciled check totals, evidence table, final status, unchecked items, stacked train/base/head를 포함한다.

## 롤백·복구

- production rollback은 SQS-5a/5b/5c 단위로만 수행한다. pointer/codec만 되돌리고 adapter/lifecycle만 남기는 부분 rollback은 허용하지 않는다.
- 실패 시 해당 train의 마지막 clean commit을 보존하고 RED tests/evidence는 유지한다. production/test 변경을 한 단위로 revert한 뒤 `compileKotlin`과 baseline SQS/S3 tests를 재실행한다.
- auto-config/runtime gate가 의도치 않게 기존 context를 바꾸면 `SqsExtendedClientAutoConfiguration` imports/conditions를 먼저 되돌리고 기존 `SqsAutoConfigurationTest`, `S3AutoConfigurationTest`, listener tests를 통과시킨다.
- Floci가 불안정하면 container를 임의로 교체하지 않고 command/image/exit/capability gap을 기록한다. LocalStack은 explicit fallback일 때만 실행한다.
- workflow receipt 손상 시 helper의 `receipt-diagnose`/recovery 절차를 사용하고 JSONL/owner file을 직접 편집하지 않는다.
- worktree cleanup은 최종 PR merge SHA와 canonical branch sync를 fresh 확인한 뒤에만 수행한다. dirty/default/ambiguous worktree는 삭제하지 않는다.

## 계획 DoD

- [ ] 승인 설계·review와 이 계획의 diff/checksum/read-back 완료
- [ ] plan review artifact 생성 및 P0/P1=0, P2/P3 disposition 기록
- [ ] 사용자 계획 승인 기록
- [ ] SQS-5a/5b/5c 각각 RED→GREEN, exact test command와 train head 기록
- [ ] Floci/ABI/manual/detekt/module checks fresh evidence
- [ ] code review·lesson·PR `## DoD Status` 완료
- [ ] 최종 exact-head merge 승인 전에는 merge/close/cleanup하지 않음

최종 상태는 `DONE`이 되기 전까지 `PENDING`으로 보고한다. 외부 latency/cleanup telemetry와 heap·throughput 실측은 Issue #515가 별도 승인·계획·증거를 갖추기 전까지 #455 완료 조건으로 주장하지 않는다.
