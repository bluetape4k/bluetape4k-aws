# MockK Strategy for Emulator-Unsupported Tests

**Date**: 2026-05-16
**Issue**: #106
**Branch**: feat/issue-106-mockk-tests

## Context

Two categories of tests in `bluetape4k-aws` cannot run against LocalStack:

1. **SES V2** (`aws-kotlin`) — LocalStack does not implement the SES V2 API surface.
2. **SNS confirmSubscription** (`aws`, `aws-kotlin`) — the subscription confirmation
   token is delivered out-of-band (via SMS or HTTP callback) to the subscriber;
   no emulator can inject it.

These tests were annotated `@Disabled` with issue references, but had zero coverage
of the bluetape4k extension functions that wrap these APIs.

## Decision

Use MockK to write contract tests that verify the bluetape4k coroutine wrappers
delegate correctly to the underlying AWS Kotlin SDK interface methods. The tests
do not need a live emulator.

## Implementation

### SES V2 (`SesV2ClientExtensionsMockTest`)

- `SesV2Client` is an **interface** in the AWS Kotlin SDK → `mockk<SesV2Client>()` works directly.
- Tests: `send` delegates to `sendEmail`, `sendBulk` delegates to `sendBulkEmail`,
  `getTemplateOrNull` returns `Template?` on success / `null` on exception /
  rethrows `CancellationException`.
- The `getTemplateOrNull` CancellationException test must use `runTest { }` from
  `kotlinx-coroutines-test`, not `runSuspendIO { }`, so that `assertFailsWith<CancellationException>`
  captures it before the coroutine machinery propagates it.

### SNS confirmSubscription (`SnsConfirmSubscriptionMockTest`)

- `SnsClient` is also an interface → directly mockable.
- The DSL `client.confirmSubscription { ... }` is `suspend inline` in the SDK,
  so it inlines to `client.confirmSubscription(request)` at the call site.
  MockK intercepts the interface method.
- Do NOT add `coVerify(exactly = 1)` to the exception propagation test.
  `coVerify` across tests on the same mock class instance (even with PER_METHOD lifecycle)
  can accumulate call records due to MockK's global ID counter, causing
  "2 matching calls found" assertion failures. The exception propagation is
  sufficiently asserted by catching and checking `thrown.shouldNotBeNull()`.

## Lessons

- `EmailTemplateContent` is the correct class name, not `TemplateContent` — the
  SDK model package uses the full prefix.
- Response types (`SendEmailResponse`, `GetEmailTemplateResponse`, `ConfirmSubscriptionResponse`)
  support DSL builders: `ResponseType { field = value }`.
- Use `mockk<T>(relaxed = true)` for response objects where exact field names are
  uncertain (e.g., `SendBulkEmailResponse`); use concrete DSL builders only when
  asserting specific field values.
- For tests that verify exception propagation from suspend functions, prefer
  try/catch + assertion over `coVerify` on call count to avoid cross-test
  accumulation issues with shared mock IDs.
