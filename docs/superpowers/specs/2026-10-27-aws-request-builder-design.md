# aws-java Request Kotlin-style builder 함수 설계

## 문제

`aws-java`는 AWS SDK v2 요청을 위한 Kotlin helper를 제공하지만 서비스별 API 모양이
일관되지 않다. Kinesis, S3, SNS, SQS, KMS, CloudWatch, DynamoDB에는
`fooRequest { ... }` 형태의 진입점이 있는 반면, SSM, Secrets Manager, Step Functions,
Lambda, Bedrock Runtime, EventBridge, EventBridge Scheduler, S3 Tables의 여러 helper는
`fooRequestOf(...)`만 제공한다. 호출자는 일부 서비스에서 직접 AWS SDK
`Request.builder()`를 사용해야 한다.

이번 변경은 기존 convenience helper의 의미를 바꾸지 않고, 두 가지 생성 경로를 명확히
분리한다.

- `fooRequest { ... }`: 인자 없이 AWS SDK `Request.Builder`를 receiver로 노출하는 순수
  Kotlin DSL이다.
- `fooRequestOf(...)`: bucket/key, ARN, name, path, secret id처럼 helper가 책임지는
  필수·핵심 정보를 명시적으로 받는 convenience helper다. 기존 `RequestOf`에 builder
  block이 있으면 그 trailing block을 유지하고, 없으면 `fooRequest { ... }`를 custom
  경로로 사용한다.

## 현재 근거

조사 기준은 `develop`의 현재 소스와 라이브 Issue #665다.

- `aws-java/src/main/kotlin/io/bluetape4k/aws/sqs/model/SendMessage.kt`는
  `sendMessageRequest { ... }`와 필수 `queueUrl`/`messageBody`를 받는
  `sendMessageRequestOf(...)`를 함께 제공한다.
- `aws-java/src/main/kotlin/io/bluetape4k/aws/s3/model/GetObjectRequest.kt`는
  `getObjectRequest(bucket, key) { ... }`와 `getObjectRequestOf(bucket, key, ...)`를
  제공해 필수 정보와 builder DSL을 분리한다.
- 대상 8개 support 파일의 `RequestOf` 함수는 대부분 builder block을 이미 적용하며,
  S3 Tables도 `...RequestOf(..., builder)`를 제공하지만 standalone `...Request`가 없다.
- baseline `./gradlew :bluetape4k-aws-java:test --no-daemon --console=plain`은
  578 tests, 15 skipped, failures 0으로 통과했다.

## 선택지와 결정

### 선택지 A: 공통 generic/reflection builder

단일 generic 함수 또는 reflection으로 AWS SDK builder를 감싼다.

**제외 이유:** Request별 builder 타입과 반환 타입이 흐려지고, public JVM ABI와 IDE
자동완성 품질이 나빠진다. 요청별 validation도 generic 경계 밖으로 새어나간다.

### 선택지 B: 명시적인 `fooRequest` + 기존 `fooRequestOf` 정렬 (선택)

각 `RequestOf`에 대응하는 `fooRequest(builder)`를 추가한다. `fooRequest`는
`Request.builder().apply(builder).build()`를 담당하고, `fooRequestOf`는 필수·핵심
정보를 먼저 설정한 뒤 `fooRequest`와 동일한 builder block을 적용한다.

**선택 이유:** 기존 S3/SQS 패턴과 동일하고, 함수별 반환 타입·KDoc·validation을
명확히 유지하며, 기존 호출자의 source/binary compatibility를 보존한다.

### 선택지 C: 호출 빈도가 높은 요청만 추가

SSM/Lambda/S3처럼 사용 빈도가 높은 일부 요청에만 DSL을 추가한다.

**제외 이유:** 같은 모듈 안에서 서비스별 discoverability와 naming이 계속 달라지고,
Issue #665의 일관된 builder 계약을 충족하지 못한다.

## API 계약

### 순수 builder DSL

새 함수는 필수 scalar 인자를 받지 않는다. block은 반드시 한 번 적용한다.

```kotlin
inline fun getParameterRequest(
    builder: GetParameterRequest.Builder.() -> Unit,
): GetParameterRequest = GetParameterRequest.builder()
    .apply(builder)
    .build()
```

