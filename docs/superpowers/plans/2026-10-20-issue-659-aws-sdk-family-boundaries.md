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
- S3 Access Grants/Vectors: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/accessgrants/S3AccessGrantsKtorOperations.kt`, `S3AccessGrantsKtorPlugin.kt`, `S3AccessGrantsKtorPluginConfig.kt`, `S3AccessGrantsKtorRuntime.kt`, `S3AccessGrantsKtorTemplate.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorPlugin.kt`, `S3VectorsKtorPluginConfig.kt`, `S3VectorsKtorRuntime.kt`.
- SES/SNS/STS: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorModels.kt`, `SesKtorOperations.kt`, `SesKtorPlugin.kt`, `SesKtorPluginConfig.kt`, `SesKtorRuntime.kt`, `SesKtorTemplate.kt`; `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorModels.kt`, `SnsKtorOperations.kt`, `SnsKtorPlugin.kt`, `SnsKtorPluginConfig.kt`, `SnsKtorRuntime.kt`, `SnsKtorTemplate.kt`; `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sts/StsKtorModels.kt`, `StsKtorOperations.kt`, `StsKtorPlugin.kt`, `StsKtorPluginConfig.kt`, `StsKtorRuntime.kt`, `StsKtorTemplate.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingTemplateTest.kt`, `CloudWatchKtorPluginTest.kt`, `CloudWatchKtorTemplateTest.kt`, `CloudWatchLogsKtorPluginTest.kt`, `CloudWatchLogsKtorRuntimeTest.kt`, `CloudWatchLogsKtorTemplateTest.kt`; `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/eventbridge/EventBridgeKtorPluginTest.kt`, `EventBridgeKtorTemplateTest.kt`; Access Grants `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/accessgrants/S3AccessGrantsKtorPluginTest.kt`, `S3AccessGrantsKtorTemplateTest.kt`; Vectors `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorPluginTest.kt`; SES `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginTest.kt`, `SesKtorTemplateTest.kt`; SNS `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginTest.kt`, `SnsKtorTemplateTest.kt`, `SnsKtorTemplateAwsEmulatorTest.kt`; STS `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sts/StsKtorPluginTest.kt`, `StsKtorTemplateTest.kt`.

- [ ] **1단계: 각 template의 매핑 회귀 테스트를 먼저 추가한다.** Kotlin request/response로 바꾸기 전 nullable 필드, pagination token, unknown enum, binary 값, 실패 entry index/code, sequence token, 전송 뒤 response metadata를 검증한다. SES attachment enum은 저장소 enum의 모든 variant, SNS binary/list 값은 입력·출력 방어 복사를 검증한다.
- [ ] **2단계: CloudWatch와 Logs를 이전한다.** public operation에는 Kotlin generated model을 사용하고, Logs는 timestamp 정렬, drain 복원, chunk 성공 뒤 가능한 중복, final flush timeout·dropped count·client close 순서를 유지한다.
- [ ] **3단계: EventBridge를 이전한다.** suspend API와 Kotlin response를 남기고 Java `CompletableFuture` `*Async` 쌍을 제거한다. failed entry 인덱스와 target 선택 필드를 그대로 매핑한다.
- [ ] **4단계: Access Grants/Vectors를 이전한다.** 동일 서비스 Kotlin request/response 모델을 사용하고, nullable 필드·unknown 값·pagination·temporary credential을 손실 없이 보존한다.
- [ ] **5단계: SES/SNS/STS를 이전한다.** SES raw/attachment bytes와 enum을 보존하고, SNS 전체 message attribute 표현과 SMS 덮어쓰기 순서를 유지하며, STS credentials를 `toString`/로그에서 노출하지 않는다.
- [ ] **6단계: 해당 테스트를 실행한다.** `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*' --tests 'io.bluetape4k.aws.ktor.eventbridge.*' --tests 'io.bluetape4k.aws.ktor.s3.accessgrants.*' --tests 'io.bluetape4k.aws.ktor.s3vectors.*' --tests 'io.bluetape4k.aws.ktor.ses.*' --tests 'io.bluetape4k.aws.ktor.sns.*' --tests 'io.bluetape4k.aws.ktor.sts.*' --no-daemon`을 실행하고 emulator 실패는 별도 분류한다.
- [ ] **7단계: 서비스 그룹을 커밋한다.** 변경된 서비스 범위와 실행한 테스트를 한국어 Lore commit에 기록한다.

