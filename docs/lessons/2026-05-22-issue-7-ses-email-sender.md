# Issue 7 SES 이메일 발신기

## 배경

Issue #7에서는 awspring 없이 `bluetape4k-aws-spring-boot`에 SES 지원을 추가했다. Spring
Boot 자동 구성, coroutine operation, template/raw 전송, attachment, JavaMail adapter를
포함한다.

## 결정

AWS SDK v2 `sesv2`를 `compileOnly` service dependency로 사용하고
`SesOperations` / `SesCoroutinesMailSender`를 기본 coroutine API로 제공한다. 하위
`send(SendEmailRequest)` 경로는 요청을 정확히 유지하고 convenience request type에는
`defaultFrom`과 `configurationSetName`을 적용한다.

JavaMail 지원에는 Jakarta Mail API와 실제 provider가 모두 필요하다. 따라서 adapter 자동
구성은 `SesJavaMailSender`를 등록하기 전에 Spring `JavaMailSender`, Jakarta Mail, Angus
Mail provider class를 확인한다.

## 결과

SES 자동 구성, request model, coroutine sender, JavaMail adapter, 자동 구성 등록, README
coverage, 대상 테스트를 추가했다. 새 Gradle module은 추가하지 않았으므로 CI/Nightly
workflow를 등록할 필요가 없었다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin`
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests "io.bluetape4k.aws.spring.ses.*"`: 17개 통과
- `./gradlew :bluetape4k-aws-spring-boot:test`: 133개 통과
- Claude Code CLI 최종 검토: P0=0, P1=0, gate PASS

## 향후 지침

JavaMail용 기능을 추가할 때 runtime에 `jakarta.mail-api`만 있으면 충분하다고 가정하지
않는다. `org.eclipse.angus:angus-mail` 같은 provider를 요구하거나 문서화하고, bean
생성에 `Session`이 필요하면 provider 존재 여부로 Spring 자동 구성을 보호한다.
