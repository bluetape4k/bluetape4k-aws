# 이슈 #659 AWS SDK 계열 경계 구현 계획

> **For agentic workers:** 구현은 `$executing-plans` 절차를 사용한다. 작업은 아래 체크박스를 순서대로 갱신하고, 각 경계의 회귀 검증을 통과한 뒤 다음 경계로 진행한다.

**목표:** `aws-ktor`의 Java SDK 참조를 AWS Kotlin SDK/Smithy로 이전하고, `aws-spring-boot`에는 AWS Java SDK 경계만 남긴다.

**구조:** 공개 설정·서비스 client/model, IMDS 전송 adapter, SigV4/S3 변환 경계, SQS/Kinesis 실행기를 각각 분리해 이전한다. 먼저 기존 관찰 동작을 테스트로 고정한 뒤 구현하고, 예제·외부 소비자 fixture·발행 메타데이터에서 Java SDK 유출이 없는지 확인한다. ABI 호환성은 목표가 아니며 런타임 동작과 자원 소유권은 명세의 인수 조건을 따른다.

**기술:** Kotlin, Ktor 3, AWS SDK for Kotlin `1.8.46`, Smithy Kotlin `1.7.9`, Gradle version catalog, JUnit 5/MockK, Floci 우선 에뮬레이터.

---

## 파일 지도

- Gradle 경계: `aws-ktor/build.gradle.kts`, `aws-spring-boot/build.gradle.kts`, `gradle/libs.versions.toml`, 외부 fixture wiring인 `build.gradle.kts`.
- 공통 Ktor 설정: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`; service 설정은 각 `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/<service>/`의 `*KtorPluginConfig.kt`와 `*KtorRuntime.kt`에 있다.
- 서비스 구현: `cloudwatch/`, `eventbridge/`, `imds/`, `kinesis/`, `s3/accessgrants/`, `s3vectors/`, `ses/`, `sns/`, `sqs/`, `sts/`의 `Operations`, `Template`, `Runtime`, `Plugin`, 모델 파일. DynamoDB 구현은 이미 Kotlin SDK이므로 수정하지 않고 classpath 경계만 확인한다.
- 서명 및 S3 REST: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4AuthLocation.kt`, `AwsSigV4Plugin.kt`, `AwsSigV4PluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClient.kt`, `S3KtorException.kt`, S3 관련 모델·암호화 파일.
- 기존 회귀 테스트는 `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/`의 같은 기능 하위 디렉터리에 둔다. 외부 소비자는 `aws-ktor/src/consumerFixture/kotlin/io/bluetape4k/aws/ktor/consumerfixture/`에 둔다.
- 소비자 예제는 `examples/aws-ktor-dynamodb-examples/`, `aws-ktor-exposed-examples/`, `aws-ktor-s3-examples/`, `aws-ktor-service-coverage-examples/`, `aws-ktor-sqs-examples/`에 있다. 각 예제의 `build.gradle.kts`, `src/main`, `src/test`, `README.md`, `README.ko.md`를 함께 점검한다.
- 문서 출력은 루트 `README.md`, `README.ko.md`, `aws-ktor/README.md`, `aws-ktor/README.ko.md`, `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`, `CHANGELOG.md`, 과거 결정 기록 `docs/superpowers/specs/2026-05-17-issue-85-aws-ktor-migration-design.md`다. 외부 manual companion은 다른 저장소와 별도 승인 범위라 이 계획에서 수정하지 않는다.

## 구현 작업

### 작업 1: 현재 동작과 직렬화 입력을 테스트로 고정

**파일:**

- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorCoreTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorDefaultsTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorApplicationResourceLifecycleTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorLifecycleEdgeCasesTest.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4PluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorPluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorTemplateTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorTemplateTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClientTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeAdvancedTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeConfigTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt`.
- 신규 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/SerializableContractTest.kt`.

- [x] **1단계: 직렬화 characterization을 추가한다.** 현재 성공적으로 왕복되는 Kinesis 값 DTO, `SqsPollBackoff`, 고정 visibility strategy, observation DTO, failure context의 입력을 `ObjectOutputStream`/`ObjectInputStream`으로 기록한다. `KinesisPutRecordRequest`는 현재 AWS Java `SdkBytes`의 backing bytes가 왕복 후 사라지는 동작을 별도 테스트로 고정하며, 작업 6에서 저장소 소유 바이트 표현으로 바꾼 뒤 payload 보존을 요구하도록 이 사례를 갱신한다. 사용자 `Random`이 든 linear strategy와 callback/client/dispatcher가 든 runtime config는 round-trip 성공을 요구하지 않고 marker/UID만 확인한다.
- [x] **2단계: 기존 동작 회귀 사례를 보강한다.** builder 적용 순서, client/provider 소유권, endpoint 정책, 서명 URI와 실제 전송 URI, S3 본문 재사용, SQS 동시 처리·종료, Kinesis iterator 복구를 기존 해당 테스트 파일에 추가한다. 기존 Ktor 테스트의 해당 커버리지를 확인하고, `AwsKtorCoreTest`에 공유 Kotlin provider/engine 전달 검증을 보탰다.
- [x] **3단계: 기존 계약을 기준으로 실행한다.** `./gradlew :bluetape4k-aws-ktor:test --no-daemon`을 실행해 characterization이 현 기준에서 통과하는지 기록한다. 외부 emulator 테스트가 실패하면 Docker/emulator 상태와 원인을 구분해 기록하고 실패를 통과로 취급하지 않는다.
- [x] **4단계: 기준 기록을 커밋한다.** 테스트만 담은 한국어 Lore commit에 `Tested:`와 `Not-tested:`를 포함한다. 기준 커밋은 `1995b6a1`이다.

### 작업 2: dependency catalog와 공통 Ktor 설정 경계

**파일:**

- 수정: `gradle/libs.versions.toml`, 루트 `build.gradle.kts`의 dependency-management constraints, `aws-ktor/build.gradle.kts`, `aws-spring-boot/build.gradle.kts`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`.
- 회귀 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorCoreTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorDefaultsTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorApplicationResourceLifecycleTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorLifecycleEdgeCasesTest.kt`.

