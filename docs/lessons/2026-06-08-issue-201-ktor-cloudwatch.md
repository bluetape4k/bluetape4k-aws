# Issue #201 Ktor CloudWatch 및 CloudWatch Logs

날짜: 2026-06-08
이슈: #201

## 배경

`aws-ktor`에는 공통 AWS 기본값, SQS 수명 주기 처리, 수동적 IMDS metadata 접근이
있었지만 Ktor용 CloudWatch 또는 CloudWatch Logs 통합은 없었다. 기존 Spring Boot
CloudWatch 작업에서 선택적 AWS SDK service jar와 명시적 Micrometer snapshot 게시를
기준으로 정했다.

## 결정

- 기존 `bluetape4k-aws-java` coroutine extension을 기반으로 선택적 Ktor CloudWatch 및
  CloudWatch Logs plugin을 추가한다.
- `software.amazon.awssdk:cloudwatch`와 `cloudwatchlogs`는 production에서
  `compileOnly`, `aws-ktor`에서는 test dependency로 유지한다.
- 주입한 client의 ownership을 보존한다. Plugin이 만든 client는
  `ApplicationStopping`에서 닫고, 주입한 client와 operation은 애플리케이션이 계속
  소유한다.
- 게시 동작을 명시적으로 유지한다. Plugin 설치는 operation/runtime만 저장한다. Metric과
  log event는 애플리케이션 code가 operation을 호출하거나 logs runtime에 event를 추가한
  뒤에만 게시한다.
- 새 공개 API에서 같은 type의 positional string을 혼동하지 않도록 log group/stream
  identity에 `CloudWatchLogStream`을 사용한다.
- Bluetape4k Ktor 생태계를 직접 재사용한다. `AwsKtorCore { ktorCore() }`로 공통
  `bluetape4k-ktor-core` baseline을 설치하고 test의 Ktor HTTP assertion에는
  `bluetape4k-ktor-testing`을 사용한다.

## 결과

이제 `aws-ktor`에는 CloudWatch metric operation, CloudWatch Logs operation, 제한된 종료
flush를 갖춘 buffered log 게시, CloudWatch용 명시적 Micrometer snapshot publisher가
있다. 영문/한글 README에 dependency, example, option, ownership, `ktorCore()` baseline
설정, opt-in 동작을 문서화했다.

## 검증

- `./gradlew :bluetape4k-aws-ktor:compileKotlin` 통과
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin` 통과
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest' --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  에서 대상 테스트 41개 통과
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  에서 `ktorCore()` bridge 후속 작업 전 대상 테스트 38개 통과
- `./gradlew :bluetape4k-aws-ktor:test`에서 테스트 126개 통과

## 향후 보호 장치

별도 issue 없이 global logging appender 또는 예약된 CloudWatch Micrometer registry
exporter를 `aws-ktor`에 추가하지 않는다. CloudWatch 게시는 명시적으로 유지하고,
cancellation을 전파하며, Ktor plugin이 AWS SDK client를 만들 때마다 수명 주기 ownership을
테스트한다. 새 Ktor 설정 또는 test utility를 추가하기 전에
`bluetape4k-projects/ktor/*`를 확인하고 dependency boundary가 허용하면 공통 Ktor
core/testing module을 우선한다.
