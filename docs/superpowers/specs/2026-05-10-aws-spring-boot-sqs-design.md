# AWS Spring Boot SQS 설계

날짜: 2026-05-10 KST
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/2
작업 트리: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs`
브랜치: `feat/2-spring-boot-sqs`

## 문제

`aws-spring-boot`에는 새로 병합된 S3 Spring Boot 패턴을 따르면서 awspring에 의존하지 않는
SQS 통합이 필요합니다.

- SQS가 런타임 클래스 경로에 있을 때만 AWS SDK v2 클라이언트를 자동 구성합니다.
- 코루틴 친화적인 발송/수신/삭제 API를 노출합니다.
- `suspend` 핸들러를 포함한 애너테이션 기반 SQS 리스너 메서드를 지원합니다.
- 명시적 ack/nack 동작을 갖는 코루틴 기반 리스너 루프를 제공합니다.
- 롱 폴링, 배치 수신, DLQ 관련 큐 속성과 LocalStack/Testcontainers 테스트를 지원합니다.

구현은 awspring에 의존하지 않아야 합니다. 기존 `aws` 모듈의 SQS 도우미와 코루틴 확장을
재사용할 수 있습니다.

## 현재 근거

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt`는
  현재 Spring Boot 서비스 자동 구성 패턴입니다. `@AutoConfiguration`, 문자열 기반
  `@ConditionalOnClass`, `@ConditionalOnProperty`, `@EnableConfigurationProperties`,
  `@ConditionalOnMissingBean`, Spring 소유 AWS SDK 클라이언트 빈을 사용합니다.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  파일은 현재 `AwsAutoConfiguration`과 `S3AutoConfiguration`을 등록합니다.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientCoroutinesExtensions.kt`
  파일은 이미 `createQueue`, `getQueueUrl`, `send`, `sendBatch`, `receiveMessages`,
  `changeMessageVisibility`, `deleteMessage`, `deleteMessageBatch`, `deleteQueue`의
  일시 중단 래퍼를 제공합니다.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/SqsAsyncClientExtensions.kt`
  파일은 SQS 수신 배치 크기 `1..10`을 검증하고 `CompletableFuture` API를 감쌉니다.
- `aws/src/main/kotlin/io/bluetape4k/aws/sqs/model/ReceiveMessage.kt`
  파일은 SQS 롱 폴링 제한인 `maxNumberOfMessages` `1..10`, `waitTimeSeconds` `0..20`을 기록합니다.
- `aws/src/test/kotlin/io/bluetape4k/aws/sqs/AbstractSqsTest.kt` and
  `SqsAsyncClientTest.kt`는 LocalStack 큐 생성과 발송/수신/삭제 테스트 패턴을 보여 줍니다.
