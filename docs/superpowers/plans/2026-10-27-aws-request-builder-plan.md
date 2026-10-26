# aws-java Request Kotlin-style builder 함수 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `aws-java`의 8개 AWS service request support 파일에 인자 없는 `fooRequest { }` DSL을 추가하고, 기존 `fooRequestOf(...)`의 필수 정보·validation·호환성을 유지한다.

**Architecture:** 각 SDK Request 타입마다 명시적인 builder-only top-level 함수를 추가한다. 기존 `RequestOf`는 필수·핵심 scalar를 검증하고 builder-only 함수에 값을 주입한 뒤 기존 final validation을 재사용한다. generic/reflection 계층은 도입하지 않는다.

**Tech Stack:** Kotlin/JVM, AWS SDK v2 model builders, JUnit 5, Bluetape assertions, Gradle, Detekt, repository binary-compatibility checks.

---

## 계획 전제와 고정 계약

- 작업 worktree: `.worktrees/feat-issue-665-aws-request-builders`
- branch: `feat/issue-665-aws-request-builders`
- base: `develop` at `81f8e3e1a3c85f1850f4a9533da430bbb06c14c9`
- issue: [#665](https://github.com/bluetape4k/bluetape4k-aws/issues/665)
- `fooRequest { }`는 필수 scalar 인자를 받지 않고 `Request.Builder.() -> Unit`만 받는다.
- `fooRequestOf(...)`는 bucket/key, ARN, name, path, secret id처럼 helper가 책임지는 필수·핵심 정보를 받는다.
- 기존 `RequestOf`에 builder block이 있으면 유지하고, 없는 함수에는 default trailing block을 추가하지 않는다. 기존 호출의 overload ambiguity를 피하기 위해 custom 필드 설정은 `fooRequest { }` 경로로 제공한다.
- `RequestOf`의 block은 기존 함수별 순서와 precedence를 유지한다. SSM, Secrets Manager,
  Step Functions, Lambda, EventBridge, Scheduler, S3 Tables는 scalar를 설정한 뒤 block이
  최종 값을 override하며, helper 필수·limit·상호배타 조건은 build 후 다시 검증한다.
  Bedrock은 `modelId`, `messages`, non-null `inferenceConfig`를 block 뒤에 helper가
  설정하고, 그 외 필드는 block이 최종 값을 결정한다. S3 Tables는 기존 post-build
  validation 순서를 그대로 유지한다.
- `aws-kotlin`, Spring/Ktor, S3 Vectors/DynamoDB Streams 신규 abstraction, dependency/build/catalog 변경은 범위 밖이다.

## 파일 지도

### Production

- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/model/SsmRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/model/SecretsManagerRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/lambda/model/LambdaRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/model/EventBridgeRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupport.kt`

### Tests

- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/lambda/model/LambdaRequestSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeRequestSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupportTest.kt`
- Modify: `aws-java/src/test/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupportTest.kt`

## Task 0: 구현 전 계획 review gate

**Files:**
- Create: `docs/review/2026-10-27-issue-665-plan-review.md`
- Evidence: `.bluetape/evidence/issue-665/`

- [ ] 승인된 설계와 이 계획을 기준으로 performance, stability, security, operator/Ops, developer/API, user/caller 6관점과 main-session integration을 완료한다. 최신 통합 표에서 P0=0, P1=0인지 확인한다.
- [ ] 통합 review에 `SPW-01`~`SPW-05`를 적용하고, 정확한 artifact path, source ledger, traceability, Korean naturalness audit, read-back 결과를 기록한다.
- [ ] `docs/review/2026-10-27-issue-665-plan-review.md`에 각 lane의 판정, 수정·재실행 결과, exact current HEAD, unchecked/N/A 항목, 구현 전 사용자 승인 대기를 기록하고 Lore 형식으로 커밋한다.
- [ ] 위 review artifact 커밋과 사용자의 수정된 설계·계획 승인을 얻기 전에는 RED 테스트나 production code를 변경하지 않는다.

## Task 1: 첫 서비스 slice의 RED 계약과 API inventory 고정

**Files:**
- `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`

- [x] 첫 slice인 SSM·Secrets Manager 테스트에만 builder-only import와 실패하는 호출을 먼저 추가한다. 예를 들어 SSM은 다음 계약을 먼저 작성한다.

```kotlin
import io.bluetape4k.aws.ssm.model.getParameterRequest

@Test
fun `builder-only request helper applies receiver block`() {
    val request = getParameterRequest {
        name("/app/secret")
        withDecryption(true)
    }

    request.name() shouldBeEqualTo "/app/secret"
    request.withDecryption() shouldBeTrue()
}
```

- [ ] 나머지 6개 서비스 테스트는 해당 구현 task의 시작 시점에 추가한다. 한 번에 모든 미구현 심볼을 import하지 않아 다음 slice의 GREEN 컴파일을 막지 않도록 한다.
- [x] RED 명령을 실행한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SsmSupportTest' --tests '*SecretsManagerSupportTest' --no-daemon --console=plain
```

Expected: SSM·Secrets Manager 범위의 `unresolved reference` 또는 동일한 symbol-missing compile failure가 발생하고 production source는 변경하지 않는다.

- [x] RED 로그와 정확한 missing symbol을 `.bluetape/evidence/issue-665/red-helpers.log`에 보존한다.

## Task 2: SSM·Secrets Manager builder-only 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/model/SsmRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/model/SecretsManagerRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`

- [x] SSM의 서로 다른 SDK Request 타입에 다음 builder-only 함수를 추가한다: `getParameterRequest`, `getParametersRequest`, `getParametersByPathRequest`, `describeParametersRequest`, `putParameterRequest`. `putSecureParameterRequestOf`, `putStringParameterRequestOf`, `putStringListParameterRequestOf`는 `PutParameterRequest` specialization/변환 helper로 유지하고 중복 builder-only 이름은 만들지 않는다.
- [x] 각 builder-only 함수는 다음 형태로만 SDK builder를 감싼다.

```kotlin
inline fun getParameterRequest(
    builder: GetParameterRequest.Builder.() -> Unit,
): GetParameterRequest = GetParameterRequest.builder()
    .apply(builder)
    .build()
```

- [x] 기존 `RequestOf` 함수의 scalar validation, callback override 순서, `AwsSecretValue` redaction을 보존하고 단일 builder/build construction 경로를 유지한다. build 후 name/path/collection/value 필수·limit 검증을 추가해 callback이 무효 값을 넣는 우회를 허용하지 않는다.
- [x] Secrets Manager에 다음 builder-only 함수를 추가한다: `getSecretValueRequest`, `batchGetSecretValueRequest`, `listSecretsRequest`, `describeSecretRequest`, `createSecretRequest`, `putSecretValueRequest`.
- [x] 기존 `RequestOf`에 builder block이 없는 함수에는 새로운 default trailing block을 추가하지 않는다. 현재 builder block이 있는 함수만 block을 builder-only 경로에 전달한다.
- [x] 각 테스트 파일에서 builder-only block 적용, required scalar 설정, scalar-vs-block override, callback 무효값의 build 후 거부, 기존 blank/limit failure를 검증한다. `AwsSecretValue` sentinel과 최종 SDK Request `toString()`에 평문이 노출되지 않는지 고정한다.
- [x] `putParameterRequest`, `createSecretRequest`, `putSecretValueRequest`의 secret-bearing builder-only KDoc에는 raw SDK builder가 평문을 보유할 수 있다는 trust-boundary 주의를 기록하고, SecureString 입력에는 `putSecureParameterRequestOf`를 사용하도록 연결한다.
- [x] RED 테스트가 GREEN으로 바뀌는지 두 테스트 파일만 실행한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SsmSupportTest' --tests '*SecretsManagerSupportTest' --no-daemon --console=plain
```

## Task 3: Step Functions·Lambda·Bedrock Runtime 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/sfn/model/SfnRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/lambda/model/LambdaRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/model/BedrockRuntimeRequestSupport.kt`
- Test: 대응하는 3개 `*RequestSupportTest.kt` 파일

- [ ] Step Functions에 `startExecutionRequest`, `stopExecutionRequest`, `describeExecutionRequest`, `listExecutionsRequest`를 추가한다.
- [ ] `startExecutionRequestOf`는 기존 builder construction과 `validateAndNormalize()`를 유지해 null input `{}` 정규화와 name/input 검증을 유지한다.
- [ ] `listExecutionsRequestOf`는 기존 construction 뒤 `validate()`를 호출해 XOR source, redrive 조건, page limit을 유지한다. builder-only 함수 자체에는 helper validation을 추가하지 않는다.
- [ ] Lambda에 `invokeRequest`를 추가하고 `invokeRequestOf`가 payload copy, qualifier, invocation/log 기본값을 주입한 뒤 기존 `InvokeRequest.validate()`를 수행하도록 유지한다.
- [ ] Bedrock Runtime에 `converseRequest`, `converseStreamRequest`를 추가한다. `contentBlockOf`와 `userMessageOf`는 Request 타입이 아니므로 이름과 동작을 변경하지 않는다.
- [ ] Bedrock `RequestOf`의 `modelId`/`messages`/non-null `inferenceConfig` helper precedence와 그 외 builder precedence를 기존대로 유지하고 테스트했다.
- [ ] 각 테스트에 builder-only 단독 block과 final validation 회귀를 추가하고, Step Functions side-effect counter로 receiver block 1회 적용을 고정했다.
- [ ] 세 테스트 파일을 대상으로 GREEN을 확인한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SfnRequestSupportTest' --tests '*LambdaRequestSupportTest' --tests '*BedrockRuntimeRequestSupportTest' --no-daemon --console=plain
```

각 서비스 테스트는 이 task 시작 시 RED 호출을 먼저 추가하고, 해당 서비스 production 함수 구현 후 같은 명령으로 GREEN을 확인한다.

## Task 4: EventBridge Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/model/EventBridgeRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`

- [ ] 다음 builder-only 함수와 기존 `RequestOf` 연결을 추가한다: `createEventBusRequest`, `deleteEventBusRequest`, `putRuleRequest`, `deleteRuleRequest`, `putEventsRequestEntry`, `putTargetsRequest`, `removeTargetsRequest`, `putEventsRequest`, `listRulesRequest`, `listTargetsByRuleRequest`.
- [ ] `targetOf`와 기존 entry helper의 non-Request 계약은 유지하되, `putEventsRequestEntry`는 SQS batch entry 패턴과 동일한 builder-only parity 대상으로 포함한다.
- [ ] `putEventsRequest`는 1..10 entries, `putTargetsRequest`/`removeTargetsRequest`는 1..10 item, list helpers는 1..100 limit을 최종 Request에서 검증한다.
- [ ] `putRuleRequest`의 `eventPattern` 또는 `scheduleExpression` 중 하나 이상 필수 계약을 보존하고, 두 값을 모두 지정하는 기존 동작도 유지한다. `create/delete`의 event-bus/rule `name` 검증을 보존하며, callback이 name/entries/targets/limit을 무효화하면 build 후 거부한다.
- [ ] 테스트에서 builder-only block 반영, `RequestOf`의 callback override, limit 초과와 빈 값 거부를 확인한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeRequestSupportTest' --no-daemon --console=plain
```

테스트 호출은 production 변경 전에 추가해 RED를 확인한 뒤 EventBridge 구현으로 GREEN을 만든다.

## Task 5: EventBridge Scheduler Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupportTest.kt`

- [ ] `createScheduleRequest`, `updateScheduleRequest`, `deleteScheduleRequest`, `getScheduleRequest`, `listSchedulesRequest`, `createScheduleGroupRequest`, `deleteScheduleGroupRequest`, `getScheduleGroupRequest`, `listScheduleGroupsRequest`를 추가한다.
- [ ] `flexibleTimeWindowOf`, `retryPolicyOf`, `deadLetterConfigOf`, `targetOf`는 Request 타입이 아니므로 기존 API를 유지한다.
- [ ] `RequestOf`의 name/expression/ARN/token/page 검증과 schedule default(`flexibleTimeWindowOff`)를 보존하고, callback 무효값에 대한 build 후 검증을 추가한다.
- [ ] 테스트에서 builder-only 필드 적용과 schedule/group required info 경로를 확인하고, list limit·retry/window 범위 실패를 유지한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SchedulerRequestSupportTest' --no-daemon --console=plain
```

테스트 호출은 production 변경 전에 추가해 RED를 확인한 뒤 Scheduler 구현으로 GREEN을 만든다.

## Task 6: S3 Tables Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupportTest.kt`

- [ ] 다음 builder-only 함수를 추가한다: `createTableBucketRequest`, `listTableBucketsRequest`, `getTableBucketRequest`, `deleteTableBucketRequest`, `createNamespaceRequest`, `listNamespacesRequest`, `getNamespaceRequest`, `deleteNamespaceRequest`, `createTableRequest`, `listTablesRequest`, `getTableRequest`, `deleteTableRequest`.
- [ ] 기존 `RequestOf`의 `tableBucketARN`, namespace segments, table selector XOR, format required, page validation을 그대로 재사용한다. S3 Tables는 현재 구현의 post-build validation 순서를 유지하며, blank scalar를 block이 덮어쓰는 경계를 기존 회귀 테스트로 고정한다.
- [ ] 기존 callback override 테스트를 유지하고, `getTableRequest`의 table ARN/path selector 혼합 거부를 확인했다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*S3TablesRequestSupportTest' --no-daemon --console=plain
```

테스트 호출은 production 변경 전에 추가해 RED를 확인한 뒤 S3 Tables 구현으로 GREEN을 만든다.

## Task 7: Cross-service API/test conformance

**Files:** Task 2–6의 production/test 파일 전체

- [ ] 모든 새 public 함수에 한국어 KDoc, 명시적 반환 타입, 기존 module import 규칙을 적용한다. KDoc에는 `fooRequest { }`의 raw builder 책임과 `fooRequestOf(...)`의 required-info/validation 경계를 명시하고, Bedrock helper-owned precedence 예외를 기록한다.
- [ ] 각 `fooRequest`가 receiver block을 실제로 한 번 호출하는지 source inspection과 테스트로 확인한다.
- [ ] 각 `RequestOf`가 주 construction 경로에서 builder를 한 번 생성하고 한 번 `build()`하는지 source inspection으로 확인한다. 위임 중첩으로 이중 builder allocation/build가 생기지 않아야 한다. 단, Step Functions `startExecutionRequestOf`의 기존 `validateAndNormalize()`가 input 생략 시 `{}`를 삽입하기 위해 `toBuilder().input("{}").build()`하는 의도된 normalization rebuild는 예외로 기록하고 회귀 테스트한다.
- [ ] `fooRequestOf`가 없는 scalar overload를 새로 만들어 overload ambiguity를 유발하지 않았는지 `kotlinc`/Gradle compile로 확인한다.
- [ ] 기존 public `RequestOf` 함수의 JVM owner와 descriptor가 유지되는지 ABI task를 먼저 inventory한다.
- [ ] 순수 SDK request object construction 변경이므로 concurrency, coroutine cancellation, client lifecycle, emulator/backend capability는 N/A임을 evidence에 기록하고, 네트워크 호출을 추가하지 않았는지 확인한다.
- [ ] 네트워크 왕복·공유 상태·반복 hot path를 변경하지 않는 순수 object construction이므로 JMH/benchmark는 N/A로 기록하고, `inline` 유지 및 generic/reflection wrapper 부재를 source inspection으로 확인한다.

```bash
./gradlew :bluetape4k-aws-java:compileKotlin --no-daemon --console=plain
./gradlew :bluetape4k-aws-java:tasks --all --no-daemon --console=plain | rg -i 'binary|compat|api'
./gradlew compileAwsJavaServiceConsumerFixture --no-daemon --console=plain
./gradlew compatibilityCheck --no-daemon --console=plain
```

- [ ] `compatibilityCheck`는 repository 공통 compatibility 회귀를 위해 항상 실행하고 결과를 보존한다. 이 task가 8개 `aws-java` facade를 직접 검사하는지와 무관하게, base/head JAR의 다음 8개 top-level facade owner를 `javap -public -s`로 반드시 비교한다: `io.bluetape4k.aws.ssm.model.SsmRequestSupportKt`, `io.bluetape4k.aws.secretsmanager.model.SecretsManagerRequestSupportKt`, `io.bluetape4k.aws.sfn.model.SfnRequestSupportKt`, `io.bluetape4k.aws.lambda.model.LambdaRequestSupportKt`, `io.bluetape4k.aws.bedrock.model.BedrockRuntimeRequestSupportKt`, `io.bluetape4k.aws.eventbridge.model.EventBridgeRequestSupportKt`, `io.bluetape4k.aws.scheduler.model.SchedulerRequestSupportKt`, `io.bluetape4k.aws.s3tables.model.S3TablesRequestSupportKt`. 기존 `*RequestOf` method descriptor가 동일하고 새 `*Request`만 additive인지 diff로 확인한다.
- [ ] base JAR는 고정 base commit `81f8e3e1a3c85f1850f4a9533da430bbb06c14c9`를 임시 worktree에서 `:bluetape4k-aws-java:jar`로 만들고, head JAR는 현재 feature worktree에서 동일 task로 만든다. 임시 worktree와 JAR 경로를 evidence에 기록하고 검증 후 제거한다.
- [ ] hosted compatibility job은 현재 `.github/workflows/ci.yml` path filter가 `aws-java/**`를 compatibility matrix에 포함하지 않으므로 `N/A (expected skipped)`로 기록한다. 이는 local `compileAwsJavaServiceConsumerFixture`와 `javap` ABI 증거를 대체하지 않는다.

## Task 8: 전체 검증과 문서 추적성

**Files:**
- Modify: 필요 시 테스트/KDoc 파일만
- Modify: `aws-java/README.md`, `aws-java/README.ko.md`, `CHANGELOG.md`
- Create/update review: 구현 후 `docs/review/2026-10-27-issue-665-code-review.md`
- Evidence: `.bluetape/evidence/issue-665/`

- [ ] 변경된 테스트를 모두 실행한다.

```bash
./gradlew :bluetape4k-aws-java:test --no-daemon --console=plain
```

- [ ] 모듈 build와 Detekt를 실행한다.

```bash
./gradlew :bluetape4k-aws-java:build -x test --no-daemon --console=plain
./gradlew :bluetape4k-aws-java:detekt --no-daemon --console=plain
```

- [ ] 두 README에 동일 구조의 짧은 paired example을 추가한다. `fooRequest { }`는 AWS SDK builder 필드만 설정하고, 필수 정보가 있는 경우 `fooRequestOf(required...)`를 사용한다는 경계를 영어/한국어로 설명한다. Bedrock precedence 예외와 SSM `PutParameterRequest` specialization 경계를 오해하지 않도록 기록한다. 중앙 manual을 이 저장소에 복제하지 않는다.
- [ ] `CHANGELOG.md`의 `[미출시] > 추가`에 Issue #665와 builder-only/`RequestOf` 계약을 한국어로 기록한다. 릴리스 dispatch나 버전 변경은 수행하지 않고 N/A 근거를 남긴다.
- [ ] `git diff --check`, 변경 경로 scope, KDoc/Issue #665 traceability를 확인한다.
- [ ] raw test/ABI/Detekt/diff logs는 `.bluetape/evidence/issue-665/`에 보존하되, 정확한 HEAD, 명령, exit/result, skipped/N/A, P0/P1 처분 요약을 커밋된 `docs/review/2026-10-27-issue-665-plan-review.md`와 구현 후 `docs/review/2026-10-27-issue-665-code-review.md`에 기록한다.
- [ ] 승인된 설계와 각 acceptance criterion의 traceability를 verifier 체크리스트에 기록한다.

## Task 9: Plan-level rollback 및 완료 기준

- [ ] RED 단계에서 symbol/API 모양이 잘못되면 production 변경 없이 테스트만 되돌리고 함수 목록을 수정한다.
- [ ] 특정 서비스의 validation regression이 발생하면 해당 support 파일의 delegate만 원복하고 기존 `RequestOf` 구현을 보존한 채 서비스 단위 테스트를 재실행한다.
- [ ] ABI 차이가 기존 top-level owner/descriptor 변경으로 나타나면 새 helper를 별도 Kotlin 파일로 옮기되 기존 파일의 `RequestOf` facade는 유지한다.
- [ ] 각 GREEN slice는 Lore 형식의 독립 커밋으로 고정한다: SSM·Secrets Manager, Step Functions·Lambda·Bedrock, EventBridge, Scheduler, S3 Tables. 실패 시 해당 slice 커밋과 대상 테스트만 되돌리고 full verification을 다시 실행한다.
- [ ] 전체 검증이 통과하고 P0/P1 finding이 없으며 issue-linked PR 준비 전까지는 merge/publish/branch deletion을 수행하지 않는다.

## Task 10: 최종 review·lesson 및 PR handoff

- [ ] 구현된 exact HEAD에서 performance, stability, security, operator/Ops, developer/API, user/caller 6관점 최종 code review와 main-session integration을 실행하고, P0/P1을 0으로 수렴한다. 결과는 `docs/review/2026-10-27-issue-665-code-review.md`에 커밋한다.
- [ ] 최종 review 문서와 lesson에 한국어 writer gate `SPW-01`~`SPW-05`를 적용하고, 명령·HEAD·skipped/N/A·P0/P1 처분을 read-back한다.
- [ ] `docs/lessons/2026-10-27-aws-request-builder.md`에 결정, 실제 구현 결과, 검증 증거, 계획에서 발견한 source drift, 향후 guard를 한국어로 기록하고 Lore 형식으로 커밋한다.
- [ ] PR 권한·base/head·remote push가 별도로 승인되지 않았으면 PR 생성, push, hosted CI dispatch, merge, publish, branch deletion은 `PENDING`으로 남긴다. 이 계획의 완료는 구현·검증·lesson까지이며 merge 승인을 의미하지 않는다.

## Traceability map

| 설계 수용 기준 | 구현 task | 검증 |
|---|---|---|
| builder-only `fooRequest { }` | Task 2–6 | 서비스별 RequestSupportTest + compile |
| 필수 정보 `fooRequestOf(...)` 보존 | Task 2–6 | 기존 validation/override 테스트 |
| scalar-vs-block precedence | Task 2–6 | callback override/final validation tests |
| public API·KDoc·ABI | Task 7 | compile + binary compatibility task |
| module quality | Task 8 | test/build/detekt/diff check |
| Issue #665 추적성 | Task 8 | evidence + PR metadata |
| PR 전 review·lesson | Task 10 | committed code review + lesson |

## Plan DoD

- [ ] 모든 함수 목록과 파일 경로가 구현 task에 매핑되었다.
- [ ] RED → GREEN 순서와 각 expected command가 정의되었다.
- [ ] validation, overload ambiguity, ABI, rollback risk가 명시되었다.
- [ ] 설계 acceptance criterion마다 검증 명령과 evidence 위치가 있다.
- [ ] README 한·영, `CHANGELOG.md`, committed review/lesson 및 exact-head read-back이 구현 task에 포함되었다.
- [ ] 계획 review에서 P0/P1이 없다.
