# AWS Spring Boot SQS 구현 계획

날짜: 2026-05-10 KST
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/2
명세: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/docs/superpowers/specs/2026-05-10-aws-spring-boot-sqs-design.md`
Worktree 경로: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs`
브랜치: `feat/2-spring-boot-sqs`

## 구현 전략

`#2`를 하나의 PR에서 구현하되 커밋을 나눈다.

1. 명세/계획 커밋
2. SQS 구현 + 테스트 + 문서 커밋

구현을 병합된 S3 Spring Boot 구조와 일치시킨다.

- `io.bluetape4k.aws.spring.sqs` 패키지
- 다음에 등록한 `SqsAutoConfiguration`
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- AWS SDK SQS 의존성을 `compileOnly`로 유지
- Spring이 `SqsAsyncClient` 빈 생명주기 소유
- `aws-spring-boot`에서 LocalStack 테스트 실행

## 작업 목록

### 1. 빌드 및 자동 구성 등록

파일:

- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

변경:

- `compileOnly(libs.aws2.sqs)`를 추가한다.
- `testImplementation(libs.aws2.sqs)`를 추가한다.
- `io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration`을 등록한다.

검사:

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

### 2. 속성과 모델 타입

대상 경로:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/`

추가:

- `SqsProperties.kt`
- `SqsReceivedMessage.kt`
- `SqsOperations.kt`

상세:

- `SqsProperties`는 다음을 검증한다.
  - 엔드포인트 재정의에는 리전 필요
  - 최대 메시지 수 `1..10`
  - 대기 시간 `0..20`
  - 가시성 시간 초과 `0..43_200`
  - 동시성 `>= 1`
  - 중지 시간 초과 `>= 1`
  - redrive 정책 대상 ARN은 비어 있지 않고 최대 수신 횟수 `>= 1`
- `SqsReceivedMessage`는 큐 URL, AWS `Message`, 본문, receipt handle을 노출한다.
- `SqsOperations`는 다음을 정의한다.
  - `getQueueUrl`
  - `createQueue`
  - `createConfiguredQueue`
  - `send`
  - `receive`
  - `delete`
  - `changeVisibility`
  - `receiveFlow`

검사:

- 공개 API에 한글 KDoc을 작성한다.
- 운영 코드에 `runBlocking`, `Thread.sleep`, `GlobalScope`를 사용하지 않는다.

### 3. SQS 클라이언트 자동 구성 기반

파일:

- `SqsAutoConfiguration.kt`

상세:

- 다음 어노테이션을 사용한다.
  - `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
  - 다음 문자열 `@ConditionalOnClass`:
    - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
    - `software.amazon.awssdk.services.sqs.SqsAsyncClient`
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
  - `@EnableConfigurationProperties(SqsProperties::class)`
- 빈 메서드는 이 기반 단계에서 `SqsAsyncClient`만 제공한다.
- `ObjectProvider<AwsCredentialsProvider>`를 사용하고
  `DefaultCredentialsProvider.builder().build()`로 대체한다.
- 선택적 `ObjectProvider<SdkAsyncHttpClient>`를 사용한다.
- 속성의 `region`과 `endpointOverride`를 적용한다.
- `region`이 없고 엔드포인트 재정의도 구성하지 않았으면 AWS SDK 기본 리전 공급자 체인을 사용한다.
- `endpointOverride`를 구성하면 `SqsProperties`가 `region`을 요구한다.
- Spring이 종료를 명시적으로 소유하도록 SQS 클라이언트 빈을
  `@Bean(destroyMethod = "close")`로 선언한다.

물러나기 규칙:

- 사용자 정의 `SqsAsyncClient`가 있으면 물러난다.

이후 listener 타입에 의존하는 빈을 추가하기 전에 이 단계에서 컴파일한다.

### 4. 템플릿 구현

파일:

- `SqsCoroutinesTemplate.kt`

상세:

- 가능한 곳에서 기존 `io.bluetape4k.aws.sqs` 코루틴 확장에 위임한다.
- 명시적 대기 시간, 가시성 시간 초과, 최대 메시지 수를 사용해 수신을 구현한다.
  - `receiveMessage { queueUrl(...); maxNumberOfMessages(...); waitTimeSeconds(...) }`
  - `visibilityTimeoutSeconds`가 null이 아니면 `visibilityTimeout(...)` 설정
  - 결과를 `SqsReceivedMessage`로 감싸기
