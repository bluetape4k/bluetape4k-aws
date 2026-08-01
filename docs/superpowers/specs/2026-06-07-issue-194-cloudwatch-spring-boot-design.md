# 이슈 #194 CloudWatch Spring Boot 통합 명세

- 작성일: 2026-06-07
- 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/194
- 작업 유형: Type A 전체 기능
- 대상 모듈: `aws-spring-boot`
- 게이트: 명세

## 배경

`aws-java`는 이미 AWS SDK v2 CloudWatch 및 CloudWatch Logs 도우미를 제공한다.

- `io.bluetape4k.aws.cloudwatch.CloudWatchAsyncClientCoroutinesExtensions`
- `io.bluetape4k.aws.cloudwatch.CloudWatchLogsAsyncClientCoroutinesExtensions`
- `io.bluetape4k.aws.cloudwatch.model.metricDatumOf`
- `io.bluetape4k.aws.cloudwatch.model.cloudwatchlogs.inputLogEventOf`

`aws-spring-boot`에는 다음과 같은 공통 Spring Boot 4 AWS 기반이 있다.

- `AwsProperties`
- `AwsClientDefaults`
- `resolveClientDefaults(...)`
- `AwsAsyncClientCustomizer`
- `AwsClientCustomizer<B>`
- SQS 및 SNS 자동 구성의 기존 선택적 비동기 클라이언트 패턴

현재 Spring Cloud AWS 문서도 CloudWatch를 선택적 Micrometer/CloudWatch 통합으로
분류한다. bluetape4k는 그 구현을 복제하거나 Micrometer registry를 전역으로 교체하면
안 된다. 이 모듈은 Spring Boot 통합 모듈이므로 Micrometer core를 기본 의존성으로
사용할 수 있다. 이 이슈에서는 기존 bluetape4k/AWS SDK v2 및 Micrometer 표면 위에
얇고 코루틴 친화적인 Spring Boot 어댑터를 제공한다.

## 목표

CloudWatch 사용자 정의 메트릭 게시, Micrometer meter 스냅숏 게시, CloudWatch Logs
이벤트 게시를 위한 선택적 Spring Boot 자동 구성을 추가한다.

애플리케이션은 다음 항목을 주입할 수 있어야 한다.

- `CloudWatchAsyncClient`
- `CloudWatchOperations`
- `CloudWatchLogsAsyncClient`
- `CloudWatchLogsOperations`
- `MeterRegistry`를 사용할 수 있을 때 `CloudWatchMeterPublishingOperations`

AWS SDK 서비스 클래스가 클래스 경로에 있고 서비스별 `enabled` 프로퍼티가 비활성화되지
않았을 때만 관련 빈을 등록한다.

## 제안 공개 표면

### 프로퍼티

기존 네임스페이스 아래에 서비스별 프로퍼티를 둔다.

- `bluetape4k.aws.cloudwatch.enabled`
- `bluetape4k.aws.cloudwatch.region`
- `bluetape4k.aws.cloudwatch.endpoint-override`
- `bluetape4k.aws.cloudwatch.namespace`
- `bluetape4k.aws.cloudwatch.batch-size`
- `bluetape4k.aws.cloudwatch.micrometer.enabled`
- `bluetape4k.aws.cloudwatch-logs.enabled`
- `bluetape4k.aws.cloudwatch-logs.region`
- `bluetape4k.aws.cloudwatch-logs.endpoint-override`
- `bluetape4k.aws.cloudwatch-logs.log-group-name`
- `bluetape4k.aws.cloudwatch-logs.log-stream-name`
- `bluetape4k.aws.cloudwatch-logs.batch-size`

기존 서비스 프로퍼티 검증과 마찬가지로 `endpoint-override`를 사용하려면 region이 필요하다.

### 자동 구성

다음을 추가한다.

- `CloudWatchAutoConfiguration`
- `CloudWatchLogsAutoConfiguration`

두 구성 모두 `@AutoConfiguration(after = [AwsAutoConfiguration::class])`를 사용하고,
관련 AWS SDK v2 비동기 클라이언트와 `SdkAsyncHttpClient`에
`@ConditionalOnClass`를 적용한다. 구성 클래스는
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에
등록한다.

클라이언트를 만들 때 다음 항목을 재사용해야 한다.

