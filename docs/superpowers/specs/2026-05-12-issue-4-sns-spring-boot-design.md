# 이슈 #4 SNS Spring Boot 설계

## 배경

- 저장소: `bluetape4k-aws`
- 이슈: <https://github.com/bluetape4k/bluetape4k-aws/issues/4>
- 대상 모듈: `aws-spring-boot`
- 작업 유형: 새 Spring Boot 기능, Full Design lane
- 관련 후속 작업: 이슈 #13은 이 PR 범위에서 제외한다.

이슈 #4는 awspring에 의존하지 않는 self-managed SNS 지원을 `aws-spring-boot`에 추가하도록 요청한다. 이 기능은 Spring Boot 4 auto-configuration, coroutine publishing template, topic ARN 조회, FIFO topic 지원, fanout 중심의 사용 evidence를 제공해야 한다.

## 근거

- 기존 local pattern: `SqsAutoConfiguration`은 `@AutoConfiguration(after = [AwsAutoConfiguration::class])`, string 기반 `@ConditionalOnClass`, `@ConditionalOnProperty`, `@EnableConfigurationProperties`, `@ConditionalOnMissingBean`, `ObjectProvider<AwsCredentialsProvider>`, 선택형 `SdkAsyncHttpClient`로 SDK async client를 등록한다.
- 기존 local pattern: `SqsCoroutinesTemplate`은 `SqsOperations` 계약을 구현하고 `kotlinx.coroutines.future.await()`와 AWS SDK v2 async 호출을 사용한다.
- 기존 local pattern: `aws-spring-boot`는 production에서 service SDK dependency를 `compileOnly`로, integration test에서 `testImplementation`으로 유지한다.
- Spring Boot 4 문서는 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`를 통한 auto-configuration 탐색과 `@AutoConfiguration`, `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@EnableConfigurationProperties`를 통한 조건부 등록을 확인해 준다.
- AWS SDK Java v2 문서는 SNS publish가 `topicArn`, `message`, 선택형 `subject`, message attribute, `messageGroupId`, `messageDeduplicationId`를 갖는 `PublishRequest`를 사용하며, FIFO topic은 `FifoTopic`, `ContentBasedDeduplication`, 선택형 FIFO throughput scope 같은 topic attribute를 사용함을 확인해 준다.
- 기존 `aws` 모듈은 이미 `io.bluetape4k.aws.sns` 아래에 topic 생성 helper와 `publishRequestOf`를 포함한 SNS helper를 제공한다.

## 목표

1. `aws-spring-boot`에 `SnsAutoConfiguration`을 추가한다.
2. `bluetape4k.aws.sns`에 binding하는 `SnsProperties`를 추가한다.
3. coroutine-first `SnsOperations` 계약과 `SnsCoroutinesTemplate`을 추가한다.
4. 표준 topic 생성, 설정 기반 topic 생성, FIFO topic 생성, topic ARN 조회, publish를 지원한다.
5. `AutoConfiguration.imports`를 통해 SNS auto-configuration을 등록한다.
6. LocalStack 기반 integration test와 ApplicationContextRunner test를 추가한다.
7. SNS를 위한 README dependency/configuration snippet을 갱신한다.

## 목표가 아닌 항목

- Spring Cloud AWS 또는 awspring dependency를 추가하지 않는다.
- SNS listener/message receiver framework를 제공하지 않는다.
- 완전한 SQS-SNS application example 모듈을 제공하지 않는다. 이슈 #13의 범위다.
- SES/email 지원을 제공하지 않는다.
- cross-account subscription 관리 API를 제공하지 않는다.
- synchronous/blocking SDK client를 제공하지 않는다.

## 공개 API

패키지: `io.bluetape4k.aws.spring.sns`

### SnsProperties 설정

configuration 접두사: `bluetape4k.aws.sns`

필드:

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `topics: Map<String, Topic> = emptyMap()`

중첩 `Topic` 필드:

- `fifo: Boolean = false`
- `contentBasedDeduplication: Boolean = true`
- `fifoThroughputScope: SnsFifoThroughputScope? = null`
- `attributes: Map<String, String> = emptyMap()`

검증:

- 기존 SQS 동작과 마찬가지로 `endpointOverride`에는 `region`이 필요하다.
- `region`이 null이면 AWS SDK 기본 region provider chain을 적용한다.
- 설정한 FIFO topic의 이름은 `.fifo`로 끝나야 한다.

### SnsPublishRequest 요청

같은 type의 positional parameter 대신 request data class를 사용한다.

필드:

- `topicArn: String`
- `message: String`
- `subject: String? = null`
- `messageAttributes: Map<String, MessageAttributeValue> = emptyMap()`
- `messageGroupId: String? = null`
- `messageDeduplicationId: String? = null`

검증:

- `topicArn`과 `message`는 blank가 아니어야 한다.
- `topicArn`이 `.fifo`로 끝나면 `messageGroupId`는 blank가 아니어야 한다.
- `topicArn`이 `.fifo`로 끝나지 않으면 `messageGroupId`와 `messageDeduplicationId`는 null이어야 한다.

### SnsFifoThroughputScope 범위

enum 값:

- `TOPIC`, AWS SNS attribute value `Topic`으로 serialize
- `MESSAGE_GROUP`, AWS SNS attribute value `MessageGroup`으로 serialize

### SnsOperations 작업

메서드:

- `suspend fun createTopic(topicName: String, attributes: Map<String, String> = emptyMap()): String`
- `suspend fun createFifoTopic(
    topicName: String,
    contentBasedDeduplication: Boolean = true,
    fifoThroughputScope: SnsFifoThroughputScope? = null,
    attributes: Map<String, String> = emptyMap(),
): String`
- `suspend fun createConfiguredTopic(topicName: String): String`
- `suspend fun findTopicArn(topicName: String): String?`
- `suspend fun publish(request: SnsPublishRequest): PublishResponse`

검증:

- `topicName`은 blank가 아니어야 한다.
- FIFO topic 이름은 `.fifo`로 끝나야 한다.
- `findTopicArn`은 null을 반환하기 전에 모든 SNS `ListTopics` page를 순회해야 한다.
- `createConfiguredTopic`은 SNS `CreateTopic`에 위임한다. AWS는 기존 이름에 대해 기존 topic ARN을 반환하며, 이 method는 기존 topic에서 변경된 attribute를 reconcile하지 않는다.

## 자동 구성

`SnsAutoConfiguration`은 다음을 수행해야 한다.

- `AwsAutoConfiguration` 뒤에 실행한다.
- SNS SDK가 `compileOnly`이므로 `SdkAsyncHttpClient`와 `SnsAsyncClient`에 string 기반 `@ConditionalOnClass`를 사용한다.
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.sns", name = ["enabled"], havingValue = "true", matchIfMissing = true)`를 사용한다.
- `SnsProperties`를 활성화한다.
- destroy method `close`로 `SnsAsyncClient`를 등록한다.
- 사용할 수 있으면 custom `AwsCredentialsProvider`와 `SdkAsyncHttpClient` bean을 사용한다.
- property의 `region`과 `endpointOverride`를 적용한다.
- `@ConditionalOnMissingBean(SnsOperations::class)`으로 `SnsCoroutinesTemplate`을 `SnsOperations`로 등록한다.