### 작업 4: SigV4 adapter와 S3 REST client 이전

**파일:**

- 수정: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4AuthLocation.kt`, `AwsSigV4Plugin.kt`, `AwsSigV4PluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClient.kt`, `S3KtorException.kt`, `S3KtorModels.kt`, `S3KtorAdvancedModels.kt`, `S3KtorClientSideEncryption.kt`, `MicrometerS3KtorClient.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/client/AwsSigV4PluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClientTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/S3KtorClientFlociTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/s3/MicrometerS3KtorClientTest.kt`.

- [ ] **1단계: 서명 벡터와 전송 캡처 테스트를 추가한다.** 고정 시간, 회전 자격 증명, session token, 원시 URI authority/path, `%2F`/`%20`, 중복·빈 query/header, payload hash/unsigned payload를 확인한다. 공개 `AwsSigner.sign()` 기반 query-signing 경로의 컴파일 검증과 고정 presign vector를 포함한다. 캡처한 최종 전송 URI·헤더·본문과 서명 입력이 동일해야 한다.
- [ ] **2단계: Ktor→Smithy 요청 변환을 구현한다.** `CredentialsProvider.resolve()`를 요청마다 사용하고, 공개 `AwsSigner.sign()` 및 `DefaultAwsSigner`를 사용한다. 사전 서명은 별도 공개 `Presigner` 타입이나 Smithy `@InternalApi` 함수에 의존하지 않고 `AwsSigningConfig`의 `AwsSignatureType.HTTP_REQUEST_VIA_QUERY_PARAMS`, 만료 시간, `UnsignedPayload`, URI 정규화 설정으로 구현한다. endpoint 정책을 resolve 전에 적용하고, 서명 결과가 실제 요청과 표현상 다르면 정규화 결과를 전송에 적용하거나 전송 전 거부한다.
- [ ] **3단계: payload 규칙과 자원 소유권을 구현한다.** 서명 없는 스트리밍 본문은 버퍼링 없이 전달하고, 서명 본문은 재생 가능한 byte array만 받는다. 재생 불가 본문은 요청 전 거부한다. standalone plugin은 caller-owned provider를 필수 주입받고, S3 factory가 만든 default provider/HttpClient는 정상 종료와 초기화 rollback에서 한 번씩 닫는다.
- [ ] **4단계: 민감 로그 차단과 presign 정책을 검증한다.** Authorization, token, signature-bearing query, body, secret이 로그에 없고, presigned URL도 HTTPS/local HTTP 정책과 고정 만료 조건을 지켜야 한다.
- [ ] **5단계: SigV4/S3 테스트를 실행한다.** `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.client.AwsSigV4PluginTest' --tests 'io.bluetape4k.aws.ktor.s3.*' --no-daemon`을 실행한다. Floci-backed 검증은 단독 순서로 실행한다.
- [ ] **6단계: 서명 경계를 커밋한다.** 바이트 buffering과 URI 재작성 제한을 한국어 Lore commit에 기록한다.

### 작업 5: IMDS Kotlin 전용 adapter와 전송 정책

**파일:**

- 수정: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorOperations.kt`, `ImdsKtorPlugin.kt`, `ImdsKtorPluginConfig.kt`, `ImdsKtorRuntime.kt`, `ImdsKtorTemplate.kt`.
- 신규 내부 구현: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsSmithyAdapter.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsEndpointPolicy.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/imds/ImdsProfileResolver.kt`.
- 테스트: `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorPluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsKtorTemplateTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/imds/ImdsProfileResolverTest.kt`.