이 경로는 AWS SDK builder의 기본 동작을 그대로 노출한다. helper 전용 필수 값 검증은
`RequestOf` 경로의 책임이며, builder-only 함수에 임의의 scalar 기본값을 추가하지 않는다.

### 필수 정보 convenience helper

기존 `RequestOf`의 인자와 validation을 유지한다. 기존 함수에 builder block이 있는
경우에는 다음처럼 `fooRequest`를 재사용하도록 정렬한다.

```kotlin
inline fun getParameterRequestOf(
    name: String,
    withDecryption: Boolean = false,
    builder: GetParameterRequest.Builder.() -> Unit = {},
): GetParameterRequest {
    name.requireNotBlank("name")

    return getParameterRequest {
        name(name)
        withDecryption(withDecryption)
        builder()
    }.also { request ->
        request.name().requireNotBlank("name")
    }
}
```

기존 `RequestOf`에 builder block이 없는 경우에는 optional 기본 인자를 가진 trailing
block overload를 추가하지 않는다. 기존 호출과의 overload ambiguity를 피하고,
custom 필드 설정은 인자 없는 `fooRequest { ... }`에서 수행한다. 예를 들어
`getObjectRequestOf(bucketName, key)`는 필수 정보 경로이고,
`getObjectRequest { bucket(bucketName); key(key); partNumber(2) }`는 builder-only
경로다.

실제 대상 함수는 각 AWS 모델의 기존 필드명과 검증 규칙을 따른다. `RequestOf`의
scalar 값은 기본값이며, 함수별 기존 precedence를 보존한다. SSM, Secrets Manager,
Step Functions, Lambda, EventBridge, EventBridge Scheduler, S3 Tables는 trailing
builder가 최종 값을 override할 수 있고, helper가 책임지는 필수 필드·limit·상호배타
조건을 build 후에도 검증해 무효한 override를 거부한다. Bedrock Runtime은 기존 동작대로
`modelId`와 `messages`, non-null `inferenceConfig`를 helper가 block 뒤에 설정하며,
그 외 모델별 필드는 builder가 설정한다. 기본 입력 검증은 builder 적용 전에 수행한다.
단, S3 Tables의 현재 `RequestOf` 구현은 builder 적용 후 build 단계에서 필수 scalar를
검증하므로, 기존 호출의 검증 순서와 block override 동작을 보존하기 위해 post-build
순서를 유지한다.

## 대상 범위

다음 support 파일의 서로 다른 AWS SDK `Request` 타입마다 대응하는 builder-only
`*Request`를 추가한다. 같은 SDK Request 타입을 공유하는 specialization helper는
중복 builder-only 이름을 만들지 않는다. 예를 들어 SSM의 `putSecureParameterRequestOf`,
`putStringParameterRequestOf`, `putStringListParameterRequestOf`는
`PutParameterRequest`를 반환하는 required-info/변환 helper로 유지하고, builder-only
진입점은 `putParameterRequest { ... }` 하나로 제공한다.

- `ssm/model/SsmRequestSupport.kt`
- `secretsmanager/model/SecretsManagerRequestSupport.kt`
- `sfn/model/SfnRequestSupport.kt`
- `lambda/model/LambdaRequestSupport.kt`
- `bedrock/model/BedrockRuntimeRequestSupport.kt`
- `eventbridge/model/EventBridgeRequestSupport.kt`
- `scheduler/model/SchedulerRequestSupport.kt`
- `s3tables/model/S3TablesRequestSupport.kt`

이번 범위에서 제외하는 항목은 `aws-kotlin`, Spring/Ktor 모듈, 독립 helper가 없는
S3 Vectors/DynamoDB Streams의 신규 추상화다.

## 검증과 실패 모드

### 검증 계약

각 대상 파일의 테스트는 최소한 다음을 확인한다.

1. `fooRequest { ... }`가 receiver block을 실제로 적용하고 최종 필드에 반영한다.
2. `fooRequestOf(required...)`가 필수 정보를 설정하고, 기존 optional 인자와 trailing
   block을 보존한다.
