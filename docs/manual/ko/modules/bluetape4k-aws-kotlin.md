---
manualId: "bluetape4k-aws-kotlin"
id: "bluetape4k-aws-kotlin"
title: "AWS SDK for Kotlin 확장"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-kotlin"
sourceDir: "aws-kotlin"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-kotlin
---

# AWS SDK for Kotlin 확장

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

native suspend 기반 AWS SDK for Kotlin에 빌더, 모델 변환, Flow helper와 클라이언트 수명 주기 유틸리티를 더합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 coroutine 중심이고 Java SDK v2 클라이언트와의 상호 운용이 필요하지 않을 때 선택하세요.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

서비스 호출 자체가 SDK의 suspend 함수입니다. 이 모듈은 간결한 요청 빌더, pagination·Flow 패턴, `with...Client` 수명 주기 helper를 제공합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
withS3Client(region = region) { s3 ->
    s3.putFromByteArray(bucket, key, bytes)
    s3.getAsByteArray(bucket, key)
}
```

`withS3Client` 블록이 끝나면 임시 클라이언트가 닫힙니다.

## 작업별 API {#api-by-task}

DynamoDB 모델 DSL·배치, DynamoDB Streams Flow/checkpoint 소비, S3 객체 작업, SQS/SNS, SES, KMS, CloudWatch, Kinesis record Flow, Lambda, STS와 HTTP engine provider를 제공합니다.

## DynamoDB coordination {#dynamodb-coordination}

> 미출시/develop: 이 절은 Issue #476을 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

`DynamoDbDistributedLock`과 `DynamoDbMetadataStore`는 호출자가 소유하는 DynamoDB table을
대상으로 하는 coroutine 우선 coordination adapter입니다. String partition key 하나만
사용하며 schema가 table을 만들거나 sort key를 추론하거나 client lifecycle을 관리하지
않습니다. 이 모듈의 service SDK는 `compileOnly`이므로 AWS Kotlin DynamoDB service SDK를
런타임 의존성으로 추가해야 합니다.

`partitionKeyAttributeName`(`id` 기본값)과 일치하는 hash key만 가진 PK-only table을
준비하세요. DynamoDB TTL은 metadata의 `ttlEpochSeconds` attribute에만 활성화하고 lock
row에는 적용하지 마세요. Metadata TTL과 논리 `expiresAt`은 같은 epoch second로 기록됩니다.

```kotlin
val schema = DynamoDbCoordinationSchema(
    tableName = "coordination",
    namespace = "orders",
)
val lock = DynamoDbDistributedLock(client, schema)
val metadata = DynamoDbMetadataStore(client, schema)

val lease = lock.tryAcquire("orders", "worker-1")
if (lease != null) {
    // downstream 쓰기에서도 lease.fencingToken을 조건으로 확인합니다.
    val renewed = lock.heartbeat(lease)
    lock.release(renewed ?: lease)
}
metadata.put("config", "v1", ttl = 60.seconds)
```

Acquire, renew, heartbeat, release는 adapter 내부 retry loop나 pre-read 없이 조건부 쓰기를
사용합니다. `tryAcquire`는 다른 owner가 이겼거나 결과가 indeterminate할 때 `null`을
반환할 수 있으므로 transport failure를 lease 성공으로 취급하면 안 됩니다. Takeover는
단조 증가하는 `LockLease.fencingToken`을 발급합니다. `release`는 stale lease에 `false`를
반환하고 owner만 제거하며 fencing counter는 보존합니다. 이미 시작된 side effect를 막으려면
downstream 상태 변경에서도 현재 token을 확인해야 합니다.

`MetadataStore.get`은 없거나 논리적으로 만료된 값을 `null`로 반환합니다.
`putIfAbsent`, `remove`, `removeIfValue`는 bounded compare-and-set 연산을 사용합니다.
만료된 item을 교체하거나 정리할 수 있지만 만료 item 정리는 호출자에게 `false`를
보고합니다. ABA 방지가 필요하면 value 안에 고유 version 또는 fencing token을 저장하세요.
잘못된 `AllOld`/`AllNew` 응답과 필요한 `AllOld` capability 부재는 정상적인 miss로
변환하지 않고 fail closed 예외로 처리합니다.

짧은 client에는 `withDynamoDbClient`를 사용하고 application 범위 client는 명시적으로
닫으세요. table cleanup을 소유한 test 또는 application coroutine이 취소되면
`NonCancellable`과 제한된 `withTimeout` 안에서 cleanup하고, unscoped background cleanup
task를 만들지 않습니다. Credential, IAM policy, downstream fencing 조건은 application의
책임입니다.

로컬 계약 테스트는 실제 AWS에 연결하지 않고 FlociServer만 사용합니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest'
```

