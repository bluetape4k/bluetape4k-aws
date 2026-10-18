# PR #656 인라인 코드 리뷰

## 리뷰 범위와 방식

- 대상: `develop` 기준 PR #656 리팩터링과 이번 리뷰에서 추가한 수정
- 최초 비교 기준: `origin/develop`의 `8e8fd3dd800232d36493290014c46fa3c1cda4b1` (이전 리뷰 시점). 현재 PR base SHA는 `849892b4b469714b5cbedc26811c5dab407c53e8`이다.
- 코드 리뷰어 lane은 제한 시간과 probe 후 중단되어 verdict를 제공하지 못했다. Workspace workflow의 `inline fallback review` 절차를 적용했으며, 이 결과는 독립 리뷰 attestation이 아니다.
- 아키텍처 리뷰와 CI matrix 리뷰는 각각 별도 native lane으로 완료됐다. 아키텍처 리뷰가 찾은 P1 세 건은 아래 수정과 회귀 검증으로 처리했다.
- 보안, 공개 API/ABI, 의존성 publication, coroutine/lifecycle, CompletableFuture 오류 처리, 테스트 발견성, 문서 계약과 변경 diff를 확인했다. 추가 diff pattern scan에서는 비밀 키 패턴이 발견되지 않았다.

## P1 발견 및 처리

| 위치 | 문제와 영향 | 처리 및 증거 |
|---|---|---|
| `aws-ktor/build.gradle.kts` | 문서와 1.0.0이 약속한 AWS SDK 공개 의존성을 `compileOnly`로 바꿔 외부 consumer의 SQS 등 공개 타입 컴파일을 깨뜨렸다. | 문서화된 SDK/API 의존성을 `api`로 복구하고, 내부 구현용 S3 SDK만 `compileOnly`로 유지했다. SQS consumer fixture를 강제 재컴파일했고 전체 `compatibilityCheck`도 통과했다. |
| `aws-kotlin/.../LambdaInvocationResult.kt` | `statusCode: Int?`를 `Int`로 바꿔 JVM getter 반환형을 `java.lang.Integer`에서 primitive `int`로 변경했다. 기존 consumer 바이너리의 getter descriptor와 호환되지 않는다. | nullable 타입을 복원하고 reflection 회귀 테스트에서 getter 반환형을 검증했다. `LambdaPayloadCodecTest` 18개가 통과했다. |
| `aws-ktor/.../DynamoDbKtorRuntime.kt`, `DynamoDbKtorPluginConfig.kt` | SDK client와 builder lambda를 보유한 runtime 설정이 새로 `Serializable`을 선언해 실제로 지킬 수 없는 직렬화 계약을 노출했다. | 두 타입의 `Serializable`과 불필요한 `serialVersionUID`를 제거하고 marker 부재 회귀 테스트를 추가했다. `DynamoDbKtorRuntimeConfigTest` 10개가 통과했다. |
| `aws-spring-boot/.../SesJavaMailSender.kt` | `whenComplete`에서 일반 `LinkedHashMap`에 병렬 기록하고, 실패한 future를 `allAsList(...).join()`에 넘겨 Spring의 기존 `MailSendException` 실패 집계를 건너뛸 수 있었다. | 각 future가 `(message, mapped exception)` 결과를 반환하도록 바꾸고 완료 순서대로 모았다. Simple/MIME 두 경로의 다중 실패 회귀 테스트를 추가했으며 `SesJavaMailSenderTest` 5개가 통과했다. |

## 추가 검토 사항

- `S3ClientSideEncryptionTransferTest`의 클래스 이름 끝에 잘못된 `ç` 문자가 있어 Gradle 기본 테스트 이름 패턴에서 빠질 수 있었다. 이름을 바로잡았고 테스트 12개가 실제 실행되어 통과했다.
- SQS client logger 이름 오타를 수정했다.
- 변경된 줄에서 Detekt가 지적한 긴 줄, TODO marker, serial UID, utility test base 경고를 정리했다. 전체 Detekt는 변경되지 않은 기존 코드에서 남은 9건 때문에 실패한다: Kinesis Floci 테스트의 긴 함수 1건, Spring Config/DynamoDB/S3/SQS의 기존 serialization·함수 길이 지적 8건. 이번 diff의 해당 실패 위치는 기준 브랜치와 동일한 줄이다.
- 현재까지 추가 P0/P1은 발견하지 못했다. 전체 module test matrix는 로컬에서 반복하지 않았으며, GitHub PR CI가 이 범위를 검증해야 한다.

## 검증 결과