- `createConfiguredQueue`를 구현한다.
  - `SqsProperties.queues[queueName]` 해석
  - `redrivePolicy`가 있으면 `QueueAttributeName.REDRIVE_POLICY` 설정
  - JSON 의존성을 추가하지 않고 로컬에서 JSON 문자열 생성:
    `{"deadLetterTargetArn":"...","maxReceiveCount":"..."}`
- `receiveFlow`를 cold 무한 flow로 구현한다.
  - 활성 상태 동안 반복
  - 수신 메시지 방출
  - 자동 삭제하지 않음
  - `CancellationException` 다시 던지기

검사:

- 이 단계 후 컴파일한다.
- 검증과 단순 템플릿 메서드의 단위 테스트를 실행한다.

### 5. Listener 어노테이션과 엔드포인트 파싱

파일:

- `SqsListener.kt`
- `SqsListenerEndpoint.kt`
- `SqsListenerMethodInvoker.kt`
- `SqsListenerAnnotationBeanPostProcessor.kt`

상세:

- `@SqsListener`는 함수를 대상으로 하고 런타임에 유지한다.
- `${...}` 자리표시자를 `Environment.resolvePlaceholders`로 해석한다.
- `#{`를 포함한 SpEL 문자열을 거부한다.
- `AopUtils.getTargetClass(bean)`에서 어노테이션을 발견한다.
- 대상 클래스 인스턴스가 아니라 프록시 빈을 통해 호출한다.
- 지원 시그니처:
  - `String`
  - AWS `Message`
  - `SqsReceivedMessage`
  - 한 매개변수 `suspend` 함수에도 동일하게 적용
- 다음 경우 빠르게 실패한다.
  - 매개변수 없음
  - 매개변수 두 개 이상
  - 지원하지 않는 매개변수 타입
  - SpEL 큐/ID 값
- 엔드포인트 ID를 다음처럼 할당한다.
  - 어노테이션 ID가 비어 있지 않으면 사용
  - 그렇지 않으면 `${beanName}.${methodName}.${queue}`

구현 참고:

- 루트 하위 프로젝트가 이미 `kotlin-reflect`에 의존하므로 suspend 감지/호출에 Kotlin
  reflection을 사용한다.
- 일반 `Throwable`보다 먼저 `CancellationException`을 잡는다.
- reflection이 handler 실패를 `InvocationTargetException`으로 감싸면 래핑을 해제하고,
  대상 원인이 `CancellationException`이면 다시 던진다.

### 6. Listener 컨테이너와 레지스트리

파일:

- `SqsMessageListenerContainer.kt`
- `SqsMessageListenerContainerRegistry.kt`

상세:

- 컨테이너는 `SmartLifecycle`을 구현한다.
- 생성자 의존성:
  - 엔드포인트
  - `SqsOperations`
  - 유효 listener 속성
  - 메서드 invoker
- `phase` 기본값은 `Int.MAX_VALUE`다.
- `start()`:
  - 큐 URL을 한 번 해석하고 캐시
  - `SupervisorJob` 생성
  - `Dispatchers.IO`에서 `concurrency`개 polling 코루틴 시작
- Polling 루프:
  - `operations.receive` 호출
  - 유효 `visibilityTimeoutSeconds`를 수신에 전달
  - 배치 안에서 메시지를 순차 처리
  - 성공 시 `operations.delete` 호출
  - handler 실패 시:
    - `CancellationException` 다시 던지기
    - 오류 가시성 시간 초과를 구성했으면 `changeVisibility` 호출
    - 그렇지 않으면 삭제하거나 가시성을 바꾸지 않음
- `stop(callback)`:
  - 새 수신 방지
  - 작업 취소
  - 최대 `stopTimeoutMillis` 대기
  - 결과와 관계없이 callback 호출
- 레지스트리는 `SmartLifecycle`을 구현하고 등록된 모든 컨테이너를 시작/중지한다.

### 6b. SQS 자동 구성 연결 완료

다시 수정할 파일:

- `SqsAutoConfiguration.kt`

작업 4, 5, 6의 타입이 생긴 뒤에만 나머지 빈을 추가한다.

- `SqsCoroutinesTemplate`
- `SqsMessageListenerContainerRegistry`
- `SqsListenerAnnotationBeanPostProcessor`

