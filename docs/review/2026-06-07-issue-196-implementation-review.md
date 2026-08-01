# Issue #196 구현 검토

날짜: 2026-06-07
범위: `aws-spring-boot` Spring Boot EC2 IMDS 통합

## 판정

PASS (P0: 0, P1: 0, P2: 0). 차단 문제 없음.

## 검토 증거

- Source: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/ImdsProperties.kt`, `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/ImdsOperations.kt`, `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/ImdsCoroutinesTemplate.kt`, `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/ImdsAutoConfiguration.kt`
- Test: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/imds/ImdsAutoConfigurationTest.kt`, `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/imds/ImdsCoroutinesTemplateTest.kt`
- 문서: `README.md`, `README.ko.md`, `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- Build: `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency imds --configuration compileClasspath`, `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*'`, `./gradlew :bluetape4k-aws-spring-boot:test`, `git diff --check`

## 검토 내용

- Bean 생성 중 metadata endpoint를 호출하지 않으며 disabled/classpath/custom client/custom operation backoff를 검증한다.
- `ImdsCoroutinesTemplate`은 모든 `get`을 `withTimeout(properties.requestTimeout.toMillis())`으로 감싸고 미완료 future timeout cancellation을 검증한다.
- Public operation은 metadata helper/IAM role name만 노출한다.
- `requireNotBlank`, assertion, `ApplicationContextRunner` 패턴을 재사용한다.
- `software.amazon.awssdk:imds`는 module `compileOnly`, 검증 `testImplementation`이다.

## 검증 결과

`dependencyInsight`에서 `compileClasspath`의 `software.amazon.awssdk:imds:2.46.0`을 확인했고 집중 test 12개와 전체 `:bluetape4k-aws-spring-boot:test` 190개가 통과했다. `git diff --check`도 PASS였다.
