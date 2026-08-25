# Issue #459 SNS HTTP annotation adapter 설계 명세

## SPW-01 — 대상, 독자, 근거

- 대상 이슈: `bluetape4k/bluetape4k-aws#459`
- 주 독자: `aws-spring-boot`를 사용하는 Spring MVC/WebFlux 애플리케이션 개발자와 유지보수자
- 언어: 한국어 기술 문서. API 이름, 패키지, 설정 키, 명령, URL, 예외 메시지는 원문을 유지한다.
- 목적: 기존 `SnsHttpMessageParser`와 #457에서 제공한 `SnsHttpMessageVerifier`를 Spring Controller의 SNS HTTP endpoint와 argument resolver에 연결한다.
- 현재 구현 근거:
  - `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessage.kt`
  - `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParser.kt`
  - `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifier.kt`
  - `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsOperations.kt`
  - `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsAutoConfiguration.kt`
- 공식 근거:
  - [Spring Cloud AWS 4.1 SNS HTTP endpoints](https://docs.awspring.io/spring-cloud-aws/docs/4.1.0/reference/html/index.html#sns-http-endpoints)
  - [AWS SNS notification JSON](https://docs.aws.amazon.com/sns/latest/dg/http-notification-json.html)
  - [AWS SNS subscription confirmation JSON](https://docs.aws.amazon.com/sns/latest/dg/http-subscription-confirmation-json.html)
  - [AWS SNS unsubscribe confirmation JSON](https://docs.aws.amazon.com/sns/latest/dg/http-unsubscribe-confirmation-json.html)
  - [AWS SNS HTTP headers](https://docs.aws.amazon.com/sns/latest/dg/http-header.html)
  - [AWS SNS endpoint preparation](https://docs.aws.amazon.com/sns/latest/dg/SendMessageToHttp.prepare.html)
  - [AWS SNS signature verification](https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html)
  - [Spring Framework Kotlin coroutines](https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html)
- 승인 상태: Type-A 실행계획 승인 후 본 설계안에 대한 사용자 승인을 받았다.

## 문제와 범위

현재 모듈은 SNS HTTP JSON의 구조 검증과 선택적 서명 검증을 제공하지만 Controller method mapping과 parameter binding을 제공하지 않는다. 애플리케이션은 각 endpoint에서 body를 직접 읽고 parser를 호출해야 하며, MVC/WebFlux와 Kotlin `suspend` handler 사이의 중복도 관리해야 한다.

이번 변경은 다음을 포함한다.

1. `Notification`, `SubscriptionConfirmation`, `UnsubscribeConfirmation`을 각각 구분하는 composed mapping annotation 3종
2. nested `Message`, optional `Subject`, `MessageAttributes`, raw `SnsHttpMessage`, confirmation status를 위한 argument resolver
3. Servlet과 WebFlux에서 body를 한 번만 읽고 resolver가 재사용하는 request/exchange cache
4. 검증 bean이 있으면 세 message type 모두 handler보다 먼저 `SnsHttpMessageVerifier`를 적용하는 보안 경계
5. 일반 handler와 Kotlin `suspend` handler의 MVC/WebFlux 테스트 및 README 사용 예

다음은 범위 밖이다.

- Spring Integration inbound adapter 전체 복제
- 인증서 다운로드·서명 암호화 로직 재구현
- 자동 subscription confirmation 또는 `SubscribeURL` HTTP 호출
- 기존 parser, verifier, SNS publish/SMS API의 의미 변경
- 실제 AWS 계정에 대한 배포 smoke test

## 공개 API 계약

### Endpoint mapping

패키지 `io.bluetape4k.aws.spring.sns.annotation.endpoint`에 다음 Kotlin annotation을 추가한다.

| Annotation | HTTP method | Header condition | 기본 응답 |
|---|---:|---|---|
| `NotificationMessageMapping` | `POST` | `x-amz-sns-message-type=Notification` | `204 No Content` |
| `NotificationSubscriptionMapping` | `POST` | `x-amz-sns-message-type=SubscriptionConfirmation` | `204 No Content` |
| `NotificationUnsubscribeConfirmationMapping` | `POST` | `x-amz-sns-message-type=UnsubscribeConfirmation` | `204 No Content` |

각 annotation의 `path`는 `@RequestMapping`의 `path`에 `@AliasFor`로 연결한다. outer `Content-Type`은 SNS가 `text/plain; charset=UTF-8`로 보낼 수 있으므로 mapping에서 `consumes`로 제한하지 않는다.

### Handler parameters

패키지 `io.bluetape4k.aws.spring.sns.annotation.handlers`에 다음 parameter annotation을 추가하고, 일반 status API는 `io.bluetape4k.aws.spring.sns.handlers`에 둔다.

- `@NotificationMessage`: mapping body의 `Message` 문자열을 concrete parameter type으로 변환한다. `String`은 원문을 반환하고, 그 외 타입은 configured Jackson 3 `ObjectMapper`로 JSON 변환한다. `MessageAttributes.contentType`이 JSON이 아니면 typed conversion을 400으로 거부한다. v1은 parameterized generic target을 지원하지 않으며 handler 등록 시 fail-fast한다.
- `@NotificationSubject`: `Notification`에서 optional `Subject`를 `String?`으로 반환한다. `String`/nullable `String` 이외의 type 또는 다른 message type에서 사용하면 handler 등록 시 fail-fast한다.
- `@NotificationMessageAttributes`: SNS `MessageAttributes`를 `Map<String, io.bluetape4k.aws.spring.sqs.SnsMessageAttribute>` snapshot으로 반환한다. 누락된 attribute map은 빈 map이다. 다른 map/value type은 handler 등록 시 fail-fast한다.
- `@NotificationRawMessage`: parser/verifier 결과인 `SnsHttpMessage`를 반환한다. annotation 없이 `SnsHttpMessage` parameter를 선언해도 동일하게 지원하며 다른 target type은 handler 등록 시 fail-fast한다.
- `NotificationStatus`: 공개 일반 API 패키지 `io.bluetape4k.aws.spring.sns.handlers`에서 `SubscriptionConfirmation`과 `UnsubscribeConfirmation`에 `topicArn`, `token`과 명시적 `suspend confirmSubscription()` operation을 제공한다. 구현은 기존 `SnsOperations.confirmSubscription(SnsHttpMessage, ...)`를 호출하며 자동으로 실행하지 않는다.

`NotificationMessage`는 `Notification`에만 허용하고, `NotificationStatus`는 두 confirmation type에만 허용한다. header가 매핑한 type과 JSON `Type`이 다르면 parser 단계에서 handler를 호출하지 않고 400을 반환한다.

### Verification and failure contract

요청 처리 순서는 다음과 같다.

```text
header route
  -> bounded body cache
  -> SnsHttpMessageParser.parse(json, x-amz-sns-message-type)
  -> expected TopicArn allowlist
  -> required SnsHttpMessageVerifier.verify(..., expectedTopicArn)
  -> argument resolution / payload conversion
  -> handler invocation
  -> 204 No Content
```

- 기본값은 signature/certificate 검증과 허용된 `TopicArn` allowlist를 모두 요구하는 fail-closed 모드다. `SnsHttpMessageVerifier` bean이 없거나 `bluetape4k.aws.sns.http-endpoints.expected-topic-arns`가 비어 있으면 요청을 handler에 전달하지 않는다. parser-only structural mode는 `verification-required=false`와 `allow-structural-only=true`를 함께 명시한 경우에만 허용한다.
- `SnsHttpMessageVerifier` bean이 존재하면 parser가 확인한 `TopicArn`이 allowlist에 포함되는지 먼저 확인하고, 그 ARN을 `expectedTopicArn`으로 전달해 signature, certificate, structural checks를 모든 세 message type에서 handler와 confirmation operation보다 먼저 실행한다.
- parser, signature, payload media type, Jackson conversion 오류는 `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` 또는 Spring이 같은 400으로 처리하는 변환 오류로 정규화한다. 정적 controller parameter 오용은 client fault가 아니므로 handler 등록 시 fail-fast하고 5xx/configuration failure로 분리한다.
- resolver가 없는 mapping handler도 SNS header가 있는 요청이면 Servlet/WebFlux pre-handler cache/filter에서 구조·서명·allowlist 검증을 통과해야 실행된다.
- verification bean 부재·allowlist 불일치는 각각 구성 누락 503·권한 거부 403으로 정규화하며 handler와 confirmation side effect를 실행하지 않는다. unsafe structural-only를 명시한 경우에만 parser structural validation을 수행한다.
- SNS가 재전송할 수 있으므로 handler의 idempotency는 애플리케이션 책임이며 이번 adapter가 상태 저장을 추가하지 않는다.

## 구현 경계

### 공통 계층

- `SnsHttpMessage`의 computed `messageAttributes` property가 기존 `raw` snapshot에서 SNS attribute object를 검증해 반환한다. filter/resolver 준비 단계에서 이 property를 한 번 평가해 malformed attribute도 handler 전에 거부한다. primary constructor를 변경하지 않아 기존 JVM/source 호출 경계를 보존한다.
- `SnsNotificationStatus`는 `NotificationStatus`를 구현하고 기존 `SnsOperations`만 호출한다.
- `SnsHttpMessagePayloadConverter`는 optional `tools.jackson.databind.ObjectMapper`를 사용하며 `nestedContentType` 인자로 envelope의 `MessageAttributes.contentType.Value`만 받는다. outer HTTP `Content-Type: text/plain`은 별도 허용 규칙이다. Jackson bean이 없으면 raw/String argument는 계속 작동하고 non-String typed payload는 명확한 400으로 실패한다.
- request attribute에는 parsed `SnsHttpMessage`를 저장하고 WebFlux exchange attribute에는 filter가 이미 구독·완료한 `Mono.just(parsed).cache()`만 저장한다. WebFlux filter가 유일한 parse/allowlist/verify 실행 소유자이며 성공·실패·취소 모든 경로에서 joined `DataBuffer`를 release하고, resolver는 attribute를 읽기만 한다. filter 없는 standalone resolver fallback은 별도 테스트 경로로 bounded read를 적용한다.

### Servlet 계층

- `SnsHttpMessageServletFilter`는 SNS header가 있는 요청의 body를 bounded byte array로 읽고 allowlist·verifier 검증한 뒤 replayable `HttpServletRequestWrapper`로 downstream에 전달한다. verifier 부재는 503, allowlist 불일치는 403, 그 밖의 입력은 400으로 종료한다.
- `SnsMvcHttpMessageArgumentResolver`는 request attribute의 parsed message를 재사용하고, filter가 없는 standalone resolver 테스트에서는 request body를 한 번 읽어 attribute에 저장한다.
- `SnsHttpEndpointWebMvcAutoConfiguration`은 `spring-webmvc`와 Servlet API가 있을 때만 `WebMvcConfigurer`와 filter registration을 제공한다.

### WebFlux 계층

- `SnsHttpMessageWebFilter`는 `DataBufferUtils.join(body, MAX_MESSAGE_BYTES + 1)`으로 사전 크기 제한을 적용한다. filter가 join → parse → allowlist → verifier를 chain 이전에 완료하며 overflow는 400으로 끝낸다. body를 복사한 직후 joined buffer를 성공·실패·취소 모두에서 release한 뒤 replayable `ServerHttpRequestDecorator`를 사용한다. synchronous verifier가 실행될 때는 Reactor `boundedElastic`로 blocking certificate/signature work를 격리하고, cancellation은 400으로 변환하지 않는다. resolver 없는 SNS mapping도 이 gate를 통과해야 한다.
- `SnsWebFluxHttpMessageArgumentResolver`는 filter가 저장한 완료된 exchange attribute의 `Mono<SnsHttpMessage>`를 공유하며 parse/verify를 재구독하지 않는다. filter 없는 standalone fallback만 resolver가 별도 bounded read를 수행한다. `kotlin suspend` handler는 Spring Framework의 coroutine invocation 경계를 그대로 사용한다.
- `SnsHttpEndpointWebFluxAutoConfiguration`은 `spring-webflux`가 있고 reactive web application일 때만 resolver/filter를 제공한다.

### Dependency and conditional-loading policy

- `spring-web`, `spring-webmvc`, `spring-webflux`, Servlet API는 compileOnly로 선언한다. 소비자가 선택한 web stack만 runtime에 둔다.
- `aws-spring-boot` 기존 AWS SDK `compileOnly` 정책과 `bluetape4k-dependencies` BOM 정책을 유지한다.
- 두 auto-configuration class를 `AutoConfiguration.imports`에 등록하고 `@ConditionalOnAwsEnabled`, `@ConditionalOnProperty(prefix="bluetape4k.aws.sns", name=["enabled"], matchIfMissing=true)`, 각 class-level `@ConditionalOnClass(name=...)`, `@ConditionalOnWebApplication`, `bluetape4k.aws.sns.http-endpoints.enabled` property로 classpath/runtime 경계를 보호한다. `verification-required`는 true, `allow-structural-only`는 false, `expected-topic-arns`는 빈 집합을 기본값으로 두고 빈 allowlist 요청을 fail-closed 한다.

## 테스트 수용 기준

1. mapping annotation 3종이 정확한 `POST` method, header, path alias, 204 response를 생성한다.
2. MVC `MockMvc`에서 세 message type이 각 handler로 라우팅되고 `@NotificationMessage`, `@NotificationSubject`, `@NotificationMessageAttributes`, raw envelope가 같은 cached body에서 해석된다.
3. WebFlux `WebTestClient`에서 동일한 세 type과 body replay가 동작한다. resolver 없는 mapping도 filter의 선행 verifier gate를 통과한다.
4. Kotlin `suspend` MVC/WebFlux handler가 정상적으로 호출되고 204를 반환한다.
5. outer `Content-Type: text/plain` JSON이 허용되고 nested JSON payload는 `MessageAttributes.contentType=application/json`으로 변환된다. nested content type이 없거나 `text/plain`이면 typed payload는 400이다.
6. malformed JSON, header/body type mismatch, invalid media type, typed conversion error가 handler를 호출하지 않고 400을 반환한다.
7. verifier bean을 주입한 MVC/WebFlux 요청에서 invalid signature와 allowlist 밖의 유효 서명이 handler와 confirmation status operation에 도달하지 않는다. resolver 없는 mapping도 verifier 완료 전에 handler에 도달하지 않으며, verifier 부재도 기본 모드에서 handler를 차단한다.
8. confirmation status의 `confirmSubscription()`은 handler가 호출할 때만 기존 `SnsOperations`로 정확히 한 번 전달되고, adapter가 자동 호출하지 않는다. invalid signature·암묵적 경로에서는 호출 0회다.
9. 기존 parser/verifier 및 SNS operations 테스트가 회귀 없이 통과한다.
10. README와 README.ko.md의 controller 예제가 동일한 API 계약을 설명한다.

## DoD와 알려진 공백

- DoD: 변경된 Kotlin source/test/docs, `git diff --check`, targeted tests, module test, detekt(기존 baseline finding 분리), optional-classpath conditional test, composed mapping runtime-hints test, Lore commit이 모두 증거로 남는다.
- Human review: 1인 개발자 요청에 따라 `N/A`; code-reviewer 역할의 독립 정적 검토와 Kotlin checklist는 수행한다.
- PR, push, merge, issue close, release는 권한 범위 밖이므로 수행하지 않는다.
- 실제 SNS delivery retry와 실제 AWS certificate endpoint는 local MockMvc/WebTestClient 증거로 대체하며 production smoke로 주장하지 않는다.

## SPW-03 — 한국어 기술 문체 점검

- API 이름, 설정 키, 명령, URL, 예외 타입은 영문 token을 보존했다.
- `검증`, `변환`, `라우팅`, `body cache`, `confirmation`을 문서 전체에서 같은 의미로 사용했다.
- “중요하다”와 같은 근거 없는 강조 대신 handler 도달 여부, HTTP 상태, 실제 resolver 결과를 계약으로 적었다.
- 현재 구현과 제안 설계를 분리해 서술했고, 실제 AWS smoke가 없다는 제한을 명시했다.

## SPW-04 — 추적성

| 요구사항 | 설계/검증 위치 |
|---|---|
| 세 message type routing | endpoint mapping 표, MVC/WebFlux routing tests |
| typed payload/raw envelope | handler parameter 계약, payload/raw resolver tests |
| content type/format 4xx | failure contract, malformed/conversion tests |
| MVC/WebFlux/suspend | Servlet/WebFlux 계층, `MockMvc`/`WebTestClient` tests |
| invalid signature pre-handler | verification pipeline, filter tests |
| explicit confirmation | `NotificationStatus`, status resolver test |
| parser/verifier reuse | 구현 경계, existing regression tests |

## SPW-05 — read-back

plan 작성 뒤 명세를 다시 읽어 제목·표·코드 블록·URL·설정 키·수용 기준이 설계 승인 내용과 일치하는지 확인했다. plan은 이 명세의 파일 경계와 수용 기준을 1:1로 추적하며, 두 문서에 `git diff --check`를 적용했다.

## Writer DoD

- [x] SPW-01 audience, purpose, source paths/URLs, identifiers, unknowns
- [x] SPW-02 boundaries, API contracts, failure modes, compatibility, acceptance, DoD
- [x] SPW-03 Korean technical register and terminology pass
- [x] SPW-04 source-to-claim and requirement-to-test traceability
- [x] SPW-05 plan 작성 후 명세·계획 read-back 및 `git diff --check` 완료