## fanout 경계

이슈 #4는 SNS publisher가 SQS-SNS fanout에 참여할 수 있음을 입증해야 하지만 전체 example application은 이슈 #13의 범위다. 이 PR은 SQS subscription을 대상으로 할 수 있는 SNS topic publishing 경로를 보여주는 하나의 집중된 integration test 또는 README snippet을 추가한다. LocalStack subscription policy 동작이 불안정하면 fanout을 문서화된 사용법으로 유지하고 실행 가능한 coverage는 표준/FIFO publish integration test에 의존할 수 있다.

선호하는 실행 가능한 증거는 하나의 LocalStack SNS-to-SQS test다. topic과 queue를 만들고, queue ARN을 topic에 subscribe하고, `SnsOperations`를 통해 publish한 뒤 SQS에서 message를 수신한다. CI에서 불안정하다면 관찰한 blocker를 test와 함께 명시적으로 문서화하고 README snippet을 이슈 #4 fanout example로 유지하며 전체 application flow는 이슈 #13에 남긴다.

## 테스트

ApplicationContextRunner 테스트:

- SNS SDK class가 있으면 `SnsAsyncClient`와 `SnsOperations`를 등록한다.
- `bluetape4k.aws.sns.enabled=false`이면 등록하지 않는다.
- custom `SnsAsyncClient`가 있으면 back off한다.
- custom `SnsOperations`가 있으면 back off한다.
- `region` 없이 `endpointOverride`를 설정하면 validation에 실패한다.
- `SnsAsyncClient`를 filtering하면 등록하지 않는다.
- FIFO topic configuration 이름과 throughput scope를 validation한다.

LocalStack 테스트:

- 표준 topic을 만들고 이름으로 조회한다.
- 표준 message를 publish하고 `PublishResponse.messageId`를 반환한다.
- FIFO topic을 만들고 `messageGroupId`와 함께 publish한 뒤 `messageId`와, 사용할 수 있으면 `sequenceNumber`를 assertion한다.
- property로 설정한 topic을 생성한다.
- AWS를 호출하기 전에 표준 topic의 FIFO 전용 publish field를 거부한다.
- 잘못됐거나 존재하지 않는 topic ARN에 대한 AWS publish error를 전파한다.
- 현재 test stack에서 LocalStack 동작이 안정적이면 SNS-to-SQS fanout을 검증한다.

## README 갱신

`README.md`와 `README.ko.md`를 모두 갱신한다.

- `aws-spring-boot` example에 `software.amazon.awssdk:sns` runtime dependency를 추가한다.
- `bluetape4k.aws.sns` configuration snippet을 추가한다.
- `SnsOperations`와 `SnsPublishRequest`를 사용하는 coroutine publish example을 추가한다.
- 완전한 SQS-SNS application example은 이슈 #13에서 별도로 추적한다고 언급한다.

## 인수 기준

- `aws-spring-boot`가 SNS SDK를 `compileOnly`로 사용해 compile된다.
- SNS auto-configuration이 `AutoConfiguration.imports`에 등록된다.
- workspace language policy에서 KDoc은 contributor-facing public documentation이므로 public API에 영문 KDoc이 있다.
- targeted `aws-spring-boot` test가 통과한다.
- strict code review에 해결되지 않은 correctness, cancellation, Spring Boot, public API blocker가 없다.
- `develop` 대상 PR을 열고 `debop`에게 할당하며 이슈 #4에 연결한다.

## 명세 검토 기록

- Claude Code advisor 검토 artifact: `.omx/artifacts/ask-claude-issue-4-sns-spec-review.md`.
- 해결한 blocker: 이제 표준 topic에서 FIFO 전용 publish field를 거부한다.
- 해결한 high-risk note: `findTopicArn` pagination, SDK 기본 region 동작, 설정 기반 topic idempotency, FIFO option 중복을 명시했다.
- 남은 구현 위험: LocalStack SNS-to-SQS fanout은 환경에 민감할 수 있다. 구현에서 실행 가능한 integration test를 시도하고 안정화할 수 없다면 CI blocker를 문서화해야 한다.
