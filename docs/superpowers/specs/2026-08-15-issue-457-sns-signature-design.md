# SNS HTTP 메시지 서명 검증 설계

## 목표

`aws-spring-boot`의 SNS HTTP(S) 메시지를 handler에 전달하기 전에 AWS 서명을 검증한다. 기존 `SnsHttpMessageParser`의 구조 검증과 `TopicArn` 확인을 유지하면서 AWS SDK v2 `sns-message-manager`를 사용해 Signature Version 1/2, 인증서 조회·캐시·체인 검증을 수행한다.

## 독자와 근거

- 독자: `aws-spring-boot`를 사용하는 Kotlin/Spring Boot 개발자와 이 모듈의 유지보수자
- 대상 저장소/기준 ref: `bluetape4k/bluetape4k-aws`, `develop`
- 요구사항: GitHub issue [#457](https://github.com/bluetape4k/bluetape4k-aws/issues/457)
- 현재 구현: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParser.kt`, `SnsHttpMessage.kt`, `SnsAutoConfiguration.kt`
- 현재 테스트: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParserTest.kt`, `SnsAutoConfigurationTest.kt`
- 외부 근거: [AWS SNS 서명 검증](https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message.html), [AWS SDK v2 SnsMessageManager API](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/messagemanager/sns/SnsMessageManager.html), [Spring Cloud AWS의 도입 사례](https://github.com/awspring/spring-cloud-aws/pull/1614)

현재 parser는 JSON 필드 타입, 메시지 유형, payload 크기, `SigningCertURL`의 HTTPS·SNS host·region·partition을 검증하지만 암호학적 서명은 검증하지 않는다. 따라서 parser 결과를 바로 notification handler나 `confirmSubscription`에 전달하면 위조 payload가 업무 코드에 도달할 수 있다.

## 범위

### 포함

1. AWS SDK v2 `software.amazon.awssdk:sns-message-manager` catalog alias와 `aws-spring-boot`의 `compileOnly`/test 의존성 추가
2. 원문 JSON과 선택적 message-type header를 받아 구조 검증 후 SDK manager로 서명을 검증하는 `SnsHttpMessageVerifier`
3. 선택적 `expectedTopicArn` 불일치의 조기 거부
4. SDK manager의 lifecycle을 닫는 `AutoCloseable` 계약
5. `bluetape4k.aws.sns.verification.enabled`(기본값 `true`) 기반의 별도 Spring Boot 자동 구성
6. 영어·한국어 README의 runtime dependency, 기본 활성화, opt-out 위험, handler 전달 순서 문서화
7. 정상, 잘못된 서명, topic 불일치, parser 거부, manager lifecycle, 자동 구성 classpath/property 경계 테스트

### 제외

- MVC/WebFlux/Ktor HTTP adapter와 controller/handler annotation
- 자체 canonical string, X.509, Signature Version 1/2 암호 구현
- SNS topic resolver·cache 또는 SQS envelope 변환
- Floci가 생성하지 않는 실제 SNS 서명의 emulator 통합 테스트
- 기본 parser의 public API 제거·변경

이번 PR은 검증 공통 계약과 사용 가능한 bean을 제공한다. adapter가 추가되는 후속 PR은 반드시 `SnsHttpMessageParser` 이후 `SnsHttpMessageVerifier`를 호출하고 검증 실패를 handler에 전달하지 않아야 한다.

## 선택한 접근

### 권장: AWS SDK manager 위임

`SnsHttpMessageVerifier.verify`는 다음 순서를 고정한다.

1. `SnsHttpMessageParser.parse(json, messageTypeHeader)`로 구조·header·URL 계약을 확인한다.
2. `expectedTopicArn`이 있으면 parsed `topicArn`과 정확히 비교하고 불일치 시 `IllegalArgumentException`을 던진다. 이 경우 인증서 네트워크 요청을 시작하지 않는다.
3. 동일한 원문 JSON을 `SnsMessageManager.parseMessage(json)`에 전달한다. manager가 서명 또는 인증서 검증에 실패하면 예외를 그대로 전파하고 결과를 반환하지 않는다.
4. 검증이 끝난 parser 결과를 반환한다. parser 결과는 기존 `SnsHttpMessage` wire 필드와 helper property를 유지한다.

manager는 기본 생성 시 bean 생성 단계에서 인증서를 조회하지 않는다. 실제 네트워크 동작은 `verify` 호출과 SDK 내부 인증서 cache miss 시점에만 발생한다. `SnsProperties.region`이 있으면 auto-configuration이 manager builder의 region pinning에 사용한다.

### 대안과 기각 사유

| 대안 | 장점 | 기각 사유 |
| --- | --- | --- |
| 자체 서명·인증서 구현 | 의존성 표면이 작음 | 보안 알고리즘, 인증서 chain, cache, host allowlist를 새로 유지해야 하며 SDK 권장 경계를 중복한다 |
| parser만 확장하고 호출자에게 위임 | 변경량이 작음 | #457의 spoofing 방어와 fail-closed 수용 기준을 충족하지 못한다 |
| SDK manager 위임 | AWS가 유지하는 검증·cache·partition 처리를 재사용 | 선택적 runtime dependency와 manager lifecycle을 문서화·테스트해야 한다 |

## API와 구성 계약

```kotlin
class SnsHttpMessageVerifier(
    private val messageManager: SnsMessageManager = SnsMessageManager.builder().build(),
) : AutoCloseable {
    fun verify(
        json: String,
        messageTypeHeader: String? = null,
        expectedTopicArn: String? = null,
    ): SnsHttpMessage

    override fun close()

    companion object {
        fun forRegion(region: String?): SnsHttpMessageVerifier
    }
}
```

- `verify`는 빈 JSON, 잘못된 field type, header/type 불일치, 악성 `SigningCertURL`, 잘못된 topic ARN을 parser 예외로 거부한다.
- `expectedTopicArn`은 null이면 검사를 생략하고, 값이 있으면 blank가 아니어야 하며 parsed ARN과 exact match해야 한다.
- `SnsMessageManager` 예외는 숨기거나 성공값으로 변환하지 않는다. 검증 실패는 fail-closed다.
- verifier가 manager를 생성한 경우 `close()`가 manager를 닫는다. Spring bean은 `destroyMethod = "close"`로 등록한다.
- `SnsMessageManager`가 runtime classpath에 없으면 verification auto-configuration은 조건 불충족으로 bean을 만들지 않는다. 기존 SNS client/template와 parser는 영향을 받지 않는다.
- `forRegion`은 non-blank region을 `Region.of`로 변환해 manager builder에 전달하고, null이면 SDK 기본 region 선택을 사용한다. 자동 구성은 `SnsProperties.region`을 이 factory에 전달한다.

구성 키는 다음과 같다.

```yaml
bluetape4k:
  aws:
    sns:
      verification:
        enabled: true
```

`enabled=false`는 자동 구성 verifier bean만 끈다. 수동으로 생성한 verifier와 parser에는 영향을 주지 않으며, 문서에서 unsigned parser 결과를 handler에 전달하지 말아야 한다고 명시한다.

`SnsProperties.Verification.enabled`의 기본값도 `true`로 유지해 configuration metadata와 conditional property의 기본 동작을 일치시킨다.

## 구성 요소와 책임

| 구성 요소 | 책임 | 비책임 |
| --- | --- | --- |
| `SnsHttpMessageParser` | 구조, type, URL, ARN, payload 크기 검증과 기존 값 객체 생성 | 서명·인증서 검증 |
| `SnsHttpMessageVerifier` | parser 결과의 topic 계약 확인, AWS SDK manager 호출, fail-closed, close 위임 | HTTP adapter lifecycle, 업무 handler 호출 |
| `SnsHttpMessageVerificationAutoConfiguration` | manager classpath/property 조건, region pinning bean 생성 | SNS client/template 생성 |
| `SnsProperties.Verification` | 기본 활성화 property binding과 metadata | 인증서 cache 설정 |
| AWS SDK `SnsMessageManager` | Signature v1/v2, signing certificate 조회·cache·chain·SNS host 검증 | application expected TopicArn 정책 |

## 오류·보안 경계

1. **악성 URL**: parser가 HTTPS, userinfo/query/fragment/port/path, SNS host, topic region/partition을 거부한다. verifier는 parser가 반환한 값만 사용한다.
2. **위조·변조 서명**: SDK manager 예외를 그대로 전파하고 parser 결과를 반환하지 않는다. handler/confirm API는 verifier 성공 이후에만 호출할 수 있다.
3. **다른 topic**: `expectedTopicArn` mismatch를 manager 호출 전에 거부해 불필요한 certificate fetch를 방지한다.
4. **네트워크 지연·cache miss**: manager 외부 호출은 lock이나 Spring bean 생성 시점에 실행하지 않는다. timeout·telemetry는 후속 lifecycle 이슈로 남기고 이번 PR에서 보장한다고 주장하지 않는다.
5. **의존성 부재 또는 opt-out**: manager가 없거나 verification property가 false면 verifier auto-config를 만들지 않는다. 이 상태에서 parser를 신뢰 경계로 오인하지 않도록 README와 KDoc에 명시한다.
6. **resource lifecycle**: Spring bean destroy 시 manager를 닫고, 수동 사용자는 `use {}` 또는 명시적 `close()`를 사용한다.

## 호환성·마이그레이션

- 기존 `SnsHttpMessageParser.parse` 시그니처와 반환 타입은 유지한다.
- 기존 `SnsProperties` client/template 구성은 유지하고 `verification` nested property만 추가한다.
- SNS message manager는 `compileOnly`이므로 consumer가 runtime에 `software.amazon.awssdk:sns-message-manager`를 추가해야 한다. version은 `bluetape4k-dependencies`의 AWS SDK v2 catalog/BOM을 따른다.
- auto-config verifier가 없던 애플리케이션은 기존 동작을 유지하지만, 보안 경계가 필요한 endpoint는 `SnsHttpMessageVerifier` bean을 주입해야 한다.
- Floci에는 실제 SNS 서명 생성 계약이 없으므로 공식 fixture 또는 manager mock을 사용한다. 실제 AWS smoke가 필요한 경우 별도 credential-gated 검증으로 기록한다.

## 수용 기준

- [ ] SDK catalog alias가 AWS SDK v2 resolved version으로 선택되고 `dependencyInsight`로 증명된다.
- [ ] 정상 Notification, SubscriptionConfirmation, UnsubscribeConfirmation JSON이 parser 이후 manager 검증을 거쳐 반환된다.
- [ ] manager가 던진 검증 예외가 동일 cause로 전파되고 성공값이 반환되지 않는다.
- [ ] 잘못된 parser input/header/URL과 expected `TopicArn` mismatch가 manager 호출 전에 거부된다.
- [ ] verifier `close()`가 manager에 정확히 한 번 위임된다.
- [ ] auto-config가 manager classpath + property true에서만 bean을 등록하고 `verification.enabled=false`, `sns.enabled=false`, manager classpath 부재에서 back off한다.
- [ ] 영어·한국어 README와 KDoc이 runtime dependency, 기본값, opt-out 위험, adapter 호출 순서를 설명한다.
- [ ] targeted test, module test, detekt/compile, `git diff --check`가 통과한다.

## DoD

- API·auto-config·catalog·docs 변경이 issue #457 범위에만 포함된다.
- 기존 parser/client/template API와 단건 wire contract가 회귀하지 않는다.
- 테스트가 성공·실패·경계·lifecycle을 고정하고 network/Floci 한계를 명시한다.
- PR 본문은 한국어로 작성하고 마지막에 `## DoD Status`를 포함한다.

## Writer gate

- SPW-01: PASS — issue, local source/test anchors, AWS primary docs, SDK API, and unresolved runtime smoke boundary recorded.
- SPW-02: PASS — scope, alternatives, API, components, failures, compatibility, acceptance, and DoD included.
- SPW-03: PASS — Korean technical register checked with `korean-naturalness-checklist.md`; identifiers, URLs, commands, and exact property keys preserved.
- SPW-04: PASS — design claims mapped to current parser/auto-config source and AWS SDK primary references.
- SPW-05: PASS — rendered Markdown read-back completed; headings, code fence, table, property key, source links, acceptance mapping, and declared network/Floci gaps are internally consistent.
- User spec review: PASS — 2026-08-15 사용자 승인으로 plan gate를 진행한다.