- [ ] **1단계: HTTP wire test를 먼저 추가한다.** fake Smithy engine/server로 endpoint allowlist, token→metadata header 흐름, TTL/cache, 401 cache 유지, 3xx no-follow, `ProxySelector.NoProxy`, cancellation, request timeout, retryable/terminal response matrix를 테스트한다. 첫 token 요청 전에 거부되는 endpoint는 wire request가 0회여야 한다.
- [ ] **2단계: 설정 우선순위와 retry test를 추가한다.** `aws.profile` > `AWS_PROFILE` > `default`, config/credentials file path property > environment > `~/.aws` default, profile section naming과 Java merge precedence를 fixture로 검증한다. config의 `[profile default]`는 `[default]`보다 우선하고, 같은 파일에서 동일 key가 반복되면 뒤의 값이 우선한다. `ec2_metadata_service_endpoint`, `ec2_metadata_service_endpoint_mode`, `metadata_service_timeout` 세 key만 읽고, timeout integer/decimal seconds·millisecond rounding·nonnumeric `IllegalStateException`을 확인한다. profile, environment, system property source를 주입 가능하게 두며 AWS Kotlin `@InternalSdkApi` loader와 internal `ImdsEndpointProvider`는 사용하지 않는다. `retries=0`/N operation 횟수, `IOException`의 `sdkErrorMetadata.isRetryable`, full-jitter 상한을 고정 시간·난수 주입으로 확인하고 token과 metadata를 포함한 전체 작업 timeout을 검증한다.
- [ ] **3단계: 저장소 소유 `ImdsProfileResolver`를 구현한다.** 표준 default/configuration/credentials profile section에서 세 scalar key만 읽는다. profile file path는 대응 system property > environment > `~/.aws` default이며 profile 이름은 `aws.profile` > `AWS_PROFILE` > `default`다. config file은 `[default]`/`[profile <name>]`, credentials file은 `[default]`/`[<name>]` section을 사용한다. config file 안에서는 `[profile default]`가 `[default]`보다 우선하고 같은 file/profile에서 동일 key가 반복되면 뒤의 값이 우선한다. Java default `ProfileFile`은 credentials file을 먼저 merge하므로 같은 profile/key가 양쪽 file에 있으면 credentials 값이 우선한다. missing/unreadable file은 값 부재로 취급하고 `~/`를 user home으로 확장한다. timeout은 integer seconds 또는 decimal seconds로 읽고 decimal은 millisecond로 반올림하며 nonnumeric 값은 `IllegalStateException`으로 실패한다. 다른 profile credential/role/SSO resolution은 구현하지 않는다.
- [ ] **4단계: 작은 내부 adapter를 구현한다.** Smithy `SdkHttpClient.call()`을 사용하고 응답 body·`HttpCall` 정리는 한 private/internal 경계로 모은다. `HttpCall.complete()`의 `@InternalApi` opt-in은 해당 함수에만 격리한다. cancellation은 catch 후 재시도하지 않고 그대로 전파한다.
- [ ] **5단계: plugin 소유 engine을 구현한다.** OkHttp engine의 redirect와 `retryOnConnectionFailure`를 끄고 `ProxySelector.NoProxy`를 사용한다. endpoint는 AWS 기본 IPv4/IPv6, HTTPS, opt-in loopback HTTP만 허용한다. system/environment proxy와 임의 engine injection은 받지 않는다.
- [ ] **6단계: 고정 retry matrix를 구현한다.** 5xx와 metadata상 retryable transport만 재시도한다. 3xx, 401/기타 4xx, 다른 status, terminal exception, cancellation은 재시도하지 않는다. full jitter 상한은 200/400/800/1600/3200/6400/12800ms 후 20초이며 각 실제 delay는 1ms부터 상한 사이다.
- [ ] **7단계: IMDS 테스트를 실행한다.** `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*' --no-daemon`을 실행한다. profile fixtures, 설치/종료, 실패 cleanup, 닫힘 횟수, 요청 횟수를 확인한다.
- [ ] **8단계: IMDS 경계를 커밋한다.** 동작 변경인 원격 HTTP·proxy 차단과 retry matrix를 한국어 Lore commit에 남긴다.

### 작업 6: Kinesis 및 SQS 모델·실행기 이전

**파일:**

