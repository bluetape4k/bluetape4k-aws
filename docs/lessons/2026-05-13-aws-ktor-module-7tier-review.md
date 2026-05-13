# AWS Ktor Module 7-Tier Review

## Context

The fourth module-sliced 7-tier review pass covered `:aws-ktor` after the
`:aws`, `:aws-kotlin`, and `:aws-spring-boot` review PRs were merged. The pass
focused on Tier 5 tests/types/silent-failure and Tier 6 performance/stability
for Ktor client MockEngine tests and SQS consumer runtime tests.

- Scope: `aws-ktor/src/test/kotlin`
- Files touched: 5
- Review rounds so far: 1 local 6-R/7-tier pass, 1 Claude advisor pass
- Review findings fixed: P0=0, P1=0, P2=2

## Decision

Ktor tests should use JUnit 5 and bluetape4k assertion APIs consistently.
Suspend MockEngine tests do not need virtual time, so `runSuspendIO` is an
acceptable common runner and keeps this module aligned with the rest of the AWS
review slices.

## Outcome

- Replaced remaining `kotlin.test.Test` imports with JUnit 5 `@Test`.
- Replaced remaining `kotlin.test.assertFailsWith` imports with
  `io.bluetape4k.assertions.assertFailsWith`.
- Replaced MockEngine `runTest` usages with `runSuspendIO`.
- Replaced touched boolean comparisons such as `shouldBeEqualTo true/false`
  with `shouldBeTrue()` / `shouldBeFalse()`.
- Sorted the touched assertion imports after Claude flagged an import-order P3.
- Left production `SqsConsumer` `runBlocking(Dispatchers.IO)` untouched because
  Ktor monitoring events are synchronous and the code already documents that
  shutdown drains SQS handlers on IO.
- Left production retry/heartbeat `delay(...)` untouched because those are
  bounded suspend timers in runtime logic, not test sleeps.

## Verification

- `./gradlew :aws-ktor:compileTestKotlin`
- `./gradlew :aws-ktor:test`
- `./gradlew detekt` completed as `NO-SOURCE`.
- `git diff --check`
- Forbidden assertion/runtime scan:
  `rg "kotlin\\.test\\.|kotlinx\\.coroutines\\.test\\.runTest|runTest\\(|shouldBeEqualTo true|shouldBeEqualTo false|org\\.assertj|org\\.amshove\\.kluent|assertThat\\(|org\\.junit\\.jupiter\\.api\\.Assertions|assertThrows|delay\\(" aws-ktor/src/test/kotlin`
- Production runtime scan reviewed:
  `rg "runBlocking\\(|delay\\(|GlobalScope|synchronized\\(|@Synchronized|runCatching\\s*\\{" aws-ktor/src/main/kotlin`
- Claude advisor review:
  `.omx/artifacts/ask-claude-code-review-aws-ktor-20260513-204834.md`

Result: 33 `:aws-ktor` tests passed. The forbidden test scan returned 0
matches. The production scan surfaced only reviewed boundaries: Ktor's
synchronous stopping event bridge and SQS runtime retry/heartbeat timers. Claude
reported P0=0, P1=0, P2=0, P3=1, APPROVE; the P3 import-order nit was fixed
before PR creation.

## Future Guard

For `:aws-ktor`, keep MockEngine and Ktor plugin tests on JUnit 5 plus
bluetape4k-assertions. Prefer `runSuspendIO` unless a test specifically needs
virtual time. Continue using `untilSuspending` for suspend polling in consumer
runtime tests, and distinguish runtime retry/heartbeat `delay(...)` from flaky
test sleeps.