- Spring Boot 4.0.3 문서에 따르면 라이브러리 자동 구성은
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`를 통해 등록합니다.
  또한 라이브러리 관리 `@ConfigurationProperties`에는 `@EnableConfigurationProperties`,
  자동 구성 테스트에는 `ApplicationContextRunner`, 선택적 의존성 부재 테스트에는
  `FilteredClassLoader` 사용을 권장합니다.
- AWS SDK Java v2 SQS 문서는 `SqsAsyncClient`/`SqsClient`의 `sendMessage`,
  `receiveMessage`, `deleteMessage`, `changeMessageVisibility`, `createQueue`, `getQueueUrl`
  작업을 사용합니다. 수신에는 `maxNumberOfMessages`, 롱 폴링에는 `waitTimeSeconds`를 사용합니다.

## 제약 사항

- Kotlin 2.3, Java 21, Spring Boot 4를 사용합니다.
- AWS 서비스 SDK 의존성은 `compileOnly`로 유지하고 소비자가 런타임 의존성을 추가합니다.
- `software.amazon.awssdk:sqs`가 없어도 자동 구성이 안전해야 합니다.
- 자동 구성된 SDK 클라이언트 수명 주기는 Spring이 소유해야 합니다. 클라이언트를
  `ShutdownQueue`에 등록하는 `SqsClientFactory`는 사용하지 않습니다.
- 공개 API KDoc은 한국어로 작성합니다.
- 운영 코드는 `runBlocking`, `GlobalScope`, `Thread.sleep`을 사용하지 않아야 합니다.
- 리스너 컨테이너는 Spring 수명 주기 중지 시 협력적으로 중지하고 코루틴을 취소해야 합니다.
- 리스너 호출이 실패하면 메시지를 삭제하지 않아야 합니다.

## 목표

1. SQS Spring Boot 자동 구성을 추가합니다.
2. `bluetape4k.aws.sqs` 아래 형식화된 SQS 속성을 추가합니다.
3. `SqsOperations`와 `SqsCoroutinesTemplate`을 추가합니다.
4. `@SqsListener` 애너테이션과 리스너 엔드포인트 등록을 추가합니다.
5. 코루틴 폴링을 사용하는 `SqsMessageListenerContainer`를 추가합니다.
6. ack/삭제 및 nack/가시성 변경 동작을 지원합니다.
7. 발송/수신/삭제와 리스너 ack/nack의 LocalStack 테스트를 지원합니다.
8. README/README.ko를 갱신합니다.

## 제외 범위

- awspring 의존성을 추가하지 않습니다.
- Spring Cloud AWS 호환 계층을 제공하지 않습니다.
- 메시지 속성을 보존하고 AWS SDK가 이미 지원하는 메시지 그룹/중복 제거 ID를 노출하는 범위를
  넘어선 FIFO 전용 고수준 추상화를 제공하지 않습니다.
- 첫 PR에는 JSON 변환 프레임워크를 추가하지 않습니다. 리스너 핸들러는 원시 메시지 본문이나
  AWS `Message`/사용자 정의 래퍼를 받습니다.
- 이 이슈에서는 SNS 팬아웃을 처리하지 않습니다. 해당 작업은 `#4`와 예제 `#13`이 담당합니다.

## 아키텍처 사전 설계

### 구성 요소

```text
SqsAutoConfiguration
  -> SqsProperties
  -> SqsAsyncClient
  -> SqsOperations / SqsCoroutinesTemplate
  -> SqsMessageListenerContainerRegistry
  -> SqsListenerAnnotationBeanPostProcessor
       -> SqsMessageListenerContainer per @SqsListener method
```

### 런타임 흐름

```text
Spring context refresh
  -> SqsAutoConfiguration registers SqsAsyncClient and template
  -> BeanPostProcessor scans beans for @SqsListener methods
  -> Registry creates listener containers
  -> SmartLifecycle starts containers after context refresh
  -> Container loop:
       receiveMessage(queueUrl/name, maxMessages, waitTimeSeconds)
       for each Message:
         invoke handler
         on success -> deleteMessage
         on failure -> changeMessageVisibility or leave message untouched
```

## API 설계

### 패키지

새 Spring SQS 코드는 모두 다음 패키지 아래에 둡니다.

```text
io.bluetape4k.aws.spring.sqs
```

