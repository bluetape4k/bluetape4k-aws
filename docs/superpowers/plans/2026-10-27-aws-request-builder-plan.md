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
- `RequestOf`의 block은 기존 순서를 유지한다. 입력 scalar를 먼저 검증하고 기본값을 설정한 뒤 block을 적용하며, 기존 final validation을 build 후 다시 적용한다.
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

## Task 1: RED 계약과 API inventory 고정

**Files:** 위 8개 `*RequestSupportTest.kt` 파일

- [ ] 각 테스트 파일에 builder-only import와 실패하는 호출을 먼저 추가한다. 예를 들어 SSM은 다음 계약을 먼저 작성한다.

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

- [ ] 같은 형태로 각 서비스의 핵심 builder-only 함수가 아직 없음을 확인한다.
- [ ] RED 명령을 실행한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SsmSupportTest' --tests '*SecretsManagerSupportTest' --tests '*SfnRequestSupportTest' --tests '*LambdaRequestSupportTest' --tests '*BedrockRuntimeRequestSupportTest' --tests '*EventBridgeRequestSupportTest' --tests '*SchedulerRequestSupportTest' --tests '*S3TablesRequestSupportTest' --no-daemon --console=plain
```

Expected: `unresolved reference` 또는 동일한 symbol-missing compile failure가 발생하고 production source는 변경하지 않는다.

- [ ] RED 로그와 정확한 missing symbol을 `.bluetape/evidence/issue-665/red-helpers.log`에 보존한다.

## Task 2: SSM·Secrets Manager builder-only 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/model/SsmRequestSupport.kt`
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/model/SecretsManagerRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`

- [ ] SSM에 다음 builder-only 함수를 추가한다: `getParameterRequest`, `getParametersRequest`, `getParametersByPathRequest`, `describeParametersRequest`, `putSecureParameterRequest`, `putStringParameterRequest`, `putStringListParameterRequest`, `putParameterRequest`.
- [ ] 각 builder-only 함수는 다음 형태로만 SDK builder를 감싼다.

```kotlin
inline fun getParameterRequest(
    builder: GetParameterRequest.Builder.() -> Unit,
): GetParameterRequest = GetParameterRequest.builder()
    .apply(builder)
    .build()
```

- [ ] 기존 `RequestOf` 함수의 scalar validation, override configuration 순서, `AwsSecretValue` redaction을 바꾸지 않고 내부 construction을 새 builder-only 함수에 위임한다.
- [ ] Secrets Manager에 다음 builder-only 함수를 추가한다: `getSecretValueRequest`, `batchGetSecretValueRequest`, `listSecretsRequest`, `describeSecretRequest`, `createSecretRequest`, `putSecretValueRequest`.
- [ ] 기존 `RequestOf`에 builder block이 없는 함수에는 새로운 default trailing block을 추가하지 않는다. 현재 builder block이 있는 함수만 block을 builder-only 경로에 전달한다.
- [ ] 각 테스트 파일에서 builder-only block 적용, required scalar 설정, scalar-vs-block override, 기존 blank/limit failure를 검증한다.
- [ ] RED 테스트가 GREEN으로 바뀌는지 두 테스트 파일만 실행한다.

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
- [ ] `startExecutionRequestOf`는 builder-only 함수에 state machine ARN과 optional values를 주입한 후 `validateAndNormalize()`를 호출해 null input `{}` 정규화와 name/input 검증을 유지한다.
- [ ] `listExecutionsRequestOf`는 builder-only 함수에 source/filter/page 값을 주입한 뒤 `validate()`를 호출해 XOR source, redrive 조건, page limit을 유지한다. builder-only 함수 자체에는 helper validation을 추가하지 않는다.
- [ ] Lambda에 `invokeRequest`를 추가하고 `invokeRequestOf`가 payload copy, qualifier, invocation/log 기본값을 주입한 뒤 기존 `InvokeRequest.validate()`를 수행하도록 유지한다.
- [ ] Bedrock Runtime에 `converseRequest`, `converseStreamRequest`를 추가한다. `contentBlockOf`와 `userMessageOf`는 Request 타입이 아니므로 이름과 동작을 변경하지 않는다.
- [ ] Bedrock `RequestOf`의 model id/messages/inference config 및 additional fields가 기존 precedence를 유지하는지 확인한다.
- [ ] 각 테스트에 builder-only 단독 block, `RequestOf` override, final validation failure를 추가한다.
- [ ] 세 테스트 파일을 대상으로 GREEN을 확인한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SfnRequestSupportTest' --tests '*LambdaRequestSupportTest' --tests '*BedrockRuntimeRequestSupportTest' --no-daemon --console=plain
```