- Kinesis: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorModels.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorOperations.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPlugin.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorRuntime.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorTemplate.kt`; 테스트 `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorPluginTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/kinesis/KinesisKtorTemplateTest.kt`.
- SQS: `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerAdvancedModels.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPlugin.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPluginConfig.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsMessageConverter.kt`, `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/MicrometerSqsConsumerObserver.kt`; 테스트 `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/MicrometerSqsConsumerObserverTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeAdvancedTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeConfigTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeFailureTest.kt`, `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntimeLocalStackTest.kt`.
- 직렬화: 작업 1의 `SerializableContractTest.kt`.

- [ ] **1단계: Kinesis value semantics 테스트를 보강한다.** request byte array 입력/읽기 방어 복사, unknown iterator 원시 값, sequence number 기반 `ExpiredIteratorException` 복구, `Latest` 초기 예외, throttle retry reset/cap/jitter, empty/non-empty poll delay, cold Flow/cancellation을 검증한다.
- [ ] **2단계: Kinesis 구현을 이전한다.** Kotlin SDK client/model로 바꾸되 저장소 DTO 필드와 기존 `Serializable` marker/UID를 유지한다. SDK generated model을 serial form에 저장하지 않으며 DTO 복사 의미를 보존한다.
- [ ] **3단계: SQS 설정·converter 테스트를 보강한다.** Kotlin `Message`, `MessageAttributeValue`, `SendMessageResponse`, body string UTF-8 bytes, interceptor/converter 실패, queue URL/receipt handle, FIFO 속성을 검증한다.
- [ ] **4단계: SQS poller와 lifecycle을 이전한다.** Kotlin `SqsClient` suspend 호출로 long polling, poller별 `maxMessages`, 전체 handler concurrency, ack/nack/heartbeat/DLQ, 수신 retry와 SDK retry를 보존한다. `shutdownTimeout` 후 poller cancel/join, handler/heartbeat cancel 요청, 현재 순서대로 owned client close를 적용한다. 진행 중 ack/nack은 확정되지 않아 재전달될 수 있음을 문서화한다.
- [ ] **5단계: 직렬화 계약을 새로운 모델로 검증한다.** 기존에 성공하던 값 DTO 입력은 repository-owned serial form으로 왕복한다. `KinesisPutRecordRequest`의 비어 있지 않은 payload도 새 저장소 소유 바이트 표현을 통해 왕복 후 보존하고 생성·읽기 방어 복사를 확인한다. callback-bearing `SqsConsumerRuntimeConfig`와 사용자 `Random`을 포함하는 linear strategy는 marker/UID만 유지하고 실행 객체 graph round-trip은 요구하지 않는다.
- [ ] **6단계: 임시 bridge가 필요한 호출부를 기록한다.** 작업 3–6의 service production migration을 마친 뒤 Java SDK 참조가 공통 `AwsKtorCore`와 아직 이전하지 않은 공통 테스트에만 남는지 확인한다. 작업 6에서는 이 API나 dependency를 삭제하지 않는다. 공통 테스트와 consumer fixture 이전은 작업 7에서 먼저 끝낸다.
- [ ] **7단계: Kinesis와 SQS 테스트를 실행한다.** `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.kinesis.*' --tests 'io.bluetape4k.aws.ktor.sqs.*' --no-daemon`을 실행한다. LocalStack fallback은 Floci coverage가 없는 항목에서만 명시적으로 사용한다.
- [ ] **8단계: 실행기 경계를 커밋한다.** at-least-once, timeout 뒤 진행 중 ack/nack, serial bytes 비호환을 한국어 Lore commit에 기록한다.

### 작업 7: Kotlin external-consumer fixture와 다섯 Ktor 예제

**파일:**

- 수정: `build.gradle.kts`, `aws-ktor/src/consumerFixture/java/io/bluetape4k/aws/ktor/consumerfixture/SqsConsumerFixture.java`(삭제), 신규 `aws-ktor/src/consumerFixture/kotlin/io/bluetape4k/aws/ktor/consumerfixture/AwsKtorConsumerFixture.kt`.
- 예제: 다섯 디렉터리 각각의 `build.gradle.kts`, `src/main/kotlin`, `src/test/kotlin`, `README.md`, `README.ko.md`.

