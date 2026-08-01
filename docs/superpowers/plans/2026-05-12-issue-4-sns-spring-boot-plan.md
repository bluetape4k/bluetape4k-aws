# 이슈 #4 SNS Spring Boot 계획

## 범위

검토한 설계 명세에 따라 이슈 #4를 `aws-spring-boot`에 구현한다.

- 명세: `docs/superpowers/specs/2026-05-12-issue-4-sns-spring-boot-design.md`
- Branch: `issue-4-sns-spring-boot`
- Base: `origin/develop`
- 범위 제외: 이슈 #13의 전체 SQS-SNS application 예제.

## 품질 gate

1. 구현 전에 명세 review의 blocker가 없어야 한다.
2. 구현 전에 계획 review의 blocker가 없어야 한다.
3. workspace 언어 정책에서 KDoc은 contributor 대상 public 문서이므로 Kotlin public API에 영문 KDoc이 있어야 한다.
4. compile-only SNS SDK 타입을 문자열 기반 `@ConditionalOnClass`로 보호해야 한다.
5. test에서 auto-configuration 동작과 coroutine SNS 동작을 입증해야 한다.
6. commit/PR 전에 최종 strict code review를 완료해야 한다.

## 구현 단계

### 1. Build 구성

- `aws-spring-boot/build.gradle.kts`에 `compileOnly(awsLibs.aws2.sns)`를 추가한다.
- `aws-spring-boot/build.gradle.kts`에 `testImplementation(awsLibs.aws2.sns)`를 추가한다.

### 2. SNS Spring package

`aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/`를 생성한다.

파일:

- AWS 값 `Topic`과 `MessageGroup`으로 명시적으로 mapping하는 `attributeValue: String`을 포함한 `SnsFifoThroughputScope.kt`.
- `SnsProperties.kt`. topic 이름이 map key이므로 `Topic` 내부에서 FIFO topic 이름을 검증하지 않는다.
- blank field와 FIFO 전용 publish field를 `init`에서 검증하는 `SnsPublishRequest.kt`.
- `SnsOperations.kt`
- `SnsCoroutinesTemplate.kt`
- `SnsAutoConfiguration.kt`

구현 세부 사항:

- 안정적인 exception 타입 `IllegalArgumentException`을 사용하는 `require(...)` validation을 적용한다.
- SDK future에 `kotlinx.coroutines.future.await()`를 사용한다.
- 포괄적인 `runCatching`을 피하여 AWS SDK 호출이 취소에 친화적으로 유지되게 한다.
- 기존 `aws` module helper가 전체 Spring template 계약을 다루지 않는 곳에서는 AWS SDK model builder를 직접 사용한다.
- `findTopicArn`은 `nextToken`으로 `listTopics`를 pagination한다.
- FIFO attribute에는 `FifoTopic=true`, `ContentBasedDeduplication=<true|false>`, 선택적 `FifoThroughputScope=<Topic|MessageGroup>`을 작성한다.
- `publish`에서는 값이 있을 때만 선택적 subject, message attribute, FIFO group id, FIFO deduplication id를 설정한다.
- `SnsPublishRequest.init`은 FIFO topic ARN에 `messageGroupId`를 요구하고 standard topic ARN에서는 `messageGroupId`/`messageDeduplicationId`를 거부해야 한다.
- `SnsCoroutinesTemplate` constructor는 SQS pattern과 같은 `SnsCoroutinesTemplate(snsAsyncClient, properties)` 형식이어야 한다.
- `createConfiguredTopic(topicName)`은 다음을 수행해야 한다.
  - `SnsProperties.topics[topicName]` 조회.
  - topic이 구성되지 않았으면 `IllegalArgumentException` throw.
  - 구성된 topic이 FIFO인데 `topicName`이 `.fifo`로 끝나지 않으면 `IllegalArgumentException` throw.

### 3. Auto-configuration 등록

- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 `io.bluetape4k.aws.spring.sns.SnsAutoConfiguration`을 추가한다.
- `SnsAutoConfiguration`에서 `@Bean(destroyMethod = "close")`와 `@ConditionalOnMissingBean`으로 client를 등록한다.
- `@Bean`과 `@ConditionalOnMissingBean(SnsOperations::class)`으로 template을 등록한다.

### 4. Test

`aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/`를 생성한다.

지원 파일:

- custom operation back-off test를 위해 SQS test pattern과 일치하는 `NoopSnsOperations.kt`.

ApplicationContextRunner test:

- `SnsAsyncClient`와 `SnsOperations` 자동 등록.
- `bluetape4k.aws.sns.enabled=false`이면 등록 비활성화.
- custom `SnsAsyncClient`가 있으면 back-off.
- custom `SnsOperations`가 있으면 back-off.
- `region` 없이 `endpointOverride`를 설정하면 실패.
- SNS SDK class가 filtering되면 등록하지 않음.
- 잘못 구성된 FIFO topic 이름 거부.

LocalStack test:

- standard topic을 생성하고 pagination 인식 lookup으로 ARN 조회.
- standard message를 publish하고 `messageId` 확인.
- property에서 구성된 topic 생성.
- FIFO topic을 생성하고 `messageGroupId`로 publish.
- standard topic에서 FIFO 전용 publish field를 local에서 거부.
- 잘못되거나 존재하지 않는 ARN error가 AWS에서 전파되는지 검증.
- 최소 SNS-to-SQS fanout test 시도. 안정적이면 유지하고 불안정하면 LocalStack blocker를 문서화한 뒤 README fanout snippet은 유지.

### 5. README

갱신:

- `README.md`
- `README.ko.md`

변경:

- Spring Boot section에 SNS runtime dependency 추가.
- `bluetape4k.aws.sns` YAML sample 추가.
- `SnsOperations`와 `SnsPublishRequest`를 사용하는 coroutine publish sample 추가.
- 전체 SQS-SNS example application은 이슈 #13에서 다룬다고 명시.

### 6. 검증

다음 순서로 실행한다.

1. 가능한 경우 변경한 Kotlin 파일의 IDE diagnostic.
2. `./gradlew :aws-spring-boot:compileKotlin`
3. `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'`
4. targeted test filtering에서 context test가 누락되면 `./gradlew :aws-spring-boot:test` 실행.

LocalStack을 사용할 수 없거나 불안정하면 정확한 failure를 보고하고 차선의 non-LocalStack coverage를 실행한다. 통과 evidence 없이 전체 integration 검증을 주장하지 않는다.

## Review checklist

- Public API가 모호한 positional same-type parameter를 노출하지 않음.
- class guard 없이 unconditional bean signature에서 compile-only SDK 타입을 사용하지 않음.
- production code에 `runBlocking` 없음.
- suspend 호출 주변에 `runCatching` 없음.
- `!!` 없음.
- 취소를 삼키지 않음.
- user bean이 있으면 auto-configuration이 back-off.
- 가능한 범위에서 FIFO validation으로 AWS runtime에서만 발생하는 예외 방지.
- README와 한글 README를 함께 갱신.

## Commit 및 PR

- Lore protocol trailer를 포함해 commit한다.
- `issue-4-sns-spring-boot`를 push한다.
- `develop` 대상 draft PR을 생성하고 `debop`에게 할당하며 이슈 #4를 연결한다.

## 계획 review 참고

- Claude Code advisor review 산출물: `.omx/artifacts/ask-claude-issue-4-sns-plan-review.md`.
- 해결한 blocker: 명시적 `SnsAsyncClient` bean 등록 형태, `createConfiguredTopic` FIFO validation 위치, `SnsPublishRequest.init` validation 위치.
- 해결한 high-risk 참고: enum AWS value mapping, 구성된 topic 누락 동작, no-op test helper, targeted test filter, template constructor 형태.
