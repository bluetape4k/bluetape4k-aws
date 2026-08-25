# #551 Spring Boot 예제 정렬 계획

## 목표

Spring Boot AWS 예제 세 모듈을 `bluetape-kotlin-patterns`와 저장소 언어
정책에 맞게 정렬한다. AWS Spring facade, Exposed transaction, WebFlux/AOT,
Floci/LocalStack 경로는 보존하고 단순 validation, public DTO 직렬화 계약,
reader-facing KDoc과 경계 회귀 테스트만 정비한다.

## 범위

- `aws-spring-boot-exposed-examples`: `OrderRecord.id`의 non-negative 검증을
  `io.bluetape4k.support.requireGe`로 교체하고 domain KDoc을 한국어로 정렬한다.
- `aws-spring-boot-s3-examples`: public response DTO에
  `Serializable`/명시적 `serialVersionUID`를 추가하고 serialization 회귀를
  검증한다.
- `aws-spring-boot-sqs-examples`: request/response DTO에
  `Serializable`/명시적 `serialVersionUID`를 추가하고 단순 blank/positive
  validation을 `io.bluetape4k.support` helper로 위임한다.
- 세 모듈의 public KDoc을 한국어로 정렬하고 기존 README locale pair를
  보존한다.

## 재사용 기준

- 단순 blank/range validation은 `requireNotBlank`, `requireGe`, `requireGt`를
  사용한다. cross-field invariant가 생기면 raw `require`를 유지한다.
- 새 예외 회귀는 반드시 `io.bluetape4k.assertions.assertFailsWith`와
  `shouldContain`을 사용하며 raw JUnit `assertThrows`를 추가하지 않는다.
- DTO serialization 검증은 기존 `bluetape4k-assertions`와
  `ObjectOutputStream` round-trip 패턴을 재사용한다.
- Spring Boot AOT, AWS facade, emulator/Testcontainers 경로와 기존 DTO
  JSON 표면은 변경하지 않는다.

## 실행 순서

1. 영어 KDoc, raw validation, DTO serialization/assertion 계약을 RED scan한다.
2. 계획을 commit한 뒤 세 모듈의 helper·DTO·KDoc 변경과 최소 회귀 테스트를
   구현한다.
3. 세 example test, Spring AOT, root detekt, raw scan, locale parity와
   `git diff --check`를 검증한다.
4. 7-Tier review와 DoD를 기록하고 #550을 base로 하는 stacked PR을 제출한다.

## 보존할 계약

- `aws-spring-boot` facade/auto-configuration과 controller endpoint 계약을
  변경하지 않는다.
- Exposed transaction, S3 encrypted/presigned 흐름, SQS/SNS fanout/DLQ 및
  Floci-first emulator 선택을 변경하지 않는다.
- public DTO의 JSON property와 기본값을 변경하지 않는다.