## Task 4: EventBridge Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/model/EventBridgeRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`

- [ ] 다음 builder-only 함수와 기존 `RequestOf` 연결을 추가한다: `createEventBusRequest`, `deleteEventBusRequest`, `putRuleRequest`, `deleteRuleRequest`, `putEventsRequestEntry`, `putTargetsRequest`, `removeTargetsRequest`, `putEventsRequest`, `listRulesRequest`, `listTargetsByRuleRequest`.
- [ ] `targetOf`와 기존 entry helper의 non-Request 계약은 유지하되, `putEventsRequestEntry`는 SQS batch entry 패턴과 동일한 builder-only parity 대상으로 포함한다.
- [ ] `putEventsRequest`는 1..10 entries, `putTargetsRequest`/`removeTargetsRequest`는 1..10 item, list helpers는 1..100 limit을 최종 Request에서 검증한다.
- [ ] `putRuleRequest`의 eventPattern/scheduleExpression 상호배타·필수 계약과 `create/delete`의 name/ARN 검증을 보존한다.
- [ ] 테스트에서 builder-only block 반영, `RequestOf`의 callback override, limit 초과와 빈 값 거부를 확인한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeRequestSupportTest' --no-daemon --console=plain
```

## Task 5: EventBridge Scheduler Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/scheduler/model/SchedulerRequestSupportTest.kt`

- [ ] `createScheduleRequest`, `updateScheduleRequest`, `deleteScheduleRequest`, `getScheduleRequest`, `listSchedulesRequest`, `createScheduleGroupRequest`, `deleteScheduleGroupRequest`, `getScheduleGroupRequest`, `listScheduleGroupsRequest`를 추가한다.
- [ ] `flexibleTimeWindowOf`, `retryPolicyOf`, `deadLetterConfigOf`, `targetOf`는 Request 타입이 아니므로 기존 API를 유지한다.
- [ ] `RequestOf`의 name/expression/ARN/token/page 검증과 schedule default(`flexibleTimeWindowOff`)를 보존한다.
- [ ] 테스트에서 builder-only 필드 적용과 schedule/group required info 경로를 확인하고, list limit·retry/window 범위 실패를 유지한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SchedulerRequestSupportTest' --no-daemon --console=plain
```

## Task 6: S3 Tables Request 계층

**Files:**
- Modify: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupport.kt`
- Test: `aws-java/src/test/kotlin/io/bluetape4k/aws/s3tables/model/S3TablesRequestSupportTest.kt`