Floci lane은 PK-only schema, conditional `AllOld`, fencing과 logical expiry 동작을
검증합니다. AWS throttling, 비동기 TTL 삭제, clock skew, 운영 quota, credential은 검증하지
않습니다.

## DynamoDB Streams Flow와 checkpoint {#dynamodb-streams}

> 미출시/develop: 이 절은 Issue #469 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

native AWS SDK for Kotlin 확장은 한 shard를 읽는 `DynamoDbStreamsClient.recordFlow`와
제한된 동시성으로 shard graph를 읽는 `shardRecordFlow`를 제공합니다. 시작 위치는
`TrimHorizon`, `Latest`, `AtSequenceNumber`, `AfterSequenceNumber`이고 SDK `Record` 타입을
그대로 유지하며 다중 shard 수집에는 stream/shard envelope를 추가합니다.

```kotlin
withDynamoDbStreamsClient(region = "ap-northeast-2") { client ->
    client.shardRecordFlow(
        streamArn = streamArn,
        checkpointStore = InMemoryDynamoDbStreamsCheckpointStore(),
    ).collect { envelope ->
        handle(envelope.record)
    }
}
```

Checkpoint는 downstream emission 뒤에 저장하고 저장된 sequence를 포함해 재생하므로
at-least-once 계약을 지키며 재시작 시 중복이 생길 수 있습니다. Root shard tree는
`maxShardConcurrency`로 제한하고 child는 parent 완료 뒤 시작합니다. 짧은 client에는
`withDynamoDbStreamsClient`를 사용하고 주입한 HTTP engine은 호출자가 소유합니다. Floci를
첫 emulator 검증 경로로 사용하며 운영 retention, throttling, resharding timing은 AWS-only
공백으로 남깁니다.

## Kinesis 멀티 샤드 consumer {#kinesis-consumer}

> 미출시/develop: 이 절은 Issue #470 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

Native `KinesisClient.consumerFlow`는 shard를 계속 발견하고 각 shard를 순차적으로
polling하며 `maxShardConcurrency`로 active shard job을 제한합니다. Parent와 adjacent
parent dependency를 graph에 보존하고, 모든 부모의 durable `KinesisCheckpoint.ShardEnd`가
완료된 뒤에만 child를 시작합니다. 단일 outer emitter가 rendezvous backpressure를
제공하므로 downstream emission이 반환된 뒤에 checkpoint를 저장합니다.

```kotlin
withKinesisClient(endpointUrl = flociEndpoint, region = "us-east-1") { client ->
    client.consumerFlow(
        streamName = "orders",
        consumerGroup = "orders-api",
        streamIdentity = "orders-generation-1",
        position = KinesisStartingPosition.TrimHorizon,
        options = KinesisConsumerOptions(ownerId = "orders-worker-1"),
        checkpointStore = durableCheckpointStore,
        leaseStore = durableLeaseStore,
    ).collect { envelope -> handle(envelope.record) }
}
```

`Sequence`는 해당 sequence를 포함해 재생하므로 at-least-once 계약이며 마지막 record가
중복될 수 있습니다. `KinesisCheckpointStore`와 `KinesisLeaseStore`는 호출자 소유 SPI이고,
`InMemory*` 구현은 테스트와 emulator 실행용입니다. `Noop*` 구현은 재시작 복구와 worker
조정을 제공하지 않는 process-local 선택입니다. Lease counter가 stale save를 fencing하며,
metrics에는 유한 label과 redacted token만 노출되고 payload·credential·request token은
전달되지 않습니다. Client와 health probe는 호출자가 소유하고 cancellation이 stop/drain
경계를 수행합니다.

로컬 검증은 Floci를 사용하고 LocalStack은 명시적인 coverage gap에만 사용합니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-kotlin:test --tests '*KinesisConsumerFlociTest'
```

Floci는 emulator 계약을 증명하지만 운영 retention이나 throttling을 증명하지는 않습니다.
Rollout은 stop → drain → canary → scale 순서로 진행하며, rollback 때는 마지막 durable
checkpoint를 재사용하고 삭제하거나 되감지 않습니다.

## Lambda 호출 helper {#lambda}

> 미출시/develop: 이 절은 Issue #314 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

AWS Kotlin SDK 모듈은 `io.bluetape4k.aws.kotlin.lambda` 아래에 native suspend `Invoke`
helper를 제공합니다. `LambdaInvocationResult<T>`는 raw SDK response, 복사한 payload,
status, 선택적 `FunctionError`, 디코드한 tail log를 함께 보존합니다. 함수 오류는 반환
데이터이고 transport와 SDK 오류는 예외로 남습니다.

### 의존성과 client 경계 {#lambda-dependency}

Lambda SDK는 `compileOnly`로 유지합니다. Consumer classpath에 직접 추가하고 짧은 호출에는
범위가 지정된 client helper를 사용하세요.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:lambda")
}
```

