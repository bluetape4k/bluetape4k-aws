# S3 ResourceLoader Protocol and Pattern Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #463에 승인된 설계대로 기존 `S3Resource`를 Spring exact `ResourceLoader` protocol과 단일 literal bucket 전용 `ResourcePatternResolver`에 연결하고, 자동 구성·수명·권한·패턴·Floci 검증 및 한국어 사용 문서를 완성한다.

**Architecture:** `S3ResourceAutoConfiguration`을 기존 `S3AutoConfiguration` 뒤에 추가한다. `S3ProtocolResolver`는 `ObjectProvider<S3Client>`를 보유한 지연 exact resolver이고, static `BeanFactoryPostProcessor`가 `ConfigurableApplicationContext.addProtocolResolver(...)`로 한 번만 등록한다. `S3ResourcePatternResolver`는 하나의 `PathMatchingResourcePatternResolver(applicationContext)`에 non-S3 요청을 위임하고, S3 pattern만 literal bucket 한 곳의 `ListObjectsV2` paginator로 처리한다. parser는 raw escape provenance와 wildcard token을 보존해 URI·권한·cross-bucket 입력을 네트워크 전에 차단한다.

**Tech Stack:** Kotlin, Spring Boot 4 auto-configuration, Spring `ProtocolResolver`/`ResourcePatternResolver`, AWS SDK v2 sync `S3Client`/`ListObjectsV2Iterable`, JUnit 5, MockK, `ApplicationContextRunner`, `FilteredClassLoader`, Floci-first Testcontainers/emulator, Gradle, Detekt.

---

## 0. 실행 경계와 선행 조건

- [ ] 작업 디렉터리가 `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat-issue-463-s3-resource-loader`이고 브랜치가 `feat/issue-463-s3-resource-loader`인지 확인한다. `develop` worktree에는 파일을 만들지 않는다.
- [ ] 승인된 설계 문서 `docs/superpowers/specs/2026-08-23-issue-463-s3-resource-loader-design.md`와 spec review `docs/review/2026-08-23-issue-463-s3-resource-loader-spec-review.md`를 읽고 이 계획의 범위와 불일치가 없는지 확인한다. 설계가 바뀌면 구현 전에 사용자 spec 승인을 다시 받는다.
- [ ] `aws-spring-boot/build.gradle.kts`의 기존 `compileOnly(libs.aws2.s3)`, `testImplementation(libs.aws2.s3)`, Spring test, MockK와 Floci test property를 재사용한다. dependency catalog, BOM, AWS client auto-configuration은 수정하지 않는다.
- [ ] `/Users/debop/.codex/skills/bluetape-workflow/references/repository-hazards.md`의 HTTP/Testcontainers, Benchmarks, GitHub Actions/Coverage, Nightly/Cleanup 절을 읽고 적용한다. 이 작업은 새 benchmark module이나 ABI-sensitive codec을 추가하지 않는 stress/conformance test이며, shared launcher/container 소유권과 CI/Nightly artifact 경계를 기존 규칙대로 확인한다.
- [ ] 모든 구현 단계는 이 계획의 RED → GREEN 순서를 따른다. 각 단계에서 관련 테스트만 먼저 실패시키고, 최소 구현 후 대상 테스트를 통과시킨 다음 다음 단계로 이동한다.
- [ ] paginator는 승인 spec의 “모든 page 소비” 계약 때문에 숨은 `maxScannedKeys`/`maxMatchedResources` 상한을 추가하지 않는다. 짧은 prefix가 큰 bucket을 읽을 수 있다는 비용 위험은 문서·IAM prefix·성능 fixture로 드러내며, 상한이 필요해지는 경우 별도 spec 승인 없이는 구현하지 않는다.

## 1. Acceptance와 변경 surface 추적

| 승인된 요구사항 | 구현 파일 | RED/GREEN 증거 | 문서·안전 확인 |
| --- | --- | --- | --- |
| exact `s3://bucket/key`가 `Resource`로 해석되고 stream close는 caller 소유 | `S3ResourceLocationParser.kt`, `S3ProtocolResolver.kt`, 기존 `S3Resource.kt`는 public signature 유지 | `S3ResourceLocationParserTest`, `S3ProtocolResolverTest`, `S3ResourceTest`, context exact test | 조기 I/O·client close 없음, 한국어 KDoc |
| `@Value`와 `ApplicationContext.getResource`가 exact를 해석 | `S3ResourceAutoConfiguration.kt`, `AutoConfiguration.imports` | lazy client `ApplicationContextRunner` test, `@Value` bean test | static BFPP와 `addProtocolResolver` 등록 순서 |
| `*`, `?`, `**`, non-empty prefix, 모든 paginator page | `S3Pattern`/`S3ResourcePatternResolver.kt` | parser token test, unsorted multi-page fixture, Floci integration | delimiter 금지, one bucket only |
| escaped wildcard/plus/slash와 malformed escape | `S3ResourceLocationParser.kt`, `S3Pattern` | `%2A/%3F/%5B/%5D`, raw `+`, `%2F`, malformed escape 각각의 단위 테스트 | `URLDecoder` form semantics 금지 |
| 중복 제거·locale-independent 정렬·empty result | `S3ResourcePatternResolver.kt` | duplicate/unsorted/no-match fixture | `String.compareTo`, retry 없음 |
| cross-bucket 및 root-level listing 차단 | parser/resolver | wildcard authority, userinfo/port, multi-bucket, empty-prefix 입력과 zero AWS-call 검증 | request scope는 호출 문자열의 한 bucket으로 고정하고 실제 IAM enforcement는 consumer 책임 |
| AccessDenied/network/paginator 중간 실패 전파 | `S3ResourcePatternResolver.kt` | `IOException` cause 보존 및 secret 비노출 fixture | partial result·empty fallback 금지 |
| 기존 missing-object `S3Resource.exists()` 의미 유지 | 기존 `S3Resource.kt`, `S3ResourceTest.kt` | 404/`NoSuchBucket`/`NoSuchKey`/`NotFound` false, permission/network throw | 기존 public contract 변경 없음 |
| non-S3 위임과 unrelated resolver 공존 | `S3ResourcePatternResolver.kt`, auto-config | classpath/file/custom protocol delegate, concrete/qualifier injection, unrelated bean backoff 불가 확인 | `@Primary` 선언 금지 |
| S3-specific custom backoff와 protocol 중복 등록 방지 | `S3ResourceAutoConfiguration.kt` | custom subclass, custom replacement, one registration invocation | custom S3 type만 missing-bean 조건에 영향 |
| disabled/missing SDK/기존 S3 client 조건 | auto-config/imports | disabled property, `FilteredClassLoader`, no `S3Client`, after-order test | `@ConditionalOnAwsEnabled`, name-based `@ConditionalOnClass`, `@ConditionalOnBean` |
| endpoint·region·credentials·path-style 재사용 및 Floci | 신규 emulator test | `S3ResourceLoaderAwsEmulatorTest` | Floci 우선, LocalStack은 capability gap만 fallback |
| 사용법·unsupported 범위·수명 문서 | `aws-spring-boot/README.md`, `README.ko.md`, en/ko manual runtime operations | 문서 구조·예시 diff 검토, writer audit | 한국어 자연스러운 설명, exact/pattern 사용 경로 분리 |

