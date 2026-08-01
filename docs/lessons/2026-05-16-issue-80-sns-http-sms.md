# Issue #80 SNS HTTP 및 SMS 지원

날짜: 2026-05-16
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/80

## 배경

Issue #4에서는 Spring Boot 4 SNS publisher와 coroutine template을 도입했다. 다음
우선순위 SNS 범위에는 Spring Cloud AWS controller annotation을 복제하지 않고 직접 SMS를
게시하고 HTTP(S) endpoint message를 처리하는 기능이 필요했다.

## 결정

- 광범위한 MVC/WebFlux annotation layer를 추가하지 않고 `SnsOperations`에
  `publishSms`와 `confirmSubscription`을 추가해 Spring surface를 명시적으로 유지한다.
- SMS 게시 option은 `SnsSmsRequest`로 모델링하고 AWS 문서의
  `AWS.SNS.SMS.SMSType`, `AWS.SNS.SMS.SenderID`, `AWS.SNS.SMS.MaxPrice`,
  `AWS.MM.SMS.OriginationNumber`, `AWS.MM.SMS.EntityId`, `AWS.MM.SMS.TemplateId` 같은
  message attribute로 mapping한다.
- `SnsHttpMessageParser`로 SNS HTTP endpoint JSON의 `Notification`,
  `SubscriptionConfirmation`, `UnsubscribeConfirmation`을 parse한다. Parser는 선택적
  `x-amz-sns-message-type` header와 JSON `Type`이 일치하는지 확인하고 HTTPS가 아니거나
  SNS가 아닌 `SigningCertURL` host를 거부하지만, 암호학적 signature 검증은 의도적으로
  수행하지 않는다.
- 보안 경계를 문서에 명확히 밝힌다. Notification을 처리하거나 subscription을 확인하기
  전에 애플리케이션이 certificate chain, `Signature`, `SignatureVersion`, 예상
  `TopicArn`을 검증해야 한다.

## 검증

- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'`에서 SMS 요청
  mapping, HTTP endpoint payload mapping, subscription 확인 요청 mapping, 기존 LocalStack
  SNS/SQS fanout 검증을 포함한 SNS 테스트 23개가 통과했다.

## 후속 작업

- AWS SDK v2 surface가 Kotlin/Spring 애플리케이션용 안정적인 verifier를 제공하지 않으면
  향후 issue에서 재사용 가능한 signature 검증 지원을 추가할 수 있다.
- 저장소의 Spring Boot AOT 예제 모음에 SNS를 추가할 준비가 되면 별도 예제 모듈에서
  parser를 Spring MVC/WebFlux controller에 연결할 수 있다.
