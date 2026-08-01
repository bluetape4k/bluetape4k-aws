# 이슈 #229 S3 Vectors 설계

작성일: 2026-06-08
이슈: #229
작업 유형: 유형 A Full Feature

## 배경

이슈 #229는 이전 Spring 및 Ktor 고급 S3 작업에서 연기한 S3 Vector 지원의 0.4.0 후속 작업이다. S3 Vectors는 별도 AWS SDK Java v2 service module과 일반 S3 object operation과 다른 runtime model을 사용하므로 이전 범위에서는 의도적으로 기본 S3 API surface에서 제외했다.

현재 upstream evidence에 따르면 AWS SDK Java v2는 `software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient`를 제공하고, 저장소의 현재 AWS SDK v2 version line인 `2.46.0`에는 `software.amazon.awssdk:s3vectors:2.46.0` Maven artifact가 배포돼 있다. AWS API surface는 vector bucket, vector index, vector put/get/list/query, policy, tagging operation을 갖는 전용 `s3vectors` service다.

## 현재 저장소 근거

- GitHub 이슈 #229는 2026-06-08 현재 SDK와 optional dependency 제약으로 갱신됐다.
- `gradle/libs.versions.toml`에는 현재 `aws2 = "2.46.0"`과 `aws2-s3`, `aws2-s3control`, `aws2-s3-transfer-manager` 같은 S3 alias가 있지만 `aws2-s3vectors` alias는 없다.
- `aws-java`는 `io.bluetape4k.aws.s3`, `cloudwatch`, `sqs`, `kinesis` 같은 package 아래에 AWS SDK Java v2 async client용 coroutine-first extension pattern을 갖는다.
- `aws-spring-boot` Access Grants는 `compileOnly` SDK dependency, string 기반 `@ConditionalOnClass` guard, 명시적 opt-in property, caller bean backoff, `ApplicationContextRunner` test를 사용한다.
- `aws-ktor` Access Grants는 caller-owned operation/client 지원, `ApplicationStopping`에서 plugin-owned client cleanup, `AwsKtorCore` 기본값/customizer 상속을 사용한다.
- 이전 lesson은 service-specific API surface가 안정되고 명시적으로 wrapping될 때까지 S3 Access Grants와 S3 Vectors를 optional로 유지하도록 요구한다.
- 이 session에서는 CodeGraph를 사용할 수 없어 source 탐색에 GNO, 공식 AWS 문서, Maven artifact 확인, 직접 source 읽기를 사용했다.

## 목표

- 기본 S3 runtime 동작을 바꾸지 않고 optional S3 Vectors 지원을 추가한다.
- 일반적인 application 경로를 위해 `S3VectorsAsyncClient` 기반의 작은 `aws-java` coroutine facade를 추가한다.
- 기본적으로 비활성화되고 SDK class 존재 여부로 보호하며 caller-owned bean과 호환되는 Spring Boot 4 auto-configuration을 추가한다.
- operation을 호출하기 전까지 side effect가 없으면서 `AwsKtorCore` 기본값과 customizer를 상속할 수 있는 Ktor 3 plugin 지원을 추가한다.
- 영문 및 한국어 README에 runtime dependency와 emulator 미지원 상태를 문서화한다.

## 목표가 아닌 항목

- `S3Operations` 또는 `S3KtorClient`에 S3 Vectors method를 추가하지 않는다.
- `software.amazon.awssdk:s3vectors`를 배포된 bluetape4k module의 `api` 또는 필수 runtime dependency로 만들지 않는다.
- 향후 검증된 emulator 계약을 추가하기 전까지 S3 Vectors의 Floci, LocalStack, Ministack 지원을 주장하지 않는다.
- 첫 단계에서 모든 관리용 policy/tagging API를 wrapping하지 않는다. application은 지원하지 않는 operation에 raw `S3VectorsAsyncClient`를 계속 사용할 수 있다.
- 이 이슈에서 AWS Kotlin SDK S3 Vectors 지원을 추가하지 않는다. 이 범위에서 검증된 upstream surface는 AWS SDK Java v2다.

## dependency 계약

`gradle/libs.versions.toml`에 추가한다.

```toml
aws2-s3vectors = { module = "software.amazon.awssdk:s3vectors", version.ref = "aws2" }
```

