# 이슈 #230 Micrometer 관측성 계획

날짜: 2026-06-07
이슈: #230

## 작업 순서

1. `aws-ktor`에 선택적 Micrometer 의존성 범위를 추가한다. Micrometer core가 이미 API 의존성이므로 `aws-spring-boot`는 그대로 둔다.
2. 변경하는 각 모듈에 low-cardinality tag, queue 이름 도출, exception 이름 지정, timer 기록을 위한 작은 공유 Micrometer helper를 추가한다.
3. Spring Boot SQS instrumentation을 구현한다.
   - `MicrometerSqsOperations`
   - `MicrometerSqsListenerInterceptor`
   - `SqsAutoConfiguration`의 조건부 자동 등록
4. Spring Boot S3 instrumentation을 구현한다.
   - `MicrometerS3Operations`
   - `S3AutoConfiguration`의 조건부 decoration
5. Ktor SQS instrumentation을 구현한다.
   - `MicrometerSqsConsumerObserver`
   - `SqsConsumerPluginConfig.micrometer(...)`
   - `SqsConsumerRuntime`의 `send` observation
6. Ktor S3 instrumentation을 구현한다.
   - `MicrometerS3KtorClient`
   - `S3KtorClient.withMicrometer(...)`
7. decorator, 조건부 등록, observer mapping, 선택한 wrapper method를 집중적으로 검사하는 테스트를 추가한다.
8. root 및 모듈 README 파일을 영어와 한국어로 갱신한다.
9. 구현 검토와 lesson을 추가한다.
10. 검증하고 commit한 뒤 PR을 생성하고 PR 본문을 확인한다. CI를 모니터링하고 검사가 성공한 뒤에만 병합한다.

## 검증 명령

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.*Micrometer*'`
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.*Micrometer*'`
- `./gradlew :bluetape4k-aws-spring-boot:test`
- `./gradlew :bluetape4k-aws-ktor:test`
- `git diff --check`

## 위험

- 사용자가 직접 interceptor를 추가하면 instrumentation이 중복될 수 있다. 자동 adapter를 일반적인 형태로 유지하고 문서화한다.
- High-cardinality tag 위험이 있다. URL, key, receipt handle, message ID, raw exception message를 기본 tag에서 제외한다.
- Ktor 의존성이 누출될 수 있다. `aws-ktor`에서 Micrometer를 compile-only와 test-only로 유지한다.
