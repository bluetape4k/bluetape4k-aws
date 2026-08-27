---
manualId: "bluetape4k-aws-java"
id: "bluetape4k-aws-java"
title: "AWS SDK for Java v2 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-java"
sourceDir: "aws-java"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-java
---

# AWS SDK for Java v2 확장

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

AWS SDK for Java v2 클라이언트에 Kotlin 빌더와 동기, `CompletableFuture`, coroutine API를 더합니다.

## 사용하기 좋은 경우 {#when-to-use}

Java SDK v2 클라이언트를 사용하거나 동기·비동기 경로가 함께 필요할 때, 또는 Spring Boot와 연동할 때 적합합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

Factory가 클라이언트를 만들고 extension이 AWS 클라이언트 타입을 그대로 확장합니다. async extension은 future를, coroutine extension은 이를 기다리는 suspend API를 제공합니다. 만든 클라이언트의 종료 책임은 애플리케이션에 있습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val s3 = S3AsyncClient.create()
try {
    s3.putAsByteArray(bucket, key, payload)
    val objects = s3.listAllObjects(bucket).toList()
} finally {
    s3.close()
}
```

공유 클라이언트는 애플리케이션 수명 주기에 맞춰 한 번만 닫습니다.

## 작업별 API {#api-by-task}

S3 객체·전송, DynamoDB Enhanced 저장소·배치, DynamoDB Streams Flow/checkpoint 소비, SQS/SNS 메시징, KMS, CloudWatch, Kinesis, Lambda, SES, STS와 요청 모델 빌더를 제공합니다.

## DynamoDB Streams Flow와 checkpoint {#dynamodb-streams}

> 미출시/develop: 이 절은 Issue #469 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

Java SDK v2 확장은 한 shard를 읽는 `DynamoDbStreamsAsyncClient.recordFlow`와
제한된 동시성으로 shard graph를 읽는 `shardRecordFlow`를 제공합니다. 시작 위치는
`TrimHorizon`, `Latest`, `AtSequenceNumber`, `AfterSequenceNumber`이며, 단일 소비는
SDK `Record`를, 다중 shard 소비는 `DynamoDbStreamsShardRecord` envelope를 반환합니다.

```kotlin
val records = client.recordFlow(
    streamArn = streamArn,
    shardId = shardId,
    position = DynamoDbStreamsStartingPosition.Latest,
    checkpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
).toList()
```

Checkpoint는 downstream emission 뒤에만 저장하고 저장된 sequence를 포함해 재생하므로
at-least-once 전달을 유지하며 재시작 시 중복이 생길 수 있습니다. Root shard tree는
`maxShardConcurrency`로 제한하고 child는 parent가 완료된 뒤 소비합니다. 짧은 작업에는
`withDynamoDbStreamsAsyncClient`를 사용하고 주입한 client의 소유권은 호출자에게 둡니다.
Emulator 검증은 Floci-first이며 운영 retention, throttling, resharding timing은 AWS-only
공백으로 남깁니다.

## Kinesis 멀티 샤드 consumer {#kinesis-consumer}

> 미출시/develop: 이 절은 Issue #470 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

`KinesisAsyncClient.consumerFlow`는 `ListShards` 페이지를 계속 탐색하며 각 shard를
순서대로 polling합니다. `maxShardConcurrency`로 active shard job 수를 제한하고,
child shard는 두 부모의 durable `KinesisCheckpoint.ShardEnd`가 모두 저장된 뒤에
시작합니다. Public Flow는 rendezvous 경계를 사용하므로 collector의 `emit`이 반환된
뒤에만 checkpoint를 저장합니다.

```kotlin
client.consumerFlow(
    streamName = "orders",
    consumerGroup = "orders-api",
    streamIdentity = "orders-generation-1",
    position = KinesisStartingPosition.TrimHorizon,
    options = KinesisConsumerOptions(ownerId = "orders-worker-1"),
    checkpointStore = durableCheckpointStore,
    leaseStore = durableLeaseStore,
).collect { envelope -> handle(envelope.record) }
```

`Sequence`는 해당 위치를 포함해 재개하므로 at-least-once 경계를 제공하며, 정확히 한 번의
외부 side effect는 호출자의 책임입니다. 분리된 `KinesisCheckpointStore`와
`KinesisLeaseStore` SPI의 수명도 호출자가 소유합니다. 제공하는 `InMemory*` store는
process-local 테스트 double이고 `Noop*` store는 재시작 복구나 worker 조정을 제공하지
않는다는 점을 명시합니다. Lease counter로 stale save를 차단하고, metrics callback에는
payload나 credential 대신 유한 label과 redacted token만 전달합니다. Client와 probe의 수명은
호출자에게 있으며 cancellation이 정상적인 stop/drain 신호입니다.

AWS credential 없이 Floci를 선택해 integration 계약을 실행할 수 있습니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-java:test --tests '*KinesisConsumerFlociTest'
```

