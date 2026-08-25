# #547 aws-spring-boot assertions 정렬 계획

## 목표

`aws-spring-boot` 테스트의 예외 검증을 `io.bluetape4k.assertions.assertFailsWith`
로 통일하고, 기존 bluetape4k assertion matcher로 예외 메시지와 결과를
검증한다. raw JUnit `assertThrows`는 이 변경 범위에서 허용하지 않는다.

## 범위

- ConfigData location parser, classpath guard, loader 테스트의
  `org.junit.jupiter.api.assertThrows` 제거
- S3 metadata와 SQS listener container 테스트의 동일한 예외 assertion 정렬
- 이미 `assertFailsWith`를 사용하는 SQS registry 테스트는 동작과 matcher를
  유지하고 중복 변경하지 않음
- 예외 타입, sanitized message, resource identity와 coroutine cancellation
  계약은 유지

## 재사용 기준

- 예외 캡처: `io.bluetape4k.assertions.assertFailsWith`
- 값/문자열 검증: 기존 `io.bluetape4k.assertions.shouldBeEqualTo`,
  `shouldContain`, `shouldNotContain`, `shouldBeTrue`,
  `shouldBeInstanceOf`를 우선 사용
- JUnit는 `@Test` 등 실행 lifecycle에만 사용하고 assertion API로 사용하지 않음

## 검증 순서

1. 변경 전 대상 파일의 raw JUnit `assertThrows` import/call을 RED scan으로
   기록한다.
2. `assertFailsWith`와 기존 bluetape matcher로 대상 테스트를 교체한다.
3. 대상 테스트와 `aws-spring-boot` 전체 테스트를 실행한다.
4. 대상 파일의 raw assertion scan, `detekt`, `git diff --check`를 실행한다.
5. 7-Tier review 문서에 재사용 근거와 미실행 범위를 기록한다.

## 수용 기준

- 대상 5개 파일의 모든 예외 assertion이
  `io.bluetape4k.assertions.assertFailsWith`를 사용한다.
- 대상 파일에서 `org.junit.jupiter.api.assertThrows`와 `assertThrows<...>`가
  0건이다.
- 예외 타입과 sanitized message/resource assertion이 유지된다.
- targeted/module test와 detekt가 통과하고, 7-Tier DoD가 기록된다.