- [ ] 다음 builder-only 함수를 추가한다: `createTableBucketRequest`, `listTableBucketsRequest`, `getTableBucketRequest`, `deleteTableBucketRequest`, `createNamespaceRequest`, `listNamespacesRequest`, `getNamespaceRequest`, `deleteNamespaceRequest`, `createTableRequest`, `listTablesRequest`, `getTableRequest`, `deleteTableRequest`.
- [ ] 기존 `RequestOf`의 `tableBucketARN`, namespace segments, table selector XOR, format required, page validation을 그대로 재사용한다.
- [ ] 기존 callback override 테스트를 builder-only delegate 경로에서도 유지하고, `getTableRequest`의 table ARN/path selector 혼합 거부를 확인한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*S3TablesRequestSupportTest' --no-daemon --console=plain
```

## Task 7: Cross-service API/test conformance

**Files:** Task 2–6의 production/test 파일 전체

- [ ] 모든 새 public 함수에 한국어 KDoc, 명시적 반환 타입, 기존 module import 규칙을 적용한다.
- [ ] 각 `fooRequest`가 receiver block을 실제로 한 번 호출하는지 source inspection과 테스트로 확인한다.
- [ ] `fooRequestOf`가 없는 scalar overload를 새로 만들어 overload ambiguity를 유발하지 않았는지 `kotlinc`/Gradle compile로 확인한다.
- [ ] 기존 public `RequestOf` 함수의 JVM owner와 descriptor가 유지되는지 ABI task를 먼저 inventory한다.
- [ ] 순수 SDK request object construction 변경이므로 concurrency, coroutine cancellation, client lifecycle, emulator/backend capability는 N/A임을 evidence에 기록하고, 네트워크 호출을 추가하지 않았는지 확인한다.

```bash
./gradlew :bluetape4k-aws-java:compileKotlin --no-daemon --console=plain
./gradlew :bluetape4k-aws-java:tasks --all --no-daemon --console=plain | rg -i 'binary|compat|api'
```

- [ ] repository가 제공하는 binary compatibility task가 있으면 해당 task를 실행하고, 없으면 generated API dump 또는 기존 compatibility helper를 사용해 추가된 함수만 확인한다. source compilation만 ABI 증거로 사용하지 않는다.

## Task 8: 전체 검증과 문서 추적성

**Files:**
- Modify: 필요 시 테스트/KDoc 파일만
- Review/update: `aws-java/README.md`, `aws-java/README.ko.md`
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

- [ ] 기존 README의 request DSL 설명과 영어/한국어 구조를 검토한다. 이미 있는 request DSL 예제가 새 builder-only/`RequestOf` 계약을 오해하게 만들면 두 README를 함께 갱신하고, 갱신이 불필요하면 그 근거를 evidence에 기록한다. 중앙 manual을 이 저장소에 복제하지 않는다.
- [ ] `git diff --check`, 변경 경로 scope, KDoc/Issue #665 traceability를 확인한다.
- [ ] 테스트 결과, ABI 결과, Detekt 결과, diff check를 `.bluetape/evidence/issue-665/`에 보존한다.
- [ ] 승인된 설계와 각 acceptance criterion의 traceability를 verifier 체크리스트에 기록한다.

## Task 9: Plan-level rollback 및 완료 기준

- [ ] RED 단계에서 symbol/API 모양이 잘못되면 production 변경 없이 테스트만 되돌리고 함수 목록을 수정한다.
- [ ] 특정 서비스의 validation regression이 발생하면 해당 support 파일의 delegate만 원복하고 기존 `RequestOf` 구현을 보존한 채 서비스 단위 테스트를 재실행한다.
- [ ] ABI 차이가 기존 top-level owner/descriptor 변경으로 나타나면 새 helper를 별도 Kotlin 파일로 옮기되 기존 파일의 `RequestOf` facade는 유지한다.
- [ ] 전체 검증이 통과하고 P0/P1 finding이 없으며 issue-linked PR 준비 전까지는 merge/publish/branch deletion을 수행하지 않는다.

## Traceability map

| 설계 수용 기준 | 구현 task | 검증 |
|---|---|---|
| builder-only `fooRequest { }` | Task 2–6 | 서비스별 RequestSupportTest + compile |
| 필수 정보 `fooRequestOf(...)` 보존 | Task 2–6 | 기존 validation/override 테스트 |
| scalar-vs-block precedence | Task 2–6 | callback override/final validation tests |
| public API·KDoc·ABI | Task 7 | compile + binary compatibility task |
| module quality | Task 8 | test/build/detekt/diff check |
| Issue #665 추적성 | Task 8 | evidence + PR metadata |

## Plan DoD

- [ ] 모든 함수 목록과 파일 경로가 구현 task에 매핑되었다.
- [ ] RED → GREEN 순서와 각 expected command가 정의되었다.
- [ ] validation, overload ambiguity, ABI, rollback risk가 명시되었다.
- [ ] 설계 acceptance criterion마다 검증 명령과 evidence 위치가 있다.
- [ ] 계획 review에서 P0/P1이 없다.