### `SqsProperties`

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs")
data class SqsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val listener: Listener = Listener(),
    val queues: Map<String, Queue> = emptyMap(),
) {
    data class Listener(
        val enabled: Boolean = true,
        val autoStartup: Boolean = true,
        val phase: Int = Int.MAX_VALUE,
        val maxMessages: Int = 10,
        val waitTimeSeconds: Int = 20,
        val visibilityTimeoutSeconds: Int? = null,
        val errorVisibilityTimeoutSeconds: Int? = null,
        val concurrency: Int = 1,
        val stopTimeoutMillis: Long = 25_000,
    )

    data class Queue(
        val url: String? = null,
        val redrivePolicy: RedrivePolicy? = null,
    )

    data class RedrivePolicy(
        val deadLetterTargetArn: String,
        val maxReceiveCount: Int,
    )
}
```

검증:

- `endpointOverride != null`이면 비어 있지 않은 `region`이 필요합니다.
- `maxMessages` 범위는 `1..10`입니다.
- `waitTimeSeconds` 범위는 `0..20`입니다.
- 가시성 제한 시간 값이 있으면 `0..43_200`초 범위여야 합니다.
- `concurrency >= 1`이어야 합니다.
- `stopTimeoutMillis >= 1`이어야 합니다.
- `RedrivePolicy.deadLetterTargetArn`은 비어 있지 않고 `maxReceiveCount >= 1`이어야 합니다.

검증은 현재 S3 속성 형식에 맞춰 `init {}` 블록에서 구현합니다. 첫 구현은 DLQ 큐를
자동 생성하지 않지만, 호출자가 이름 있는 큐 구성을 명시적으로 생성하면
`createConfiguredQueue`가 `RedrivePolicy`를 SQS `RedrivePolicy` 큐 속성으로 적용합니다.

### `SqsOperations`

```kotlin
interface SqsOperations {
    suspend fun getQueueUrl(queueName: String): String
    suspend fun createQueue(queueName: String, attributes: Map<QueueAttributeName, String> = emptyMap()): String
    suspend fun createConfiguredQueue(queueName: String): String
    suspend fun send(queueUrl: String, body: String, delaySeconds: Int? = null): SendMessageResponse
    suspend fun receive(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): List<SqsReceivedMessage>
    suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse
    suspend fun changeVisibility(queueUrl: String, receiptHandle: String, timeoutSeconds: Int): ChangeMessageVisibilityResponse
    fun receiveFlow(
        queueUrl: String,
        maxMessages: Int = 10,
        waitTimeSeconds: Int = 20,
        visibilityTimeoutSeconds: Int? = null,
    ): Flow<SqsReceivedMessage>
}
```

`SqsReceivedMessage`는 AWS SDK `Message`와 큐 URL을 감쌉니다.

```kotlin
data class SqsReceivedMessage(
    val queueUrl: String,
    val message: Message,
) {
    val body: String get() = message.body()
    val receiptHandle: String get() = message.receiptHandle()
}
```

### `SqsCoroutinesTemplate`

`SqsCoroutinesTemplate`은 가능한 경우 기존 `aws` 모듈 SQS 코루틴 확장에 위임하여
`SqsOperations`를 구현합니다. 편의 확장이 다루지 않는 옵션은 `SqsAsyncClient`를 직접 호출할 수 있습니다.

### `@SqsListener`

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SqsListener(
    val queue: String,
    val id: String = "",
    val maxMessages: Int = -1,
    val waitTimeSeconds: Int = -1,
    val visibilityTimeoutSeconds: Int = -1,
    val errorVisibilityTimeoutSeconds: Int = -1,
    val autoStartup: Boolean = true,
)
```

`queue`는 다음 중 하나를 받습니다.

- 전체 큐 URL
- `SqsOperations.getQueueUrl`을 통해 해석되는 큐 이름
- `SqsProperties.queues`의 키

첫 PR에서 지원하는 핸들러 시그니처:

```kotlin
@SqsListener("queue-name")
fun handle(body: String)

@SqsListener("queue-name")
suspend fun handle(body: String)

@SqsListener("queue-name")
fun handle(message: Message)

@SqsListener("queue-name")
suspend fun handle(message: SqsReceivedMessage)
```

지원하지 않는 시그니처는 컨텍스트 시작 중 `IllegalArgumentException`으로 빠르게 실패합니다.

큐/ID 애너테이션 값은 `Environment.resolvePlaceholders`를 통해 해석하므로 `${app.queue}`가
동작합니다. 첫 PR에서는 SpEL을 지원하지 않으며 `#{...}`를 포함한 값은 명확한 오류로 빠르게 실패합니다.

애너테이션 스캐너는 프록시 빈의 메서드를 발견하도록 `AopUtils.getTargetClass`로 사용자
클래스를 검사해야 합니다. `@Transactional` 같은 어드바이스가 계속 활성화되도록 호출은
Spring 빈 프록시를 거쳐야 합니다.

### `SqsMessageListenerContainer`

컨테이너는 Spring `SmartLifecycle`을 구현합니다.

- `start()`는 상위 `SupervisorJob` 하나를 시작합니다.
- 리스너가 늦게 시작하고 일찍 중지하도록 `phase` 기본값은 `Int.MAX_VALUE`입니다.
- `concurrency`는 폴링 코루틴 수를 뜻합니다.
- 각 폴링 코루틴은 폴링 한 번에 최대 `maxMessages`개 메시지를 받습니다.
- 수신 배치 안의 메시지는 해당 폴링 코루틴이 순차 처리하며 전체 병렬성은 `concurrency`로 제한합니다.
- 동기 핸들러는 `Dispatchers.IO`의 컨테이너 코루틴에서 실행하고, 일시 중단 핸들러도 같은
  컨테이너 코루틴 컨텍스트에서 호출합니다.