Floci 실행은 SDK와 emulator 호환성만 증명합니다. 운영 rollout은 stop → drain → canary →
scale 순서로 진행하고, rollback 때는 마지막 durable checkpoint를 재사용하며 삭제하거나
되감지 않습니다.

## Lambda 호출 helper {#lambda}

> 미출시/develop: 이 절은 Issue #314 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

Java SDK v2 모듈은 `io.bluetape4k.aws.lambda` 아래에 동기,
`CompletableFuture`, coroutine 호출 helper를 제공합니다. `LambdaInvocationResult<T>`는
raw `InvokeResponse`, 복사한 payload, status code, 선택적 `FunctionError`, 디코드한
`LogType.Tail` 값을 함께 보존합니다. 함수 오류는 결과 데이터이며 transport와 SDK 오류는
예외로 그대로 전달됩니다.

### 의존성과 client 경계 {#lambda-dependency}

Lambda SDK는 `compileOnly`로 유지합니다. Consumer의 compile/runtime classpath에 직접
추가하고 client 수명은 애플리케이션 경계에서 닫으세요.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:lambda")
}
```

```kotlin
import io.bluetape4k.aws.lambda.invokeString
import io.bluetape4k.aws.lambda.withLambdaClient
import software.amazon.awssdk.regions.Region

fun invokeOrder(): String = withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeString("orders-handler", "{\"id\":1}")
    check(!result.hasFunctionError)
    result.value.orEmpty()
}
```

Typed payload는 mapper와 codec을 애플리케이션 경계에서 선택하세요.

```kotlin
data class OrderRequest(val id: Int)
data class OrderResponse(val accepted: Boolean)
val mapper = tools.jackson.databind.ObjectMapper()

withLambdaClient(region = Region.AP_NORTHEAST_2) { client ->
    val result = client.invokeTyped(
        "orders-handler",
        OrderRequest(1),
        LambdaPayloadCodecs.jackson(mapper, OrderResponse::class.java),
    )
    check(result.value != null)
}
```

`invokeBytes`, `invokeString`, `invokeTyped` 중 필요한 API와 애플리케이션이 소유한
`LambdaPayloadCodec`을 사용하세요. Null payload와 빈 payload는 구분합니다. Request builder는
blank function/qualifier를 검사하고 invocation type이 `RequestResponse`가 아니면 tail log를
거부합니다. ARN, 크기, IAM, 함수 존재 여부는 AWS 계약으로 남깁니다.

Async 취소는 AWS SDK future로 전달되고 coroutine `CancellationException`은 변환하지
않습니다. Helper는 retry, 배포, polling, 로깅, IAM policy 관리를 추가하지 않습니다. Opt-in
smoke test는 사전 배포된 함수와 명시적인 function/region 입력이 필요하며, 저장소는 Floci
Lambda를 미지원으로 기록하고 입력이 없으면 client 생성 전에 건너뜁니다. Raw payload,
디코드한 tail log, SDK response body는 기본적으로 기록하거나 저장하지 마세요.

## S3 Tables 관리 {#s3-tables}

> 미출시/develop: 이 절은 Issue #311 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

Java SDK v2 확장은 raw S3 Tables request·response·exception 타입을 유지하면서 table bucket,
namespace, table의 생성·목록·조회·삭제를 제공합니다. 목록 helper는 service의 한 페이지만
반환하므로 다음 페이지가 필요하면 호출자가 `continuationToken`을 전달합니다.
`ListTables`의 `namespace`는 선택 사항이므로 bucket 범위 목록에도 사용할 수 있습니다.
`CreateTable` 기본값은 `ICEBERG`이고 `GetTable`은 table ARN 또는 bucket/namespace/name
selector 중 하나를 사용합니다.

서비스 SDK는 `compileOnly`이므로 consumer가 직접 추가해야 합니다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3tables")
}
```