## 2. Task 1 — parser와 token contract (TDD RED → GREEN)

### 2.1 RED: parser contract를 먼저 고정한다

- [ ] 새 테스트 파일 `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLocationParserTest.kt`를 만든다. parser와 모델은 `internal` 접근 수준으로 직접 테스트한다.
- [ ] 다음 입력을 각각 독립 테스트로 작성한다. 각 실패 테스트는 `IllegalArgumentException`을 확인하고 AWS client 호출이 없는 순수 단위 테스트로 둔다.
  - `s3://bucket/config/application.yml` → bucket/key exact 결과
  - scheme `S3://bucket/key` 허용, `file://...`는 parser 직접 호출 시 입력 오류로 처리하고 resolver는 `null` 위임
  - `s3://bucket/dir/`, `%2F`, `%20`, raw `+`는 각각 decoded key와 trailing slash를 보존
  - query, fragment, userinfo, port, authority의 `*`, `?`, `[`, `]`, `/`, 빈 bucket 거부
  - `s3://bucket`, `s3://bucket/`, 빈 decoded key 거부
  - exact에서 raw `*`/`?`는 wildcard 오용으로 거부하고 `%2A`/`%3F`는 literal key로 허용
  - pattern에서 raw `*`, `?`, `**` token과 첫 wildcard 전 non-empty prefix 계산
  - `%2A`, `%3F`, `%5B`, `%5D`는 literal token이고 raw `[]` character-class 표기는 거부
  - malformed `%`, 불완전 UTF-8 percent bytes, 중복 decode가 필요한 escape 거부
  - `s3://bucket/*.json`, `s3://bucket/**`, `s3://bucket/?`는 빈 prefix root listing으로 거부
  - bucket wildcard, `s3://bucket-a/path,s3://bucket-b/path`, authority 구분자 우회 입력은 모두 거부
- [ ] RED 실행 명령을 기록한다.

```bash
set -euo pipefail
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceLocationParserTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

기대 결과는 parser/model이 아직 없어서 compilation failure이며, 이는 RED 증거다. 실패가 다른 기존 테스트나 dependency 문제라면 parser 구현 전에 원인을 분리한다.

### 2.2 GREEN: raw URI와 wildcard token을 하나의 내부 계약으로 구현한다

- [ ] `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLocationParser.kt`에 아래 공개 범위의 내부 모델과 메서드를 구현한다.

```kotlin
internal enum class S3WildcardKind {
    STAR,
    DOUBLE_STAR,
    QUESTION,
}

internal sealed interface S3PatternToken {
    data class Literal(val value: String): S3PatternToken
    data class Wildcard(val kind: S3WildcardKind): S3PatternToken
}

internal data class S3Pattern(
    val bucket: String,
    val prefix: String,
    val tokens: List<S3PatternToken>,
) {
    val hasWildcards: Boolean
        get() = tokens.any { it is S3PatternToken.Wildcard }

    fun prepareMatcher(): S3PatternMatcher
}

internal fun interface S3PatternMatcher {
    fun matches(key: String): Boolean
}

internal class S3ResourceLocationParser {
    fun parseExact(location: String): S3ObjectLocation
    fun parsePattern(location: String): S3Pattern
}
```

- [ ] URI 처리 순서를 고정한다: `s3://` 여부를 대소문자 무시로 확인하고, raw authority/path를 분리한 뒤 query/fragment와 authority 금지 문자를 검사한다. bucket authority는 percent escape를 허용하지 않는 단일 literal로 제한한다.
- [ ] path는 `URLDecoder`로 처리하지 않는다. `%HH`를 byte buffer로 모은 뒤 strict UTF-8 decoder(`CodingErrorAction.REPORT`)로 정확히 한 번 decode하고, raw `+`는 그대로 둔다. malformed escape와 invalid UTF-8은 `IllegalArgumentException`으로 변환한다.
- [ ] raw path scanner가 percent escape에서 나온 `*`, `?`, `[`, `]`와 raw metacharacter를 구분한다. raw `*`/`?`만 wildcard token으로 만들고 escaped metacharacter는 `Literal` token으로 만든다. raw bracket은 pattern에서 거부하고 escaped bracket은 literal로 보존한다.
- [ ] `parseExact`는 wildcard token이 없고 decoded key가 blank가 아닌 경우에만 기존 `S3ObjectLocation(bucket, key)`를 반환한다. `parsePattern`은 wildcard가 없는 exact도 표현할 수 있지만 `hasWildcards=false`인 경우 resolver가 listing을 생략하도록 한다.
- [ ] 첫 wildcard 전 literal token을 decoded 문자열로 합쳐 `prefix`로 저장한다. prefix가 빈 pattern은 root-level bucket 전체 listing이므로 wildcard 종류와 관계없이 즉시 거부한다. escaped wildcard는 prefix 계산을 중단시키지 않는다.
- [ ] `S3Pattern.matches` 준비 단계는 listing마다 pattern-side encoded representation과 pattern에 없는 private-use sentinel 후보를 한 번 계산한다. literal `%2A`/`%3F`와 후보 key의 같은 문자를 sentinel로 치환하고, wildcard token은 `*`, `?`, `**` 그대로 둔 뒤 `AntPathMatcher`를 실행한다. 후보 key에 sentinel이 포함되면 다른 후보를 per-key로 선택하고, 모든 sentinel 후보가 소진되면 안전한 literal 오인 방지를 위해 partial result 없이 명시적 matcher 오류를 낸다. raw/escaped wildcard 의미를 보존하는 충돌·후보 소진 fixture를 둔다.
- [ ] parser GREEN 실행으로 위 단위 테스트를 통과시킨다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceLocationParserTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

## 3. Task 2 — exact protocol과 pattern resolver (TDD RED → GREEN)

### 3.1 RED: resolver 상호작용·위임·오류를 고정한다

- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ProtocolResolverTest.kt`에 다음을 작성한다.
  - non-S3 location은 `null`이고 existing resolver chain에 위임된다.
  - exact S3 location은 `S3Resource`와 location만 만들며 `headObject`, `getObject`, `close`를 resolve 중 호출하지 않는다.
  - malformed S3 문법은 `IllegalArgumentException`이고 silent fallback이 아니다. parser 오류와 listing 진단에는 raw URI, credential/header, synthetic secret을 그대로 복사하지 않고 control character를 escaped/truncated representation으로만 남긴다.
  - `getInputStream()`이 반환한 stream은 caller가 `use`로 닫고 resolver/client는 client를 닫지 않는다.
- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolverTest.kt`에 다음 RED를 작성한다.
  - non-S3 `getResource`, `getResources`, `getClassLoader`가 같은 `PathMatchingResourcePatternResolver(applicationContext)` delegate를 거친다.
  - exact S3 `getResources`는 listing 없이 one-element array를 반환한다.
  - multi-page iterable의 unsorted keys를 `AntPathMatcher`로 필터링하고 duplicate key를 제거한 뒤 `String.compareTo` 오름차순으로 반환한다.
  - request에 literal bucket과 첫 wildcard 전 prefix만 들어가고 delimiter는 설정하지 않는다.
  - no-match는 non-null empty array이고 paginator를 한 번만 소비한다.
  - `AccessDenied`, endpoint/network 예외, 중간 page 예외는 partial result나 empty array가 아니라 `IOException`으로 전파하고 cause를 보존한다. 메시지에는 bucket/prefix만 있고 credential/header/secret은 없다.
  - synthetic secret과 `%0A`, `%00`, `%1B`가 포함된 입력/cause에서도 exception/log 진단이 control-escaped·bounded이며 raw secret, URI, header를 포함하지 않는다.
  - PUA sentinel을 포함한 candidate key는 다른 sentinel 후보로 안전하게 매칭하고, 후보를 모두 소진한 adversarial fixture는 `IOException` cause와 partial result 없음으로 종료한다.
  - wildcard authority, 빈 prefix, raw `[]`, malformed escape 입력은 AWS 호출 0회로 즉시 거부한다.
  - malformed/cross-bucket/root 입력은 parser 결과를 먼저 만든 뒤 `ObjectProvider<S3Client>`를 조회하므로 provider 호출도 0회다.
  - key가 `http://169.254.169.254/metadata`처럼 보여도 endpoint/region/credentials를 바꾸지 않고 주입된 client의 literal S3 key로만 전달된다.
  - `getResources`의 Java reflection declared exception에 `IOException`이 아직 없어서 RED가 되고, 구현 후 `@Throws(IOException::class)`를 추가해 같은 assertion을 GREEN으로 통과시킨다.
- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolverPerformanceTest.kt`에 50 page × 1,000 synthetic key와 4개 동시 호출 fixture를 먼저 작성한다. 호출 시작 barrier와 completion latch로 네 paginator가 실제로 겹치게 하고, stall guard timeout을 둔다. 이 RED는 resolver가 없거나 구현되지 않아 실패해야 하며, all-page 소비·독립 결과·중복 제거·정렬·추가 retry 없음과 key별 AWS 호출 없음이 acceptance다.
- [ ] RED 명령은 두 resolver 테스트를 함께 실행해 source class 부재/미완성 구현을 확인한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ProtocolResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourcePatternResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourcePatternResolverPerformanceTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

### 3.2 GREEN: 지연 client와 단일 delegate를 구현한다

- [ ] `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ProtocolResolver.kt`를 `open` class로 만든다. constructor는 `ObjectProvider<S3Client>`와 stateless parser만 받으며, `resolve`는 `s3:`가 아니면 `null`, S3이면 `parseExact` 후 resolve 시점에만 `s3ClientProvider.getObject()`를 호출해 `S3Resource`를 만든다. `ResourceLoader` 인자는 chain 계약상 받되 client를 만들 때 사용하지 않는다.

```kotlin
open class S3ProtocolResolver(
    private val s3ClientProvider: ObjectProvider<S3Client>,
): ProtocolResolver {
    private val parser = S3ResourceLocationParser()

    override fun resolve(location: String, resourceLoader: ResourceLoader): Resource? {
        if (!location.startsWith("s3:", ignoreCase = true)) return null
        val parsed = parser.parseExact(location)
        return S3Resource(s3ClientProvider.getObject(), parsed)
    }
}
```

- [ ] `S3ResourcePatternResolver`의 public constructor와 method signatures를 다음처럼 고정한다. parser/matcher는 내부에서 만들고 caller가 S3 client를 소유하지 않는다.

```kotlin
open class S3ResourcePatternResolver(
    private val applicationContext: ApplicationContext,
    private val s3ClientProvider: ObjectProvider<S3Client>,
): ResourcePatternResolver {
    override fun getResource(location: String): Resource
    @Throws(IOException::class)
    override fun getResources(locationPattern: String): Array<Resource>
    override fun getClassLoader(): ClassLoader?
}
```

- [ ] `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolver.kt`를 `open` class로 구현하고 `private val delegate = PathMatchingResourcePatternResolver(applicationContext)` 하나만 만들며 non-S3 `getResource`, non-S3 `getResources`, `getClassLoader`는 모두 이 delegate에 위임한다. parser·listing diagnostics는 bucket/prefix를 control-escaped·bounded representation으로 만들고 raw URI·credential/header·AWS secret을 포함하지 않는다.
- [ ] `getResources` 선언에는 `@Throws(IOException::class)`를 붙여 Spring checked-exception contract를 Java caller의 `Exceptions` attribute에도 노출한다. Task 2 RED에서 고정한 reflection assertion을 GREEN으로 통과시켜 Kotlin source contract와 Java interop contract가 함께 고정되는지 검증한다.
- [ ] 새 public surface의 class·method KDoc을 한국어로 작성한다. `S3ProtocolResolver` KDoc은 exact `s3://bucket/key` 문법, non-S3 `null` 위임, resolve 시점의 지연 client 조회, `@Value`/`ApplicationContext.getResource` 사용, stream/client 수명 비소유를 설명한다. `S3ResourcePatternResolver`와 `getResource`, `getResources`, `getClassLoader` KDoc은 exact와 wildcard pattern 경계, non-S3 delegate, literal 단일 bucket·non-empty prefix·지원 wildcard, `IOException`/동기 blocking, caller의 stream/client/context 소유권을 각각 명시한다.
- [ ] S3 `getResource`는 exact parser를 먼저 성공시킨 뒤 lazy provider를 조회한다. S3 `getResources`도 pattern parser 결과가 exact이면 provider를 조회해 one-element array를 만들고, wildcard이면 parser가 반환한 pattern을 listing `try` 블록 안에서 `prepareMatcher()`까지 성공시킨 뒤에만 provider를 조회해 아래 순서의 한 번의 listing을 수행한다. parser 입력 오류만 `IllegalArgumentException`으로 남기고 sentinel 후보 소진/transport/listing 오류는 `IOException` cause로 감싸며 partial result를 반환하지 않는다. 모든 malformed/cross-bucket/root 입력은 AWS client/paginator 접근 0회다.