- `stop()`은 상위 작업을 취소하고 새 수신을 중지합니다.
- `stop(callback)`은 최대 `stopTimeoutMillis`까지 기다린 뒤 AWS HTTP 롱 폴링 퓨처가 취소를
  관찰하지 않았더라도 콜백을 호출합니다.
- 중지 후 반환된 진행 중 수신 결과는 무시합니다.
- 각 메시지는 독립적으로 처리합니다.
- 성공하면 메시지를 삭제합니다.
- 실패하면 구성된 `errorVisibilityTimeoutSeconds`로 가시성을 변경합니다. 값이 null이면
  큐 가시성 제한 시간 동안 메시지를 그대로 둡니다.
- 일반 핸들러 오류 처리 전에 `CancellationException`을 다시 던집니다.

리스너 컨테이너는 `Thread.sleep`을 사용하지 않고 롱 폴링과 코루틴 취소에 의존해야 합니다.

첫 PR에는 자동 가시성 하트비트가 없습니다. 핸들러가 큐 가시성 제한 시간을 넘을 수 있는
사용자는 더 큰 `visibilityTimeoutSeconds`를 구성하거나 후속 작업에서 명시적 가시성 연장을
추가해야 합니다. SQS는 최소 한 번 전달을 유지하므로 핸들러는 멱등이어야 합니다.

### `SqsMessageListenerContainerRegistry`

레지스트리는 리스너 컨테이너를 Spring 수명 주기 빈으로 소유하며 다음을 제공합니다.

- `register(container)`
- `getContainer(id)`
- `containers`
- `SmartLifecycle`을 통한 `start/stop` 전달

이를 통해 애너테이션 처리와 컨테이너 수명 주기를 분리합니다.

큐 URL 해석은 컨테이너 시작 시 한 번 수행하고 컨테이너 수명 동안 캐시합니다. 해석 순서는 다음과 같습니다.

1. 구성된 `queues[name].url`
2. `http://` 또는 `https://`로 시작하는 리터럴 URL
3. `SqsOperations.getQueueUrl(queueName)`

### `SqsListenerAnnotationBeanPostProcessor`

초기화된 빈에서 `@SqsListener`가 붙은 메서드를 검색합니다. 시작 시 메서드 호출자를 한 번
해석하고 레지스트리에 컨테이너를 등록합니다.

일시 중단 감지와 호출에 필요한 곳에서만 Kotlin 리플렉션을 사용합니다. Kotlin 리플렉션을
사용할 수 없거나 너무 무거우면 API를 검증한 뒤에만 Spring의 코루틴 인식 호출 지원을 사용합니다.
이 저장소의 공통 하위 프로젝트 의존성에 Kotlin 리플렉션이 이미 있으므로 첫 구현은
`kotlin.reflect.full.callSuspend`를 사용할 수 있습니다.

## `receiveFlow` 계약

`SqsOperations.receiveFlow`는 차갑고 무한한 흐름을 반환합니다. 각 수집은 자체 수신 루프를
시작하고 `SqsReceivedMessage` 값을 방출합니다. 흐름은 메시지를 자동으로 삭제하지 않으며,
소비자가 `delete` 또는 `changeVisibility`를 명시적으로 호출합니다. 취소하면 이후 수신을
중지하고 취소 뒤 도착한 진행 중 수신 결과는 무시합니다.

## 의존성 변경

`aws-spring-boot/build.gradle.kts`:

```kotlin
compileOnly(libs.aws2.sqs)
testImplementation(libs.aws2.sqs)
```

이미 존재하는 Spring Boot, Kotlin 코루틴, AWS SDK v2 산출물 외에 새 외부 런타임 의존성을 추가하지 않습니다.

`kotlin-reflect`는 루트 하위 프로젝트 의존성에 이미 구성되어 있으므로 일시 중단 핸들러
리플렉션에 사용할 수 있습니다.

## 설계 선택지

### 선택지 A - 템플릿만 제공

`SqsAutoConfiguration`, `SqsProperties`, `SqsCoroutinesTemplate`을 추가하되 애너테이션
리스너는 추가하지 않습니다.

장점:

- 작고 위험이 낮습니다.
- LocalStack 테스트가 단순합니다.

