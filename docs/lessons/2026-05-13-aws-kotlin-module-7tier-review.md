# AWS Kotlin Module 7-Tier Review

## Context

The second module-sliced 7-tier review pass covered `:aws-kotlin` after the
`:aws` pass and PR #64 merge. The review focused on the same Tier 5
tests/types/silent-failure and Tier 6 performance/stability signals.

- Scope: `aws-kotlin/src/test/kotlin`
- Files touched: 37
- Review rounds so far: 1 local 6-R/7-tier pass, 2 Claude advisor passes
- Review findings fixed: P0=0, P1=0, P2=4

## Decision

AWS Kotlin SDK tests should follow the same test policy as the Java SDK module:
repository-native assertions, real IO dispatchers for LocalStack-backed suspend
tests, and no fixed coroutine sleeps after already-observed SDK operations.

## Outcome

- Replaced 36 `kotlin.test.assertFailsWith` imports with
  `io.bluetape4k.assertions.assertFailsWith`.
- Replaced the touched `kotlin.test.assertNull` / `assertNotNull` usages in
  `DynamoDbModelSupportTest` with `shouldBeNull` / `shouldNotBeNull` after
  Claude caught the narrower scan gap.
- Converted 21 KMS/STS LocalStack-backed suspend tests from `runTest` to
  `runSuspendIO`.
- Removed 1 fixed S3 `delay(1.seconds)` after `getAll` had already collected
  and asserted the expected object bodies.
- Preserved `runTest` in lifecycle tests that intentionally assert explicit
  10-second close timeouts without LocalStack network calls.

## Verification

- `./gradlew :aws-kotlin:compileTestKotlin`
- `./gradlew :aws-kotlin:test`
- `git diff --check`
- `./gradlew detekt` completed as `NO-SOURCE`.
- Forbidden assertion/fixed-delay scan:
  `rg "kotlin\\.test\\.|org\\.junit\\.jupiter\\.api\\.assertThrows|assertThrows<|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(" aws-kotlin/src/test/kotlin`
- Claude advisor review:
  `.omx/artifacts/ask-claude-code-review-aws-kotlin-20260513-200423.md`
- Claude advisor re-review:
  `.omx/artifacts/ask-claude-code-review-aws-kotlin-rereview-20260513-200734.md`
- Residual `runTest` check:
  `rg "import kotlinx\\.coroutines\\.test\\.runTest|runTest\\(" aws-kotlin/src/test/kotlin -g '*.kt'`

Result: 443 `:aws-kotlin` tests passed, 5 pending, and the forbidden scan
returned 0 matches. Claude reported P0=0, P1=0, P2=1, P3=1 on first pass; the
P2 leftover `kotlin.test` assertion finding was fixed before PR creation.
Claude re-review reported P0=0, P1=0, P2=0, P3=1 and approved. Residual
`runTest` usage is limited to `ClientLifecycleTest`, which explicitly documents
that it does not call LocalStack or network APIs and asserts 10-second close
timeouts.

## Future Guard

For `:aws-kotlin`, do not treat native AWS Kotlin SDK suspend APIs as virtual
time just because the API is suspend-first. If a test talks to LocalStack,
creates AWS clients against emulator endpoints, or exercises real SDK IO, use
`runSuspendIO`. Keep `runTest` for pure lifecycle/timeout or mock-only tests
only when the test is intentionally not exercising backend IO.

Also scan for the whole `kotlin.test.` prefix, not only `assertFailsWith`,
because touched files can still retain `assertNull` or `assertNotNull`.
