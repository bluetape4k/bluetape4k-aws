# Issue #635 SNS HTTP envelope 공통 정책 설계

## 문서 상태

- 대상 저장소: `bluetape4k/bluetape4k-aws`
- 대상 브랜치: `feat/issue-635-sns-envelope-policy`
- 기준 브랜치와 SHA: `origin/develop`, `216c95203ff63e0d8c7f822c77ac4386c6c3b6c1`
- 관련 이슈: [#635](https://github.com/bluetape4k/bluetape4k-aws/issues/635)
- 승인된 접근: adapter decode + `aws-java` 순수 정책 + 공통 test fixture
- 구현 중지 지점: PR 생성과 exact-head CI·리뷰 완료 후 병합 승인 대기

## 문제

Ktor와 Spring Boot의 `SnsHttpMessageParser`가 본문 크기, 필수 필드,
`Type`/header, topic ARN, `SigningCertURL`, 메시지 타입별 필드 조합을 각각 검증한다.
두 구현이 현재 비슷해 보여도 변경 지점과 JSON decoder가 달라 정책이 어긋날 수 있다.
특히 Ktor 기본 parser는 Jackson strict duplicate detection을 명시하지만 Spring parser는
`JsonParserFactory`의 runtime 구현에 의존한다.

파싱 결과는 여전히 신뢰되지 않은 구조 데이터다. 이 작업은 SNS 서명, 인증서 체인,
예상 topic ARN, 재생 방지, credentials, IAM 또는 retry 정책을 공통 parser로 옮기지 않는다.

## 현재 근거

| 근거 | 확인 결과 | 설계 반영 |
| --- | --- | --- |
| Issue #635 | framework별 decode와 공통 순수 검증을 분리하고 동일 corpus를 요구한다. | core policy와 adapter decoder를 분리한다. |
| Ktor parser | 기본 `ObjectMapper`만 `STRICT_DUPLICATE_DETECTION`을 사용하며 정책 helper를 자체 소유한다. | 기존 constructor와 기본 parser를 유지하고 decode 결과만 core policy에 전달한다. |
| Spring parser | `JsonParserFactory.parseMap` 뒤에서 같은 정책을 다시 구현한다. | adapter 내부 strict Jackson decoder로 duplicate rejection을 명시한다. |
| 모듈 의존성 | `aws-java`, `aws-ktor`, `aws-spring-boot` 모두 Jackson 3을 이미 `compileOnly`로 사용하며 두 adapter는 `aws-java`에 의존한다. | 새 dependency 없이 core는 Jackson 타입을 노출하지 않는다. |
| 공개 API | 두 adapter가 서로 다른 `SnsHttpMessage`와 `SnsHttpMessageType`을 공개한다. | 기존 타입과 `parse` signature를 그대로 두고 validated envelope를 변환한다. |
| baseline | Ktor parser 6개와 Spring parser 10개 테스트가 통과한다. | 변경 후 같은 테스트와 공통 corpus를 함께 실행한다. |

## 대안

### A. adapter decode + 공통 순수 정책 + 공통 test fixture

`aws-java`가 framework 중립 `SnsHttpEnvelopePolicy`, validated value, rejection reason을
제공한다. adapter는 strict JSON decode만 수행하고 기존 공개 모델로 변환한다.
`java-test-fixtures` variant가 한 corpus를 두 adapter 테스트에 제공한다.

- 장점: 정책 SSOT, 기존 adapter API 보존, decoder dependency 역전 방지, 동일 regression 입력
- 단점: additive public core API와 test fixture variant가 생긴다.
- 결정: 이 접근을 사용한다.

### B. Jackson parser 전체를 `aws-java`로 이동

구현량은 작지만 core 공개 surface가 Jackson type과 설정에 결합된다. Spring/Ktor가 다른
decoder를 선택할 수 없고 이슈의 dependency 경계를 위반하므로 기각한다.

### C. adapter 구현을 유지하고 테스트 파일만 복제

초기 diff는 작지만 검증 helper와 corpus가 계속 두 벌이다. 정책 변경 누락을 막지 못하므로
이번 이슈의 목적을 충족하지 못한다.

## 승인된 설계

### 1. core policy와 값 계약

`aws-java`에 다음 additive public 타입을 둔다.

```kotlin
enum class SnsHttpEnvelopeType(val value: String)

enum class SnsHttpEnvelopeRejectionReason {
    BODY_REQUIRED,
    BODY_TOO_LARGE,
    INVALID_JSON,
    REQUIRED_FIELD,
    INVALID_FIELD_TYPE,
    UNSUPPORTED_TYPE,
    HEADER_TYPE_MISMATCH,
    INVALID_TOPIC_ARN,
    INVALID_URI,
    INVALID_SIGNING_CERT_URI,
    TYPE_CONTRACT,
}

class SnsHttpEnvelopeValidationException(
    val reason: SnsHttpEnvelopeRejectionReason,
    message: String,
    cause: Throwable? = null,
): IllegalArgumentException(message, cause)

data class SnsHttpEnvelope(/* normalized fields and raw snapshot */)
```

`SnsHttpEnvelopePolicy.parse`는 JSON decoder를 함수로 받는다.

```kotlin
fun parse(
    json: String,
    messageTypeHeader: String? = null,
    maxMessageBytes: Int = MAX_MESSAGE_BYTES,
    decoder: (String) -> Map<String, Any?>,
): SnsHttpEnvelope
```

core는 UTF-8 byte 상한을 먼저 확인하고 decoder를 정확히 한 번 호출한다. decoder가 던진
예외는 기존 `IllegalArgumentException` 계층을 유지하는
`SnsHttpEnvelopeValidationException(INVALID_JSON)`으로 바꾼다. 예외 메시지와 reason에는
payload, 서명, URL 전체, topic ARN 전체를 넣지 않는다.

### 2. 공통 검증 규칙

- 기본 본문 상한은 `256 * 1024` bytes다. 정확히 256 KiB는 허용하고 1 byte 초과는 거부한다.
- 필수 필드 `Type`, `MessageId`, `TopicArn`, `Message`, `Timestamp`,
  `SignatureVersion`, `Signature`, `SigningCertURL`은 비어 있지 않은 문자열이어야 한다.
- 지원 타입은 `Notification`, `SubscriptionConfirmation`,
  `UnsubscribeConfirmation`뿐이다.
- 비어 있지 않은 `x-amz-sns-message-type`은 trim 후 JSON `Type`과 같아야 한다.
- confirmation 타입은 `Token`과 `SubscribeURL`이 필요하다.
- `Notification`은 `Token`과 `SubscribeURL`을 포함할 수 없다.
- topic ARN은 `arn:<partition>:sns:<region>:<account>:<resource>` 구조와 비어 있지 않은
  partition/region을 가져야 한다.
- `SigningCertURL`은 HTTPS, host 존재, userinfo/query/fragment/custom port 없음,
  `.pem` path를 만족해야 한다.
- signing host는 partition별 정확한 label 구조만 허용한다.
  - `aws`, `aws-us-gov`: `(sns|sns-fips).<region>.amazonaws.com`
  - `aws-cn`: `(sns|sns-fips).<region>.amazonaws.com.cn`
- signing host region과 topic ARN region은 같아야 한다.
- `SubscribeURL`, `UnsubscribeURL`은 기존과 같이 URI syntax만 검증한다.

### 3. adapter 경계와 호환성

Ktor와 Spring parser는 각자 strict Jackson `ObjectMapper`로 JSON object를
`Map<String, Any?>`로 한 번 decode한다. duplicate key, array root, malformed JSON은 decoder
실패로 처리되고 core의 `INVALID_JSON` reason으로 수렴한다.

각 adapter는 `SnsHttpEnvelope`를 기존 공개 `SnsHttpMessage`와 기존 enum으로 변환한다.
다음 계약은 유지한다.

- Ktor `SnsHttpMessageParser` constructor, `default()`, `parse` signature
- Spring `SnsHttpMessageParser` object와 `parse` signature
- 두 adapter의 기존 `SnsHttpMessage`, `SnsHttpMessageType`, `raw` 타입
- parser는 암호학적 서명 검증을 수행하지 않는다는 KDoc/README 경계
- caller validation은 `IllegalArgumentException` 호환 계층

### 4. 공통 conformance corpus

`aws-java`에 `java-test-fixtures` plugin을 적용하고
`aws-java/src/testFixtures/kotlin/io/bluetape4k/aws/sns/`에 framework 중립 corpus를 둔다.
두 adapter는 `testImplementation(testFixtures(project(":bluetape4k-aws-java")))`로 같은
fixture를 소비한다.

valid corpus는 세 메시지 타입, header trim/생략, 256 KiB 경계를 포함한다. invalid corpus는
duplicate key, malformed/non-object JSON, 256 KiB + 1 byte, 필수 필드 누락/비문자열,
지원하지 않는 타입, header 불일치, topic ARN, URI syntax, scheme, userinfo, query, fragment,
port, `.pem`, host label, region, partition을 포함한다. 각 invalid case는 기대 reason과
redacted message를 함께 고정한다.

### 5. 성능과 운영 계약

- byte 상한 검사는 decode 전에 수행한다.
- JSON decode는 adapter당 한 번만 실행한다.
- core normalization은 field 수에 비례하며 payload를 다시 직렬화하지 않는다.
- rejection reason은 고정 enum이며 raw body, signature, token을 노출하지 않는다.
- Spring의 기존 endpoint error policy는 이 예외를 `400` 입력 오류로 처리한다.
- emulator나 실제 AWS 호출은 이 순수 parser 정책 검증에 필요하지 않다.

## 실패 모드와 대응

| 실패 모드 | 신호 | 대응 |
| --- | --- | --- |
| custom Ktor mapper가 duplicate detection을 끈다. | 공통 duplicate case가 Ktor에서 통과한다. | parser가 사용하는 factory에 strict detection이 없으면 별도 strict reader를 만들거나 constructor 계약을 강화한다. |
| Spring Jackson이 compileOnly runtime에 없다. | Spring module test/classpath 또는 AOT smoke가 실패한다. | 기존 Spring Boot Jackson classpath 계약을 확인하고 새 runtime dependency는 추가하지 않는다. |
| core raw map 변환이 adapter 공개 `raw` 의미를 바꾼다. | 기존 parser 테스트나 message attribute 테스트가 실패한다. | adapter별 기존 raw 변환 규칙을 명시적으로 유지한다. |
| host allowlist가 합법적인 partition endpoint를 거부한다. | 공통 valid partition case가 실패한다. | AWS 공식 host 형식 근거로 corpus와 정책을 함께 수정하고 추측성 suffix 허용은 추가하지 않는다. |
| 예외 메시지가 payload 일부를 포함한다. | corpus redaction assertion이 실패한다. | 고정 reason/message만 사용하고 decoder 원문 메시지는 외부 message에 합치지 않는다. |
| fixture variant가 publication/CI를 깨뜨린다. | `testFixturesClasses`, module build 또는 Gradle metadata 검증 실패 | test fixture plugin 변경을 rollback하고 한 root resource를 두 source set에 연결하는 대안을 재검토한다. |

## 검증 전략

1. core policy test에서 decoder 호출 횟수, 크기 선검사, 필드/ARN/URI/type 계약을 RED→GREEN으로 고정한다.
2. 공통 corpus를 두 adapter test에 연결하고 기존 parser에서 정책 차이를 RED로 확인한다.
3. core policy와 strict adapter decoder를 구현해 두 adapter의 기대 reason을 일치시킨다.
4. 기존 parser, resolver/filter, message attribute 관련 회귀 테스트를 실행한다.
5. 세 affected module test, compile, detekt, `git diff --check`를 실행한다.
6. public API 변경은 additive core 타입뿐이며 기존 adapter descriptor가 유지되는지 `javap` 또는 repository compatibility task로 확인한다.

## 수용 조건

- [ ] `aws-java`가 framework-neutral decoded envelope 정책을 소유한다.
- [ ] core production API가 Ktor, Spring, Jackson 타입에 의존하지 않는다.
- [ ] Ktor/Spring 기존 parser API와 model ABI가 유지된다.
- [ ] 두 adapter가 한 conformance corpus의 valid/invalid case를 모두 같은 reason으로 처리한다.
- [ ] duplicate key, hostile signing host, partition/region 불일치, oversized body, 필수 필드 위반을 양쪽에서 거부한다.
- [ ] 본문은 decode 전에 제한하고 decoder는 한 번만 호출한다.
- [ ] 예외 reason/message가 payload와 secret-bearing field를 노출하지 않는다.
- [ ] KDoc와 영어·한국어 README가 structural parsing과 signature verification 경계를 구분한다.
- [ ] affected tests, compile, detekt, compatibility, CI가 exact head에서 통과한다.

## DoD

- [ ] 공통 validator와 rejection reason 구현
- [ ] 공통 test fixture 및 adapter conformance GREEN
- [ ] 기존 adapter API/ABI와 raw mapping 회귀 없음
- [ ] 성능·보안·운영 검토 P0=0/P1=0
- [ ] 문서 parity와 한국어 기술 문체 검사 통과
- [ ] Lore lesson/commit, PR metadata, exact-head CI와 review 완료
- [ ] 병합은 fresh exact-head 승인 전까지 실행하지 않음