단점:

- `@SqsListener`에 대한 이슈 목표를 충족하지 못합니다.
- 사용자가 여전히 폴링 루프를 작성해야 합니다.

결정: 이 이슈에서는 기각합니다.

### 선택지 B - 최소 리스너 컨테이너와 원시 메시지 변환

템플릿과 가벼운 애너테이션 프로세서 및 컨테이너를 추가합니다. 원시 `String`, AWS `Message`,
`SqsReceivedMessage` 핸들러 파라미터만 지원합니다.

장점:

- 이슈 범위를 충족합니다.
- 변환과 재시도 의미를 명시적으로 유지합니다.
- 하나의 PR로 처리할 만큼 작습니다.
- awspring 호환성 약속을 피합니다.

단점:

- 아직 JSON/객체 변환이 없습니다.
- 고급 리스너 엔드포인트 레지스트리 의미가 없습니다.

결정: 선택합니다.

### 선택지 C - awspring 형태의 리스너 프레임워크

페이로드 변환, 헤더 매핑, 수동 ack 객체, 재시도 정책 DSL, 큐 생성을 갖춘 더 풍부한
엔드포인트 등록기를 구축합니다.

장점:

- 기능이 더 완전합니다.

단점:

- `#2`에는 범위가 너무 넓습니다.
- Spring Cloud AWS를 성급하게 재구현할 위험이 큽니다.
- 테스트 매트릭스가 더 큽니다.

결정: 첫 SQS PR에서는 기각합니다.

## 실패 형태 및 완화책

| 위험 | 영향 | 완화책 |
|---|---|---|
| 선택적 SQS SDK가 없는데 자동 구성이 구체 클래스를 너무 일찍 참조함 | 앱 시작 실패 | 자동 구성 클래스 수준에서 문자열 `@ConditionalOnClass`를 사용하고 S3 패턴을 따르며 `FilteredClassLoader` 테스트를 추가합니다. |
| 핸들러 실패 후 리스너가 메시지를 삭제함 | 데이터 손실 | 핸들러가 성공한 뒤에만 삭제하며 실패 시 가시성 제한 시간을 적용하거나 아무 작업도 하지 않습니다. |
| 컨텍스트 종료 시 리스너 루프가 코루틴을 누출함 | 테스트/앱 종료 멈춤 | `SmartLifecycle.stop`이 `SupervisorJob`을 취소하고 테스트에서 컨테이너 중지를 확인합니다. |
| 롱 폴링이 종료를 막음 | 느린 종료 | 중지는 새 수신을 차단하고 늦은 결과를 무시하며 수명 주기 콜백 완료에 `stopTimeoutMillis`를 사용합니다. |
| 핸들러 오류 경로가 `CancellationException`을 삼킴 | 리스너 중지 거부 | `CancellationException`을 먼저 포착해 다시 던집니다. |
| 프록시 Spring 빈이 `@SqsListener` 애너테이션을 숨김 | 리스너가 조용히 미등록됨 | `AopUtils.getTargetClass(bean)`을 검색하고 프록시 빈을 통해 호출합니다. |
| 핸들러 시그니처 지원이 모호함 | 런타임 예외 상황 | 지원하지 않는 시그니처는 컨텍스트 시작 시 빠르게 실패합니다. |
| 큐 이름과 URL 해석이 불명확함 | 리스너 시작 불가 | 해석 순서는 구성된 `queues[name].url`, 전체 URL, `getQueueUrl(queueName)`이며 이를 문서화합니다. |
| 핸들러가 가시성 제한 시간보다 오래 실행됨 | 중복 처리 | 첫 PR에는 하트비트가 없으며 멱등성과 가시성 크기 설정을 문서화합니다. |
| DLQ 범위가 전체 프로비저닝으로 확대됨 | 기능 범위 확대 | 첫 PR은 `createConfiguredQueue`를 명시적으로 호출할 때만 `RedrivePolicy`를 적용합니다. |

## 인수 기준