- [x] **1단계: 버전 기준점을 확인한다.** `settings.gradle.kts`가 가리키는 고정 `bluetape4k-dependencies` catalog의 AWS Kotlin 버전 `1.8.46`과 Smithy Kotlin `1.7.9`를 확인했다. service/Smithy dependency 선언 전에는 `dependencyInsight`를 실행하지 않았다.
- [x] **2단계: 설정 경계 테스트를 먼저 작성한다.** 공용 Kotlin typed customizer의 보관·등록 순서를 같은 서비스의 customizer 두 개로 검증한다. 기존 DynamoDB 회귀 테스트에서 shared/service 순서, 주입 client 소유권, 생성 client 단일 종료를 확인한다.
- [x] **3단계: catalog와 Kotlin dependency를 추가하고 해결 버전을 검증한다.** AWS Kotlin 서비스 aliases는 versionless로 선언하고 루트 `build.gradle.kts` dependency-management 목록에 `aws.sdk.kotlin:s3control` 및 `aws.sdk.kotlin:s3vectors` constraints를 `bt4kVersion("aws-kotlin")`으로 추가했다. Smithy aliases는 `version.ref = "aws-smithy-kotlin"`을 사용하고 카탈로그 버전을 `1.7.9`로 고정했다. 서비스 public API와 Smithy public types는 `api`, signer/OkHttp implementation은 `implementation`으로 선언했다. 기존 Java wrapper/API dependencies와 test fixture는 전환용 bridge로 유지했다. `dependencyInsight`에서 AWS Kotlin `1.8.46`, Smithy `1.7.9` 및 `http-client-jvm`·`http-client-engine-okhttp-jvm` 선택을 확인했다. Spring은 Java wrapper API를 유지하면서 stale 주석 의존을 제거했다.
- [x] **4단계: `AwsKtorCore`에 Kotlin 설정 경계를 추가한다.** Smithy `CredentialsProvider`와 공유 `HttpClientEngine`은 이미 공개 설정에 있었다. AWS Kotlin service builder customizer 10종을 추가했고 Java runtime이 사용하는 기존 provider/customizer와 dependencies는 임시 bridge로 유지했다. Ktor `HttpClientConfig`와 Smithy `HttpClientEngine`은 별도 타입이다.
- [x] **5단계: service endpoint 검사를 customizer 뒤에 둔다.** DynamoDB client는 공유 설정과 service 설정을 적용한 뒤 모든 builder customizer 이후 최종 endpoint/region을 검사한다. HTTPS는 허용하고, opt-in 시에도 정확한 `localhost`, canonical IPv4 loopback, `::1` HTTP만 허용한다. 악성/모호한 URL, 원격 HTTP, userinfo, zone IPv6를 거부하는 테스트가 있다. 주입 DynamoDB client는 builder 검사를 거치지 않으며 호출자 책임을 API KDoc에 명시했다.
- [x] **6단계: Kotlin provider/client 종료 계약을 고정한다.** AWS Kotlin `1.8.46` 및 Smithy Kotlin `1.7.9` 고정 source와 공식 lifecycle 문서에서 명시 주입 `CredentialsProvider` 및 `HttpClientEngine`은 호출자 소유임을 확인했다. mock closeable provider/engine을 DynamoDB client에 전달해 plugin runtime 종료 뒤 닫히지 않음을 검증했다.
- [x] **7단계: 공통·Spring 회귀 테스트를 실행한다.** `./gradlew :bluetape4k-aws-ktor:test :bluetape4k-aws-spring-boot:test --no-daemon`이 `BUILD SUCCESSFUL`로 끝났다. Ktor 전체 테스트 302개가 통과했고 Spring Boot 테스트 task는 Gradle cache에서 복원됐다. endpoint/core/DynamoDB 타깃 테스트도 별도 `BUILD SUCCESSFUL`이다.
- [x] **8단계: Kotlin 공통 변경을 커밋한다.** 한국어 Lore commit에 공개 dependency scope, 설정 순서, provider/engine 호출자 소유권, 임시 Java bridge 유지 범위를 기록했다.

### 작업 3: CloudWatch, EventBridge, S3 보조 서비스, SES, SNS, STS 이전

**파일:**

- CloudWatch/Logs: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingOperations.kt`, `CloudWatchKtorOperations.kt`, `CloudWatchKtorPlugin.kt`, `CloudWatchKtorPluginConfig.kt`, `CloudWatchKtorRuntime.kt`, `CloudWatchKtorTemplate.kt`, `CloudWatchLogsKtorOperations.kt`, `CloudWatchLogsKtorPlugin.kt`, `CloudWatchLogsKtorPluginConfig.kt`, `CloudWatchLogsKtorRuntime.kt`, `CloudWatchLogsKtorShutdown.kt`, `CloudWatchLogsKtorTemplate.kt`.
- EventBridge: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/eventbridge/EventBridgeKtorOperations.kt`, `EventBridgeKtorPlugin.kt`, `EventBridgeKtorPluginConfig.kt`, `EventBridgeKtorRuntime.kt`, `EventBridgeKtorTemplate.kt`.
- S3 Access Grants/Vectors: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/accessgrants/S3AccessGrantsKtorOperations.kt`, `S3AccessGrantsKtorPlugin.kt`, `S3AccessGrantsKtorPluginConfig.kt`, `S3AccessGrantsKtorRuntime.kt`, `S3AccessGrantsKtorTemplate.kt`; S3 Vectors Ktor-owned Kotlin facade/template `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorOperations.kt`, `S3VectorsKtorTemplate.kt`, `S3VectorsKtorPlugin.kt`, `S3VectorsKtorPluginConfig.kt`, `S3VectorsKtorRuntime.kt`. The separate `aws-java` S3 Vectors facade remains owned by its Java SDK module.
- SES/SNS/STS: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorModels.kt`, `SesKtorOperations.kt`, `SesKtorPlugin.kt`, `SesKtorPluginConfig.kt`, `SesKtorRuntime.kt`, `SesKtorTemplate.kt`; `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorModels.kt`, `SnsKtorOperations.kt`, `SnsKtorPlugin.kt`, `SnsKtorPluginConfig.kt`, `SnsKtorRuntime.kt`, `SnsKtorTemplate.kt`; `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sts/StsKtorModels.kt`, `StsKtorOperations.kt`, `StsKtorPlugin.kt`, `StsKtorPluginConfig.kt`, `StsKtorRuntime.kt`, `StsKtorTemplate.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingTemplateTest.kt`, `CloudWatchKtorPluginTest.kt`, `CloudWatchKtorTemplateTest.kt`, `CloudWatchLogsKtorPluginTest.kt`, `CloudWatchLogsKtorRuntimeTest.kt`, `CloudWatchLogsKtorTemplateTest.kt`; `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/eventbridge/EventBridgeKtorPluginTest.kt`, `EventBridgeKtorTemplateTest.kt`; Access Grants `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/accessgrants/S3AccessGrantsKtorPluginTest.kt`, `S3AccessGrantsKtorTemplateTest.kt`; Vectors `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorPluginTest.kt`, `S3VectorsKtorTemplateTest.kt`; SES `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginTest.kt`, `SesKtorTemplateTest.kt`; SNS `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginTest.kt`, `SnsKtorTemplateTest.kt`, `SnsKtorTemplateAwsEmulatorTest.kt`; STS `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sts/StsKtorPluginTest.kt`, `StsKtorTemplateTest.kt`.