- [ ] **1단계: Kotlin consumer fixture compile test를 추가한다.** 공개 service client/model/customizer, S3 REST, SQS consumer/converter, SigV4, IMDS 설정을 fixture에서 import하고 Java SDK 타입 없이 compile하도록 root verification task를 바꾼다.
- [ ] **2단계: 공통 테스트와 consumer fixture를 Kotlin 경계로 옮긴다.** `AwsKtorCoreTest`, `AwsKtorDefaultsTest` 및 남은 Ktor 테스트에서 Java provider/customizer/SDK 모델 참조를 Kotlin API로 바꾼다. Java SQS fixture를 Kotlin fixture로 교체하고 `rg`로 `AwsKtorCore.kt` 외에 `aws-ktor/src/main`, `src/test`, `src/consumerFixture`의 Java SDK 참조가 없는지 확인한다. bridge가 남은 상태에서 `:bluetape4k-aws-ktor:compileTestKotlin`을 실행한다.
- [ ] **3단계: 임시 Java API와 dependency bridge를 제거한다.** 위 검색에서 남은 참조가 `AwsKtorCore.kt`의 임시 public Java API뿐임을 확인한 다음 해당 API를 삭제한다. 이어 `aws-ktor/build.gradle.kts`에서 `api(project(":bluetape4k-aws-java"))`, `testFixtures(project(":bluetape4k-aws-java"))`, 나머지 `libs.aws2.*`를 제거하고 `:bluetape4k-aws-ktor:compileTestKotlin` 및 `compileAwsKtorSqsConsumerFixture`를 다시 실행한다. fixture compile/runtime configuration에도 `software.amazon.awssdk:*`와 `bluetape4k-aws-java`가 없어야 한다.
- [ ] **4단계: 예제 build/source/test를 이전한다.** 다섯 Ktor 예제의 `libs.aws2.*` 직접 의존과 Java SDK import를 제거하고 Kotlin service SDK 모듈만 필요한 예제에 선언한다. SES, SNS, CloudWatch, Logs, Kinesis, STS coverage routes와 S3/SQS/DynamoDB tests의 request/model 사용을 새 타입에 맞춘다.
- [ ] **5단계: 각 예제 classpath를 검증한다.** 각 프로젝트의 `compileClasspath`와 `runtimeClasspath` dependency report에서 Java SDK group과 `bluetape4k-aws-java`가 없는지 확인한다. `aws-ktor-exposed-examples`의 compileOnly Exposed 선택 통합도 published metadata에 노출되지 않는지 확인한다.
- [ ] **6단계: fixture와 예제 컴파일·테스트를 실행한다.** `./gradlew compileAwsKtorSqsConsumerFixture`, 이어서 `:aws-ktor-dynamodb-examples:test`, `:aws-ktor-exposed-examples:test`, `:aws-ktor-s3-examples:test`, `:aws-ktor-service-coverage-examples:test`, `:aws-ktor-sqs-examples:test`를 실행한다. Docker 공유 emulator 테스트는 한 번에 하나씩 실행한다.
- [ ] **7단계: 예제 경계를 커밋한다.** 각 예제의 소비자 의존성과 검증 결과를 한국어 Lore commit에 기록한다.

### 작업 8: 마이그레이션 문서와 과거 결정 연결

**파일:**

- 수정: `README.md`, `README.ko.md`, `aws-ktor/README.md`, `aws-ktor/README.ko.md`, `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`, `CHANGELOG.md`.
- 수정: `examples/aws-ktor-dynamodb-examples/README.md`, `README.ko.md`; `examples/aws-ktor-exposed-examples/README.md`, `README.ko.md`; `examples/aws-ktor-s3-examples/README.md`, `README.ko.md`; `examples/aws-ktor-service-coverage-examples/README.md`, `README.ko.md`; `examples/aws-ktor-sqs-examples/README.md`, `README.ko.md`.
- 수정: `docs/superpowers/specs/2026-05-17-issue-85-aws-ktor-migration-design.md`.

- [ ] **1단계: 문서 assertion 목록을 작성한다.** bilingual README에서 각 SDK 경계, 공개 타입 예시, consumer runtime dependency 책임이 같은 뜻인지 확인한다.
- [ ] **2단계: README를 갱신한다.** Ktor 문서에서 Java SDK 전이 의존 설명을 제거하고 Kotlin SDK/Smithy 서비스 사용법을 기록한다. Spring README는 Java SDK를 설명하고 Kotlin SDK 참조가 없어야 한다. 루트 README의 예제 목록과 모듈 소개를 반영한다.
- [ ] **3단계: CHANGELOG와 역사 문서를 갱신한다.** `CHANGELOG.md`의 `[미출시]` / `변경` 아래에 제거되는 Java API, Kotlin 대체 타입, consumer migration, 직렬화 bytes 비호환, IMDS remote HTTP/proxy 제한을 한국어로 기록한다. Issue #85 문서에는 그 당시 결정 기록을 보존한 채 이슈 #659가 해당 경계를 대체한다는 링크만 추가한다.
- [ ] **4단계: 문서 계약을 확인한다.** Kotlin SDK service alias와 `bluetape4k-aws-java` 문자열을 Ktor README/example 안내에서 검색하고 의도된 CHANGELOG migration 설명만 남았는지 확인한다. 영어/한국어 headings, links, anchors와 코드 예시를 대조한다.
- [ ] **5단계: 문서를 커밋한다.** 사용자-facing prose는 한국어로 쓰고 API·command·identifier는 원문을 유지한다.

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
