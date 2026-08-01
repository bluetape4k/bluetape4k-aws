# Issue #193 Spring SQS 고급 기능

## 배경

Milestone 0.3.0 버전에서는 기존 raw `String`, AWS `Message`, `SqsReceivedMessage`
listener 계약의 source 호환성을 유지하면서 Spring Boot SQS 운영 환경을 강화해야 한다.

## 결정

Listener container에 opt-in typed payload 변환, `SqsAcknowledgement` 수동 ack/nack,
재시도/backoff property, `SqsListenerInterceptor` hook을 추가한다. `ObjectMapper` bean이
있을 때만 Jackson 3를 사용하고 일반적인 interception 지점을 제공해 Micrometer별 통합은
핵심 runtime에서 제외한다.

## 결과

이제 listener는 변환한 payload를 받고 acknowledgement를 수동으로 결정하며 최종
visibility 처리 전에 process 내부에서 handler 실패를 재시도할 수 있다. 또한 순서가
지정된 Spring bean을 통해 receive/handler/ack/failure 단계를 관찰할 수 있다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsAutoConfigurationTest' --tests '*SqsListenerAwsEmulatorTest' --no-daemon --max-workers=1`

## 향후 보호 장치

SQS listener container에 Micrometer hard dependency를 추가하지 않는다. 향후 issue에서
first-class metric이 필요하면 `SqsListenerInterceptor` 위에 별도 adapter로 Micrometer
관찰 기능을 구현한다.
