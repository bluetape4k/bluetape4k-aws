# aws-spring-boot S3 auto-configuration 계획

작성일: 2026-05-10
명세: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/docs/superpowers/specs/2026-05-10-aws-spring-boot-s3-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/1

## 실행 규칙

- `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3`에서 작업한다.
- #1을 PR #28(`aws #9`)과 독립적으로 유지하고 base는 `origin/develop`로 유지한다.
- awspring을 사용하지 않는다.
- AWS service SDK dependency는 main code에서 `compileOnly`, 검증에서는 명시적인 test dependency로 유지한다.
- public API에 한국어 KDoc을 작성한다.
- README.md와 README.ko.md를 동기화한다.

## 계획

### 1. build 및 auto-configuration 등록

1. `aws-spring-boot/build.gradle.kts`를 갱신한다.
   - `compileOnly(libs.aws2.s3)`를 추가한다.
   - `testImplementation(libs.aws2.s3)`를 추가한다.
   - `compileOnly`에서 기존 `testImplementation` extension을 유지한다.
   - configuration metadata용 `annotationProcessor(libs.spring.boot.configuration.processor)`가 있는지 검증하고 없으면 추가한다.
2. `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 S3 auto-configuration class를 추가한다.
3. S3 auto-config의 ordering reference를 제외하고 `AwsAutoConfiguration`은 변경하지 않는다.

검증:
- `./gradlew :aws-spring-boot:compileKotlin --no-daemon`

### 2. property와 model type

`io.bluetape4k.aws.spring.s3` package를 생성한다.

파일:
- `S3Properties.kt`
- `S3ObjectLocation.kt`
- `S3ListPage.kt`
- request option에 named model이 필요하면 `S3PresignRequest.kt`를 사용하고 그렇지 않으면 method parameter를 사용해 이 파일을 생략한다.

작업:
1. prefix `bluetape4k.aws.s3`를 사용하는 `S3Properties`를 구현한다.
2. `init`에 endpoint+region invariant를 추가한다.
3. 기본 duration이 15분인 nested `Presign` property를 추가한다.
4. 한국어 KDoc을 포함한 단순 location/page model type을 추가한다.
5. 기본값이 있는 immutable data class를 우선한다.

검증:
- 단계 5의 ApplicationContextRunner property-binding test.

### 3. S3 자동 구성

`S3AutoConfiguration.kt`를 생성한다.

필수 annotation:
- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- S3 및 AWS HTTP client FQCN string을 사용하는 `@ConditionalOnClass(name = [...])`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(S3Properties::class)`

bean 메서드:
1. `s3Client(...)`: `S3Resource`용 Spring-managed `S3Client`.
2. `s3AsyncClient(...)`: 주요 async S3 client.
3. `s3Presigner(...)`: presigned URL 지원.
4. `s3Operations(s3AsyncClient, s3Client, s3Presigner, properties)`:
   `S3CoroutinesTemplate`.

builder 규칙:
- `ShutdownQueue` ownership을 피하도록 `S3ClientFactory`가 아니라 AWS SDK builder로 client를 inline 구성한다.
- region이 non-null일 때만 `Region.of(properties.region)`을 적용한다.
- non-null일 때만 `endpointOverride`를 적용한다.
- `ObjectProvider<AwsCredentialsProvider>`를 통해 `AwsCredentialsProvider`를 resolve하고 bean이 없으면 `DefaultCredentialsProvider.builder().build()`로 fallback한다. `AwsAutoConfiguration`이 있다고 가정하지 않는다.
- `ObjectProvider<SdkHttpClient>`와 `ObjectProvider<SdkAsyncHttpClient>` override를 받는다.
- `S3Configuration`을 구성하고 path-style, accelerate, 선택형 chunked encoding flag를 적용한다.

back-off 규칙:
- 각 bean method에 `@ConditionalOnMissingBean`을 적용한다.

### 4. S3Operations와 template

생성:
- `S3Operations.kt`
- `S3CoroutinesTemplate.kt`

구현:
1. `existsBucket`.
2. byte와 text용 `upload`.
3. `downloadBytes` and `downloadText`.
4. `delete`.
5. `listPage`.
6. `listFlow`.
7. `resource`.
8. `presignGet`.
9. `presignPut`.

구현 규칙:
- constructor input은 `S3AsyncClient`, sync `S3Client`, `S3Presigner`, `S3Properties`다.
- 적합한 곳에 기존 `aws` module coroutine extension을 재사용한다.
- 직접 async SDK 호출에 `CompletableFuture.await()`를 사용한다.
- suspend 호출을 broad `runCatching`으로 감싸지 않는다.
- catch boundary를 도입하면 `CancellationException`을 다시 던진다.
- `listFlow`는 continuation token과 `flow { emit(...) }`을 사용해야 한다.
- `presignPut` KDoc은 `Content-Type`을 포함한 signed header가 실제 upload request와 일치해야 한다고 명시해야 한다.

### 5. S3Resource 구현

`S3Resource.kt`를 생성한다.

구현:
- `AbstractResource`를 확장한다.
- `S3Client`와 `S3ObjectLocation`을 저장한다.
- `getDescription()`은 `s3://bucket/key`를 반환한다.
- `exists()`, `contentLength()`, `lastModified()`는 `headObject`를 호출한다.
- `getInputStream()`은 `ResponseTransformer.toInputStream()`과 함께 sync `getObject`를 호출한다.
- 적합한 곳에서 missing object/bucket error를 일반적인 Spring `Resource` 의미로 변환한다.
  - `exists()`는 404/NoSuchKey/NoSuchBucket에 false를 반환한다.
  - `getInputStream()`은 SDK exception을 전파한다.

### 6. 테스트