```kotlin
withS3TablesClient(region = Region.AP_NORTHEAST_2) { client ->
    val bucketArn = client.createTableBucket("orders-tables").arn()
    client.createNamespace(bucketArn, listOf("analytics"))
    client.createTable(bucketArn, "analytics", "orders")
}
```

`s3TablesClient`와 `s3TablesClientOf`는 application-scoped client를 만들고 `ShutdownQueue`에
등록합니다. 일찍 닫거나 주입한 HTTP client를 관리하는 책임은 호출자에게 있습니다.
`withS3TablesClient`는 등록하지 않은 단기 client를 만들어 block이 끝날 때 service client만 닫습니다.
이 모듈은 S3 Tables management surface만 제공하며 Iceberg data plane, SQL, Athena, Glue,
Redshift, Apache Iceberg 통합 facade를 구현하지 않습니다. 로컬 emulator의 S3 Tables fidelity도
보장한다고 주장하지 않습니다.

## Step Functions 실행 helper {#step-functions}

> 미출시/develop: 이 절은 Issue #313 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

Java SDK v2 경로는 `io.bluetape4k.aws.sfn` 패키지에
`StartExecution`, `StopExecution`, `DescribeExecution`, `ListExecutions`를 위한 얇은
helper를 추가합니다. 동기와 단발성 async/coroutine 연산은 AWS SDK raw 응답을
반환합니다. Polling은 `SfnAsyncClient`에서만 제공하므로 event loop나 Flow collector에서
blocking `SfnClient`를 사용하지 않습니다.

### Dependency boundary {#step-functions-dependency}

모듈은 Step Functions SDK를 `compileOnly`로 유지합니다. 애플리케이션은 모듈과 함께
서비스 SDK를 compile/runtime classpath에 직접 추가해야 합니다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:sfn")
}
```

버전은 중앙 Bluetape4k BOM으로 맞추세요. Region, credentials provider, endpoint, HTTP
client와 close 경계도 애플리케이션이 소유합니다.

### Standard, Express, and Map Run {#step-functions-capabilities}

Helper는 실행을 새로운 domain type으로 변환하지 않고 AWS 서비스 의미를 그대로 유지합니다.

| Helper/API | Standard | Express | Map Run child |
| --- | --- | --- | --- |
| `StartExecution` | 지원 | 지원 | child를 직접 시작하는 API가 아님 |
| `DescribeExecution` | 지원 | 일반 Express 실행에는 미지원 | AWS가 지원하는 child execution ARN에서 조건부 지원 |
| `ListExecutions` | state machine ARN | 일반 Express 실행에는 미지원 | `mapRunArn`으로 조건부 지원 |
| `StopExecution` | 지원 | 미지원 | Standard child에서 조건부 지원, Express child는 미지원 |
| `describeExecutionFlow` | `DescribeExecution`과 같은 범위 | 미지원 | `DescribeExecution`과 같은 조건부 범위 |

`StartExecution`에서 Standard 실행은 같은 name과 input이면 idempotent하지만 Express
실행은 그렇지 않습니다. Retry 시 Express 실행의 중복 시작을 막는 책임은 호출자에게 있습니다.

Java request builder는 `listExecutionsByStateMachine`과
`listExecutionsByMapRun`을 분리합니다. 전체 페이지가 필요하면 raw SDK paginator를
직접 사용할 수 있습니다. `PENDING_REDRIVE`는 raw terminal 응답이지 business success
결과가 아닙니다. Express 실행 관찰을 CloudWatch Logs로 수행하는 경로는 이 helper 범위
밖에 둡니다.

### Polling and cancellation {#step-functions-polling}

`SfnAsyncClient.describeExecutionFlow(...)`는 cold
`Flow<DescribeExecutionResponse>`를 반환합니다. Collect를 시작하면
`DescribeExecution`을 즉시 한 번 호출하고, `RUNNING` 응답을 emit한 뒤
`SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`, `PENDING_REDRIVE` 중 알려진 terminal
응답을 emit하고 종료합니다. `SfnExecutionPollingOptions.pollInterval` 기본값과 최솟값은
1초입니다. 1초는 collector 하나의 하한일 뿐 account/Region quota를 보장하지 않습니다.

```kotlin
import io.bluetape4k.aws.sfn.describeExecutionFlow
import io.bluetape4k.aws.sfn.withSfnAsyncClient
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse
import kotlin.time.Duration.Companion.seconds