```kotlin
import io.bluetape4k.aws.kotlin.lambda.invokeString
import io.bluetape4k.aws.kotlin.lambda.withLambdaClient

suspend fun invokeOrder(): String =
    withLambdaClient(region = "ap-northeast-2") { client ->
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

withLambdaClient(region = "ap-northeast-2") { client ->
    val result = client.invokeTyped(
        "orders-handler",
        OrderRequest(1),
        LambdaPayloadCodecs.jackson(mapper, OrderResponse::class.java),
    )
    check(result.value != null)
}
```

`invokeBytes`, `invokeString`, `invokeTyped` 중 필요한 API와 애플리케이션이 소유한
`LambdaPayloadCodec`을 사용하세요. Null과 빈 payload는 구분합니다. Request DSL은 blank
function/qualifier를 검사하고 `RequestResponse`에서만 tail log를 허용합니다. ARN, 크기,
IAM, 함수 존재 여부는 AWS 계약으로 남깁니다.

Native suspend cancellation은 그대로 전달되며 `withLambdaClient`는 service client만
닫고 주입한 HTTP engine은 호출자 소유로 남깁니다. Retry, 배포, polling, 로깅, IAM policy
관리는 추가하지 않습니다. Opt-in smoke lane은 사전 배포된 함수와 명시적인 function/region
입력이 필요하며 Floci Lambda는 미지원으로 기록하고 입력이 없으면 client 생성 전에
건너뜁니다. Raw payload, 디코드한 tail log, SDK response body는 기본적으로 기록하거나
저장하지 마세요.

## S3 Tables 관리 {#s3-tables}

> 미출시/develop: 이 절은 Issue #311 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

native AWS SDK for Kotlin 확장은 S3 Tables request·response·exception 타입을 유지하면서 table
bucket, namespace, table의 생성·목록·조회·삭제를 제공합니다. 목록은 raw service의 한 페이지를
반환하므로 다음 페이지에는 `continuationToken`을 호출자가 전달합니다. `ListTables`의
`namespace`는 선택 사항이므로 bucket 범위 목록에도 사용할 수 있습니다. `CreateTable` 기본값은
`OpenTableFormat.Iceberg`이고 `GetTable`은 table ARN 또는 bucket/namespace/name selector를
사용합니다.

서비스 SDK는 `compileOnly`이므로 consumer가 직접 추가해야 합니다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:s3tables")
}
```

```kotlin
withS3TablesClient(region = "ap-northeast-2") { client ->
    val bucketArn = client.createTableBucket("orders-tables").arn
    client.createNamespace(bucketArn, listOf("analytics"))
    client.createTable(bucketArn, "analytics", "orders")
}
```

`s3TablesClientOf`가 반환한 application-scoped client를 닫는 책임은 호출자에게 있습니다.
`withS3TablesClient`는 block이 끝날 때 service client만 닫고, 주입한 HTTP engine은 호출자가
관리합니다. 이 모듈은 management operation만 제공하며 Iceberg data plane, SQL, Athena, Glue, Redshift, Apache Iceberg
통합 facade를 구현하지 않습니다. 로컬 emulator의 S3 Tables fidelity도 보장하지 않습니다.

## Step Functions 실행 helper {#step-functions}

> 미출시/develop: 이 절은 Issue #313 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

AWS Kotlin SDK 경로는 `io.bluetape4k.aws.kotlin.sfn` 패키지에
`StartExecution`, `StopExecution`, `DescribeExecution`, `ListExecutions`를 위한 native
suspend helper를 추가합니다. Helper는 AWS Kotlin SDK raw 응답을 반환하고 SDK의 예외와
상태 타입을 그대로 노출합니다.

### Dependency boundary {#step-functions-dependency}

모듈은 Step Functions SDK를 `compileOnly`로 유지합니다. 애플리케이션은 모듈과 함께
서비스 SDK를 compile/runtime classpath에 직접 추가해야 합니다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:sfn")
}
```

버전은 중앙 Bluetape4k BOM으로 맞추세요. Region, credentials provider, endpoint, HTTP
engine과 close 경계도 애플리케이션이 소유합니다.

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