- [x] **1단계: 각 template의 매핑 회귀 테스트를 먼저 추가한다.** Kotlin request/response로 바꾸기 전 nullable 필드, pagination token, unknown enum, binary 값, 실패 entry index/code, sequence token, 전송 뒤 response metadata를 검증한다. SES attachment enum은 저장소 enum의 모든 variant, SNS binary/list 값은 입력·출력 방어 복사를 검증한다.
- [x] **2단계: CloudWatch와 Logs를 이전한다.** public operation에는 Kotlin generated model을 사용하고, Logs는 timestamp 정렬, drain 복원, chunk 성공 뒤 가능한 중복, final flush timeout·dropped count·client close 순서를 유지한다.
- [x] **3단계: EventBridge를 이전한다.** suspend API와 Kotlin response를 남기고 Java `CompletableFuture` `*Async` 쌍을 제거한다. failed entry 인덱스와 target 선택 필드를 그대로 매핑한다.
- [x] **4단계: Access Grants/Vectors를 이전한다.** 동일 서비스 Kotlin request/response 모델을 사용하고, nullable 필드·unknown 값·pagination·temporary credential을 손실 없이 보존한다. S3 Vectors Ktor 플러그인은 현재 `aws-java`의 Java 모델 `S3VectorsOperations`에 결합되어 있으므로, Ktor 전용 Kotlin `Operations`/`Template`을 추가하고 애플리케이션 접근자도 해당 facade로 전환한다. `aws-java`의 독립 API는 유지한다.
- [x] **5단계: SES/SNS/STS를 이전한다.** SES raw/attachment bytes와 enum을 보존하고, SNS 전체 message attribute 표현과 SMS 덮어쓰기 순서를 유지하며, STS credentials를 `toString`/로그에서 노출하지 않는다.
- [x] **6단계: 해당 테스트를 실행한다.** Task 3 전체 focused suite와 두 lifecycle 테스트를 실행했다. JUnit XML은 23개 suite, 148개 테스트, 실패 0, skip 0을 기록했고 `git diff --check`와 대상 서비스 패키지의 AWS SDK v2 service import 검색도 통과했다. 독립 리뷰는 사용량 제한으로 실행하지 못해 A-08에 남긴다.
- [x] **7단계: 서비스 그룹을 커밋한다.** 변경된 서비스 범위와 실행한 테스트를 한국어 Lore commit에 기록했다.

### 작업 4: SigV4 adapter와 S3 REST client 이전

**파일:**

