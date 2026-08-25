# #550 Ktor examples 정렬 계획

## 목표

Ktor AWS 예제 네 모듈을 Kotlin pattern과 repository language policy에 맞게
정렬한다. 예제의 AWS facade/plugin 경로, DTO `Serializable`/명시적
`serialVersionUID`, emulator 계약은 유지하면서 단순 숫자 validation과
reader-facing KDoc만 정비한다.

## 범위

- `aws-ktor-exposed-examples`: `OrderRecord.id`의 non-negative 검증을
  `io.bluetape4k.support.requireGe`로 교체하고 public KDoc을 한국어로 정렬한다.
- `aws-ktor-s3-examples`: in-memory data-key provider의 demo/production 경계와
  Ktor facade 설명을 한국어로 정렬한다.
- `aws-ktor-sqs-examples`: SQS consumer lifecycle/route KDoc을 한국어로 정렬하고
  `Serializable` response summary 계약을 보존한다.
- `aws-ktor-service-coverage-examples`: plugin facade 경로와 public request/
  response DTO KDoc을 한국어로 정렬하고 기존 `requireNotBlank` helper 및
  `Serializable`/`serialVersionUID`를 보존한다.

## 재사용 기준

- 단순 numeric validation은 `io.bluetape4k.support.requireGe`를 사용한다.
- 새 예외 회귀가 필요하면 `io.bluetape4k.assertions.assertFailsWith`와
  `shouldContain`을 사용하며 raw JUnit `assertThrows`를 추가하지 않는다.
- 기존 `bluetape4k-assertions`, `bluetape4k.junit5.coroutines`,
  `bluetape4k.ktor.testing`, `bluetape4k.testcontainers`를 재사용한다.

## 실행 순서

1. 영어 KDoc과 raw numeric validation, assertion/serialization 계약을 RED scan한다.
2. 계획을 commit한 뒤 네 모듈의 최소 문서·helper 변경과 필요한 회귀 테스트를
   구현한다.
3. 네 example test, 관련 detekt, raw scan, locale/README parity와
   `git diff --check`를 검증한다.
4. 7-Tier review와 DoD를 기록하고 stacked PR로 제출한다.

## 보존할 계약

- AWS Java SDK v2 wrapper/facade 및 Ktor plugin accessor 경로를 변경하지 않는다.
- Floci/MockK/Testcontainers 기반 테스트의 emulator 선택과 resource cleanup을
  변경하지 않는다.
- 모든 public DTO의 `Serializable`과 명시적 `serialVersionUID`를 유지한다.