`listExecutionsByStateMachine`과 `listExecutionsByMapRun`은 source invariant를 명시적으로
유지합니다. 전체 페이지가 필요하면 AWS Kotlin SDK의 `listExecutionsPaginated` paginator를
직접 사용할 수 있습니다. `PENDING_REDRIVE`는 raw terminal 응답이지 business success
결과가 아닙니다. Express 실행을 CloudWatch Logs로 관찰하는 경로는 이 helper 범위 밖에
둡니다.

### Polling and cancellation {#step-functions-polling}

`SfnClient.describeExecutionFlow(...)`는 cold
`Flow<DescribeExecutionResponse>`를 반환합니다. Collect를 시작하면
`DescribeExecution`을 즉시 한 번 호출하고, `Running` 응답을 emit한 뒤
`Succeeded`, `Failed`, `TimedOut`, `Aborted`, `PendingRedrive` 중 알려진 terminal
응답을 emit하고 종료합니다. `SfnExecutionPollingOptions.pollInterval` 기본값과 최솟값은
1초입니다. 1초는 collector 하나의 하한일 뿐 account/Region quota를 보장하지 않습니다.

```kotlin
import aws.sdk.kotlin.services.sfn.model.DescribeExecutionResponse
import io.bluetape4k.aws.kotlin.sfn.describeExecutionFlow
import io.bluetape4k.aws.kotlin.sfn.withSfnClient
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

suspend fun awaitExecution(executionArn: String): DescribeExecutionResponse =
    withSfnClient(region = "ap-northeast-2") { client ->
        withTimeout(30.seconds) {
            client.describeExecutionFlow(executionArn).last()
        }
    }
```

제한 시간이나 deadline은 호출자가 지정합니다. Cancellation은 그대로 전파되며 Flow가
`StopExecution`을 자동 호출하지 않습니다. `withSfnClient`는 블록이 끝나면 service client를
닫지만 주입한 HTTP engine은 호출자 소유로 남깁니다. Flow를 닫힌
`withSfnClient` 블록 밖으로 반환하지 말고 client 범위 안에서 collect를 완료하세요. SDK가
알 수 없는 상태를 반환하면 응답을 emit하지 않고 fail-closed로 종료합니다.

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
민감한 식별자에는 plain hash 대신 caller가 관리하는 HMAC을 사용하세요. Custom `endpointUrl`
override, credentials provider와 HTTP engine은 caller가 소유한 trust boundary입니다. 신뢰하는
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

사용할 `aws.sdk.kotlin:<service>` 모듈을 명시적으로 추가하세요. native Kotlin SDK client가 알맞은 Ktor 통합에서도 사용할 수 있습니다.

## 설정 {#configuration}

클라이언트를 만들 때 region, credential provider, endpoint, retry strategy, CRT 또는 OkHttp engine을 선택합니다.

## 실패 유형과 해결 방법 {#failures}

Java SDK v2 모델과 Kotlin SDK 모델을 섞지 마세요. 제한 없는 Flow 수집, 누락된 서비스 모듈, 닫히는 범위 밖에서 생성한 client를 점검하세요.

## 운영 {#operations}

호출량이 많다면 장수명 client를 공유하고, 범위가 짧은 작업에는 `with...Client`를 사용하세요. 선택한 HTTP engine과 timeout 정책도 운영 문서에 남겨야 합니다.

## 테스트 {#testing}

서비스 테스트는 Floci에서 먼저 실행하고, native Kotlin SDK 기능을 지원하지 않을 때만 emulator를 명시적으로 바꾸세요.

## 학습 경로와 예제 {#workshops}

S3 요청 빌더부터 시작해 DynamoDB 모델 변환을 익힌 뒤 Kinesis 또는 pagination Flow 처리로 넓혀 가세요.

## 제약 사항 {#limitations}

Java SDK v2 호환 wrapper가 아니므로 타입과 서비스 지원 범위가 다를 수 있습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Kotlin 아키텍처

[![Bluetape4k AWS Kotlin 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-architecture-01.svg)

_배포본 README: [`aws-kotlin/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.ko.md)_

### Bluetape4k AWS Kotlin operation 처리 흐름

[![Bluetape4k AWS Kotlin operation 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-flow-02.svg)

_배포본 README: [`aws-kotlin/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.ko.md)_

### Bluetape4k AWS Kotlin client 수명 주기 시퀀스 다이어그램

[![Bluetape4k AWS Kotlin client 수명 주기 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-kotlin-sequence-03.svg)

_배포본 README: [`aws-kotlin/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-kotlin/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/s3/S3ClientSupport.kt)
- [릴리스 소스: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt`](../../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisRecordFlow.kt)
- [릴리스 테스트: emulator 선택](../../../../aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt)
