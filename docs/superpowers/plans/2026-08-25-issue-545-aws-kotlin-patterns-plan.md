# #545 aws-kotlin 테스트 패턴 정렬 계획

## 목표

`aws-kotlin` smoke 테스트를 bluetape4k assertions와 ID generator에 맞추고,
suspend cleanup 경계에서 `CancellationException`을 보존한다. 기존 AWS Kotlin
SDK 호출 순서와 Floci/LocalStack 실행 조건은 변경하지 않는다.

## 범위

- `aws-kotlin/.../s3tables/S3TablesSmokeTest.kt`
  - raw JUnit assertion을 `io.bluetape4k.assertions` matcher로 교체한다.
  - 생성 리소스 suffix에 `Uuid.V7`을 사용한다.
  - cleanup의 `runCatching`을 명시적 `try/catch`로 바꾸고 cancellation을 재전파한다.
- `aws-kotlin/.../sfn/SfnSmokeTest.kt`
  - 생성 state machine 이름에 `Uuid.V7`을 사용한다.
  - lifecycle과 cleanup의 suspend 예외 처리를 cancellation 보존 방식으로 정렬한다.

## 검증 순서

1. 변경 전 raw assertion/UUID/`runCatching` 검색 결과를 RED evidence로 보관한다.
2. S3 Tables 정규화 및 cleanup 단위 테스트를 GREEN으로 실행한다.
3. `bluetape4k-aws-kotlin` 모듈 테스트와 Detekt를 실행한다.
4. 두 대상 파일의 raw assertion/UUID/`runCatching` 검색이 비어 있는지 확인한다.
5. `git diff --check`와 7-Tier 리뷰 문서를 작성한다.

## 수용 기준

- `CancellationException`은 cleanup에서 수집하지 않고 호출자에게 재전파된다.
- 정상 cleanup 실패만 기존처럼 첫 번째 실패와 suppressed 예외로 보존된다.
- S3 Tables 대상 5개 테스트와 aws-kotlin 테스트가 통과한다.
- 변경된 테스트 파일에서 raw JUnit assertion, `java.util.UUID`,
  `UUID.randomUUID`, `runCatching`이 남지 않는다.
