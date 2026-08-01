# Issue #230 Micrometer 관측성

날짜: 2026-06-07
이슈: #230

## 배경

`aws-spring-boot`는 CloudWatch meter 게시를 위해 이미 Micrometer를 Spring Boot baseline
dependency로 취급했지만 SQS와 S3 operation은 자동으로 timer를 기록하지 않았다.
`aws-ktor`에는 SQS observer hook과 S3 helper가 있었으나 모든 Ktor 사용자에게
Micrometer를 강제하지 않도록 의도적으로 제한했다.

## 결정

기존 확장 지점을 통해 Micrometer를 지원한다.

- Spring Boot에서는 `MeterRegistry` bean이 있으면 자동 구성한 SQS와 S3 operation bean을
  감싼다.
- 구체 type인 `SqsCoroutinesTemplate`와 `S3CoroutinesTemplate` bean을 유지한다. 별도 자동
  구성 단계에서 Micrometer decorator를 primary operation bean으로 등록한다.
- Receive, handler, acknowledgement 단계를 위한 Micrometer SQS listener interceptor를
  추가한다.
- Ktor에서는 SQS observer bridge와 S3 client wrapper를 통해 Micrometer를 opt-in으로
  유지한다.
- 기본 tag는 낮은 cardinality를 유지한다. Queue URL, message ID, receipt handle, S3
  object key, 원본 exception message를 tag로 사용하지 않는다.
- 선택적 `micrometer-core`가 Spring runtime dependency를 추가하지 않고 관리되는
  catalog에서 해석되도록 Spring Boot BOM platform을 `aws-ktor` compile/test scope에
  추가한다.

## 결과

`MeterRegistry`가 있는 Spring Boot 애플리케이션은 구체 template bean과의 호환성을
유지하면서 primary operation decorator를 통해 SQS/S3 operation timer를 자동으로 얻는다.
Ktor 애플리케이션은 SQS consumer event에 `micrometer(meterRegistry)`, 선택한 S3 client
호출에 `s3.withMicrometer(meterRegistry)`를 사용해 명시적으로 활성화할 수 있다. 영문 및
한글 README에 dependency 경계와 tag policy를 문서화했다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
  로 `io.micrometer:micrometer-core:1.16.5` 확인
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency micrometer-core --configuration compileClasspath`
  로 `io.micrometer:micrometer-core:1.16.5` 확인
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-ktor:compileKotlin`
  통과
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.*Micrometer*' :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.*Micrometer*'`
  통과
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sqs.SqsAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest'`
  통과
- `./gradlew :bluetape4k-aws-spring-boot:test :bluetape4k-aws-ktor:test`에서 Spring Boot
  테스트 195개와 Ktor 테스트 85개 통과
- `git diff --check` 통과

## 향후 보호 장치

명시적 version이 없는 catalog alias의 선택적 library를 추가할 때 사용하는 module이 관리
platform을 이미 import하는지 확인한다. 관측성 adapter는 기본적으로 낮은 cardinality를
유지하고 높은 cardinality tag는 명시적으로 opt-in하게 한다.
