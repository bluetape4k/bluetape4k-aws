# Issue #4 SNS Spring Boot Completion Lesson

## Context

Issue #4 added SNS support to `aws-spring-boot`: Boot 4 auto-configuration, coroutine operations, topic lookup, FIFO publishing, and SNS-to-SQS fanout verification.

PR: <https://github.com/bluetape4k/bluetape4k-aws/pull/55>

Commit: `0793e10 feat: Spring Boot에서 SNS 발행을 코루틴으로 제공`

The PR was opened as a draft against `develop`, assigned to `debop`, and kept issue #13 explicitly out of scope. Issue #13 remains the place for the full SQS-SNS application example.

## Decision

Follow the existing SQS Spring Boot pattern instead of introducing a separate abstraction style:

- compile-only AWS SNS SDK dependency in production,
- string-based `@ConditionalOnClass`,
- `@ConditionalOnMissingBean` back-off for SDK client and operations,
- coroutine template over `SnsAsyncClient`,
- LocalStack integration tests.

For new Spring Boot library features, strict design gates paid off:

- write the spec before implementation,
- review the spec with an external advisor,
- write the implementation plan after the spec is stable,
- review the plan before editing production code,
- run a final strict code review after tests pass.

## Outcome

The implementation includes:

- `SnsAutoConfiguration` for `SnsAsyncClient` and `SnsOperations`,
- `SnsOperations`, `SnsCoroutinesTemplate`, `SnsProperties`, and `SnsPublishRequest`,
- standard topic creation, FIFO topic creation, topic ARN lookup, and publish,
- FIFO validation before AWS calls where the contract is knowable locally,
- SNS-to-SQS fanout integration verification,
- README and Korean README updates,
- durable spec, plan, and this lesson document.

The public API stayed request-object based for publish to avoid ambiguous same-type positional parameters.

## Verification

- `./gradlew :aws-spring-boot:compileKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.*'` — 16 passing
- `./gradlew :aws-spring-boot:test` — 49 passing
- Claude advisor reviewed the spec, plan, and final diff with no remaining blockers.

Review artifacts were saved under `.omx/artifacts/` during the worktree session:

- `ask-claude-issue-4-sns-spec-review.md`
- `ask-claude-issue-4-sns-plan-review.md`
- `ask-claude-issue-4-sns-code-review.md`
- `ask-claude-issue-4-sns-final-code-review.md`

## What Worked

- Starting from the SQS implementation avoided unnecessary new abstractions.
- The spec review caught a real FIFO semantic bug before implementation: FIFO-only publish fields must be rejected for standard topics.
- The plan review caught missing implementation placement details: client bean lifecycle, configured-topic validation location, and `SnsPublishRequest.init` validation.
- A single executable LocalStack fanout test gave better evidence than README-only fanout documentation.
- Running targeted SNS tests first made failures faster to isolate before running the full module suite.

## What To Watch

- Spring Boot property binding treats dots in map keys specially. Use bracket notation in tests and examples when the key is a topic name like `orders.fifo`: `topics[orders.fifo]`.
- LocalStack SNS-to-SQS fanout requires queue policy setup. If this test fails in a future environment, inspect queue policy and subscription confirmation before weakening the test.
- `@file:Suppress("DEPRECATION")` in LocalStack tests should stay scoped to the file and should not hide unrelated deprecations in production code.
- Keep issue #13 separate. Do not expand a library feature PR into a full example application unless the issue scope changes.

## Future Guidance

- For Spring Boot map keys containing dots, use bracket notation in property-value tests, such as `topics[orders.fifo]`.
- Keep full application examples separate from library feature PRs when a follow-up issue owns the example module.
- Use one executable LocalStack fanout test when adding publish-side integration features; it provides better evidence than README-only snippets.
- For bluetape4k new-feature PRs that add public APIs, preserve the Spec -> Plan -> Implementation -> Tests -> Code Review sequence even when the implementation looks straightforward.
