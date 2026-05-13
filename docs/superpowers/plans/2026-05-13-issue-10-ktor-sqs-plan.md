# Issue #10 Ktor SQS Consumer / Publisher Plan

Date: 2026-05-13
Spec: `docs/superpowers/specs/2026-05-13-issue-10-ktor-sqs-design.md`
Issue: https://github.com/bluetape4k/bluetape4k-aws/issues/10

## Tasks

### T0 - Discovery

- [x] Inspect existing `aws-ktor` SigV4/S3 patterns.
- [x] Inspect existing `aws` SQS coroutine extensions.
- [x] Check Ktor 3 plugin lifecycle docs.
- [x] Check AWS SDK v2 SQS async API assumptions.
- [x] Run external advisor review and fold in lifecycle/backoff/DLQ/shutdown findings.

### T1 - Build Wiring

- [x] Add `software.amazon.awssdk:sqs` to `aws-ktor` compile/test dependencies.
- [x] Keep server dependencies `compileOnly` and test-only where possible.

### T2 - Runtime API

- [x] Add SQS plugin config and model classes.
- [x] Add message converter interface and default converter.
- [x] Add `SqsConsumerRuntime` with start/stop/send and polling.
- [x] Add receive-loop `SqsPollBackoff`.
- [x] Add graceful shutdown timeout and optional visibility heartbeat.
- [x] Validate queue identity, concurrency, receive bounds, visibility bounds, DLQ/failure visibility conflicts, and heartbeat constraints.

### T3 - Ktor Plugin

- [x] Add `SqsConsumer` plugin using Ktor lifecycle events.
- [x] Use `ApplicationStarted` for start and `ApplicationStopping` for drain/stop.
- [x] Add `SqsKtorPlugin` alias.
- [x] Store runtime in an application attribute for explicit publish access.

### T4 - Tests

- [x] Add config validation/unit tests.
- [x] Add Testcontainers LocalStack SQS round-trip tests.
- [x] Add multi-coroutine/multithread publisher test with Awaitility.
- [x] Add graceful shutdown cancellation test with Awaitility.
- [x] Add manual DLQ forwarding test with metadata assertion.
- [x] Add queue-name resolution retry regression test.
- [x] Add delete-failure-is-not-DLQ regression test.
- [x] Add slow-handler backpressure regression test.
- [x] Add lifecycle start test.

### T5 - Docs

- [x] Update `aws-ktor/README.md` and `aws-ktor/README.ko.md`.
- [x] Include dependency snippet for `software.amazon.awssdk:sqs`.
- [x] Include Ktor plugin usage, publisher usage, and concurrency/shutdown notes.
- [x] Document manual DLQ non-atomic semantics and native redrive preference.

### T6 - Verification and PR

- [x] Run targeted compile/tests.
- [x] Run full `:aws-ktor:test`.
- [x] Add lesson.
- [ ] Commit with Lore trailers.
- [ ] Push, create PR assigned to `debop`, monitor CI, and mark ready when green.

## Acceptance Criteria

- Ktor applications can install `SqsConsumer`.
- Runtime consumes and deletes successful messages.
- Runtime can publish messages.
- Tests prove concurrent coroutine consumption with Awaitility.
- Tests prove LocalStack/Testcontainers SQS integration.
- README docs explain multithread/coroutine behavior and graceful shutdown.
- Tests prove cancellation does not accidentally delete in-flight messages.
- Failure semantics and manual DLQ caveats are documented.
