# #548 aws-spring-boot validation 정렬 계획

## 목표

`aws-spring-boot` SQS 설정 DTO의 단순 blank/range/greater-than 검증을
`io.bluetape4k.support` helper로 정렬하고, `bluetape4k-assertions`로
경계값과 실패 메시지를 검증한다.

## 범위

- `SqsProperties.Listener`의 maxMessages, waitTimeSeconds, concurrency,
  stopTimeoutMillis 검증
- `SqsProperties.Retry`의 maxAttempts, backoff, multiplier, jitterRatio 검증
- `SqsProperties.RedrivePolicy`의 deadLetterTargetArn와 maxReceiveCount 검증
- visibility timeout 값의 단순 range 검증
- heartbeat의 동시 설정/interval<heartbeat 관계 불변식과 Spring binding,
  Serializable/serialVersionUID 계약은 유지

## 재사용 기준

- blank: `requireNotBlank`
- inclusive range: `requireInRange`
- lower bound: `requireGe` 또는 수치에는 `requirePositiveNumber`
- `Duration`의 zero-or-positive 검증: `requireGe(Duration.ZERO, name)`
- 예외 검증: `io.bluetape4k.assertions.assertFailsWith`와
  `shouldContain`, `shouldBeEqualTo` 등 기존 matcher
- 관계형 불변식은 bluetape helper를 기계적으로 조합하지 않고 raw `require`로
  유지하며 그 이유를 review에 기록

## 검증 순서

1. 변경 전 단순 validation raw `require`와 테스트 경계를 RED scan으로
   기록한다.
2. production validation을 공용 helper로 교체하고 경계/메시지 regression을
   `bluetape4k-assertions`로 보강한다.
3. SQS configuration targeted test, `aws-spring-boot` 전체 test,
   detekt를 순차 실행한다.
4. raw 단순 validation scan, `git diff --check`, Spring/Kotlin checklist와
   7-Tier review를 완료한다.

## 수용 기준

- 단순 caller validation에 raw `require`가 남지 않는다(heartbeat 관계 검증은
  명시적으로 제외).
- `IllegalArgumentException`, parameter 의미, Spring binding과 Serializable
  계약이 유지된다.
- 모든 failure assertion은 `io.bluetape4k.assertions.assertFailsWith`를
  사용한다.
- targeted/module test, detekt, raw scan이 통과하고 7-Tier DoD가 기록된다.