- `AwsProperties.resolveClientDefaults(...)`
- `AwsCredentialsProvider`
- 선택적 `SdkAsyncHttpClient`
- `AwsAsyncClientCustomizer`
- 서비스별 `AwsClientCustomizer<CloudWatchAsyncClientBuilder>`
- 서비스별 `AwsClientCustomizer<CloudWatchLogsAsyncClientBuilder>`

### Operation 계약

기존 `aws-java` 코루틴 확장 위에 다음 코루틴 operation을 추가한다.

- `CloudWatchOperations`
  - `putMetricData(namespace, metricData)`
  - 구성된 기본 namespace를 사용하는 `putMetricData(metricData)`
  - `putMetricDatum(namespace, metricDatum)`
  - 구성된 기본 namespace를 사용하는 `putMetricDatum(metricDatum)`
  - `listMetrics(namespace, metricName, dimensions)`
- `CloudWatchLogsOperations`
  - `createLogGroup(logGroupName)`
  - `createLogStream(logGroupName, logStreamName)`
  - `putLogEvents(logGroupName, logStreamName, logEvents)`
  - 구성된 기본 group과 stream을 사용하는 `putLogEvents(logEvents)`
  - `describeLogGroups(logGroupNamePrefix)`
  - `describeLogStreams(logGroupName, logStreamNamePrefix)`

하위 도우미보다 추가 동작을 제공하는 호출자 입력에는 bluetape4k 검증 도우미를 적용한다.
구성된 namespace/group/stream이 없으면 `IllegalArgumentException`으로 즉시 실패한다.

### Micrometer 친화적 도우미

`micrometer-core`를 일반 `aws-spring-boot` 의존성으로 추가하고, 명시적으로 선택한
애플리케이션 사용자 정의 메트릭을 위해 `MeterRegistry` 위에 가벼운 도우미를 제공한다.

도우미는 다음 조건을 충족해야 한다.

- 기존 `MeterRegistry`를 요구한다.
- `@ConditionalOnClass(MeterRegistry::class)`를 적용한다.
- `@ConditionalOnBean(MeterRegistry::class)`를 적용한다.
- 선택한 meter 스냅숏을 `CloudWatchOperations`로 게시할 수 있다.
- 전역 `MeterRegistry`를 만들거나 교체하지 않는다.
- 이 PR에서 `micrometer-registry-cloudwatch`를 추가하지 않는다.

## 제외 범위

- Spring Cloud AWS 구현을 복제하지 않는다.
- 전역 Micrometer registry를 교체하지 않는다.
- 항상 실행되는 CloudWatch 게시를 추가하지 않는다.
- 이 PR에서 `micrometer-registry-cloudwatch` 자동 등록을 추가하지 않는다.
- Ktor CloudWatch 플러그인 작업은 포함하지 않는다. #201이 담당한다.
- 컴파일 문제로 작은 호환성 수정이 필요하다고 입증되지 않는 한 저수준 `aws-java` 또는
  `aws-kotlin` API를 변경하지 않는다.

## 예상 파일

변경 가능성이 있는 파일은 다음과 같다.

- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/*`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/cloudwatch/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- 루트 `README.md`
- 루트 `README.ko.md`
- 아키텍처 diagram이 변경되는 경우 `docs/images/readme-diagrams/aws-spring-boot-architecture-01.*`
- `docs/review/*`
- `docs/lessons/*`

## 검증 요구 사항

최소 로컬 검증은 다음과 같다.

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.cloudwatch.*'`
- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-spring-boot:test`
- `git diff --check`

필수 검토 게이트는 다음과 같다.

- 명세 검토: P0=0, P1=0
- 계획 검토: P0=0, P1=0
- 구현 검토: P0=0, P1=0
- 병합 전 PR 검토 근거

## 미결 질문

- 향후 이슈에서 네이티브 Micrometer CloudWatch 내보내기를 원하는 사용자를 위해
  `micrometer-registry-cloudwatch` 자동 등록을 추가해야 하는가?
- 저장소의 Floci 우선 정책에서 CloudWatch Logs 로컬 에뮬레이터 범위를 신뢰할 수 있는가?
  그렇지 않다면 이 이슈에는 단위 테스트와 자동 구성 테스트로 충분하며, 해당 공백을
  문서화해야 한다.