optional API를 사용하는 다음 위치에 `compileOnly`와 `testImplementation`으로 추가한다.

- `aws-java/build.gradle.kts`
- `aws-spring-boot/build.gradle.kts`
- `aws-ktor/build.gradle.kts`

consumer 문서에 다음을 명시해야 한다.

```kotlin
runtimeOnly("software.amazon.awssdk:s3vectors")
```

## 공개 API 형태

### aws-java 모듈

`io.bluetape4k.aws.s3vectors` package를 추가한다.

공개 API 표면:

- `S3VectorsOperations`: 일반 application operation용 suspend facade.
- `S3VectorsCoroutinesTemplate`: `CompletableFuture.await()`를 사용하는 `S3VectorsAsyncClient` 기반 구현.
- `S3VectorsAsyncClientCoroutinesExtensions`: 직접 SDK client 사용으로 충분할 때 사용하는 low-level `*Suspend` extension. suffix는 이미 `CompletableFuture`를 반환하는 AWS SDK async-client method와 Kotlin member resolution conflict를 피한다.

초기 stable operation set:

- `listVectorBuckets`
- `getVectorBucket`
- `listIndexes`
- `getIndex`
- `putVectors`
- `getVectors`
- `listVectors`
- `queryVectors`

이 subset은 discovery 및 application read/write/query workflow를 다루면서 policy, tagging, destructive administrative API를 raw SDK client에 남긴다.

### aws-spring-boot 모듈

`io.bluetape4k.aws.spring.s3vectors` package를 추가한다.

공개 API 표면:

- `S3VectorsProperties`
- `S3VectorsAutoConfiguration`
- `aws-java`의 `S3VectorsOperations`
- `aws-java`의 `S3VectorsCoroutinesTemplate`

속성 접두사:

```properties
bluetape4k.aws.s3-vectors
```

초기 property:

- `enabled`: 기본값 `false`
- `region`: 선택적인 서비스별 재정의
- `endpointOverride`: 선택적인 서비스별 엔드포인트 재정의

auto-configuration 계약:

- `AwsAutoConfiguration` 뒤에 등록한다.
- `S3VectorsAsyncClient`, `S3VectorsAsyncClientBuilder`, `SdkAsyncHttpClient`, bean signature에 나타나는 supporting AWS SDK type에 string 기반 `@ConditionalOnClass(name = [...])`를 사용한다.
- `bluetape4k.aws.s3-vectors.enabled=true`를 요구한다.
- caller bean이 없을 때만 plugin-owned `S3VectorsAsyncClient`를 생성한다.
- caller bean이 없을 때만 `S3VectorsOperations`를 생성한다.
- `AwsProperties.resolveClientDefaults`, `applyAwsDefaults`, `applyGlobalCustomizers("s3vectors", ...)`, `applyServiceCustomizers(...)`를 재사용한다.

### aws-ktor 모듈

`io.bluetape4k.aws.ktor.s3vectors` package를 추가한다.

공개 API 표면:

- `aws-java`의 `S3VectorsOperations`
- `aws-java`의 `S3VectorsCoroutinesTemplate`
- `S3VectorsKtorPlugin`
- `S3VectorsKtorPluginConfig`
- `Application.s3Vectors()`와 `Application.s3VectorsOrNull()`
- `AwsKtorS3VectorsAsyncClientCustomizer`

plugin 계약:

- plugin 설치는 runtime/operation만 저장하고 AWS를 호출하지 않는다.
- caller-owned operation은 client 생성과 endpoint validation을 우회한다.
- caller-owned `S3VectorsAsyncClient`를 application-owned로 유지한다.
- plugin-owned client는 기존 Ktor Access Grants lifecycle pattern을 사용해 `ApplicationStopping`에서 한 번 닫는다.
- plugin-created client는 `AwsKtorCore`의 region, endpoint, credential, service customizer를 상속한 뒤 service-local customizer를 적용한다.
- endpoint override에는 non-blank region이 필요하다.
- route-level test는 `bluetape4k-ktor-testing` helper를 사용해야 한다.
- shared `aws-java` facade가 실제 package-boundary 문제를 만든다고 구현에서 입증할 때만 Ktor-local facade를 허용한다. 추가하기 전에 계획/검토에 이유를 기록한다.