- `./gradlew clean build -x test --parallel --no-configuration-cache --no-build-cache --no-daemon` — 통과. SQS consumer fixture와 `compatibilityCheck` 포함.
- `LambdaPayloadCodecTest` — 18개 통과.
- `DynamoDbKtorRuntimeConfigTest` — 10개 통과.
- `SesJavaMailSenderTest` — 5개 통과.
- `S3ClientSideEncryptionTransferTest` — 12개 통과.
- `git diff --check` — 통과.
- `detekt` — 변경 위치 지적은 정리했으나, 위에 적은 변경되지 않은 기존 지적 9건으로 전체 task는 실패.
- GitHub PR CI는 변경을 push한 뒤 새 exact head에서 확인해야 하므로 이 리뷰 기록 시점에는 대기 상태다.

## 후속 독립 리뷰와 기존 판정 정정

기존 `Inline verdict: PASS; P0=0, P1=0`은 독립 검토 attestation이 아니며 최종 판정으로 사용하지 않는다. 뒤이은 독립 리뷰가 이전 후보 `b79c5b8`에서 P1 회귀를 발견했다. 다음 항목을 수정하고 현재 후보 `227e492ed42f7c5e51047a53fa33ddc7f962fada`에서 다시 검토했다.

| 영역 | 발견 사항 | 수정 및 회귀 증거 |
|---|---|---|
| 공개 JVM ABI | 리팩터링 과정에서 `AwsExposedDatabaseFactory`, `AwsSdkRdsIamAuthTokenGenerator`, `SnsNotification`, SQS batch 모델의 공개 생성자 descriptor가 사라질 수 있었다. | 공개 secondary constructor를 복구했다. Exposed 및 SQS 모델의 reflection ABI 검증이 각각 통과했다. |
| Ktor 엔진 소유권 | DynamoDB plugin이 전역 공유 기본 엔진을 plugin 소유 client에 전달해 plugin 종료와 전역 엔진 종료 책임이 충돌할 수 있었다. | plugin 설정의 명시적 엔진만 전달하고 둘 다 설정되지 않으면 SDK 관리 엔진을 사용한다. runtime 종료 테스트와 설정 테스트를 통과했다. 공유 엔진을 상속할 때 호출자가 서비스 client를 먼저 닫고 엔진을 마지막에 닫도록 KDoc를 명시했다. |
| 진단 문자열 | 설정 객체와 SQS context의 `toString()`이 endpoint, credential, 메시지 본문 등 외부 입력을 노출할 수 있었다. | 값 대신 설정 여부와 안전한 키 정보만 출력하며 sentinel 기반 redaction 테스트가 통과했다. |
| CI 도구 설정 | `.github/workflows/ci.yml`에서 `actions/setup-java` 버전이 `v6.0.0`으로 낮아져 있었다. | 13개 사용 위치를 `v6.0.1`로 복구했고, workflow 검증 CI를 통과했다. |

## 현재 후보 판정과 검증 경계

- 독립 code-review lane은 `227e492e`를 **APPROVE**, P0/P1/P2 각각 0으로 판정했다.
- 독립 architecture/lifecycle lane은 공유 엔진 KDoc를 재검토하고 **CLEAR**, P0/P1/P2 각각 0으로 판정했다. `DynamoDbKtorRuntimeConfigTest` 10개와 `git diff --check`가 통과했다.
- 추가 로컬 증거: Exposed constructor ABI 1개, SQS constructor ABI 1개, Ktor redaction/config/default-engine 테스트 5개, Floci DynamoDB runtime 테스트 3개가 통과했다.
- `detekt` 전체 실행은 기준 브랜치에 이미 있던 9개 지적 때문에 실패한다. 실패 위치는 Kinesis Floci, Spring Config/DynamoDB/S3/SQS의 변경되지 않은 코드이며, PR 회귀로 분류하지 않았다.
- 이전 CI run `39165049180`은 17개 검사가 성공했으나 `Test / aws-ktor emulator examples`가 30분 후 취소되고 `CI Status`가 실패했다. 따라서 이 run은 녹색 CI가 아니다.
- run `39171801549`는 SHA `227e492e`에서 실행 중이었다. 이 문서와 KDoc가 추가된 최종 PR SHA에 대한 CI는 별도로 확인해야 한다. 최종 exact-head CI, PR review/thread, mergeability는 병합 직전 live GitHub에서 다시 읽는다.

**후속 독립 리뷰 verdict:** 현재 코드 후보 P0=0, P1=0, P2=0. 병합 여부는 최종 exact-head CI와 PR 상태 검증이 완료된 후 별도로 판단한다.