- 수정: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4AuthLocation.kt`, `AwsSigV4Plugin.kt`, `AwsSigV4PluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClient.kt`, `S3KtorException.kt`, `S3KtorModels.kt`, `S3KtorAdvancedModels.kt`, `S3KtorClientSideEncryption.kt`, `MicrometerS3KtorClient.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4PluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClientTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClientFlociTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/MicrometerS3KtorClientTest.kt`.
- downstream 예제와 문서: `aws-ktor/README.md`, `README.ko.md`, `examples/aws-ktor-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorExamples.kt`, 관련 예제 테스트 두 파일, bilingual 예제 README. suspend presign과 Kotlin 자격 증명으로 바뀐 API 때문에 예제 모듈 컴파일 오류가 확인되어 이 소비 경계를 Task 4에 포함했다.

- [x] **1단계: 서명 벡터와 전송 캡처 테스트를 추가한다.** 고정 시간, 회전 자격 증명, session token, 원시 URI authority/path, `%2F`/`%20`, 중복·빈 query/header, payload hash/unsigned payload를 확인한다. 공개 `AwsSigner.sign()` 기반 query-signing 경로의 컴파일 검증과 고정 presign vector를 포함한다. 캡처한 최종 전송 URI·헤더·본문과 서명 입력이 동일해야 한다. 실제 MockEngine 전송 캡처에서 최종 요청을 검증했다.
- [x] **2단계: Ktor→Smithy 요청 변환을 구현한다.** `CredentialsProvider.resolve()`를 요청마다 사용하고, 공개 `AwsSigner.sign()` 및 `DefaultAwsSigner`를 사용한다. 사전 서명은 별도 공개 `Presigner` 타입이나 Smithy `@InternalApi` 함수에 의존하지 않고 `AwsSigningConfig`의 `AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS`, 만료 시간, `UnsignedPayload`, URI 정규화 설정으로 구현한다. endpoint 정책을 resolve 전에 적용하고, 서명 결과가 실제 요청과 표현상 다르면 정규화 결과를 전송에 적용하거나 전송 전 거부한다. 고정 SigV4 vector와 URI round-trip 검사가 통과했다.
- [x] **3단계: payload 규칙과 자원 소유권을 구현한다.** 서명 없는 스트리밍 본문은 버퍼링 없이 전달하고, 서명 본문은 재생 가능한 byte array만 받는다. 재생 불가 본문은 요청 전 거부한다. standalone plugin은 caller-owned provider를 필수 주입받고, S3 factory가 만든 default provider/HttpClient는 정상 종료와 초기화 rollback에서 한 번씩 닫는다. 회전 provider, pre-resolve 거부, streaming, idempotent close와 close failure 억제를 검증했다.
- [x] **4단계: 민감 로그 차단과 presign 정책을 검증한다.** Authorization, token, signature-bearing query, body, secret이 로그에 없고, presigned URL도 HTTPS/local HTTP 정책과 고정 만료 조건을 지켜야 한다. 로그 redaction, loopback opt-in, 원격 HTTP 선검사와 만료 범위를 검증했다.
- [x] **5단계: SigV4/S3 테스트를 실행한다.** 집중 명령은 Floci Docker 환경을 명시해 실행했고, 초기화 rollback close 테스트를 포함한 최신 실행에서 55개 테스트가 모두 통과했다. 별도 `:aws-ktor-s3-examples:test`도 통과했다. `detekt`는 변경 코드 분석 전 Kotlin `2.4.20`/Detekt 번들 Kotlin `2.4.10` 불일치로 중단되어 A-07의 정적 분석 증거에는 사용할 수 없다.
- [x] **6단계: 서명 경계를 커밋한다.** 바이트 buffering과 URI 재작성 제한, 확인된 검증 및 정적 분석 공백을 한국어 Lore commit에 기록한다.

### 작업 5: IMDS Kotlin 전용 adapter와 전송 정책

**파일:**

- 수정: `aws-ktor/build.gradle.kts`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorPlugin.kt`, `ImdsKtorPluginConfig.kt`, `ImdsKtorRuntime.kt`, `ImdsKtorTemplate.kt`.
- 신규 내부 구현: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsSmithyAdapter.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsEndpointPolicy.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsProfileResolver.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorApplicationResourceLifecycleTest.kt`, `AwsKtorLifecycleEdgeCasesTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorPluginTest.kt`, `ImdsKtorTemplateTest.kt`, `ImdsProfileResolverTest.kt`.

- [x] **1단계: HTTP wire test를 먼저 추가한다.** fake Smithy engine/server로 endpoint allowlist, token→metadata header 흐름, TTL/cache, 401 cache 유지, 3xx no-follow, `ProxySelector.NoProxy`, cancellation, request timeout, retryable/terminal response matrix를 테스트했다. 첫 token 요청 전에 거부되는 endpoint는 wire request 0회를 확인했다.
- [x] **2단계: 설정 우선순위와 retry test를 추가한다.** profile/environment/system-property precedence, profile file section·merge, timeout parsing/rounding, retry 횟수·retryable transport를 fixture와 주입된 clock/random으로 검증했다.
- [x] **3단계: 저장소 소유 `ImdsProfileResolver`를 구현한다.** 승인된 세 scalar key만 읽고, profile/file precedence·missing/unreadable file·`~/` 확장·timeout parsing을 구현했다. 다른 profile credential/role/SSO resolution은 추가하지 않았다.
- [x] **4단계: 작은 내부 adapter를 구현한다.** Smithy `SdkHttpClient.call()` 경계에 응답 body와 `HttpCall` 정리를 모으고, `HttpCall.complete()`의 `@InternalApi` opt-in을 국소화했다. cancellation은 재시도하지 않고 전파한다.
- [x] **5단계: plugin 소유 engine을 구현한다.** OkHttp redirect·connection retry를 끄고 `ProxySelector.NoProxy`를 적용했다. endpoint allowlist와 opt-in loopback HTTP를 지키며 system/environment proxy와 임의 engine injection은 받지 않는다. Smithy `1.7.9`의 공개 config에는 connection retry setter가 없어 `aws-ktor/build.gradle.kts`에 `compileOnly("com.squareup.okhttp3:okhttp:5.5.0")`를 추가했다. 해당 OkHttp artifact는 이미 runtime에 전이되어 compile-only 선언은 runtime dependency를 추가하지 않는다. 2026-10-21 사용자 선택으로 이 compile-only 참조를 유지하는 Task 5 파일 범위를 승인했다.
- [x] **6단계: 고정 retry matrix를 구현한다.** 5xx와 metadata상 retryable transport만 재시도한다. 3xx, 401/기타 4xx, 다른 status, terminal exception, cancellation은 재시도하지 않는다. full jitter 상한은 200/400/800/1600/3200/6400/12800ms 후 20초이며 각 실제 delay는 1ms부터 상한 사이다.
- [x] **7단계: IMDS 테스트를 실행한다.** 집중 테스트 41개와 전체 Ktor suite 343개가 통과했고 skip/failure/error는 모두 0이었다. `compileKotlin`과 `git diff --check`도 통과했다. 독립 Task 5 재리뷰는 `APPROVE`, P0/P1/P2=0이다. `detekt`는 Kotlin `2.4.10`/`2.4.20` toolchain 불일치로 실행되지 않아 A-07 정적 분석 증거에는 사용하지 않는다.
- [x] **8단계: IMDS 경계를 커밋한다.** 원격 HTTP·proxy 차단, redirect/retry 제한, 검증 결과와 detekt 공백을 한국어 Lore commit에 기록한다.

### 작업 6: Kinesis 및 SQS 모델·실행기 이전

**파일:**

- Kinesis: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorModels.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorOperations.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPlugin.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorRuntime.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorTemplate.kt`; 테스트 `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorTemplateTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorApplicationResourceLifecycleTest.kt`.
- SQS: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerAdvancedModels.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsMessageConverter.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/MicrometerSqsConsumerObserver.kt`; 테스트 `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/MicrometerSqsConsumerObserverTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeAdvancedTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeConfigTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeLocalStackTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsKotlinModelContractTest.kt`.
- 직렬화: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/SerializableContractTest.kt`.