## test 전략

JUnit 5, MockK, bluetape4k assertion, `runSuspendIO` 또는 적절한 coroutine test helper, `ApplicationContextRunner`, 저장소에 이미 있는 Ktor test application pattern을 사용한다.

필수 test:

- `aws-java`: template이 지원하는 각 SDK 호출을 위임하고 `CompletableFuture` 완료를 기다린다.
- `aws-java`: low-level suspend extension이 SDK request/response type을 보존한다.
- `aws-spring-boot`: 기본적으로 비활성화된다.
- `aws-spring-boot`: property를 활성화하면 client와 operation을 등록한다.
- `aws-spring-boot`: `software.amazon.awssdk.services.s3vectors` class가 없으면 `FilteredClassLoader`를 통해 정상적으로 back off한다.
- `aws-spring-boot`: caller-owned `S3VectorsAsyncClient`와 `S3VectorsOperations` bean을 재사용한다.
- `aws-spring-boot`: global 및 service-specific customizer를 service name `s3vectors`로 적용한다.
- `aws-ktor`: disabled plugin은 operation을 저장하지 않는다.
- `aws-ktor`: caller-owned operation은 client validation을 우회한다.
- `aws-ktor`: caller-owned async client를 plugin shutdown에서 닫지 않는다.
- `aws-ktor`: plugin-owned async client는 `AwsKtorCore` 기본값을 상속하고 application shutdown에서 닫힌다.
- `aws-ktor`: route-level 사용법에서 bluetape4k Ktor testing helper로 설치된 operation을 호출할 수 있다.

이 이슈에 emulator test를 추가하지 않는다. test report와 README에서 S3 Vectors의 local emulator 지원을 암시하면 안 된다.

## 문서

갱신 대상:

- 루트 `README.md`
- 루트 `README.ko.md`
- 존재한다면 `aws-java/README.md`와 `aws-java/README.ko.md`
- `aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`
- `aws-ktor/README.md`와 `aws-ktor/README.ko.md`

문서는 다음을 설명해야 한다.

- S3 Vectors는 optional이며 일반 S3 object operation과 분리된다.
- consumer가 AWS SDK runtime dependency를 추가해야 한다.
- Spring Boot와 Ktor integration은 기본적으로 disabled/opt-in이다.
- 이 범위에서는 emulator 기반 test를 주장하지 않는다.

README architecture 또는 flow content가 새 visual asset이 필요할 만큼 바뀔 때만 diagram 작업이 필요하다. diagram을 추가하면 `bluetape4k-diagram` PNG/SVG workflow와 geometry gate를 사용해야 한다.

## 위험

- S3 Vectors service는 새로워 이전 S3 API보다 빠르게 바뀔 수 있다. 첫 public facade를 좁고 직접적으로 유지한다.
- SDK async client도 credential 또는 endpoint discovery 중 block할 수 있으므로 production 사용자는 일반 AWS SDK timeout/retry configuration이 필요하다.
- 현재 S3 Vectors 동작을 입증하는 emulator가 없으므로 request construction과 wiring test가 first-pass confidence boundary다.
- `AwsKtorCore`에 service-specific customizer를 추가하면 defaults object가 비대해질 수 있다. 새 customizer를 기존 service customizer와 대칭적으로 유지하고 기존 test로 equality/toString 동작을 다룬다.
- local catalog alias 추가는 `bluetape4k-dependencies`가 같은 alias를 제공할 때까지 dependency governance를 중복한다. 기존 `aws2` version line을 사용하고 새 version key를 피한다.

## DoD

- 명세 검토에서 `P0=0`, `P1=0`을 보고한다.
- 계획 검토에서 `P0=0`, `P1=0`을 보고한다.
- `:bluetape4k-aws-java`, `:bluetape4k-aws-spring-boot`, `:bluetape4k-aws-ktor`가 compile되고 focused test가 통과한다.
- public 동작을 문서화할 때 README locale set을 갱신한다.
- `docs/lessons/2026-06-08-issue-229-s3-vectors.md`를 추가한다.
- 최종 7단계 code review에서 `P0=0`, `P1=0`을 보고한다.