- `SqsAutoConfiguration`이 `AutoConfiguration.imports`에 등록됩니다.
- SQS SDK가 있으면 `SqsAsyncClient`를 자동 구성하고 사용자 빈이 있으면 물러납니다.
- `SqsProperties`가 엔드포인트/수신/리스너 제약을 바인딩하고 검증합니다.
- `SqsOperations`와 `SqsCoroutinesTemplate`이 생성/조회/발송/수신/삭제/가시성 변경/흐름을 지원합니다.
- `@SqsListener`가 `String`, `Message`, `SqsReceivedMessage`의 동기 및 일시 중단 핸들러를 지원합니다.
- `@SqsListener` 큐/ID 값의 `${...}` 자리표시자를 지원하며 SpEL 표현식은 빠르게 실패합니다.
- `@SqsListener` 메서드가 있는 프록시 빈을 발견합니다.
- `SqsMessageListenerContainer`는 성공 시 ack하고 실패 시 삭제하지 않습니다.
- 리스너 종료가 `stopTimeoutMillis`를 넘어 멈추지 않습니다.
- `receiveFlow`를 명시적 ack 차가운 흐름으로 문서화하고 테스트합니다.
- LocalStack 테스트가 템플릿 발송/수신/삭제와 리스너 ack/nack을 다룹니다.
- 테스트가 `FilteredClassLoader`를 통한 SQS SDK 부재를 다룹니다.
- 테스트가 지원하지 않는 리스너 메서드 시그니처의 빠른 실패를 다룹니다.
- 테스트가 구성된 폴링 코루틴 수를 초과하지 않으면서 `concurrency > 1` 수신을 다룹니다.
- README.md와 README.ko.md가 SQS 자동 구성과 리스너 사용법을 보여 줍니다.
- README.md와 README.ko.md가 SQS 최소 한 번 전달과 멱등 핸들러 책임을 문서화합니다.
- 변경 후에도 야간 예제 작업이 유효합니다.

## 검증 계획

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`
- `./gradlew :aws-spring-boot:test --no-daemon`
- `./gradlew :aws-spring-boot:koverHtmlReport --no-daemon`
- `./gradlew detekt --parallel --no-daemon`
- `./gradlew build -x test --parallel --no-daemon`
- `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
- `rg 'CancellationException' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs`
- `yq e '.' .github/workflows/nightly.yml >/dev/null`
- `git diff --check`

## 미해결 질문

사용자를 차단하는 질문은 없습니다. 선택한 첫 구현은 메시지 변환을 의도적으로 원시 상태로
유지하고 JSON/객체 변환을 후속 이슈로 미룹니다.

이 PR에서는 다음 정책을 확정합니다.

- `concurrency`는 폴링 코루틴 수입니다.
- `${...}` 자리표시자는 지원하고 SpEL은 지원하지 않습니다.
- 실패한 핸들러는 기본적으로 큐 가시성 제한 시간을 유지합니다.
- DLQ 지원은 명시적 `createConfiguredQueue` 재구동 속성으로 제한합니다.
- 가시성 하트비트와 Micrometer 메트릭은 연기합니다.

## 단계 체크리스트

### 0단계 - 작업 트리 설정

| 항목 | 상태 | 메모 |
|---|---|---|
| 기능 작업 트리 생성 | 완료 | `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs` |
| 작업 트리 내부에서 명령 실행 | 완료 | 명세 경로가 작업 트리 안에 있습니다. |
| 작업 트리 내부에 명세/계획 작성 | 완료 | 이 명세는 `docs/superpowers/specs`에 있습니다. |
| 현재 origin/develop에서 갱신 | 완료 | PR #28/#29 병합 후 `origin/develop`에서 작업 트리를 생성했습니다. |

### 1단계 - 요구 사항 수집

| 항목 | 상태 | 메모 |
|---|---|---|
| 대상 저장소 확인 | 완료 | `bluetape4k-aws`, 이슈 #2입니다. |
| 메모리 앵커 확인 | 완료 | PR 제목 접두사, 절대 자문 경로, 한국어 문서/KDoc, 작업 트리 위생을 확인했습니다. |
| 리뷰 전용 경계 | 해당 없음 | 사용자가 구현을 요청했습니다. |
| 구체 산출물 검사 | 완료 | GitHub 이슈 #2 본문을 검사했습니다. |
| 의도와 경계 명확화 | 완료 | #2 다음 #3 순서입니다. |

### 1-R 단계 - 명세 작성 전 조사