- [x] **1단계: Kinesis value semantics 테스트를 보강한다.** `KinesisKtorTemplateTest`에서 입력·반환 byte array 방어 복사, unknown iterator 원시 값, 마지막 sequence number부터의 만료 iterator 복구, `Latest` 초기 만료 예외, cold/repeated Flow, pending 요청 cancellation, throttle 성공 후 reset·retry 상한·jitter, empty/non-empty 응답별 poll delay를 검증했다.
- [x] **2단계: Kinesis 구현을 이전한다.** client/model을 AWS Kotlin SDK로 바꾸고 DTO의 `Serializable` marker/UID를 보존했다. SDK generated model을 serial form에서 제외하고 shard iterator와 put-record DTO에 저장소 소유 proxy/byte array를 사용해 기존 필드 및 복사 의미를 유지했다.
- [x] **3단계: SQS 설정·converter 테스트를 보강한다.** Kotlin `Message` 기반 UTF-8 변환과 absent body, 원본 모델 전달, system attributes/FIFO 값, queue URL/receipt handle, converter/interceptor 오류 경로를 검증했다. `SqsKotlinModelContractTest`와 runtime 테스트가 이를 고정한다.
- [x] **4단계: SQS poller와 lifecycle을 이전한다.** Kotlin `SqsClient` suspend API에서 long polling, poller별 `maxMessages`, 전체 handler concurrency, 명시적 message/system attributes, ack/nack/heartbeat/DLQ, receive retry 및 SDK retry를 유지했다. 종료 timeout 뒤 poller/handler 취소와 소유 client close 순서를 회귀 테스트로 확인하고, `SqsConsumerPlugin` KDoc에 at-least-once 전달과 진행 중 ack/nack의 불확정 결과를 기록했다.
- [x] **5단계: 직렬화 계약을 새로운 모델로 검증한다.** `SerializableContractTest`에서 값 DTO와 payload, SQS failure context 및 binary message attribute proxy를 왕복하고 byte array 방어 복사를 확인했다. callback-bearing `SqsConsumerRuntimeConfig`와 사용자 `Random`이 있는 linear strategy는 marker/UID만 검증하고 실행 객체 graph 왕복을 요구하지 않았다.
- [x] **6단계: 임시 bridge가 필요한 호출부를 기록한다.** Kinesis/SQS production과 관련 테스트 디렉터리에서 Java AWS SDK 참조가 0개임을 검색했다. 전체 Ktor 검색에서는 `AwsKtorCore.kt`, 공통 테스트(`AwsKtorCoreTest.kt`, `AwsKtorDefaultsTest.kt`, `AwsKtorLifecycleEdgeCasesTest.kt`, `S3KtorClientFlociTest.kt`)와 Java `SqsConsumerFixture.java`만 남았다. 공통 테스트·fixture와 bridge 제거는 작업 7 범위로 남겨 두었다.
- [x] **7단계: Kinesis와 SQS 테스트를 실행한다.** 집중 Kinesis/SQS/serialization 테스트 57개와 Floci `SqsConsumerRuntimeLocalStackTest` 4개가 통과했다(실패 0, 오류 0, skip 0). Floci 실행은 Test worker가 Colima `DOCKER_HOST`를 자동 상속하지 않는 점을 확인한 뒤 임시 Gradle init script로 `DOCKER_HOST`와 socket override를 전달해 `-Dbluetape4k.aws.emulator=floci`로 재실행했다. `git diff --check`도 통과했다. 전체 Ktor suite와 정적 분석은 작업 9에서 실행한다.
- [x] **8단계: 실행기 경계를 커밋한다.** at-least-once, timeout 뒤 진행 중 ack/nack, serial bytes 비호환을 한국어 Lore commit `df8ca042`에 기록했다.

#### 작업 6 계획 갱신 writer DoD (2026-10-21)

- [x] **SPW-01 — 독자·목적·근거 고정:** 대상은 이슈 #659 구현 계획을 실행하는 유지보수자이며 언어는 한국어다. 근거는 위 파일 목록, `KinesisKtorTemplateTest`, SQS 회귀 테스트, `SerializableContractTest`, fresh JUnit XML 57+4개 결과, Java SDK 참조 검색이다. 전체 Ktor suite·정적 분석 결과는 아직 미확인으로 남겼다.
- [x] **SPW-02 — 계획 계약 충족:** 작업 6의 파일 범위와 의존 순서, 회귀 계약, 검증 명령·결과, 작업 7의 bridge 정리 경계, 미완료 commit 단계를 구분했다.
- [x] **SPW-03 — 한국어 기술 문체:** `KO-01`–`KO-05` PASS(사실·식별자 보존, 구체적 상태만 기록, 문장·용어·문체 확인, 불필요한 비유 없음). `KO-06` N/A(이 계획 갱신은 한국어 단일 artifact이며 locale 페이지·시각 자산을 다루지 않음). `KO-07` N/A(`terminology-rules.json`에는 `clinic-appointment` 규칙만 있고 AWS 계획에 적용되는 규칙이 없음).
- [x] **SPW-04 — 기술 의미·추적성 검증:** Kinesis/SQS 보존 계약은 현재 테스트 이름·구현 및 JUnit XML과 대조했다. Floci 수치는 fresh XML의 tests=4, failures=0, errors=0, skipped=0에서 읽었고, Java SDK 잔여 경로는 작업 7로 명시했다.
- [x] **SPW-05 — 최종 읽기·DoD:** 계획의 작업 6 섹션과 Markdown 구조를 다시 읽었다. 1–7단계만 검증 완료로 표시하고, commit은 실제 commit SHA 확인 전까지 미완료로 남겼다.

### 작업 7: Kotlin external-consumer fixture와 다섯 Ktor 예제

**파일:**

