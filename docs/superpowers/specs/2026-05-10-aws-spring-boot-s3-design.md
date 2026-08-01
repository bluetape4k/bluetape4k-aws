# aws-spring-boot S3 자동 구성 설계

날짜: 2026-05-10
저장소: `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/1

## 문제

`aws-spring-boot`는 현재 기본 `AwsCredentialsProvider`를 등록하는
`AwsAutoConfiguration`만 노출한다. 이슈 #1은 awspring 없는 다음 S3 Spring Boot 4
통합을 요구한다.

- `S3AutoConfiguration`: `S3AsyncClient` 자동 등록
- `S3Operations`: 업로드, 다운로드, 삭제, 목록 인터페이스
- `S3CoroutinesTemplate`: `S3AsyncClient` 기반 코루틴 우선 연산
- `S3Properties`: `@ConfigurationProperties("bluetape4k.aws.s3")`
- `S3Resource`: S3 객체용 Spring `Resource` 래퍼
- 사전 서명 URL 지원
- LocalStack + Testcontainers 검증

구현은 AWS 서비스 SDK 의존성을 `compileOnly`로 두고 소비자가 런타임 서비스 모듈을
제공한다는 저장소 규칙과 호환돼야 한다.

## 근거

### 현재 저장소

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt`
  는 `DefaultCredentialsProvider`만 등록한다.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  는 현재 `AwsAutoConfiguration`을 등록한다.
- `aws/src/main/kotlin/io/bluetape4k/aws/s3/S3ClientFactory.kt`에는 다음이 이미 있다.
  `S3ClientFactory.Async.create(endpointOverride, region, credentialsProvider, ...)`
  를 이미 제공하며 생성한 클라이언트를 `ShutdownQueue`에 등록한다.
- `aws/src/main/kotlin/io/bluetape4k/aws/s3/S3AsyncClientCoroutinesExtensions.kt`
  는 `getAsByteArray`, `getAsString`, `putAsByteArray`, `putAsString`, `putAsFile`,
  삭제/이동 도우미 등의 suspend 래퍼를 이미 제공한다.
- `aws/src/test/kotlin/io/bluetape4k/aws/s3/AbstractS3Test.kt`는 다음을 사용한다.
  LocalStack 기준인 `localStackServer.region()`, `localStackServer.credentialsProvider`
  를 S3 통합 테스트의 기준으로 사용한다.

### 생태계 패턴

- `bluetape4k-graph` Spring Boot starter는 `@AutoConfiguration`,
  `@EnableConfigurationProperties`, 별도 속성 클래스를 사용한다.
- `bluetape4k-leader` Spring Boot starter는 `@ConditionalOnClass`,
  `@ConditionalOnMissingBean`, 백엔드별 자동 구성, 공개 Spring API의 한글 KDoc을 사용한다.
- `bluetape4k-projects` Spring Boot 테스트는 `AutoConfigurations.of(...)`와
  `ApplicationContextRunner`로 빈 등록, 속성 바인딩, 물러나기 동작을 검증한다.

### 공식 문서

- Spring Boot 4는
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에서
  한 줄에 완전한 클래스 이름 하나씩 라이브러리 자동 구성을 발견한다.
- Spring Boot 자동 구성은 `@ConditionalOnClass`, `@ConditionalOnMissingBean` 같은
  조건을 사용하고 `@ConfigurationProperties`와 `@EnableConfigurationProperties`를
  통해 타입 지정 속성을 노출해야 한다.
- `ApplicationContextRunner`는 자동 구성, 속성 바인딩, 물러나기 동작 테스트에 권장되는 경량 API다.
- AWS SDK Java v2는 사용자 정의 엔드포인트에 `endpointOverride`를 사용해도 리전을
  요구한다. `S3Presigner`는 `signatureDuration`으로 사전 서명 GET/PUT 요청을 만든다.

## 목표

1. S3 SDK 클래스가 있을 때 다음 S3 빈을 자동 구성한다.
   - Spring `Resource` 동기 브리지용 `S3Client`
   - `S3AsyncClient`
   - `S3Presigner`
   - `S3CoroutinesTemplate`이 구현하는 `S3Operations`
2. 다음 `bluetape4k.aws.s3.*` 속성을 바인딩한다.
   - `enabled`
   - `region`
   - `endpoint-override`
   - `path-style-access-enabled`
   - `accelerate-mode-enabled`
   - `chunked-encoding-enabled`
   - 사전 서명 URL 기본값
