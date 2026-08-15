# SNS batch publishing 구현 교훈

## 맥락

Issue #456은 Java SDK v2, AWS Kotlin SDK, `aws-spring-boot` 템플릿에 SNS
`PublishBatch` 지원을 추가하는 작업이다. 기존 `SnsOperations` 구현체의 source·binary
호환성을 유지하면서 AWS의 요청당 10개 제한, 부분 성공, cancellation, 대량 입력의
동시성 경계를 한 번에 다뤄야 했다.

## 결정

- low-level Java/Kotlin 확장은 SDK request/response와 예외를 그대로 전달하고,
  입력 검증만 조기에 수행한다.
- Spring API는 `SnsPublishBatchRequest`의 호출자 ID를 유지하고, 10개 chunk와 fixed
  worker 및 bounded result channel로 입력 순서를 복원한다.
- transport/protocol 오류는 raw cause와 payload를 노출하지 않는 Spring 예외로
  정규화하고, 자동 재시도·rollback·보상 트랜잭션은 제공하지 않는다.
- 기존 `SnsOperations`에는 순차 단건 fallback default를 추가하고, `NoopSnsOperations`는
  deterministic batch 결과를 명시적으로 반환한다.
- Spring Cloud AWS식 공개 `BatchExecutionStrategy`·converter SPI는 현재 범위를
  넓히지 않고 [#514](https://github.com/bluetape4k/bluetape4k-aws/issues/514)로
  분리했다. publisher cleanup/latency telemetry와 heap/throughput 측정은
  [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서 수행한다.

## 예상 밖의 문제와 수정

1. 기본 `./gradlew build -x test --parallel --no-daemon`은 Maven POM 생성 단계의
   configuration-cache 오류(`ConfigurationContainer.delegate is null`)로 실패했다.
   `--no-configuration-cache` 재실행은 성공했으며, 이 차이를 lesson과 PR 검증에
   남긴다.
2. 처음에는 ignored `build/consumer-fixtures` 산출물을 URLClassLoader로 읽어 ABI를
   확인했다. clean checkout에서는 산출물이 없거나 API 변경 후 재생성될 수 있어
   pre-change 증거가 되지 않았다. baseline class를
   `aws-spring-boot/src/test/resources/sns-abi/`에 보존하고 SHA-256
   `b8814d524f38f624ad8c51401286a694d64785ab352ecc1d301d186711c7d177`를 검증하는
   isolated `ClassLoader`로 바꿨다.
3. 응답을 받은 뒤 결과 매핑을 먼저 수행하면 cancellation이나 malformed response가
   발생할 때 terminal chunk의 `completedEntryIds`가 누락될 수 있었다. 응답 직후
   `NonCancellable` 경계에서 entry ID를 기록한 뒤 매핑하도록 순서를 고정했다.
4. SDK 예외를 class-name 문자열로 분류하면 concrete `SnsException`이 `UNKNOWN`으로
   남는다. `SdkServiceException`·`SdkClientException` 타입 판별과 회귀 테스트로
   수정했다.
5. 첫 detekt 실행에서 magic number, 복잡도, broad catch, 테스트 line-length가
   드러났다. named constant와 경계별 suppression, `RuntimeException` catch, 테스트
   정리로 의도를 코드에 남겼다.
6. 테스트 assertion을 다시 전수 점검하면서 `bluetape4k-projects`의
   `bluetape4k-assertions` 사용 패턴으로 통일했다. 변경된 SNS 테스트에서는
   `shouldBeEqualTo`, `shouldBeLessOrEqualTo`, `shouldNotContain`,
   `assertFailsWith` 등을 사용하고 generic `check`·JUnit `assertThrows`를
   제거했다. 단순 client 생성·close smoke 테스트는 반환값이 non-null인 Kotlin
   계약 자체가 대상이므로 별도 assertion을 추가하지 않았다.

## 결과와 검증

- 기준 구현 commit: `d0690d324a0a36ac08ce1ba295b19cc3afcc7576` 이후의 최종
  remediation 변경은 구현 커밋과 통합 review 커밋으로 고정한다.
- 명시적 SNS assertion-audit targeted 실행은 `24 passing`, `BUILD SUCCESSFUL`을
  기록했다. Java·Kotlin·Spring 모듈 전체 테스트는 `359 passing`,
  `BUILD SUCCESSFUL`이었다.
- `publishBatchSuspend` caller cancellation이 underlying `CompletableFuture`를
  취소하는 테스트와, Spring executor가 terminal entry를 매핑 전에 보존하는 테스트를
  추가했다.
- `./gradlew detekt --no-daemon`이 성공했다.
- `git diff --check`가 성공했다.
- Gradle configuration-cache가 Dokka의 `kotlinx/serialization/StringFormat`를
  찾지 못하는 wildcard targeted 실행은 실패했지만, 동일한 정확한 테스트 목록을
  `--no-configuration-cache`로 다시 실행해 성공 증거를 남겼다. 기본 build의
  별도 POM configuration-cache 실패와 no-cache 성공도 각각 보존한다.
- 실제 AWS publisher latency/cleanup, heap·throughput 수치는 측정하지 않았다. 이
  범위의 미검증 항목이며 #515의 후속 측정으로 남긴다.

## 리뷰에서 확인한 누락

- 초기 Step 6-R에서는 clean checkout ABI 재현성, terminal ID 기록 순서, concrete SDK
  exception 분류, builder 최종 검증을 충분히 증명하지 못했다. 독립 리뷰에서 발견한
  뒤 구현·테스트·문서를 함께 수정했다.
- 1인 개발자 저장소 정책에 따라 human review gate는 N/A다. CI와 exact-head merge
  승인은 별도 게이트로 유지한다.

## 다음 작업을 위한 방어선

- API/ABI 변경 시 baseline binary를 test resource와 hash로 먼저 고정하고, 변경 후
  consumer fixture를 재컴파일하지 않는다.
- 결과를 반환하는 외부 호출은 terminal response 관찰과 결과 변환을 분리하고,
  cancellation이 그 사이에 들어와도 reconciliation ID를 잃지 않는지 테스트한다.
- build 기본 경로가 configuration-cache 문제로 막히면 원인 로그를 보존한 뒤
  `--no-configuration-cache`를 재실행한다. 성공한 대체 검증과 기본 경로의 제한을
  모두 보고한다.
- #514와 #515를 닫지 않고 후속 범위로 유지해 SPI 확장과 실제 성능·운영 측정을
  별도 근거로 완료한다.

## DoD Status

- [x] context·decision·surprise·outcome 기록
- [x] 검증 명령과 결과 기록
- [x] 리뷰 누락과 future guard 기록
- [ ] PR·CI·merge·local sync·cleanup

**상태: PENDING — 최종 Step 6-R 통합과 PR 게이트가 남아 있다.**
