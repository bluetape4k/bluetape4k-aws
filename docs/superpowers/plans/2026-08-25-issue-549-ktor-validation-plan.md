# #549 Ktor validation 정렬 계획

## 목표

`AwsExposedPluginConfig`의 단순 `Duration` positive validation을
`io.bluetape4k.support.requireGt`로 정렬하고, `bluetape4k-assertions`와 기존
Ktor coroutine 테스트 도구로 실패 경계와 plugin lifecycle 회귀를 고정한다.

## 범위

- `startTimeout`과 `stopTimeout`의 단항 positive 조건만 공용 helper로 교체한다.
- `databaseProperties`/DSL 혼용 및 중복 이름 거부처럼 여러 상태의 관계를
  검증하는 `require`는 명시적 진단을 보존하기 위해 유지한다.
- plugin start timeout, stop timeout, 유효한 양수 경계와 helper failure
  parameter를 테스트한다.

## 재사용 기준

- production validation: `io.bluetape4k.support.requireGt(Duration.ZERO, name)`.
- exception assertion: `io.bluetape4k.assertions.assertFailsWith`만 사용하며
  JUnit `assertThrows`와 `kotlin.test.assertFailsWith`는 사용하지 않는다.
- value/message assertion: `shouldBeEqualTo`, `shouldContain`을 사용한다.
- suspend lifecycle: 기존 `io.bluetape4k.junit5.coroutines.runSuspendIO`와
  Ktor `testApplication`을 재사용한다.

## 실행 순서

1. 현재 raw 단항 validation을 RED scan으로 기록한다.
2. 계획을 commit한 뒤 helper 적용과 boundary/lifecycle 테스트를 구현한다.
3. targeted test, 전체 `aws-ktor` test, detekt, assertion/raw scan,
   `git diff --check`를 실행한다.
4. 7-Tier review와 DoD 문서를 기록하고 stacked PR로 제출한다.

## 보존할 계약

- `IllegalArgumentException` 예외 타입과 timeout 진단 의미를 보존한다.
- registry factory, settings resolver, Ktor plugin start/stop lifecycle과
  owned client/resource 수명주기를 변경하지 않는다.