```kotlin
val resources = try {
    val request = ListObjectsV2Request.builder()
        .bucket(pattern.bucket)
        .prefix(pattern.prefix)
        .build()
    val preparedPattern = pattern.prepareMatcher()
    val s3Client = s3ClientProvider.getObject()
    val matchedKeys = HashSet<String>()
    for (page in s3Client.listObjectsV2Paginator(request)) {
        for (object in page.contents()) {
            if (preparedPattern.matches(object.key())) matchedKeys += object.key()
        }
    }
    val sortedKeys = matchedKeys.toMutableList().apply {
        sortWith(Comparator { left, right -> left.compareTo(right) })
    }
    Array<Resource>(sortedKeys.size) { index ->
        S3Resource(s3Client, S3ObjectLocation(pattern.bucket, sortedKeys[index]))
    }
} catch (cause: Exception) {
    throw IOException(
        "S3 resource listing failed for bucket [${safeS3DiagnosticPart(pattern.bucket)}] " +
            "and prefix [${safeS3DiagnosticPart(pattern.prefix)}].",
        cause,
    )
}
```

- [ ] paginator 호출과 object loop 전체를 `try/catch`로 감싸되 `IllegalArgumentException`은 parser 입력 오류로 그대로 두고 AWS/listing/matcher 예외만 `IOException`으로 감싼다. top-level message는 `safeS3DiagnosticPart(bucket)`/`safeS3DiagnosticPart(prefix)`처럼 control-escape·최대 길이 제한을 적용한 helper 결과만 보간하고 `cause.message`·raw URI·request header·credential을 복사하지 않는다. 원인 예외 객체는 `IOException`의 cause로 보존한다.
- [ ] 위 imperative loop에서 client를 한 listing 호출에 한 번만 provider에서 얻고, resource 생성은 key별 AWS 호출을 추가하지 않는다. client를 닫거나 stream을 미리 열거나 결과를 cache하지 않는다. `ListObjectsV2Request`에는 delimiter를 호출하지 않는다. 1,000개 이상의 synthetic key fixture로 이 상호작용을 검증한다.
- [ ] hot path는 Java stream 연쇄 대신 page/object를 imperative loop로 순회해 `HashSet<String>` 하나에 dedupe하고, key를 한 번 정렬한 뒤 `Resource[]`를 한 번 만든다. 이 최적화는 동작을 바꾸지 않으며 paginator page 수·client 획득 횟수·결과 정렬을 상호작용으로 검증한다.
- [ ] `S3ResourceTest.kt`를 새로 만들거나 기존 관련 테스트에 보강해 `S3Resource.exists()`의 missing code/404 false와 permission/network throw를 유지하고, resolver가 stream/client를 소유하지 않음을 회귀 검증한다.
- [ ] `safeS3DiagnosticPart`는 control code point를 `\\u{...}` 형태로 escape하고 UTF-16 기준 128자에서 자른 뒤 bracketed diagnostic 값으로 반환한다. PUA sentinel이 pattern/key 모두와 충돌하면 다음 후보를 시도하고, 후보가 모두 소진되면 AWS 호출 결과를 일부 반환하지 않고 `IOException`으로 종료한다.
- [ ] parser, protocol, pattern, resource 테스트를 GREEN 명령으로 통과시킨다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceLocationParserTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ProtocolResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourcePatternResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

## 4. Task 3 — auto-configuration, registration, backoff (TDD RED → GREEN)

### 4.1 RED: context 조건과 lifecycle을 고정한다

- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceAutoConfigurationTest.kt`에 `ApplicationContextRunner` 테스트를 추가한다. 기존 `AwsAutoConfiguration`과 `S3AutoConfiguration` 테스트를 깨지 않도록 resolver auto-configuration을 독립 fixture로도, 전체 S3 configuration 뒤의 순서로도 검증한다.
- [ ] 다음 테스트를 작성한다.
  - `S3Client` bean이 있고 enabled/default property면 `S3ProtocolResolver`, 이름이 `s3ResourcePatternResolver`인 `S3ResourcePatternResolver`, registrar가 생긴다.
  - `bluetape4k.aws.enabled=false`, `bluetape4k.aws.s3.enabled=false`, `S3Client` bean 없음 각각에서 resolver가 없다.
  - `bluetape4k.aws.s3.enabled=false`는 resolver 전용 kill switch가 아니라 기존 `S3AutoConfiguration`의 client/template와 새 resolver를 함께 끄는 기존 S3 backend switch임을 context assertion과 운영 문서에 고정한다. resolver만 되돌리려면 해당 auto-config/import diff를 rollback하고 별도 flag는 새 spec 승인 없이는 추가하지 않는다.
  - S3 SDK class를 `FilteredClassLoader`로 제거하면 name-based `@ConditionalOnClass`가 auto-config를 건너뛴다.
  - custom `S3ProtocolResolver` subclass는 정확히 하나일 때 default를 backoff한다. custom `S3ResourcePatternResolver` subclass가 있으면 이름과 무관하게 기본 구현을 backoff하며, replacement는 고정 bean name `s3ResourcePatternResolver`를 사용해 qualifier 계약을 유지한다. 다른 이름의 custom subtype을 등록하면 기본 bean과 fixed-name qualifier가 함께 제공되지 않는다는 점을 명시적으로 검증한다. unrelated `ProtocolResolver`/`ResourcePatternResolver` bean은 S3-specific default backoff를 유발하지 않는다.
  - 예약된 `s3ResourcePatternResolver` 이름을 unrelated `ResourcePatternResolver` bean이 차지하면 bean name collision으로 context가 명확히 실패하며, 이 이름은 S3 pattern replacement 전용이라는 경계를 negative context test와 문서에 고정한다.
  - custom S3 protocol resolver가 둘 이상이면 `ObjectProvider.getObject()`의 명확한 `NoUniqueBeanDefinitionException`으로 startup이 실패하고 임의 resolver를 선택하지 않는다. pattern resolver는 S3-specific subtype 한 개가 있으면 기본 구현을 backoff하고, replacement는 fixed-name 한 개만 qualifier 계약을 보장한다.
  - fixed-name custom pattern subclass는 default 없이 `s3ResourcePatternResolver` 이름을 유지하고 concrete type과 `@Qualifier("s3ResourcePatternResolver") ResourcePatternResolver` 주입이 모두 성공한다. 다른 이름의 custom subtype은 default와 fixed-name bean을 모두 없애므로 사용자가 fixed name으로 등록해야 한다는 계약을 문서화한다.
  - custom protocol resolver가 `ApplicationContext.getResource("s3://bucket/key")`를 처리하고 default와 이중 등록되지 않는다. custom counter는 exact 호출 1회여야 한다.
  - lazy `S3Client` bean은 context refresh 중 생성되지 않고 exact resolve 때만 생성된다. resolver는 resolve 중 client를 close하지 않는다. `@Bean(destroyMethod = "")`인 외부 client fixture는 context close 뒤에도 resolver가 close하지 않음을 확인하고, Spring-managed auto-configured client fixture는 context lifecycle에 따라 close가 정확히 한 번 일어남을 별도 확인한다.
  - `@Value("s3://bucket/key")` bean과 `ApplicationContext.getResource(...)`가 context 초기화 완료 전에 protocol chain으로 exact를 해석한다.
  - custom endpoint/region/path-style을 가진 주입 `S3Client`에서 resolver가 endpoint·region·credentials를 재구성하거나 변경하지 않고 request의 bucket/key만 설정한다.
  - `S3ResourceAutoConfiguration`이 `@AutoConfiguration(after = [S3AutoConfiguration::class])`, `@ConditionalOnBean(S3Client::class)`, 기존 AWS enabled/property 조건을 모두 가진다.
- [ ] `AutoConfiguration.imports`에 새 class가 기존 `io.bluetape4k.aws.spring.s3.S3AutoConfiguration` 바로 다음에 있어야 한다는 파일 assertion을 추가한다.
- [ ] RED 명령을 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceAutoConfigurationTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

### 4.2 GREEN: static BFPP와 S3-specific conditional을 구현한다

- [ ] `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceAutoConfiguration.kt`를 추가한다. 선언은 다음 조건을 모두 포함한다.

```kotlin
@AutoConfiguration(after = [S3AutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(
    name = [
        "org.springframework.core.io.Resource",
        "org.springframework.core.io.ProtocolResolver",
        "org.springframework.core.io.support.ResourcePatternResolver",
        "software.amazon.awssdk.services.s3.S3Client",
    ],
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.s3",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnBean(S3Client::class)
class S3ResourceAutoConfiguration
```

- [ ] 기본 `S3ProtocolResolver` bean은 `@ConditionalOnMissingBean(S3ProtocolResolver::class)`로 선언한다. 기본 pattern bean은 `@Bean(name = ["s3ResourcePatternResolver"])`와 `@ConditionalOnMissingBean(S3ResourcePatternResolver::class)`로 선언해 S3-specific custom subtype이면 이름과 무관하게 backoff한다. custom replacement는 반드시 같은 이름을 사용해 fixed-name qualifier 계약을 보존한다. 두 default 모두 `@Primary`를 붙이지 않으며, unrelated `ResourcePatternResolver` bean은 S3-specific default backoff를 유발하지 않는다.
- [ ] protocol registrar는 Kotlin `companion object`의 `@Bean @JvmStatic` static factory로 선언해 configuration class가 일반 singleton처럼 조기 생성되지 않게 한다. 테스트 가능한 `internal class S3ProtocolResolverRegistrar`로 두고, registrar는 `ConfigurableApplicationContext`와 `ObjectProvider<S3ProtocolResolver>`만 받아 `postProcessBeanFactory`에서 provider로 resolver를 선택한 뒤 `addProtocolResolver`를 한 번 호출한다. `S3Client`를 registrar method signature에 직접 주입하거나 조회하지 않는다.
- [ ] registrar는 instance-local flag가 아니라 `ConfigurableListableBeanFactory`에 context-scoped guard singleton을 설치해 동일 context의 두 registrar instance·재진입·동시 호출에도 `addProtocolResolver`가 정확히 1회만 성공하게 한다. 다른 `ApplicationContext`는 각자 하나씩 등록한다. custom S3 resolver가 있으면 provider가 custom instance를 선택하고, unrelated resolver는 그대로 coexist한다.
- [ ] registrar 단위 테스트에서 동일 context의 두 registrar를 4개 executor task로 반복·동시 호출하고, 별도 context에서는 각각 1회 등록되는지 검증한다. latch는 start 30초/complete 120초 timeout을 사용하고, `finally`에서 executor를 `shutdownNow()`한 뒤 10초 안에 종료시킨다. 이 테스트는 resolver chain 등록만 다루며 listing state를 공유하지 않는다.
- [ ] `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 다음 줄을 S3 auto-configuration 바로 뒤에 추가한다.

```text
io.bluetape4k.aws.spring.s3.S3ResourceAutoConfiguration
```

- [ ] auto-config GREEN 명령을 실행하고, 기존 `S3AutoConfigurationTest`도 함께 실행해 S3 client/template/transfer bean 수가 변하지 않는지 확인한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceAutoConfigurationTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest" \
  -PskipAwsEmulatorTests=true --no-daemon
```

## 5. Task 4 — Floci-first emulator integration과 sequential smoke

- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLoaderAwsEmulatorTest.kt`를 추가한다. 기존 `AwsSpringBootTestEmulator.get("s3")`와 `S3CoroutinesTemplateAwsEmulatorTest`의 endpoint/region/path-style setup을 재사용하고, test class 이름 끝을 `AwsEmulatorTest`로 유지한다.
- [ ] context를 닫을 수 있는 fixture에서 한 bucket만 생성하고 다음 object를 업로드한다: `config/application.json`, `config/nested/application.json`, `config/readme.txt`, `other/application.json`. 호출마다 bucket 이름을 unique하게 만들되 다른 bucket을 검색하지 않는다.
- [ ] `ApplicationContext.getResource("s3://<bucket>/config/application.json")` exact read와 `S3ResourcePatternResolver.getResources("s3://<bucket>/config/*.json")`, `config/**/*.json`를 검증한다. `InputStream.use`로 caller close를 증명하고 결과 key가 `String.compareTo` 순서인지 확인한다.
- [ ] resolver가 `ListObjectsV2`에 delimiter를 넣지 않아 nested `**` 결과가 누락되지 않는지 real object 결과로 확인한다. Floci가 pagination boundary를 재현하도록 충분한 object를 추가하거나 SDK request page size fixture를 사용한다.
- [ ] Floci capability가 부족할 때만 동일 테스트를 `-Dbluetape4k.aws.emulator=localstack`으로 재실행하고, capability gap의 정확한 로그/오류를 검증 보고서에 기록한다. 이유 없는 LocalStack 전환은 허용하지 않는다.
- [ ] emulator fixture는 `try/finally`에서 context와 unique bucket의 test-owned 리소스를 정리하고, shared launcher/container를 테스트가 임의로 중지하지 않는다. 첫 Floci 실패 로그와 test report를 보존한 뒤, API capability gap으로 분류된 경우에만 LocalStack을 실행한다. system property를 직접 바꾸는 보조 fixture가 생기면 원래 값을 `finally`에서 복원한다.
- [ ] emulator cleanup 순서는 `object delete → bucket delete → ApplicationContext.close()`로 고정한다. context-managed `S3Client`는 `context.close()`가 정확히 한 번 close하고 테스트가 직접 다시 close하지 않는다. `@Bean(destroyMethod = "")` 외부 client fixture는 context close가 close하지 않으며 caller owner가 정확히 한 번 close한다. primary test failure가 있으면 cleanup 예외를 `addSuppressed`로 붙이고 primary failure를 유지한다. Floci 첫 실패 직후 `aws-spring-boot/build/verification/issue-463/floci-first-failure/`에 XML, stdout, capability-gap 분류 파일을 첫 실패 기준 데이터로 보존한 뒤에만 LocalStack fallback을 실행한다.
- [ ] 공유 Docker 자원을 위해 emulator test는 반드시 다음처럼 단독·순차 실행한다.

```bash
set -euo pipefail
FLOCI_DIR=aws-spring-boot/build/verification/issue-463/floci-first-failure
mkdir -p "$FLOCI_DIR"
FLOCI_STATUS=0
if ./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "*S3ResourceLoaderAwsEmulatorTest" \
  -Dbluetape4k.aws.emulator=floci --max-workers=1 --no-daemon \
  2>&1 | tee "$FLOCI_DIR/floci-stdout.txt"; then
  printf 'exit=0\ncapability-gap=false\n' > "$FLOCI_DIR/capability-gap-classification.txt"
else
  FLOCI_STATUS=$?
  FLOCI_XML="$(find aws-spring-boot/build/test-results/test -type f -name '*S3ResourceLoaderAwsEmulatorTest*.xml' -print -quit 2>/dev/null || true)"
  if [ -z "$FLOCI_XML" ]; then
    printf 'exit=%s\ncapability-gap=unknown\nreport=missing\n' "$FLOCI_STATUS" \
      > "$FLOCI_DIR/capability-gap-classification.txt"
    echo 'Floci failure produced no S3 emulator XML report' >&2
    exit "$FLOCI_STATUS"
  fi
  cp "$FLOCI_XML" "$FLOCI_DIR/"
  if rg -n 'NotImplemented|UnsupportedOperationException|UnknownOperation|501 Not Implemented' \
      "$FLOCI_DIR/floci-stdout.txt"; then
    printf 'exit=%s\ncapability-gap=true\n' "$FLOCI_STATUS" \
      > "$FLOCI_DIR/capability-gap-classification.txt"
  else
    printf 'exit=%s\ncapability-gap=false\n' "$FLOCI_STATUS" \
      > "$FLOCI_DIR/capability-gap-classification.txt"
  fi
  if ! rg -q '^capability-gap=true$' "$FLOCI_DIR/capability-gap-classification.txt"; then
    exit "$FLOCI_STATUS"
  fi
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests "*S3ResourceLoaderAwsEmulatorTest" \
    -Dbluetape4k.aws.emulator=localstack --max-workers=1 --no-daemon \
    2>&1 | tee "$FLOCI_DIR/localstack-stdout.txt"
fi
```

- [ ] `-PskipAwsEmulatorTests=true` 실행에서 이 class만 제외되고 non-emulator 테스트가 계속 실행되는지 확인한다. Docker가 없으면 테스트를 성공으로 표시하지 않고 `N/A` 또는 blocked 원인을 기록한다.

## 6. Task 5 — README와 manual을 같은 계약으로 갱신한다

- [ ] `aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`의 S3 feature section에 동일한 heading·예시 순서로 다음을 추가한다.
  - exact `applicationContext.getResource("s3://order-config/config/application.yml")`
  - `@Value("s3://order-config/config/application.yml")` exact 주입 예시와 `ApplicationContext.getResources(...)`는 이 pattern resolver로 자동 interception되지 않으므로 `s3ResourcePatternResolver`를 직접 주입해야 한다는 경계
  - 기본 이름 `s3ResourcePatternResolver`의 concrete `S3ResourcePatternResolver` 또는 `@Qualifier("s3ResourcePatternResolver") ResourcePatternResolver` 주입; custom replacement도 이 고정 이름을 사용
  - `getResources("s3://order-config/config/**/*.yml")` 예시
  - literal bucket 한 개, non-empty prefix, `*`/`?`/`**`만 지원한다는 제한
  - cross-bucket, `s3://bucket/*.json`, `s3://bucket/**`, object write/output stream 미지원
  - caller가 stream을 닫고 client/context 수명 안에서 resource를 사용하는 규칙
  - 기본 resolver/direct `S3Resource`는 parser guard를 제공하지만 custom resolver replacement와 직접 `S3Resource` 생성은 caller가 입력 검증·IAM을 책임진다는 경계
  - `s3ResourcePatternResolver`는 기본·custom S3 pattern replacement가 공유하는 예약 bean 이름이며, unrelated bean에 재사용하지 않는다.
- [ ] `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`와 `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`에 `## S3 ResourceLoader` 절을 같은 위치와 구조로 추가한다. exact protocol과 pattern bean의 사용 경로를 분리하고, `@Value` exact 주입을 포함하며 `ApplicationContext.getResources(...)`는 이 S3 pattern 경로를 자동 interception하지 않는다는 점을 명시한다. IAM 예시는 한 bucket ARN과 prefix로 제한한다. exact 읽기는 `s3:GetObject`(HEAD 포함)만 필요하고 pattern listing은 여기에 `s3:ListBucket`과 `s3:prefix` 조건이 추가된다는 최소 권한 차이를 구분하며, `s3:GetObject`는 같은 bucket/key prefix에만 허용한다. `s3:ListAllMyBuckets`와 cross-bucket 정책은 추가하지 않는 예시를 쓴다. `bluetape4k.aws.s3.enabled=false`가 resolver만이 아니라 전체 S3 auto-configuration을 끄는 기존 switch라는 점과 실제 IAM enforcement, custom/direct resolver의 caller 책임을 명시한다. secret, key, unrestricted exception text를 로그/metric에 넣지 않는 운영 규칙도 적는다.
- [ ] manual에는 `getResources`가 caller thread에서 모든 paginator page를 동기로 소비하고 내부 offload/coalescing/cache를 제공하지 않는다는 점, 짧은 prefix도 비용·heap을 늘릴 수 있어 non-empty prefix와 IAM prefix를 함께 설계해야 한다는 점을 적는다. 주입 client의 timeout/transport 설정이 그대로 적용되며 resolver가 retry/executor를 추가하지 않는다고 명시한다.
- [ ] manual의 `Sources`와 README link가 실제 source path를 가리키는지 확인한다. 새 dependency, release, awspring, cross-bucket feature를 문서에 추가하지 않는다.
- [ ] 한국어 문서는 literal translation이 아니라 caller가 root listing 거부·권한 전파·수명 경계를 오해하지 않도록 자연스럽게 작성한다. 구현 KDoc과 reader-facing comments도 한국어로 작성한다.

## 7. Task 6 — 통합 검증, contract audit, rollback 증거

- [ ] 다음 순서로 targeted parser/unit → `ApplicationContextRunner` context → performance stress → Floci → 전체 module test를 실행한다. 각 단계의 실패 로그와 결과를 보존해 뒤 단계가 앞 단계의 원인을 덮지 않게 하며, 각 명령의 `BUILD SUCCESSFUL` 또는 정확한 실패/skip 원인을 기록한다.

```bash
set -euo pipefail
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceLocationParserTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ProtocolResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourcePatternResolverTest" \
  --tests "io.bluetape4k.aws.spring.s3.S3ResourceAutoConfigurationTest" \
  -PskipAwsEmulatorTests=true --no-daemon
mkdir -p aws-spring-boot/build/verification/issue-463
ISSUE463_INIT="$(mktemp -t issue-463-gradle)"
trap 'rm -f "$ISSUE463_INIT"' EXIT
printf '%s\n' \
  'gradle.projectsEvaluated { allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { jvmArgs = (jvmArgs ?: []).findAll { !it.startsWith("-Xmx") } + "-Xmx256m"; maxHeapSize = "256m"; testLogging { showStandardStreams = true } } } }' \
  > "$ISSUE463_INIT"
./gradlew --init-script "$ISSUE463_INIT" :bluetape4k-aws-spring-boot:test \
  --tests "*S3ResourcePatternResolverPerformanceTest" \
  -PskipAwsEmulatorTests=true --no-daemon --rerun-tasks \
  | tee aws-spring-boot/build/verification/issue-463/performance-run.log
rg -n 'issue-463-test-max-memory=' \
  aws-spring-boot/build/verification/issue-463/performance-run.log
# Execute the bounded Floci wrapper from Task 4 here; it is the sole Floci entrypoint
# and preserves stdout/XML/classification before any capability-gap fallback.
./gradlew :bluetape4k-aws-spring-boot:test \
  -PskipAwsEmulatorTests=true --no-daemon
./gradlew detekt
./gradlew build -x test --parallel
./gradlew :bluetape4k-aws-spring-boot:tasks --all --no-daemon | grep -E 'generatePom|generateMetadata'
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
ruby scripts/manual/manual_contract_test.rb
```

- [ ] `git diff --check`와 writer terminology audit를 실행한다.

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  aws-spring-boot/README.md \
  aws-spring-boot/README.ko.md \
  docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md \
  docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md \
  docs/superpowers/plans/2026-08-23-issue-463-s3-resource-loader.md
```

- [ ] dependency contract를 확인한다. `aws-spring-boot/build.gradle.kts`에 새 dependency가 없는지 diff로 확인하고, `./gradlew :bluetape4k-aws-spring-boot:generatePomFileForBluetapeAwsPublication :bluetape4k-aws-spring-boot:generateMetadataFileForBluetapeAwsPublication --no-configuration-cache --no-build-cache --no-daemon`으로 `aws-spring-boot/build/publications/BluetapeAws/pom-default.xml`과 `module.json`을 생성한다. 두 파일의 존재를 먼저 확인하고, POM validator와 `grep` 결과를 별도 검증 산출물로 남겨 `software.amazon.awssdk:s3`가 runtime dependency로 노출되지 않고 기존 compileOnly 계약이 유지되는지 확인한다. publish는 실행하지 않는다.

```bash
set -euo pipefail
POM=aws-spring-boot/build/publications/BluetapeAws/pom-default.xml
MODULE=aws-spring-boot/build/publications/BluetapeAws/module.json
test -f "$POM" && test -f "$MODULE"
mkdir -p aws-spring-boot/build/verification/issue-463
ruby scripts/publication/validate_poms.rb \
  | tee aws-spring-boot/build/verification/issue-463/pom-validation.txt
if rg -n -U '<artifactId>s3</artifactId>\s*<version>[^<]+</version>\s*<scope>runtime</scope>' "$POM"; then
  echo 'runtime S3 dependency leaked into the published POM' >&2
  exit 1
fi
rg -n 'software\.amazon\.awssdk:s3|<artifactId>s3</artifactId>|runtime' "$POM" "$MODULE" \
  | tee aws-spring-boot/build/verification/issue-463/pom-metadata-grep.txt || true
```
- [ ] 이 기능은 새 모듈·coroutine suspend API·JDK preview API를 추가하지 않는다. 따라서 settings/BOM/coverage aggregation과 coroutine cancellation test는 N/A로 명시하고, 기존 coroutine S3 test가 전체 module test에서 회귀 없이 통과하는 것을 보조 증거로 사용한다. synchronous `Resource` API의 blocking은 주입된 sync `S3Client` transport 설정을 따르며 새 executor/retry/cancellation layer를 만들지 않는다.
- [ ] 새 모듈·test resource·settings 등록·BOM alias·coverage aggregation·CI/Nightly workflow 변경은 없다. 기존 `.github/workflows/ci.yml`의 `bluetape4k-aws-spring-boot` test 경로가 새 `*Test`를 자동 수집하는지 read-only로 확인하고, path filter로 빠지는 job이 없는지 확인한다. 필요 없는 workflow/coverage 변경은 추가하지 않는다.
- [ ] CI/Nightly owner와 evidence를 `debop`(Issue #463 assignee)로 고정하고, blocker는 Issue #463/Epic #500에 기록한다. `.github/workflows/ci.yml`의 `test-aws-spring-boot`(path filter `aws-spring-boot/**`, timeout 10m, expected artifacts `coverage-aws-spring-boot`·`test-results-aws-spring-boot`)와 `.github/workflows/nightly-tests.yml`의 동일 job/10m scheduled·manual full 경로를 read-only 확인한다. `coverage-report`가 해당 coverage artifact를 요구하는지 확인하고 workflow mutation은 하지 않는다.
- [ ] 50 page × 1,000 key performance test의 constrained-heap wall-clock과 stall guard가 기존 CI/Nightly 10분 timeout 안에 들어오는지 로컬 증적으로 기록한다. 초과하면 workflow timeout을 임의로 늘리지 않고 fixture 규모 조정 또는 별도 spec/CI 승인으로 되돌린다.
- [ ] `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolverPerformanceTest.kt`를 추가해 50 page × 1,000 synthetic key, duplicate/no-match, pattern-side matcher 준비, 호출 시작 barrier와 completion latch를 이용한 4개 이상 동시 호출에서 page 소비 횟수·결과 순서·호출 독립성·추가 retry 없음·메모리 상한 없는 all-page 계약을 검증한다. 기존 test 관례인 `CountDownLatch`/`ExecutorService`를 사용하고 별도 benchmark plugin을 추가하지 않는다. start barrier 30초, stall guard 120초, worker 종료 10초를 고정한다. 시간 단정은 환경 편차로 flaky하지 않게 하되 stall guard timeout은 넉넉하게 고정한다. 테스트는 `issue-463-test-max-memory=<bytes>`를 stdout에 기록하고 실제 test worker의 `Runtime.maxMemory()`가 320 MiB 이하인지 assertion으로 고정한다. 저장소 공통 `-Xmx4G` 설정을 바꾸지 않고, 아래 임시 Gradle init script를 이 실행에만 주입해 `-Xmx256m`을 마지막 JVM argument로 추가하며 실행 로그에서 effective heap 증거를 보존한다.
- [ ] 임시 init script는 `gradle.projectsEvaluated` 이후 적용해 root build script의 `-Xmx4G`가 먼저 추가된 뒤에도 기존 `-Xmx*` 인자를 제거하고 `maxHeapSize = "256m"` 및 마지막 `-Xmx256m`을 설정한다. 따라서 저장소 기본 heap 설정은 변경하지 않고 이 performance 실행만 opt-in으로 제한한다.

```bash
set -euo pipefail
mkdir -p aws-spring-boot/build/verification/issue-463
ISSUE463_INIT="$(mktemp -t issue-463-gradle)"
trap 'rm -f "$ISSUE463_INIT"' EXIT
printf '%s\n' \
  'gradle.projectsEvaluated { allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { jvmArgs = (jvmArgs ?: []).findAll { !it.startsWith("-Xmx") } + "-Xmx256m"; maxHeapSize = "256m"; testLogging { showStandardStreams = true } } } }' \
  > "$ISSUE463_INIT"
./gradlew --init-script "$ISSUE463_INIT" :bluetape4k-aws-spring-boot:test \
  --tests "*S3ResourcePatternResolverPerformanceTest" \
  -PskipAwsEmulatorTests=true --no-daemon --rerun-tasks \
  | tee aws-spring-boot/build/verification/issue-463/performance-run.log
rg -n 'issue-463-test-max-memory=' \
  aws-spring-boot/build/verification/issue-463/performance-run.log
```
- [ ] 이 performance test는 승인된 all-page 동작을 검증하는 것이며 결과 상한을 추가하지 않는다. 4개 동시 호출은 각 호출이 독립 paginator와 local collection을 사용하고 다른 호출의 result/state를 공유하지 않는지만 확인한다.
- [ ] performance test는 timeout·assertion 실패 여부와 관계없이 `finally`에서 executor를 `shutdownNow()`하고 10초 안에 `awaitTermination`을 확인한다. cleanup 실패는 primary test failure에 suppressed로 붙이고 worker thread를 남기지 않는다.
- [ ] pattern resolver timeout/transport `SdkException` fixture가 단 한 번의 paginator 호출 뒤 `IOException` cause로 전파되는지 검증하고, 새 API가 synchronous `Resource` surface이므로 coroutine cancellation test는 N/A로 명시한다.
- [ ] 운영 surface에는 새 health indicator, metric tag, unbounded log를 추가하지 않는다. listing 오류는 bounded bucket/prefix 메시지와 cause만 남기고, rollback은 새 resolver source/import/test/docs를 함께 되돌리는 단위로 확인한다.
- [ ] acceptance traceability 표의 모든 행에 실제 테스트 파일·명령·결과를 채운다. AWS credential 기반 real smoke는 자격 증명이 없으면 `N/A`로 표시하되 Floci/mock/fixture 검증을 생략하지 않는다.
- [ ] rollback check를 수행한다. 새 resolver source 4개, auto-configuration imports 한 줄, 새 테스트, README/manual 변경을 하나의 feature diff로 식별할 수 있어야 하며, rollback 시 기존 `S3Resource.kt`, `S3ObjectLocation.kt`, `S3AutoConfiguration.kt`, S3 client/config를 건드리지 않고 새 surface만 함께 되돌릴 수 있어야 한다.
- [ ] implementation commit은 한국어 Lore protocol을 사용한다. intent line은 “왜”를 설명하고 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailers를 포함한다. 이 계획 commit은 구현과 분리하고, PR/merge/release/tag는 이 계획의 종료 조건에 포함하지 않는다.

## 8. 종료 조건과 이후 handoff

- [ ] 코드·테스트·문서·auto-config import가 모두 구현되고 Required checks가 `7/7` 또는 각 N/A와 차단 사유를 명시한다.
- [ ] P0/P1 오류, 미검증 security/lifecycle/permission 경계, root/cross-bucket listing 경로, secret 노출 경로가 0개다.
- [ ] 구현 결과와 fresh verification evidence를 바탕으로 별도 code review를 요청한다. CI가 path-filtered/skipped이면 green으로 간주하지 않고 실제 장기 job과 coverage를 확인한다.
- [ ] merge는 이 계획의 범위가 아니다. exact validated head/base, CI, review, mergeability, metadata를 fresh-read한 뒤 사용자의 별도 명시적 merge 승인을 받아야 한다.

## 예상 변경 파일 목록

추가:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLocationParser.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ProtocolResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceAutoConfiguration.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLocationParserTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ProtocolResolverTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolverTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourcePatternResolverPerformanceTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceAutoConfigurationTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ResourceLoaderAwsEmulatorTest.kt`

수정:

- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/README.md`
- `aws-spring-boot/README.ko.md`
- `docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md`
- `docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md`

수정하지 않음:

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Resource.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ObjectLocation.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt`
- `aws-spring-boot/build.gradle.kts` 및 dependency catalog
- release/publish/tag/GitHub PR/merge surface
