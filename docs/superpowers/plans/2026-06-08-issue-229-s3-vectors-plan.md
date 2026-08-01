# 이슈 #229 S3 Vectors 계획

작성일: 2026-06-08
이슈: #229
명세: `docs/superpowers/specs/2026-06-08-issue-229-s3-vectors-design.md`

## 목표

필수 runtime dependency를 추가하거나 일반 S3 object-operation 동작을 바꾸지 않으면서 `aws-java`, `aws-spring-boot`, `aws-ktor`에 선택형 S3 Vectors 지원을 제공한다.

## gate 순서

1. 이슈 접수 및 갱신.
2. 명세 작성.
3. 명세 검토, `P0=0`, `P1=0` 필수.
4. 계획 작성.
5. 계획 검토, `P0=0`, `P1=0` 필수.
6. 구현.
7. local 검증.
8. 7단계 code review, `P0=0`, `P1=0` 필수.
9. PR 본문 검증 및 CI.

## 구현 단계

### 단계 1 - dependency alias

- 기존 `aws2` version line을 사용해 `gradle/libs.versions.toml`에 `aws2-s3vectors`를 추가한다.
- 다음 파일에 `compileOnly(libs.aws2.s3vectors)`와 `testImplementation(libs.aws2.s3vectors)`를 추가한다.
  - `aws-java/build.gradle.kts`
  - `aws-spring-boot/build.gradle.kts`
  - `aws-ktor/build.gradle.kts`
- 세 module 모두 dependency insight로 검증한다.

DoD:

- `s3vectors`는 compile/test scope에만 나타난다.
- `api` 또는 `runtimeOnly` dependency를 추가하지 않는다.
- 새 version key를 도입하지 않는다.

### 단계 2 - shared aws-java facade

- `io.bluetape4k.aws.s3vectors.S3VectorsOperations`를 추가한다.
- `S3VectorsAsyncClient` 기반 `S3VectorsCoroutinesTemplate`을 추가한다.
- AWS SDK member-method resolution conflict를 피하도록 `*Suspend` name을 사용해 같은 stable operation set을 위한 `S3VectorsAsyncClientCoroutinesExtensions`를 추가한다.
- 다음 suspend function을 제공한다.
  - `listVectorBuckets`
  - `getVectorBucket`
  - `listIndexes`
  - `getIndex`
  - `putVectors`
  - `getVectors`
  - `listVectors`
  - `queryVectors`
- `CompletableFuture.await()`를 사용하고 suspend 호출을 `runCatching`으로 감싸지 않는다.
- public class, interface, extension function에 영문 KDoc을 추가한다.

DoD:

- Spring과 Ktor가 adapter-specific operation interface를 중복하지 않고 이 facade를 재사용할 수 있다.
- 지원하지 않는 policy/tagging/destructive admin API는 raw SDK client를 통해 계속 사용할 수 있으며 first-pass 범위 제외로 문서화한다.

### 단계 3 - aws-java test

- `S3VectorsCoroutinesTemplate`용 focused MockK test를 추가한다.
- template을 넘어서는 coverage를 제공하는 곳에 low-level suspend extension용 focused test를 추가한다.
- 가능한 곳에서 exceptional completion과 cancellation propagation을 다룬다.

DoD:

- test는 bluetape4k assertion과 `runSuspendIO` 또는 IO-like async SDK future용 기존 coroutine helper를 사용한다.
- emulator 또는 실제 AWS dependency를 도입하지 않는다.

### 단계 4 - Spring Boot auto-configuration

- `io.bluetape4k.aws.spring.s3vectors.S3VectorsProperties`를 추가한다.
- `S3VectorsAutoConfiguration`을 추가한다.
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에서 `AwsAutoConfiguration` 뒤에 등록한다.
- 다음 항목을 포함한 compile-only SDK type에 string `@ConditionalOnClass(name = [...])` guard를 사용한다.
  - `software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClient`
  - `software.amazon.awssdk.services.s3vectors.S3VectorsAsyncClientBuilder`
  - `software.amazon.awssdk.http.async.SdkAsyncHttpClient`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.s3-vectors", name = ["enabled"], havingValue = "true")`를 사용한다.
- 다음 조건으로 `S3VectorsAsyncClient`를 생성한다.
  - shared `AwsProperties` client 기본값
  - 자격 증명 provider fallback
  - 선택형 async HTTP client bean
  - service name `s3vectors`를 사용하는 global customizer
  - service-specific `AwsClientCustomizer<S3VectorsAsyncClientBuilder>` 적용
- caller bean이 없으면 `S3VectorsCoroutinesTemplate`을 통해 shared `S3VectorsOperations`를 생성한다.

DoD:

- caller-provided client와 operation bean이 올바르게 back off한다.
- owned client는 Spring이 `destroyMethod = "close"`를 통해 닫는다.
- 기본 S3 property로 S3 Vectors가 암시적으로 활성화되지 않는다.

### 단계 5 - Spring Boot test

- `S3VectorsAutoConfigurationTest`를 추가한다.
- test 사례:
  - 기본적으로 비활성화
  - 활성화하면 `S3VectorsAsyncClient`, `S3VectorsProperties`, `S3VectorsOperations` 등록
  - `s3vectors` class가 없으면 `FilteredClassLoader`를 통해 back off
  - caller-provided `S3VectorsAsyncClient` 재사용
  - caller-provided `S3VectorsOperations`가 있으면 template back off
  - region 없는 endpoint override는 shared 기본값을 통해 실패
  - global 및 service customizer를 결정적인 순서로 적용

