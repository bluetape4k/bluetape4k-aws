# AWS Spring Boot Module 7-Tier Review

## Context

The third module-sliced 7-tier review pass covered `:aws-spring-boot` after the
`:aws` and `:aws-kotlin` review PRs were merged. The pass focused on Tier 5
tests/types/silent-failure and Tier 6 performance/stability for Spring Boot 4
auto-configuration and LocalStack-backed integration tests.

- Scope: `aws-spring-boot/src/test/kotlin`
- Files touched: 8
- Review rounds so far: 1 local 6-R/7-tier pass, 1 Claude advisor pass
- Review findings fixed: P0=0, P1=0, P2=3

## Decision

Spring Boot LocalStack tests should use the same backend-aware test runtime as
the lower-level AWS modules: `runSuspendIO` for suspend AWS calls, Awaitility
`untilSuspending` when a suspend poll is actually waiting for asynchronous
state, and `LocalStackServer.Launcher.getLocalStack(...)` instead of ad hoc
`start()`/`stop()` lifecycle management in each test class.

## Outcome

- Replaced `runTest` / `runBlocking` usages in touched `:aws-spring-boot` tests
  with `runSuspendIO`.
- Replaced 7 direct `LocalStackServer().withServices(...)` test containers with
  `LocalStackServer.Launcher.getLocalStack(...)`.
- Removed direct per-class `localStack.start()` / `localStack.stop()` calls and
  let the launcher + shutdown queue own container lifecycle.
- Converted the SQS listener post-delete suspend poll from a one-shot blocking
  receive into Awaitility `untilSuspending`.
- Simplified the queue creation bridge after Claude flagged the original
  nullable capture as awkward for a future generic `runSuspendIOReturning<T>`
  helper.
- Left production `KmsTextEncryptor` `runBlocking(Dispatchers.IO)` untouched
  because it adapts Spring Security's synchronous `TextEncryptor` contract.

## Verification

- `./gradlew :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test`
- `./gradlew detekt` completed as `NO-SOURCE`.
- `git diff --check`
- Forbidden assertion/fixed-delay/runtime scan:
  `rg "kotlin\\.test\\.|org\\.junit\\.jupiter\\.api\\.Assertions|assertThrows|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(|runTest|runBlocking|LocalStackServer\\(\\)\\.withServices" aws-spring-boot/src/test/kotlin`
- Production/test concurrency scan:
  `rg "GlobalScope|runBlocking\\(|Thread\\.sleep|delay\\(|synchronized\\(|@Synchronized|runCatching\\s*\\{|Dispatchers\\.Default|Dispatchers\\.IO|CancellationException|while\\s*\\(true\\)" aws-spring-boot/src/main/kotlin aws-spring-boot/src/test/kotlin`
- Claude advisor review:
  `.omx/artifacts/ask-claude-code-review-aws-spring-boot-20260513-202644.md`

Result: 68 `:aws-spring-boot` tests passed. The forbidden test scan returned 0
matches. The production scan only surfaced reviewed boundaries: the synchronous
`TextEncryptor` adapter, SQS IO coroutine scopes, explicit cancellation
handling, and `S3Resource.exists()`'s non-suspend `runCatching`. Claude reported
P0=0, P1=0, P2=2, P3=2, APPROVE. One P2 queue helper readability finding was
fixed; the remaining P2 is accepted as a bounded lifecycle tradeoff because the
launcher pattern intentionally keeps started LocalStack containers under the
shared shutdown queue until the Gradle test JVM exits.

## Future Guard

For `:aws-spring-boot`, do not use `runTest` or plain `runBlocking` around real
AWS SDK, LocalStack, or Spring auto-configuration calls. Prefer `runSuspendIO`
for suspend AWS operations, and use `untilSuspending` when the test is polling a
suspend condition. Prefer `LocalStackServer.Launcher.getLocalStack(...)` so
tests do not own container shutdown independently from the shared launcher.

Keep `KmsTextEncryptor`'s blocking bridge under review when changing the public
encryption API, but do not remove it casually: `TextEncryptor` is intentionally
synchronous.
