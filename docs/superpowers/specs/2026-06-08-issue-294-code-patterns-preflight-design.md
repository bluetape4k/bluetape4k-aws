# Issue 294 Code Patterns Preflight Design

## 목적

0.4.0 배포 전에 `bluetape4k-aws` 전체 코드에서 `bluetape4k-code-patterns` 위반과
bluetape4k ecosystem 재사용 누락을 선별해 수정한다. 사용자 요청의 예시는 대표 신호이며,
이번 작업은 repo-wide scan을 기준으로 P0/P1 및 high-confidence P2 개선을 우선 처리한다.

## 범위

- 대상 repo: `bluetape4k-aws`
- 대상 branch: `refactor/issue-294-code-patterns-preflight`
- 대상 모듈:
  - published modules: `aws-java`, `aws-kotlin`, `aws-exposed`, `aws-ktor`, `aws-spring-boot`
  - examples/test code는 touched scope 또는 명확한 pattern violation만 포함
- 제외:
  - 대규모 public API 재설계
  - 새 dependency 도입
  - Testcontainers backed full matrix 병렬 실행
  - release/publish workflow 실행

## 현재 스캔 증거

### Data Class Serialization

Production `src/main` data class scan:

| Module | Data classes | Missing `Serializable` | Serializable but UID gap |
|---|---:|---:|---:|
| `aws-java` | 9 | 6 | 3 |
| `aws-kotlin` | 7 | 5 | 1 |
| `aws-exposed` | 7 | 1 | 0 |
| `aws-ktor` | 23 | 17 | 0 |
| `aws-spring-boot` | 60 | 21 | 1 |

Rule: production data classes in published modules should implement `java.io.Serializable`
and define `serialVersionUID` when they form public/configuration/value objects or are likely
to cross serialization/cache/test fixture boundaries. Private implementation records may be
fixed when low-risk; otherwise record follow-up.

### Constructor Validation And Factory Guards

Confirmed concrete target:

- `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsSecretString.kt`
  - public constructor validates in `init`
  - `AwsSecretString.of(value)` delegates without pre-guard
  - `awsSecretStringOf(value)` delegates without pre-guard

Rule: value objects that validate constructor inputs should prefer a private constructor plus
`companion object operator fun invoke(...)`, and all public factories should guard before
construction.

### Coroutine Blocking Boundaries

Current scan found:

- `runInterruptible` already used in Ktor runtime close paths.
- Several paths use `withContext(Dispatchers.IO) { runInterruptible { ... } }`.
- Ktor plugin lifecycle code uses bounded `runBlocking(Dispatchers.IO)` because Ktor stop hooks
  are synchronous. This is a reviewed exception unless a local async lifecycle API is available.

Rule: direct blocking cleanup in suspend functions should use `runInterruptible(Dispatchers.IO)`.
Synchronous framework lifecycle bridges may use `runBlocking(Dispatchers.IO)` only when the
framework hook is not suspend and the call is bounded.

### Ecosystem Reuse Gaps

Scan signals:

- `AwsJdbcDataSourceFactory` uses Hikari directly and a DriverManager-backed wrapper. Hikari
  construction should use `bluetape4k-jdbc` helpers when the catalog can expose that dependency.
  The RDS IAM DriverManager-backed wrapper needs a separate reusable abstraction because it must
  inject a refreshed token for every physical connection.
- Tests still import raw JUnit/kotlin assertion APIs in several files:
  - `HttpClientEngineProviderTest`
  - `CrtHttpEngineSupportTest`
  - `SesV2ClientExtensionsMockTest`
  - `KmsEncryptedFieldCodecTest`
  - `SqsClientExtensionsTest`
  - `SqsExamples`
  - Kinesis tests using `kotlin.test.assertFailsWith`
- UUID/random use appears in tests/examples and at least one DSL nonce helper. Production nonce
  generation should be reviewed for deterministic behavior and ecosystem utility reuse; test names
  may stay as UUID when uniqueness as UUID is the behavior being tested, otherwise prefer
  `Base58.randomString(8)` or established test helpers.

## 우선순위

| Priority | Category | Required action |
|---|---|---|
| P0 | Behavior/security regression | Fix before PR; none known yet |
| P1 | Pattern violation that can cause invalid state, cancellation leak, or public API misuse | Fix before PR |
| P2 | Broad but low-risk consistency issue | Fix if mechanical and covered by compile/tests, otherwise create follow-up |
| P3 | Cosmetic/style-only issue | Defer unless touched |

## 설계 결정

1. `AwsSecretString`는 private constructor + companion `operator fun invoke` + guarded `of`/top-level factory로 개선한다.
2. Published module production data classes는 compile-safe 범위에서 `Serializable`과 `serialVersionUID`를 보강한다.
3. Coroutine cleanup paths는 `runInterruptible(Dispatchers.IO)` 형태로 단순화하되, synchronous framework lifecycle bridges는 현재 예외로 남기고 review artifact에 근거를 남긴다.
4. Raw assertion imports는 touched 또는 high-signal files부터 `bluetape4k-assertions`로 교체한다.
5. JDBC/DataSource raw usage는 Hikari construction처럼 helper가 정확한 영역만 이 PR에서 치환하고,
   RDS IAM per-connection DriverManager path는 follow-up issue #295로 전환한다.

## 수용 기준

- #294 issue body와 spec/plan이 repo-wide scope로 일치한다.
- Scan evidence가 review artifact에 남는다.
- P0/P1 findings는 0개가 될 때까지 수정한다.
- Targeted Gradle checks가 통과한다.
- PR body 마지막 섹션은 `## DoD Status`다.