물러나기 규칙:

- `SqsCoroutinesTemplate`은 `@ConditionalOnMissingBean(SqsOperations::class)`을 사용한다.
- 레지스트리는 `@ConditionalOnMissingBean`을 사용한다.
- 어노테이션 BPP는 `@ConditionalOnMissingBean`을 사용한다.

검사:

- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

테스트:

- ack는 handler 성공 후 메시지를 삭제한다.
- 실패한 handler는 가시성 시간 초과 후 메시지를 다시 사용할 수 있게 남긴다.
- 중지는 제한 시간 안에 반환한다.
- `concurrency=2`로 여러 메시지를 처리해 동시성이 예상 개수의 polling 루프를 간접적으로 생성함을 검증한다.

### 7. 자동 구성 테스트

파일:

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsAutoConfigurationTest.kt`
- `NoopSqsOperations.kt`
- `aws-spring-boot/src/test/resources/logback-test.xml`

`ApplicationContextRunner`를 사용한다.

테스트 사례:

- `SqsAsyncClient`, `SqsProperties`, `SqsOperations`, `SqsCoroutinesTemplate`,
  레지스트리, BPP를 등록한다.
- `bluetape4k.aws.sqs.enabled=false`이면 물러난다.
- 사용자 정의 `SqsOperations`가 있으면 물러난다.
- 리전 없는 엔드포인트 재정의가 실패한다.
- 리전이 있으면 엔드포인트 재정의 URI 바인딩을 허용한다.
- SQS 클래스를 제거한 `FilteredClassLoader`가 SQS 자동 구성을 비활성화한다.
- 잘못된 listener 속성이 바인딩에 실패한다.
- 가능하면 컨텍스트 종료 시 자동 구성/사용자 정의 spy 클라이언트의
  `SqsAsyncClient.close()`를 호출한다.
- `${...}` 자리표시자 값을 해석한다.
- SpEL 큐/ID 값이 빠르게 실패한다.
- `<logger name="io.bluetape4k.aws.spring.sqs" level="DEBUG"/>`를 추가한다.

### 8. LocalStack 통합 테스트

파일:

- `SqsCoroutinesTemplateLocalStackTest.kt`
- `SqsListenerLocalStackTest.kt`

패턴:

- `LocalStackServer().withServices("sqs")`를 사용한다.
- `localStack.getCredentialProvider()`로 `AwsCredentialsProvider` 빈을 등록한다.
- 다음을 설정한다.
  - `bluetape4k.aws.sqs.region=${localStack.regionName}`
  - `bluetape4k.aws.sqs.endpoint-override=${localStack.awsEndpoint}`
  - 테스트 속도를 위한 작은 대기/가시성 값

테스트 사례:

- 템플릿 생성/전송/수신/삭제
- `receiveFlow`가 방출하며 명시적 삭제를 요구
- listener가 `String` 본문을 처리하고 성공 시 삭제
- suspend listener가 `SqsReceivedMessage` 처리
- 실패한 listener는 삭제하지 않고 짧은 가시성 시간 초과 후 메시지를 다시 수신 가능
- `concurrency=2`가 구성한 개수보다 많은 루프를 만들지 않고 두 polling 코루틴으로 여러 메시지를 처리
- 속성에서 자리표시자 큐 값 해석
- 프록시 빈 listener 발견
- 지원하지 않는 시그니처가 컨텍스트 시작에 실패

### 9. 문서

파일:

- `README.md`
- `README.ko.md`

추가:

- `aws-spring-boot` + `software.amazon.awssdk:sqs` Gradle 의존성 코드 조각
- YAML 속성:
  - 리전
  - 엔드포인트 재정의
  - listener 최대 메시지 수, 대기 시간, 동시성
- `SqsOperations` 예제
- `@SqsListener` 동기 및 suspend 예제
- 최소 한 번 전달/멱등 handler 경고

### 10. 검증

실행:

```bash
./gradlew :aws-spring-boot:compileKotlin --no-daemon
./gradlew :aws-spring-boot:test --no-daemon
./gradlew :aws-spring-boot:koverHtmlReport --no-daemon
./gradlew detekt --parallel --no-daemon
./gradlew build -x test --parallel --no-daemon
rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin
rg 'CancellationException' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs
rg 'SqsClientFactory' aws-spring-boot/src/main/kotlin
yq e '.' .github/workflows/nightly.yml >/dev/null
git diff --check
```

`:aws-spring-boot:detekt`를 사용할 수 없으면 루트 `detekt`를 사용하고 이를 기록한다.

### 11. 리뷰와 정리

- 다음에 집중한 로컬 코드 리뷰를 실행한다.
  - 생명주기 취소
  - 성공 후에만 메시지 삭제
  - 선택적 클래스패스 안전성
  - listener 시그니처 검증
  - 공개 KDoc과 README 일관성
- 이 PR 범위를 넓히지 않으면서 나중에 S3와 안전하게 공유할 수 있을 때만 중복 도우미 코드를 제거한다.
- 컴파일에 필요하지 않으면 이 PR에서 S3를 리팩터링하지 않는다.

### 12. 커밋과 PR

커밋 1:

- 명세/계획만 포함
- Lore 커밋 프로토콜
- `Co-authored-by: OmX <omx@oh-my-codex.dev>`

커밋 2:

- 구현/테스트/문서
- Lore 커밋 프로토콜
- `Co-authored-by: OmX <omx@oh-my-codex.dev>`

PR:

- 제목: `[feat] Add Spring Boot SQS integration`
- 본문은 한국어로 작성한다.
- `Closes #2`를 포함한다.
- 검증 명령과 테스트 결과를 포함한다.

