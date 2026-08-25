# #544 aws-java 테스트 패턴 정렬 실행 계획

## 목표

`aws-java/src/test`의 raw JUnit assertion과 `UUID.randomUUID()`를
bluetape4k assertions 및 `Uuid.V7` generator로 정렬하여 테스트 의도를
matcher 이름과 ecosystem 식별자 정책으로 드러낸다.

## 범위

- `S3TablesSmokeTest`: 문자열/collection/boolean 검증을
  `io.bluetape4k.assertions` matcher로 교체하고 테스트 리소스 suffix를
  `Uuid.V7`로 생성
- DynamoDB enhanced tests: table/entity IDs를 `Uuid.V7.nextIdAsString()`으로
  교체
- `SfnSmokeTest`: state machine 이름 식별자를 `Uuid.V7.nextIdAsString()`으로
  교체
- raw assertion/UUID scan을 검증 증거와 문서에 남김

## 수용 기준

1. `aws-java/src/test`에 raw JUnit assertion call/import가 없다.
2. test-only `UUID.randomUUID()` 사용이 없고, AWS wire-format 고정 상수는
   유지한다.
3. S3 Tables normalization/cleanup targeted 5개 테스트와 aws-java module
   test가 통과한다.
4. 7-Tier review에 matcher/ID generator, emulator 경계, regression evidence를
   기록한다.

## 검증 순서

1. RED scan으로 raw assertion/UUID 잔여를 기록한다.
2. 테스트 코드만 수정하고 matcher/ID generator import를 확인한다.
3. S3 Tables targeted 5개와 `aws-java:test`를 실행한다.
4. raw scan, diff check, Kotlin pattern/7-Tier review를 수행한다.
5. Lore commit, push, Korean PR exact-head CI, merge를 수행한다.

## 제외 범위

- production AWS client API 변경
- wire-format UUID 또는 외부 고정 식별자 변경
- emulator/Testcontainers lifecycle 수정

## DoD Status

- 상태: 구현 전 승인된 계획
- 완료: 목표, 파일 범위, 수용 기준, 검증 순서, 제외 범위
- 미완료: 구현, 테스트, review, PR, merge