3. 재정의 경계를 명시적으로 유지한다.
   - 사용자 정의 `S3AsyncClient`가 있으면 클라이언트 생성을 물러난다.
   - 사용자 정의 `S3Presigner`가 있으면 presigner 생성을 물러난다.
   - 사용자 정의 `S3Operations`가 있으면 템플릿 생성을 물러난다.
4. 일반 S3 워크플로에 코루틴 우선 API를 제공한다.
   - 바이트/문자열/리소스/파일/경로 업로드
   - 바이트/문자열/리소스 다운로드
   - 객체 삭제
   - 크기가 제한된 목록 페이지
   - `Flow` 기반 객체 목록
   - 사전 서명 GET/PUT URL
5. Spring 통합용 `S3Resource`를 제공한다.
   - 위치 메타데이터(`bucket`, `key`)
   - `exists()`, `contentLength()`, `lastModified()`, `getInputStream()`
   - `Resource`에 숨은 업로드 의미를 두지 않고 업로드는 `S3Operations`에 유지
6. LocalStack 통합 검증과 ApplicationContextRunner 검증을 추가한다.
7. README.md와 README.ko.md를 동기화한다.

## 제외 범위

- awspring에 의존하지 않는다.
- 별도 예제 이슈인 Ktor 서버 예제를 구현하지 않는다.
- 이 이슈에서 S3 이벤트 알림, S3 Select, 디렉터리 버킷, 객체 잠금, ACL 정책 도우미,
  TransferManager 기반 고수준 디렉터리 전송을 구현하지 않는다.
- 동기 연산을 기본 API로 노출하지 않는다. Spring `Resource`는 동기 추상화이고 운영
  `runBlocking`은 허용할 수 없으므로 Spring 관리 `S3Client`는 `S3Resource`에만 허용한다.
- 이 이슈에서 예제 모듈을 게시하지 않는다.

## 제안 API

### 패키지 구조

```text
io.bluetape4k.aws.spring.s3
  S3AutoConfiguration
  S3Properties
  S3Operations
  S3CoroutinesTemplate
  S3Resource
  S3ObjectLocation
  S3ListPage
  S3PresignRequest
```

### 빌드 변경

`aws-spring-boot`는 S3 서비스 SDK를 직접 선언해야 한다.

- `compileOnly(libs.aws2.s3)`
- `testImplementation(libs.aws2.s3)`

현재 버전 카탈로그의 AWS SDK v2 `s3` 산출물이 `S3Presigner`를 제공하며 별도
`s3-presigner` 별칭은 없다.

