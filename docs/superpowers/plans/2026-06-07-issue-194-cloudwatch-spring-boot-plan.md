# 이슈 #194 CloudWatch Spring Boot 통합 계획

- 작성일: 2026-06-07
- 이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/194
- 명세: `docs/superpowers/specs/2026-06-07-issue-194-cloudwatch-spring-boot-design.md`
- 게이트: 계획

## 결정

- CloudWatch와 CloudWatch Logs를 `io.bluetape4k.aws.spring.cloudwatch` 아래의 서로
  다른 선택적 Spring Boot 자동 구성으로 구현한다.
- 기존 SQS/SNS 비동기 클라이언트 패턴과 Spring Cloud AWS CloudWatch 비동기 클라이언트
  방향에 맞춰 AWS SDK v2 비동기 클라이언트를 사용한다.
- `aws-spring-boot`에서 AWS 요청 생성을 중복하지 않고 `aws-java` 코루틴 확장을 재사용한다.
- AWS SDK v2 CloudWatch 및 CloudWatch Logs 의존성을 `aws-spring-boot`에
  `compileOnly`와 `testImplementation`으로 추가한다.
- Spring Boot 애플리케이션이 Micrometer를 기본 관측 표면으로 사용하므로
  `micrometer-core`를 일반 `aws-spring-boot` 의존성으로 추가한다.
- 명시적인 `MeterRegistry` 기반 메트릭 게시 도우미를 제공하되
  `micrometer-registry-cloudwatch`를 추가하거나 전역 registry를 교체·생성하지 않는다.
- 짧고 집중된 검사로 신뢰성을 입증하지 못하면 에뮬레이터 기반 CloudWatch 테스트를
  추가하지 않는다. 단위 테스트와 `ApplicationContextRunner` 범위를 필수 게이트로 둔다.

## 단계별 계획

1. 의존성 표면 추가
   - `aws-spring-boot/build.gradle.kts`에 `libs.aws2.cloudwatch`와
     `libs.aws2.cloudwatchlogs`를 `compileOnly` 및 `testImplementation`으로 추가한다.
   - `gradle/libs.versions.toml`에 `micrometer-core` alias를 추가하고
     `aws-spring-boot`의 `api` 의존성으로 사용한다. 버전은 Spring Boot 의존성 관리를
     재사용한다.

2. 프로퍼티와 상수 추가
   - `enabled`, `region`, `endpointOverride`, `namespace`, `batchSize`, 중첩된
     `micrometer.enabled`를 갖는 `CloudWatchProperties`를 추가한다.
   - `enabled`, `region`, `endpointOverride`, `logGroupName`, `logStreamName`,
     `batchSize`를 갖는 `CloudWatchLogsProperties`를 추가한다.
   - 직렬화 가능한 data class를 사용하고 endpoint/region 및 양의 batch size를 검증한다.

3. Operation 계약과 템플릿 추가
   - `CloudWatchOperations`와 `CloudWatchCoroutinesTemplate`을 추가한다.
   - `CloudWatchLogsOperations`와 `CloudWatchLogsCoroutinesTemplate`을 추가한다.
   - `CloudWatchMeterPublishingOperations`와 기본 구현을 추가한다. 기본 구현은
     `MeterRegistry`에서 선택한 `Meter` 스냅숏을 읽고 유한한 Micrometer 측정값을
     CloudWatch `MetricDatum` 값으로 변환한다.
   - 기존 `aws-java` 코루틴 확장에 위임한다.
   - 기본 namespace/group/stream 호출 전에 구성된 기본값을 검증한다.

4. 자동 구성 추가
   - 다음 항목을 갖는 `CloudWatchAutoConfiguration`을 추가한다.
     - `SdkAsyncHttpClient`와 `CloudWatchAsyncClient`를 위한 `@ConditionalOnClass`
     - 서비스별 `@ConditionalOnProperty`
     - 공유 credentials, HTTP 클라이언트, 전역 비동기 customizer, 서비스별 customizer 지원
     - `MeterRegistry`가 있고 `bluetape4k.aws.cloudwatch.micrometer.enabled=true`일 때
       조건부 Micrometer 게시 도우미
   - 같은 패턴으로 `CloudWatchLogsAsyncClient`용 `CloudWatchLogsAutoConfiguration`을
     추가한다.
   - 두 클래스를 `AutoConfiguration.imports`에 등록한다.

5. 테스트 추가
   - 빈 등록, 비활성 프로퍼티, 사용자 빈 백오프, endpoint override 검증, 프로퍼티 바인딩,
     클래스 경로 누락을 `ApplicationContextRunner`로 검증한다.
   - `@BeforeEach`에서 `clearMocks(...)`로 초기화하는 MockK 필드 mock을 사용한다.
   - 기본 namespace/group 검증, 완료된 future를 통한 AWS SDK 비동기 클라이언트 위임,
     Micrometer `SimpleMeterRegistry` meter 스냅숏 변환을 위한 operation/템플릿 테스트를
     추가한다.

6. 문서 갱신
   - 프로퍼티와 예제로 `aws-spring-boot/README.md`와 `README.ko.md`를 갱신한다.
   - 필요하면 루트 `README.md`와 `README.ko.md`의 서비스/모듈 기능 표를 갱신한다.
   - README 흐름에 CloudWatch/Logs lane이 필요할 때만 `aws-spring-boot` 아키텍처 diagram을
     갱신하고, 변경 시 `bluetape4k-diagram`을 적용한다.

7. 검토, lesson, 검증
   - P0=0/P1=0인 구현 검토를 추가한다.
   - `docs/lessons/2026-06-07-issue-194-cloudwatch-spring-boot.md`를 추가한다.
   - 다음 명령을 실행한다.
     - `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.cloudwatch.*'`
     - `./gradlew :bluetape4k-aws-spring-boot:test`
     - `git diff --check`

## 위험과 완화

- CloudWatch Logs sequence-token 동작에는 서비스별 특성이 있다. 완화: 저수준 게시
  operation만 제공하고 이 PR에서 AWS SDK 오류나 상태 관리를 숨기지 않는다.
- Micrometer registry 통합으로 범위가 커질 수 있다. 완화: `micrometer-core`와 명시적인
  `MeterRegistry` 스냅숏 게시만 추가하고 CloudWatch registry 자동 등록은 후속 작업으로
  남긴다.
- 로컬 에뮬레이터의 CloudWatch/Logs 동작이 불완전할 수 있다. 완화: 짧은 검증으로 안정성을
  입증하지 못하면 에뮬레이터 범위를 필수 조건으로 두지 않는다.

## 완료 조건

기능 구현, 문서와 lesson 갱신, 로컬 대상/전체 모듈 테스트와 diff 검사 통과, P0=0/P1=0인
구현 검토, 검증된 DoD 본문을 갖는 PR 생성, CI/PR 검토 근거 확보가 모두 끝나면 작업을
종료한다.