- 수정: `build.gradle.kts`, `aws-ktor/src/consumerFixture/java/io/bluetape4k/aws/ktor/consumerfixture/SqsConsumerFixture.java`(삭제), 신규 `aws-ktor/src/consumerFixture/kotlin/io/bluetape4k/aws/ktor/consumerfixture/AwsKtorConsumerFixture.kt`.
- SNS HTTP envelope 정책: `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/SnsHttpEnvelopePolicy.kt`, 공통 test fixture 및 policy test를 각각 `aws-kotlin/src/main`, `src/testFixtures`, `src/test`로 이동하고 Ktor parser/conformance test import를 갱신한다. 이 정책은 AWS SDK Java 타입을 쓰지 않지만 Ktor가 `aws-java` 모듈에 의존하게 하던 공통 Kotlin 코드다.
- 예제: 다섯 디렉터리 각각의 `build.gradle.kts`, `src/main/kotlin`, `src/test/kotlin`, `README.md`, `README.ko.md`.

- [x] **1단계: Kotlin consumer fixture compile test를 추가한다.** `AwsKtorConsumerFixture.kt`가 public service client/model/customizer, S3 REST, SQS consumer/converter, SigV4, IMDS 설정을 Java SDK 타입 없이 import한다. `compileAwsKtorSqsConsumerFixture`가 성공했다.
- [x] **2단계: 공통 테스트와 consumer fixture를 Kotlin 경계로 옮긴다.** Ktor 공통 테스트와 fixture를 Kotlin API로 바꾸고 Java SQS fixture를 제거했다. `SnsHttpEnvelopePolicy`, conformance corpus, policy test를 AWS Java SDK를 참조하지 않는 `aws-kotlin`으로 이동했다. Ktor 및 다섯 예제의 `compileKotlin`/`compileTestKotlin`, consumer fixture compile이 성공했고 `rg -n 'software\.amazon\.awssdk|libs\.aws2\.' aws-ktor/src examples/aws-ktor-*` 결과는 0건이다.
- [x] **3단계: 임시 Java API와 dependency bridge를 제거한다.** `AwsKtorCore.kt`의 Java customizer/provider API, `api(project(":bluetape4k-aws-java"))`, Java test fixture bridge, `libs.aws2.*` 의존성을 제거했다. `:bluetape4k-aws-ktor:compileTestKotlin`과 `compileAwsKtorSqsConsumerFixture`가 성공했고 fixture classpath에도 AWS Java 의존성이 없다.
- [x] **4단계: 예제 build/source/test를 이전한다.** 다섯 예제의 AWS 서비스 SDK 직접 의존과 모델 호출을 AWS Kotlin SDK로 옮겼다. Exposed 예제는 공용 설정 클래스가 있는 `bluetape4k-aws-exposed`를 유지하고, 사용하지 않는 Java RDS IAM adapter 의존성만 제외했다. 제거 후 6개 테스트에서 `NoopAwsDatabaseSettingsResolver`가 없어짐을 확인해 모듈 자체는 복구했다.
- [x] **5단계: 각 예제 classpath와 발행 metadata를 검증한다.** 다섯 예제의 main `compileClasspath`/`runtimeClasspath` 10개와 `awsKtorSqsConsumerFixtureClasspath`에서 `software.amazon.awssdk` 및 `bluetape4k-aws-java`가 0건이다. 생성 POM의 실제 top-level dependencies에도 AWS Java와 Exposed compileOnly가 없고, Gradle `apiElements`는 AWS Kotlin/Smithy API를 제공하면서 signer·OkHttp engine implementation을 포함하지 않는다. POM의 `dependencyManagement` 카탈로그는 발행 의존성으로 세지 않았다.
- [x] **6단계: fixture와 예제 컴파일·테스트를 실행한다.** 통합 compile 명령이 `BUILD SUCCESSFUL`로 끝났다. 다섯 예제 테스트를 Docker 공유 emulator가 겹치지 않도록 개별 순차 실행해 DynamoDB 2개, Exposed 6개, S3 3개, 서비스 커버리지 6개, SQS 5개가 통과했다(총 22개, 실패/오류/skip 0). Floci 테스트는 셸의 managed `DOCKER_HOST`와 socket override를 상속해 실행했고 DynamoDB 테스트는 loopback HTTP를 명시적으로 허용했다.
- [x] **7단계: 예제 경계를 커밋한다.** `82285b6e`에 Ktor API, AWS Kotlin consumer fixture, 다섯 예제의 SDK 경계와 compile·test·classpath·발행 metadata 검증 결과를 한국어 Lore 형식으로 기록했다.

#### 작업 7 계획 갱신 writer DoD (2026-10-21)

- [x] **SPW-01 — 독자·목적·근거 고정:** 독자는 이슈 #659 계획을 실행하는 유지보수자이며, 목적은 Kotlin consumer fixture·예제·발행 경계의 실행 결과를 기록하는 것이다. 근거는 `build.gradle.kts`, `aws-ktor/build.gradle.kts`, `aws-kotlin/build.gradle.kts`, 다섯 예제 Gradle/source/test 파일, 생성 POM/module metadata, Gradle compile/test 결과와 JUnit XML이다.
- [x] **SPW-02 — 계획 계약 충족:** Task 7의 fixture 이동, Java bridge 제거, 예제 의존성, main classpath, 발행 metadata, 순차 테스트 및 미완료 commit을 순서대로 기록했다. PR·merge·sync·cleanup은 완료 범위에 넣지 않았다.
- [x] **SPW-03 — 한국어 기술 문체:** `KO-01`–`KO-05` PASS(식별자·수치 보존, 검증 결과 중심 기술, 번역투와 불필요한 수식어 제거, 일관된 용어 사용, 비유 없음). `KO-06` N/A(계획은 단일 한국어 artifact이며 locale 페이지나 시각 자산을 변경하지 않음). `KO-07` PASS — `audit-korean-terms.mjs --rules .../terminology-rules.json` 결과 1 file, `series=clinic-appointment`, `findings=0`; 현재 rule set에 AWS 특화 규칙은 없다.
- [x] **SPW-04 — 기술 의미·추적성 검증:** 컴파일 task, 다섯 예제 JUnit XML, 10개 main classpath와 consumer fixture classpath, 생성 POM 및 `apiElements`를 실행 결과와 대조했다. POM의 `dependencyManagement` 카탈로그와 실제 dependencies를 구분했다.
- [x] **SPW-05 — 최종 읽기·DoD:** 계획의 Task 7 행과 writer DoD를 다시 읽고 `git diff --check`를 확인했다. Task 7은 `82285b6e`에 커밋했다.

