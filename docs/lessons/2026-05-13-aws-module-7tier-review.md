# AWS Module 7-Tier Review

## Context

The first module-sliced 7-tier review pass started with `:aws` after merging
the open PRs into `develop`. The review focused on Tier 5
tests/types/silent-failure risks and Tier 6 performance/stability risks before
moving to sibling modules.

- Scope: `aws/src/test/kotlin`
- Files touched: 14
- Review rounds so far: 1 local 6-R/7-tier pass, 2 Claude advisor passes
- Review findings fixed: P0=0, P1=0, P2=3

## Decision

Touched `:aws` tests should use repository-native assertions and real IO test
dispatchers consistently. Fixed sleeps around LocalStack/DynamoDB eventual
state are not acceptable when `bluetape4k-junit5` already provides
`untilSuspending`.

## Outcome

- Replaced remaining touched JUnit/kotlin.test exception assertions with
  `io.bluetape4k.assertions.assertFailsWith`.
- Converted 26 IO-backed coroutine test bodies from `runTest` to
  `runSuspendIO`.
- Kept `runTest` in non-IO coroutine support tests and mock-only coroutine
  wrapper tests after Claude flagged the broader conversion as convention drift.
- Replaced 9 fixed DynamoDB async sleeps with Awaitility
  `untilSuspending` checks for table activation and item visibility.
- Preserved existing runtime behavior; this PR is test-hardening only.

## Verification

- `./gradlew :aws:compileTestKotlin`
- `./gradlew :aws:test`
- `git diff --check`
- `./gradlew :aws:detekt` attempted, but `:aws` has no `detekt` task.
- `./gradlew detekt` completed as `NO-SOURCE`.
- Forbidden assertion/fixed-delay scan:
  `rg "org\\.junit\\.jupiter\\.api\\.assertThrows|kotlin\\.test\\.assertFailsWith|assertThrows<|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(" aws/src/test/kotlin`
- Claude advisor review:
  `.omx/artifacts/ask-claude-code-review-aws-20260513-183825.md`
- Claude advisor re-review:
  `.omx/artifacts/ask-claude-code-review-aws-rereview-20260513-184404.md`

Result: 252 `:aws` tests passed, 2 pending, and the forbidden scan returned 0
matches. Claude reported P0=0, P1=0 and approved; its mock-only `runSuspendIO`
P2 convention finding was fixed before PR creation. Claude re-review reported
P0=0, P1=0, P2=0, P3=3 and approved; the cheap import-order P3 was cleaned up.

## Future Guard

For AWS module reviews, treat test runtime choice as a Tier 5/Tier 6 signal:
`runTest` is for virtual-time or non-IO coroutine tests, while LocalStack,
AWS SDK async clients, Ktor, and real network/file work should use
`runSuspendIO`. Replace fixed sleeps with `untilSuspending` or service-specific
state polling in the same patch that touches the test.