## 위험

| 위험 | 완화책 |
|---|---|
| Kotlin reflection 호출 경계 사례 | 지원 시그니처를 좁게 유지하고 빠르게 실패한다. |
| AWS long polling으로 인한 종료 테스트 불안정 | 낮은 대기/가시성 값과 `stopTimeoutMillis`를 사용한다. |
| LocalStack 큐 타이밍 | 고유 큐 이름을 사용하고 assertion에서 짧은 polling으로 직접 수신한다. |
| awspring 복제 과잉 구현 | 첫 PR에서 페이로드 변환/수동 ack/메트릭/heartbeat를 제외한다. |
| 선택적 클래스패스 실패 | 구현 완료 전에 FilteredClassLoader 테스트를 추가한다. |

## 3단계 체크리스트

| 항목 | 상태 | 비고 |
|---|---|---|
| 의존성 순서대로 작업 배치 | 완료 | 템플릿/listener 전에 클라이언트 자동 구성 기반, listener 타입 생성 후 최종 자동 구성 연결. |
| 검증 명령 포함 | 완료 | 대상 검사와 광범위한 검사를 나열함. |
| 자문 리뷰 | 완료 | Claude 자문을 검토하고 수정 사항을 반영함. |
| 커밋/PR 프로토콜 포함 | 완료 | Lore 커밋, 한글 PR 본문, `[feat]` 제목. |

## Claude Code Opus 자문

산출물:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/2-spring-boot-sqs/.omx/artifacts/ask-claude-aws-spring-boot-sqs-plan-20260510-185205.md`

모델: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| 심각도 | 지적 | 결정 | 후속 조치 |
|---|---|---|---|
| 높음 | 타입이 생기기 전에 자동 구성 작업이 참조함. | 수용 | 클라이언트 기반과 6b 최종 연결로 분리함. |
| 높음 | SQS 클라이언트 생명주기 지침 누락. | 수용 | `@Bean(destroyMethod = "close")`를 추가함. |
| 높음 | 동시성 LocalStack 테스트가 명시적이지 않음. | 수용 | 작업 8에 동시성 테스트를 추가함. |
| 높음 | `visibilityTimeoutSeconds`가 사용되지 않는 설정이었음. | 수용 | 수신/flow 가시성 매개변수와 컨테이너 연결을 추가함. |
| 높음 | 새 패키지 logger 누락. | 수용 | `logback-test.xml` 갱신을 추가함. |
| 중간 | 빈별 물러나기가 모호함. | 수용 | 명시적 물러나기 규칙을 추가함. |
| 중간 | 리전 대체 동작이 명시되지 않음. | 수용 | AWS SDK 기본 리전 체인 동작을 추가함. |
| 중간 | Reflection이 취소를 감쌀 수 있음. | 수용 | `InvocationTargetException` 래핑 해제 규칙을 추가함. |
| 낮음 | `SqsClientFactory` 검색 추가. | 수용 | 검증 검색을 추가함. |