### 작업 8: 마이그레이션 문서와 과거 결정 연결

**파일:**

- 수정: `README.md`, `README.ko.md`, `aws-ktor/README.md`, `aws-ktor/README.ko.md`, `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`, `CHANGELOG.md`.
- 수정: `examples/aws-ktor-dynamodb-examples/README.md`, `README.ko.md`; `examples/aws-ktor-exposed-examples/README.md`, `README.ko.md`; `examples/aws-ktor-s3-examples/README.md`, `README.ko.md`; `examples/aws-ktor-service-coverage-examples/README.md`, `README.ko.md`; `examples/aws-ktor-sqs-examples/README.md`, `README.ko.md`.
- 수정: `docs/superpowers/specs/2026-05-17-issue-85-aws-ktor-migration-design.md`.

- [x] **1단계: 문서 assertion 목록을 작성한다.** bilingual README는 다음 assertion을 함께 전달해야 한다: (a) Ktor plugin 공개 타입은 AWS SDK for Kotlin/Smithy Kotlin이며 기존 Java SDK v2 public API는 제거되었다, (b) 예제별 공개 클라이언트·요청 모델은 실제 AWS Kotlin 타입과 일치한다, (c) Ktor POM의 Kotlin/Smithy `api` dependency와 애플리케이션이 추가하는 Ktor engine·serialization·DB driver 선택 사항을 구분한다, (d) Spring Boot 문서는 AWS SDK v2 서비스 및 compileOnly 소비자 의존성만 설명한다, (e) CHANGELOG는 API/직렬화 bytes 비호환과 IMDS HTTP/proxy 제약을 기록하고 Issue #85 문서는 원래 결정을 보존한 채 #659로 연결한다. 소스, Gradle 선언, 생성 POM, Task 7 compile/test 결과를 사실 근거로 고정했다.
- [x] **2단계: README를 갱신한다.** `aws-ktor`의 English/한국어 안내와 코드 예제를 AWS Kotlin/Smithy API에 맞추고, `aws-spring-boot`의 SNS sibling-module 절을 Java SDK v2 전용으로 정리했다. 루트 README와 다섯 예제 README 쌍에 plugin 공개 model/client와 애플리케이션 선택 의존성 경계를 기록했다.
- [x] **3단계: CHANGELOG와 역사 문서를 갱신한다.** `CHANGELOG.md`의 `[미출시]` / `변경` 아래에 Java API 대체, consumer migration, ABI·직렬화 bytes 비호환, IMDS remote HTTP/proxy 제한을 한국어로 기록했다. Issue #85의 기존 기록은 보존하고 #659 후속 결과를 추가했다.
- [x] **4단계: 문서 계약을 확인한다.** Ktor README 및 다섯 예제 README에서 Java SDK v2 클래스·모듈 참조와 오래된 generated-model 호출을 검색해 결과 0건을 확인했다. Spring Boot README에는 AWS Kotlin SDK 참조가 없다. 19개 Markdown 파일의 상대 경로 링크와 내부 heading anchor가 모두 확인됐고, 8개 bilingual README 쌍의 heading·code fence 수가 일치한다. 영어·한국어 코드 예제는 Kotlin service client/model과 Gradle dependency scope에 맞는다. 11개 한국어 문서의 용어 감사 결과는 `findings=0`이며 `git diff --check`가 통과했다.
- [x] **5단계: 문서를 커밋한다.** 한국어 사용자 문구와 기술 식별자를 보존해 문서 19개 경로를 `c0a2cc73`에 한국어 Lore 형식으로 커밋했다. PR 생성은 이 계획의 범위에 포함하지 않는다.

#### 작업 8 문서·기술 artifact writer DoD (2026-10-21)

- [x] **SPW-01 — 독자·목적·근거 고정:** 계획 독자는 이슈 #659 구현·검증 담당 유지보수자이며, 목적은 문서 검증 및 제출 전 작업을 정확히 추적하는 것이다. Issue #85 설계 기록의 독자는 과거 마이그레이션 결정을 조회하는 유지보수자이며, 목적은 당시 결정을 보존하면서 #659의 실제 후속 결과를 연결하는 것이다. 근거는 이 계획, Issue #85 설계 문서, 현재 Ktor/Spring source와 Gradle 설정, 변경된 README·CHANGELOG, 상대 링크/용어 감사 결과다.
- [x] **SPW-02 — artifact 계약 충족:** 계획은 문서 파일 목록, 사실 assertion, 완료된 편집·검증과 아직 실행하지 않은 통합 검증·commit을 분리한다. Issue #85 기록은 기존 결론을 수정하지 않고 #659 구현 결과를 새 섹션으로 추가한다.
- [x] **SPW-03 — 한국어 기술 문체:** 두 문서의 `KO-01`–`KO-05`를 사실·식별자 보존, 구체적인 동사와 상태, 기술 register, 용어 일관성, 비유 여부로 재검토했다. `KO-06`은 두 문서가 단일 한국어 계획/설계 artifact여서 N/A다. `KO-07`은 11개 한국어 변경 문서 감사 결과 `findings=0`으로 통과했다.
- [x] **SPW-04 — 기술 의미·추적성 검증:** README의 Kotlin client/model과 dependency 설명을 현재 Ktor source 및 POM/Gradle 경계와 대조했다. Task 5의 OkHttp `5.5.0` compile-only 참조는 전이 runtime에 이미 존재하고 게시 POM에서는 제외됨을 기록했으며, 2026-10-21 사용자가 이 파일 범위를 선택했다. IMDS 전송 정책과 ABI/직렬화 비호환은 CHANGELOG와 구현 계약에 맞는다.
- [x] **SPW-05 — 최종 읽기·DoD:** 계획과 Issue #85 설계 문서, bilingual README, CHANGELOG를 다시 읽었다. 19개 상대 링크와 heading anchor, 오래된 SDK API 참조, 용어 감사, whitespace를 확인했다. 문서 19개 경로는 `c0a2cc73`에 커밋했다.