| 항목 | 상태 | 메모 |
|---|---|---|
| 공식 문서 확인 | 완료 | Spring Boot 4.0.3 자동 구성 문서와 AWS SDK Java v2 SQS 문서를 확인했습니다. |
| 현재 저장소 검색 | 완료 | S3 Spring Boot 패턴과 기존 SQS 도우미/테스트를 검사했습니다. |
| 서드파티 가정 확인 | 완료 | SQS 수신/삭제/가시성과 Spring 가져오기/조건부 문서를 확인했습니다. |
| 채택/차용/제외 결정 기록 | 완료 | `aws` SQS 확장은 재사용하고 awspring과 풍부한 변환은 제외했습니다. |
| 기술 제약 식별 | 완료 | compileOnly SDK, 코루틴 수명 주기, LocalStack입니다. |

### 2단계 - 브레인스토밍 및 명세

| 항목 | 상태 | 메모 |
|---|---|---|
| 아키텍처 사전 설계 | 완료 | 위의 구성 요소와 런타임 흐름입니다. |
| 조사 결과 반영 | 완료 | 현재 근거와 제약 사항 섹션입니다. |
| 현재 동작 주장에 근거 제시 | 완료 | 파일 경로를 나열했습니다. |
| 명세 경로 확인 | 완료 | 작업 트리 로컬 경로입니다. |
| 위험/실패 형태 포함 | 완료 | 실패 형태 표입니다. |
| 접근 방식 비교 포함 | 완료 | 선택지 A/B/C입니다. |
| 미해결 질문 해결 | 완료 | 차단 질문이 없습니다. |

## Claude Code Opus 자문

산출물:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/.omx/artifacts/ask-claude-aws-spring-boot-sqs-spec-20260510-184644.md`

모델: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| 심각도 | 발견 사항 | 결정 | 후속 조치 |
|---|---|---|---|
| 높음 | 동시성과 배치 처리 모델이 정의되지 않았습니다. | 수용 | `concurrency`를 폴링 코루틴 수로, 배치 처리를 폴링 코루틴별 순차 처리로 정의했습니다. |
| 높음 | 동기 핸들러 디스패처가 명시되지 않았습니다. | 수용 | 핸들러에 `Dispatchers.IO` 호출을 명시했습니다. |
| 높음 | `SmartLifecycle` 단계 기본값 `0`은 위험합니다. | 수용 | 기본값을 `Int.MAX_VALUE`로 변경했습니다. |
| 높음 | 롱 폴링 종료는 코루틴 취소에만 의존할 수 없습니다. | 수용 | `stopTimeoutMillis`, 늦은 결과 무시, 중지 콜백 정책을 추가했습니다. |
| 높음 | `CancellationException` 재전파 규칙이 없습니다. | 수용 | 명시적 취소 규칙과 검증 grep을 추가했습니다. |
| 높음 | 프록시 빈이 리스너 애너테이션을 숨길 수 있습니다. | 수용 | `AopUtils.getTargetClass` 검색과 프록시 호출 규칙을 추가했습니다. |
| 높음 | 자리표시자/SpEL 정책이 정의되지 않았습니다. | 수용 | `${...}` 자리표시자를 지원하고 첫 PR에서 SpEL을 거부합니다. |
| 높음 | `receiveFlow` 계약이 정의되지 않았습니다. | 수용 | 차갑고 무한한 흐름, 명시적 ack, 취소 의미를 추가했습니다. |
| 중간 | 사용하지 않는 DLQ 속성은 YAGNI입니다. | 범위 제한 수용 | 느슨한 필드를 `createConfiguredQueue`에 연결된 `RedrivePolicy`로 교체했습니다. |
| 중간 | `kotlin-reflect` 런타임 가용성을 명시해야 합니다. | 수용 | 루트 의존성 가용성을 기록했습니다. |
| 중간 | 검증 메커니즘이 명시되지 않았습니다. | 수용 | S3와 같은 `init {}` 검증을 선택했습니다. |
| 중간 | 가시성 하트비트/멱등성이 빠졌습니다. | 수용 | 하트비트 없음과 최소 한 번 전달/멱등 핸들러 책임을 문서화했습니다. |
| 중간 | 테스트 매트릭스에 클래스 경로, 종료, 시그니처, 동시성 사례가 빠졌습니다. | 수용 | 인수 기준을 추가했습니다. |