DoD:

- test는 `ApplicationContextRunner`, 필요한 곳에만 MockK, bluetape4k assertion을 사용한다.
- emulator/실제 AWS claim을 도입하지 않는다.

### 단계 6 - Ktor shared defaults

- `AwsKtorS3VectorsAsyncClientCustomizer`를 추가한다.
- `AwsKtorDefaults`와 `AwsKtorCoreConfig`에 새 customizer list를 추가한다.
- equality, hashCode, toString helper, 기존 `AwsKtorCoreTest` coverage를 갱신한다.

DoD:

- 기존 `AwsKtorCore` 동작을 backward compatible하게 유지한다.
- S3 Vectors customizer는 기존 S3 Control/CloudWatch/SQS customizer lane과 대칭이다.

### 단계 7 - Ktor plugin

- `io.bluetape4k.aws.ktor.s3vectors` package를 추가한다.
- runtime holder, plugin config, plugin, application accessor를 추가한다.
  - `S3VectorsKtorRuntime`
  - `S3VectorsKtorPluginConfig`
  - `S3VectorsKtorPlugin`
  - `Application.s3Vectors()`
  - `Application.s3VectorsOrNull()`
- `aws-java`의 `S3VectorsOperations`와 `S3VectorsCoroutinesTemplate`을 재사용한다.
- caller-owned operation, caller-owned async client, plugin-owned async client를 지원한다.
- `ApplicationStopping`에서 plugin-owned client만 닫는다.
- `AwsKtorCore` region, endpoint override, Java credentials provider, S3 Vectors customizer를 상속한 뒤 service-local customizer를 적용한다.

DoD:

- operation을 호출하기 전까지 plugin install에는 side effect가 없다.
- caller-owned operation은 client 생성과 endpoint validation을 우회한다.
- plugin-owned client를 생성해야 할 때만 endpoint override에 region이 필요하다.

### 단계 8 - Ktor test

- focused plugin/config/runtime test를 추가한다.
- test 사례:
  - disabled plugin은 operation을 저장하지 않고 accessor 실패
  - injected operation을 저장하고 route-level 사용 동작
  - injected operation이 endpoint validation 우회
  - injected client를 application-owned로 유지
  - plugin-owned client를 한 번 닫음
  - shared customizer가 service customizer보다 먼저 실행
  - template이 지원하는 모든 operation을 `S3VectorsAsyncClient`에 위임

DoD:

- route-level test는 status/assertion helper가 현재 Ktor test 형태에 맞는 곳에 `bluetape4k-ktor-testing` helper를 사용한다.
- test는 bluetape4k assertion과 strict interaction check가 중요한 곳에 class-level MockK mock을 사용한다.

### 단계 9 - 문서, 조사 보존, lesson

- 공식 AWS S3 Vectors 조사를 간결한 한국어 요약 note와 함께 `bluetape4k-wiki`에 보존하고 wiki toolchain을 사용할 수 있으면 GNO 명령으로 검증한다.
- README locale set을 갱신한다.
  - 루트 `README.md`와 `README.ko.md`
  - `aws-java/README.md` and `aws-java/README.ko.md`
  - `aws-spring-boot/README.md` and `aws-spring-boot/README.ko.md`
  - `aws-ktor/README.md` and `aws-ktor/README.ko.md`
- `docs/lessons/2026-06-08-issue-229-s3-vectors.md`를 추가한다.
- README architecture/flow content가 visual이 필요할 만큼 바뀔 때만 diagram asset을 추가한다. 추가한다면 `bluetape4k-diagram` PNG/SVG 및 geometry gate를 따른다.

DoD:

- 문서에서 optional runtime dependency를 명시한다.
- 문서에서 emulator 기반 S3 Vectors 동작을 주장하지 않는다고 명시한다.
- lesson에 optional dependency와 shared facade 재사용 결정을 기록한다.

## 검증 명령

순서대로 실행한다.

```bash
./gradlew :bluetape4k-aws-java:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-java:test --tests '*S3Vectors*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3Vectors*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-ktor:test --tests '*S3Vectors*' --tests '*AwsKtorCoreTest' --no-daemon --max-workers=1
git diff --check
```

GitHub Actions snapshot metadata가 Sonatype 403을 반환하면 실패한 CI job을 한 번 재시도하고 code failure로 처리하기 전에 log에서 failure를 분류한다.

## 검토 확인 목록

- P0/P1 workflow gate 준수.
- `software.amazon.awssdk:s3vectors`가 optional로 유지됨.
- Spring과 Ktor가 shared `aws-java` operation facade를 재사용함.
- compile-only SDK type을 string `@ConditionalOnClass` guard로 보호함.
- Spring 및 Ktor integration이 기본적으로 비활성화되거나 없음.
- coroutine cancellation을 삼키지 않음.
- plugin-owned resource는 닫고 caller-owned resource는 닫지 않음.
- public KDoc은 영문.
- README locale set 갱신.
- emulator 기반 동작을 주장하지 않음.