3. scalar와 block이 같은 필드를 설정할 때 기존 함수별 precedence가 사용된다. Bedrock의
   `modelId`/`messages`/non-null `inferenceConfig`는 helper 값이 우선하고, 그 외 대상은
   block의 최종 값이 사용된다.
4. blank 필드, 범위 초과, 배치 크기 초과, 필수 조건 위반이 기존과 같은
   `IllegalArgumentException` 계약으로 거부된다. EventBridge `PutRuleRequest`는
   `eventPattern` 또는 `scheduleExpression` 중 하나 이상을 요구하며, 두 값을 모두
   지정하는 기존 동작은 유지한다.

### 주요 실패 모드

- **block 무시:** 함수가 block 파라미터를 받지만 호출하지 않으면 compile은 통과해도
  caller 설정이 사라진다. 각 builder-only와 `RequestOf` override 테스트로 방지한다.
- **필수 값 검증 우회:** builder가 helper scalar를 빈 값으로 덮어쓰면 AWS 호출까지
  잘못된 요청이 전달될 수 있다. `RequestOf`는 입력 검증과 최종 Request 검증을 모두
  수행하며, Bedrock helper-owned 필드는 block 뒤에 다시 설정한다.
- **검증 순서 회귀:** `ListExecutionsRequest`, EventBridge batch, Scheduler page 같은
  함수의 limit/상호배타 검증을 delegate 과정에서 누락할 수 있다. 기존 검증 helper를
  재사용하고 해당 경로의 실패 테스트를 유지한다.
- **public ABI 회귀:** 기존 top-level `*RequestOf`의 JVM owner 또는 descriptor가
  바뀌면 precompiled consumer가 깨질 수 있다. 기존 함수는 유지하고 새 `*Request`
  top-level entrypoint만 추가한 뒤 repository binary compatibility 검사를 수행한다.

## 호환성·마이그레이션

- 기존 `fooRequestOf(...)` 함수 이름, 인자 순서, 기본값, 반환 타입을 제거하거나
  의미 변경하지 않는다.
- 기존 `RequestOf`에 builder block이 있는 경우에만 그 block을 유지한다. builder block이
  없는 `RequestOf`에는 default trailing block overload를 추가하지 않아 기존 호출의
  overload ambiguity를 만들지 않는다.
- 기존 직접 SDK builder 호출은 즉시 제거하지 않는다. 새 DSL은 점진적 migration
  경로이며, 이번 변경은 public helper 추가와 구현 정렬에 한정한다.
- `@JvmName`, 파일 위치, top-level facade owner를 불필요하게 바꾸지 않는다.

## 수용 기준

- 8개 대상 support 파일의 공개 request helper에 builder-only `fooRequest { }`가
  존재하고, 모든 block이 실제로 적용된다.
- `fooRequestOf(...)`는 필수·핵심 정보를 명시적으로 받으며 기존 호출과 검증 계약을
  유지한다.
- 기본값, scalar-vs-block override, validation failure 테스트가 대상별로 통과한다.
- S3 Tables는 기존 post-build 검증 순서와 blank scalar를 block이 덮어쓰는 경계를 회귀
  테스트로 고정한다.
- `aws-java` targeted test/compile, module test, Detekt, binary compatibility 또는
  저장소 표준 ABI 검사, `git diff --check`가 통과한다.
- 변경된 public API에는 한국어 KDoc과 Issue #665 추적성이 있다.
- 새 builder-only KDoc은 필수 필드와 helper validation의 책임 경계를 설명하고,
  `fooRequestOf(...)`가 required-info 경로임을 명시한다. Bedrock helper-owned
  precedence 예외도 해당 KDoc에 기록한다.

## DoD

- [ ] 설계 리뷰에서 P0/P1이 없다.
- [ ] 구현 계획과 설계가 승인되고 feature branch에 커밋된다.
- [ ] TDD RED → GREEN 증거와 대상별 테스트가 기록된다.
- [ ] ABI/정적 분석/모듈 검증 및 diff check가 최신 HEAD에서 통과한다.
- [ ] PR 전 review 및 한국어 lesson을 완료한다.