### 속성

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.aws.s3")
data class S3Properties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val pathStyleAccessEnabled: Boolean = false,
    val accelerateModeEnabled: Boolean = false,
    val chunkedEncodingEnabled: Boolean? = null,
    val presign: Presign = Presign(),
) {
    init {
        require(endpointOverride == null || !region.isNullOrBlank()) {
            "bluetape4k.aws.s3.region is required when endpoint-override is set."
        }
    }

    data class Presign(
        val duration: Duration = Duration.ofMinutes(15),
    )
}
```

리전 해석 순서:

1. `bluetape4k.aws.s3.region`
2. 빌더 리전을 설정하지 않고 AWS SDK 기본 리전 공급자 체인 사용
3. LocalStack 테스트는 속성을 통해 리전을 명시적으로 설정

엔드포인트 재정의 해석:

- `endpointOverride`가 설정되면 `S3AsyncClient`, `S3Client`, `S3Presigner` 모두에 전달한다.
- 엔드포인트 재정의를 사용할 때도 AWS SDK 서명에는 리전이 필요하다. 속성 객체는
  `region` 없는 `endpointOverride`를 거부한다.

S3 클라이언트 옵션:

- Spring이 `ShutdownQueue` 등록 없이 빈 생명주기를 소유해야 하므로 `S3ClientFactory`
  대신 `S3AutoConfiguration`에서 클라이언트를 직접 생성한다.
- 동기 및 비동기 클라이언트에 `S3Configuration.builder()`를 적용한다.
  - `pathStyleAccessEnabled`
  - `accelerateModeEnabled`
  - null이 아닐 때 `chunkedEncodingEnabled`
- `ObjectProvider<AwsCredentialsProvider>`에서 자격 증명을 해석하고 빈이 없으면
  `DefaultCredentialsProvider.builder().build()`를 사용한다. 애플리케이션이
  `AwsAutoConfiguration`을 재정의하거나 생략해도 S3 자동 구성을 견고하게 유지한다.
- 선택적인 사용자 제공 `SdkHttpClient` / `SdkAsyncHttpClient` 빈을 클라이언트 전송
  재정의로 받는다. 이 이슈에서 HTTP 클라이언트별 속성을 추가하지 않는다.

### 자동 구성 형태

`AutoConfiguration.imports`에 나열한 최상위 자동 구성 클래스를 사용하고 컴포넌트
스캔으로 가져오지 않는다.

```kotlin
@AutoConfiguration(after = [AwsAutoConfiguration::class])
@ConditionalOnClass(name = [
    "software.amazon.awssdk.http.SdkHttpClient",
    "software.amazon.awssdk.http.async.SdkAsyncHttpClient",
    "software.amazon.awssdk.services.s3.S3Client",
    "software.amazon.awssdk.services.s3.S3AsyncClient",
    "software.amazon.awssdk.services.s3.presigner.S3Presigner",
])
@ConditionalOnProperty(prefix = "bluetape4k.aws.s3", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(S3Properties::class)
class S3AutoConfiguration
```

compileOnly S3 SDK 타입을 반환하는 각 빈 메서드는 클래스 수준 문자열 조건으로 즉시
클래스 로딩을 피해야 한다. 사용자 재정의 경계는 다음과 같다.

- `@ConditionalOnMissingBean(S3Client::class)`
- `@ConditionalOnMissingBean(S3AsyncClient::class)`
- `@ConditionalOnMissingBean(S3Presigner::class)`
- `@ConditionalOnMissingBean(S3Operations::class)`

### S3Operations 연산

```kotlin
interface S3Operations {
    suspend fun existsBucket(bucket: String): Boolean
    suspend fun upload(bucket: String, key: String, bytes: ByteArray, contentType: String? = null): PutObjectResponse
    suspend fun upload(bucket: String, key: String, text: String, contentType: String = "text/plain; charset=utf-8"): PutObjectResponse
    suspend fun downloadBytes(bucket: String, key: String): ByteArray
    suspend fun downloadText(bucket: String, key: String, charset: Charset = Charsets.UTF_8): String
    suspend fun delete(bucket: String, key: String): DeleteObjectResponse
    suspend fun listPage(bucket: String, prefix: String? = null, maxKeys: Int = 1000, continuationToken: String? = null): S3ListPage
    fun listFlow(bucket: String, prefix: String? = null, pageSize: Int = 1000): Flow<S3Object>
    fun resource(bucket: String, key: String): S3Resource
    fun presignGet(bucket: String, key: String, duration: Duration? = null): URL
    fun presignPut(bucket: String, key: String, duration: Duration? = null, contentType: String? = null): URL
}
```

`S3CoroutinesTemplate`은 가능한 곳에서 기존 `aws` 모듈 확장 함수에 위임해야 한다.
직접 AWS SDK 호출은 비블로킹으로 유지하거나 `CompletableFuture`를 기다려야 한다.
`CancellationException`을 다시 던지고 suspend 본문을 광범위한 `runCatching`으로
감싸지 않는다.

서명은 로컬 계산이므로 `presignGet`과 `presignPut`은 동기 방식이다. 기간 우선순위는
호출별 기간, `S3Properties.presign.duration` 순이다. `presignPut`이 `Content-Type`에
서명하면 호출자는 URL을 사용할 때 같은 헤더를 전송해야 한다.

### S3Resource 리소스

`S3Resource`는 업로드 가능한 리소스를 구현하지 않고 `AbstractResource`를 확장해야 한다.
`Resource`는 계약상 동기 방식이며 운영 `runBlocking`은 허용할 수 없으므로 Spring이
관리하는 동기 `S3Client`를 사용해야 한다. 다음을 수행한다.

- `getDescription()`에서 `s3://bucket/key`를 반환한다.
- `exists`, `contentLength`, `lastModified`에 `headObject`를 호출한다.
- 전체 객체를 메모리에 만들지 않도록 `getInputStream()`에서
  `getObject(..., ResponseTransformer.toInputStream())`를 호출한다.
- 읽기 전용으로 유지하며 업로드는 `S3Operations`에 둔다.

## 설계 선택지

### 선택지 A - 최소 자동 구성 + 코루틴 템플릿(선택)

Spring이 관리하는 `S3Client`, `S3AsyncClient`, `S3Presigner`, `S3CoroutinesTemplate`을
생성한다. 일반 연산에는 기존 `aws` 코루틴 확장을 사용하고 `headObject`, 페이지 목록,
presigner 공백에는 직접 SDK 호출을 사용한다.

장점:
- 이슈 #1과 정확히 일치한다.
- 기존 `aws` 모듈 동작을 재사용한다.
- Spring 통합을 얇고 테스트 가능하게 유지한다.
- awspring을 피하고 동기 클라이언트를 Spring `Resource` 브리지로 제한한다.

단점:
- `S3Resource`가 동기 Spring `Resource` 호출 전용 동기 클라이언트 빈 하나를 도입한다.
- Presigner는 자체 생명주기를 갖는 두 번째 클라이언트 형태 객체다.

### 선택지 B - 리소스 우선 API

더 풍부한 `S3Resource`를 만들고 `S3Operations`를 주로 리소스 팩토리로 만든다.

장점:
- Spring 사용자에게 익숙하다.
- awspring의 공개 API와 비슷하다.

단점:
- `Resource`를 통한 업로드 의미가 어색하고 블로킹으로 만들기 쉽다.
- 코루틴 우선 API를 동기 추상화 뒤로 밀어 넣는다.

### 선택지 C - TransferManager 중심 API

`S3TransferManager`를 자동 구성하고 transfer manager API로 연산을 구현한다.

장점:
- 대용량 파일과 디렉터리 전송에 더 강한 경로다.

단점:
- 의존성과 생명주기 범위가 더 크다.
- 이슈 #1의 일반 업로드/다운로드/목록/삭제 API에는 필요하지 않다.
- 첫 Spring Boot S3 통합을 작게 유지하기 어렵다.

## 위험

| 위험 | 영향 | 완화책 |
|---|---|---|
| 런타임 AWS S3 클래스 누락 | Spring 컨텍스트의 예기치 않은 실패 | 문자열 기반 `@ConditionalOnClass(name = [...])`로 보호하고 S3 SDK를 compileOnly로 유지한다. |
| 리전 없는 엔드포인트 재정의 | Presigner/클라이언트 서명 실패 | 이 속성 조합을 거부하고 테스트한다. |
| `S3Resource`가 이벤트 루프 스레드 블로킹 | 숨은 지연 또는 교착 상태 | 동기 `S3Client`를 `Resource`에서만 사용하고 코루틴 템플릿을 기본 API로 문서화한다. |
| 사용자 정의 빈 덮어쓰기 | 애플리케이션별 SDK 설정 유실 | 각 빈에 `@ConditionalOnMissingBean`을 둔다. |
| 클라이언트/presigner 생명주기 충돌 | 이중 닫기 또는 리소스 누수 | `ShutdownQueue` 없이 클라이언트를 직접 만들고 Spring이 `AutoCloseable` 빈 생명주기를 소유한다. |
| 대형 버킷 목록 | 힙 압박 | `listPage`와 `listFlow`를 제공하고 제한 없는 평면 목록 API를 피한다. |
| 사전 서명 PUT 헤더 불일치 | 서명 불일치로 소비자 업로드 실패 | `Content-Type` 같은 서명 헤더가 업로드 요청과 일치해야 함을 문서화한다. |
| 속성 메타데이터 누락 | 불편한 Boot UX | `@ConfigurationProperties`와 이미 구성된 어노테이션 프로세서를 사용한다. |
| LocalStack 테스트가 느리거나 불안정 | CI 불안정 | 경량 ApplicationContextRunner 테스트와 집중된 LocalStack 왕복 테스트를 분리한다. |

## 인수 기준

- `S3AutoConfiguration`이 `AutoConfiguration.imports`에 나타난다.
- `ApplicationContextRunner` 테스트가 다음을 입증한다.
  - 기본 속성 바인딩
  - 비활성화 속성에서 물러나기
  - 기본 `S3AsyncClient`, `S3Presigner`, `S3Operations` 등록
  - 사용자 정의 빈을 교체하지 않음
  - 엔드포인트/리전/path-style 속성 적용
- 비활성화 속성이 모든 S3 빈 등록을 물러나게 한다.
- 리전 없는 엔드포인트 재정의가 명확한 예외로 빠르게 실패한다.
- LocalStack 통합 테스트가 다음을 입증한다.
  - 바이트/텍스트 업로드
  - 바이트/텍스트 다운로드
  - 접두사별 `listPage`
  - 접두사별 `listFlow`
  - 삭제
  - `S3Resource.exists/contentLength/getInputStream`
  - 예상 메서드와 만료 시간으로 사전 서명 GET/PUT URL 생성
- 공개 API에 한글 KDoc이 있다.
- README.md와 README.ko.md가 속성, 빈, 사용 예제를 설명한다.
- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:test --no-daemon`
  가 통과한다.
- `./gradlew :aws-spring-boot:detekt --no-daemon`은 해당 작업을
  사용할 수 있으면 통과하고, 그렇지 않으면 루트 `./gradlew detekt --no-daemon`이 통과한다.
- README.md와 README.ko.md를 함께 갱신한다.
- `git diff --check`가 통과한다.

## 미결 질문

로컬에서 해결함:

- Spring `Resource`는 동기 방식이므로 `S3Resource`는 동기 `S3Client`를 사용할 수
  있으며 `runBlocking`은 거부한다.
- 목록은 제한 없는 목록을 반환하지 않고 페이지와 flow API를 사용해야 한다.
- v1의 사전 서명은 기본 기간 하나를 사용한다. 실제 사용 사례가 나타나면 메서드별
  기본값을 나중에 추가할 수 있다.
- Spring이 자동 구성 클라이언트 생명주기를 소유하므로 이 빈에 `ShutdownQueue`를 사용하지 않는다.
- HTTP 클라이언트 재정의는 새 속성이 아니라 선택적 사용자 `SdkHttpClient` /
  `SdkAsyncHttpClient` 빈을 통해 수행한다.
- #1은 `origin/develop`에서 진행할 수 있으며 #9는 독립적이고 병합 대기 중이다.

구현 계획 전에 사용자 에스컬레이션은 필요하지 않다.

## Claude Code Opus 자문

산출물:
`/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat/1-spring-boot-s3/.omx/artifacts/ask-claude-aws-spring-boot-s3-spec-20260510-180655.md`

| 심각도 | 지적 | 결정 | 후속 조치 |
|---|---|---|---|
| 차단 | `S3Resource`의 `runBlocking`은 안전하지 않은 운영 코드다. | 수용 | `Resource`에 동기 `S3Client`를 사용하고 `runBlocking`은 사용하지 않는다. |
| 차단 | CompileOnly S3 클래스에 문자열 기반 클래스 조건이 필요하다. | 수용 | 명세에서 `@ConditionalOnClass(name = [...])`를 요구한다. |
| 차단 | `aws-spring-boot`에 S3 SDK 의존성이 명시되지 않았다. | 부분 수용 | `compileOnly/testImplementation(libs.aws2.s3)`를 추가한다. 현재 AWS SDK가 `s3`에서 `S3Presigner`를 노출하므로 별도 `s3-presigner`는 거부한다. |
| 차단 | 자격 증명/리전/HTTP 클라이언트 연결이 명시되지 않았다. | 수용 | 실행 순서, 자격 증명 공급자, 선택적 SDK HTTP 클라이언트 빈 재정의, 엔드포인트+리전 불변 조건을 추가한다. |
| 차단 | `pathStyleAccessEnabled`와 `chunkedEncodingEnabled` 연결이 누락됐다. | 수용 | 자동 구성에서 `S3Configuration`을 직접 만든다. |
| 차단 | `ShutdownQueue`와 Spring 생명주기가 충돌할 수 있다. | 수용 | 자동 구성이 클라이언트를 직접 만들고 Spring이 생명주기를 소유한다. |
| 차단 | `list` API에 제한이 없었다. | 수용 | `listPage`와 `listFlow`로 교체한다. |

## 단계 체크리스트 완료

| 항목 | 상태 | 비고 |
|---|---|---|
| 아키텍처 사전 설계 실행 | 완료 | 선택지 A/B/C를 비교하고 A를 선택함. |
| 1-R 단계 조사 반영 | 완료 | Spring Boot 4 문서, AWS SDK 문서, 현재 저장소와 생태계 패턴을 포함함. |
| 현재 동작의 소스 근거 제시 | 완료 | 기존 자동 구성, imports, S3 팩토리/확장, LocalStack 테스트를 인용함. |
| 기능 worktree 내부 명세 경로 | 완료 | 이 파일은 `.worktrees/feat/1-spring-boot-s3` 아래에 있음. |
| 위험/실패 모드 포함 | 완료 | 위험 표 참조. |
| 접근 비교 포함 | 완료 | 선택지 A/B/C. |
| 미결 질문 해결 | 완료 | 에스컬레이션이 필요 없음. |
| 초안 작업 목록 반환 | 완료 | 인수 기준이 구현 작업을 정의하며 계획에서 확장함. |