### 작업 9: 통합 검증 및 제출 전 독립 리뷰

**검증 대상:** `aws-ktor`, `aws-spring-boot`, 다섯 Ktor 예제, consumer fixture, 발행 metadata, 전체 Kotlin 정적 분석.

- [ ] **1단계: 소스 경계 검색을 수행한다.** `rg -n 'software\.amazon\.awssdk|libs\.aws2\.' aws-ktor/src examples/aws-ktor-*` 결과가 0건인지 확인한다. `rg -n 'bluetape4k-aws-kotlin|aws\.sdk\.kotlin' aws-spring-boot/src aws-spring-boot/build.gradle.kts` 결과가 0건인지 확인한다.
- [ ] **2단계: compile/test와 정적 분석을 수행한다.** `./gradlew :bluetape4k-aws-ktor:test :bluetape4k-aws-spring-boot:test --no-daemon`, 다섯 예제의 `test`, `compileAwsKtorSqsConsumerFixture`, `./gradlew detekt`, `./gradlew build -x test --parallel`을 각각 실행한다. Floci/Testcontainers 작업은 Docker 사용 규칙대로 순차 실행한다.
- [ ] **3단계: 외부 metadata를 검사한다.** `./gradlew :bluetape4k-aws-ktor:generatePomFileForBluetapeAwsPublication :bluetape4k-aws-ktor:generateMetadataFileForBluetapeAwsPublication --no-daemon` 후 `aws-ktor/build/publications/BluetapeAws/pom-default.xml`과 `module.json`에서 AWS Java group과 `bluetape4k-aws-java`가 없는지 확인한다. 공개 Smithy/AWS Kotlin type artifact가 API metadata에 있고 default signer/OkHttp engine 구현 artifact는 public API metadata에 없는지 확인한다.
- [ ] **4단계: classpath report를 검증한다.** fixture configuration과 다섯 예제의 compile/runtime dependency report를 저장하고 forbidden module이 없는지 검사한다. Spring Boot runtime에 Java SDK가 유지되고 AWS Kotlin 의존은 없는지 확인한다.
- [ ] **5단계: 전체 diff와 문서를 점검한다.** `git diff --check`, API type 검색, link/whitespace 검사, 명세 19개 인수 조건, serializable 타입 표, bilingual docs 목록을 대조한다. 생성물·임시 로그는 추적하지 않는다.
- [ ] **6단계: 정확한 branch HEAD 독립 코드 리뷰를 받는다.** `$code-review`와 repository checklist를 사용해 API, architecture, callers, security, stability, performance, integration 관점을 확인한다. 로컬 소스 리뷰는 P0/P1=0이어야 한다. 아직 PR이 없으므로 PR review thread와 정확한 PR HEAD CI는 `N/A (PR 생성 전)` / `PENDING (후속 A-10)`으로 남긴다. Reviewer 호출이 사용량 제한으로 불가능하면 `PENDING`으로 기록하고 리뷰 통과라고 주장하지 않는다.
- [ ] **7단계: 완료 보고를 작성한다.** 체크리스트의 DoD, 실제 실행 명령과 결과, 남은 emulator/metadata/manual 위험을 함께 보고한다. 상세 manual companion은 `bluetape4k.github.io` 별도 저장소의 승인된 후속 작업이며, 그 validator가 고정 release tag/peeled commit에서 통과하기 전 release를 만들지 않는다.

## 완료 경계

- 이 계획 실행은 소스·테스트·문서 변경과 정확한 branch HEAD의 검증 및 독립 코드 리뷰 보고까지 포함한다.
- Issue #659의 PR 생성·merge·develop 동기화·worktree 정리는 이 계획만으로 승인되지 않는다. 그 단계는 최신 HEAD CI/review read-back을 만든 뒤 별도 승인 게이트를 따른다.
- ABI 호환성 확인은 요구하지 않는다. 공개 모델/API 교체와 기존 Java serialized bytes 비호환은 CHANGELOG에 기록한다.

## 명세 추적성 자체 점검

| 명세 인수 조건 | 이 계획의 작업 |
|---|---|
| 1–3: SDK 경계, public dependency scopes, 발행 metadata, Spring Java-only | 작업 2, 7, 9 |
| 4–6: resource ownership, builder 순서, service endpoint 보안 | 작업 2, 4 |
| 7–8: IMDS endpoint/proxy/redirect/retry/timeout/cancellation | 작업 5 |
| 9–11: SQS lifecycle, Kinesis iterator/throttle, CloudWatch Logs flush | 작업 3, 6 |
| 12–14: SigV4 vectors, S3 streaming/presign, secret redaction | 작업 4 |
| 15–16: external fixture, 다섯 예제, bilingual docs, changelog, issue #85 이력 | 작업 7–8 |
| 17: central manual 별도 승인과 release validation gate | 작업 8–9 완료 경계 |
| 18: Serializable marker/UID와 DTO별 round-trip 경계 | 작업 1, 6, 8 |
| 19: branch HEAD 독립 코드 리뷰와 P0/P1 | 작업 9 (로컬 사전 제출 게이트); 정확한 PR HEAD CI와 review thread read-back은 후속 A-10에서 검증하고 그 전에는 `PENDING` |

구현 파일 지도, 회귀 대상, Gradle task 및 metadata 산출 위치를 현재 저장소에서 확인했다. 구현 전 catalog가 실제 해결하는 SDK 버전과 공개 API는 작업 2에서 다시 고정한다. ABI compatibility suite는 명세상 요구하지 않으며 이 계획에도 넣지 않았다.