`AwsAutoConfigurationTest`를 확장하며 기존 credentials-provider coverage를 제거하지 않는다.

ApplicationContextRunner 테스트:
1. `AwsAutoConfiguration`이 여전히 기본 credentials provider를 등록한다.
2. S3 auto-config가 `S3Client`, `S3AsyncClient`, `S3Presigner`, `S3Operations`를 등록한다.
3. `enabled=false`는 S3 bean을 등록하지 않는다.
4. user-provided `S3AsyncClient`, `S3Client`, `S3Presigner`, `S3Operations`가 있으면 기본값이 back off한다.
5. `region` 없는 `endpoint-override`는 빠르게 실패한다.
6. region/endpoint/path-style/chunked/accelerate property를 binding한다.
7. presign duration 기본값을 binding한다.
8. credentials provider bean이 없으면 S3 auto-config가 `DefaultCredentialsProvider`로 fallback한다.

LocalStack integration 테스트:
1. `aws-spring-boot/src/test/kotlin` 아래에 `AbstractS3SpringBootTest`를 추가한다.
   - `LocalStackServer.Launcher.getLocalStack("s3")`를 사용한다.
   - endpoint, region, static credential용 helper property를 제공한다.
   - `:aws` test source에 의존하지 않는다.
2. property를 통해 region과 endpoint override를 binding한다.
3. `existsBucket`.
4. byte/text를 upload하고 download한다.
5. prefix로 page 및 flow 목록을 조회한다.
6. 삭제하고 absence를 검증한다.
7. `S3Resource.exists`, `contentLength`, and `getInputStream`.
8. presigned GET/PUT URL 생성 형태를 검증한다.

테스트 hygiene:
- context-runner failure가 빠르게 드러나도록 LocalStack test를 별도 class에 유지한다.
- 고유한 bucket/key name을 사용한다.
- 생성한 object/bucket을 정리한다.

### 7. 문서

갱신:
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- 루트 `README.md`
- 루트 `README.ko.md`

문서화 항목:
- consumer dependency 요구 사항: AWS SDK `s3` runtime dependency 추가.
- 자동 구성된 bean.
- `bluetape4k.aws.s3.*` 속성.
- coroutine template 사용법.
- `S3Resource` read-only sync 연결.
- presigned PUT content-type/header 계약.

### 8. 검증

순서대로 실행한다.

1. `./gradlew :aws-spring-boot:compileKotlin --no-daemon`
2. `./gradlew :aws-spring-boot:test --no-daemon`
3. `./gradlew :aws-spring-boot:koverHtmlReport --no-daemon`
4. task가 있으면 `./gradlew :aws-spring-boot:detekt --no-daemon`, 그렇지 않으면
   `./gradlew detekt --no-daemon`
5. `./gradlew build -x test --parallel --no-daemon`
6. `rg 'runBlocking|Thread\\.sleep|GlobalScope' aws-spring-boot/src/main/kotlin`
   production hit가 없어야 한다.
7. `git diff --check`

test가 실패하면 PR 전에 수정한다. LocalStack을 사용할 수 없으면 failure를 기록하고 차선의 ApplicationContextRunner coverage를 실행하며 LocalStack 검증이 통과했다고 주장하지 않는다.

### 9. commit과 PR

1. advisor 검토 후 명세/계획을 먼저 commit한다.
2. Lore trailer와 `Co-authored-by: OmX <omx@oh-my-codex.dev>`를 포함해 구현을 별도로 commit한다.
3. `feat/1-spring-boot-s3`를 push한다.
4. `[feat] Add Spring Boot S3 auto-configuration` title로 PR을 생성한다.
5. PR 본문은 한국어로 작성하고 `Closes #1`을 포함한다.

## 단계 확인 목록 완료 상태

| 항목 | 상태 | 기록 |
|---|---|---|
| 누락된 구현 작업 포함 | 완료 | build, property, auto-config, operation, resource, test, 문서, 검증. |
| dependency-safe 순서 | 완료 | auto-config/template/resource/test/docs 전에 build/property 수행. |
| test와 diagnostic 포함 | 완료 | ContextRunner, LocalStack, compile, test, detekt, build, diff check. |
| dependency/API 위험 포함 | 완료 | S3 SDK compileOnly/test dependency, string class guard, Spring lifecycle, S3Configuration. |
| 적절한 complexity label | 완료 | 유형 A full design 유지. |

## Claude Code Opus 자문

산출물:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/.omx/artifacts/ask-claude-aws-spring-boot-s3-plan-20260510-181111.md`

| 심각도 | 발견 사항 | 결정 | 후속 조치 |
|---|---|---|---|
| blocking | credentials provider injection 계약의 명세가 부족하다. | 수용 | 기본 fallback과 함께 `ObjectProvider<AwsCredentialsProvider>`를 사용한다. |
| blocking | `S3Operations`의 `resource()`에 sync `S3Client`가 필요하다. | 수용 | template constructor와 bean signature에 sync client를 포함한다. |
| blocking | LocalStack test base는 `:aws` test source를 재사용할 수 없다. | 수용 | 이 module에 `AbstractS3SpringBootTest`를 추가한다. |
| blocking | configuration processor 검증이 누락됐다. | 수용 | 단계 1에서 기존 processor dependency를 검증한다. |
| blocking | AWS HTTP client class guard가 누락됐다. | 수용 | `SdkHttpClient`와 `SdkAsyncHttpClient` FQCN을 포함한다. |
| non-blocking | 기존 credentials provider test를 유지해야 한다. | 수용 | `AwsAutoConfigurationTest`를 교체하지 않고 확장한다. |
| non-blocking | commit-format comment가 활성 AGENTS Lore protocol과 충돌한다. | 기각 | AGENTS.md가 요구하는 Lore commit trailer를 사용한다. |