suspend fun awaitExecution(executionArn: String): DescribeExecutionResponse =
    withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
```

제한 시간이나 deadline은 호출자가 지정합니다. Cancellation은
`CancellationException`으로 그대로 전파되며 Flow가 `StopExecution`을 자동 호출하거나
호출자가 소유한 client를 닫지 않습니다. SDK가 반환한 상태를 알 수 없으면 응답을 emit하지
않고 fail-closed로 종료합니다. `withSfnAsyncClient` 블록 또는 애플리케이션이 소유한
client 범위 안에서 collect를 완료하세요.

### IAM and KMS {#step-functions-iam-kms}

Operation과 resource 종류별로 최소 권한 statement를 분리합니다. State machine alias와
version, Standard execution, Express execution, labelled Map Run child, Map Run resource는
서로 다른 ARN 형태를 사용하므로 하나의 넓은 wildcard statement로 합치지 않습니다.

| Statement | IAM action | Resource 경계 |
| --- | --- | --- |
| Start execution | `states:StartExecution` | 대상 state machine: unqualified `statemachine`, qualified `statemachinealias` 또는 `statemachineversion` ARN |
| Start execution의 dependent describe | `states:DescribeExecution` | 생성되는 Standard `execution` 또는 Express `express` execution ARN pattern을 위한 별도 statement |
| Describe execution | `states:DescribeExecution` | Standard `execution`, 지원되는 Express `express` 또는 지원되는 labelled Map Run child (`labelled execution`/`labelled express`) ARN |
| Stop execution | `states:StopExecution` | Standard `execution` 또는 labelled Standard child ARN만 허용하며 Express execution stop은 부여하지 않음 |
| State machine 기준 목록 | `states:ListExecutions` | state-machine ARN만 허용 |
| Map Run 기준 목록 | `states:ListExecutions` | Map Run `maprun` ARN만 허용 |

Qualified state machine, Standard execution, Express execution, labelled Map Run
child와 Map Run resource pattern은 statement를 분리합니다. 하나의 wildcard로 합치지
말고, dependent `states:DescribeExecution` grant도 `states:StartExecution`과 별도
statement로 둡니다.

`StopExecution(error = null, cause = null)`이면 암호화할 오류 상세를 보내지 않으므로
execution role의 추가 KMS data-key grant를 피할 수 있습니다. 암호화된 execution data를
`includedData = ALL_DATA`로 요청하면 caller의 KMS key policy와 `kms:Decrypt` 권한이
여전히 trust boundary입니다. Helper가 이 경계를 우회하지 않으며 KMS service error도
변환하지 않고 유지합니다.

Raw ARN, execution name, input, output, error, cause, trace header와 raw SDK response
payload는 기본적으로 log나 저장소에 남기지 않습니다. 안정적인 상관관계 key가 필요하면
민감한 식별자에는 plain hash 대신 caller가 관리하는 HMAC을 사용하세요. Custom `endpoint`
override, credentials provider와 HTTP client는 caller가 소유한 trust boundary입니다. 신뢰하는
emulator 또는 private endpoint에만 사용하고 production credential이나 payload를 신뢰하지
않는 endpoint, non-TLS endpoint로 보내지 않습니다.

### Quotas and observability {#step-functions-operations}

Step Functions API quota는 account와 Region 기준이며 Region이나 AWS 정책에 따라 달라질 수
있습니다. 운영 polling budget을 정하기 전에 [현재 service
quota](https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html)를
다시 확인하세요. Helper는 rate limiter, retry policy, jitter와 내부 timeout을 추가하지
않습니다.

- 모든 collector에 `withTimeout` 또는 외부 deadline을 적용합니다.
- account/Region별 collector 수와 전체 polling rate를 제한하고, 동기화된 burst를 피하도록
  시작 시점을 분산하거나 caller 소유 jitter/rate limiter를 적용합니다.
- 같은 execution을 여러 consumer가 관찰하면 `shareIn` 또는 `stateIn`으로 하나의 polling
  source를 공유합니다.
- Service, operation, outcome/status, latency, retry 횟수, throttle 횟수와 SDK response
  metadata의 AWS request ID를 기록합니다. Request/response payload는 기록하지 않습니다.
- Raw execution ARN 대신 제한된 logical identity로 CloudWatch execution 및 throttling
  metric을 관찰합니다.

### Emulator evidence {#step-functions-emulator}

저장소의 Floci-first selector로 Step Functions smoke를 먼저 실행하고, 필요한 경우
LocalStack을 명시적으로 선택합니다. Floci가 이 service를 지원하지 않으면 정확한
assumption message인
`live integration unverified: Floci does not support Step Functions`와 skipped test
result를 보존합니다. LocalStack이 미지원이거나 실패하면 test result XML과 함께
`live integration unverified: LocalStack Step Functions smoke failed: <error>`를 기록하며
어느 결과도 live-integration PASS로 바꾸지 않습니다. Unit, consumer compile과
dependency-publication 검증은 별도 local evidence로 유지합니다.

Emulator smoke가 증명하는 범위는 endpoint 호환성과 기본 request/response serialization뿐입니다.
Production IAM resource policy, KMS key policy, quota, latency와 managed-service 동작은
증명하지 않습니다. 별도 credential-gated AWS 검증이 없으면 IAM/KMS integration 상태는
계속 `UNVERIFIED`입니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요.

## 연동 {#integrations}

Spring Boot와 Ktor 모듈이 이 라이브러리를 기반으로 동작합니다. 런타임에는 `software.amazon.awssdk:s3`처럼 실제로 쓰는 Java SDK v2 서비스만 추가하세요.

## 설정 {#configuration}

AWS client builder에서 region, credentials, endpoint override, HTTP 구현체, 재시도와 timeout을 정한 뒤 클라이언트를 공유하세요.

## 실패 유형과 해결 방법 {#failures}

빈 queue URL, 끝까지 읽지 않은 S3 pagination, 누락된 서비스 JAR, coroutine 스레드에서의 blocking 호출, 닫히지 않은 async client를 먼저 점검하세요.

## 운영 {#operations}

thread-safe client는 재사용하고 동시성을 제한하세요. 재시도와 throttling을 관측하고 S3 이동이 copy 후 delete라는 점을 운영 절차에 반영해야 합니다.

## 테스트 {#testing}

endpoint override와 테스트용 고정 자격 증명으로 Floci를 먼저 사용하세요. Floci가 제공하지 않는 서비스 동작만 LocalStack으로 보완합니다.

## 학습 경로와 예제 {#workshops}

`client-lifecycle` → `sync-async-coroutines` → `service-patterns` 순서로 읽고, 릴리스에 포함된 S3·DynamoDB·SQS 예제로 이어가세요.

## 제약 사항 {#limitations}

이 artifact는 모든 AWS 서비스 SDK를 런타임에 끌어오지 않습니다. coroutine helper를 사용해도 동기 클라이언트가 비동기 클라이언트로 바뀌지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Java 아키텍처

[![Bluetape4k AWS Java 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-architecture-01.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.ko.md)_

### Bluetape4k AWS Java operation 처리 흐름

[![Bluetape4k AWS Java operation 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-flow-02.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.ko.md)_

### Bluetape4k AWS Java coroutine 시퀀스 다이어그램

[![Bluetape4k AWS Java coroutine 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-java-sequence-03.svg)

_배포본 README: [`aws-java/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-java/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt)
- [릴리스 소스: `aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt`](../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/dynamodb/repository/DynamoDbCoroutineRepository.kt)
- [릴리스 테스트: emulator 선택](../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt)
