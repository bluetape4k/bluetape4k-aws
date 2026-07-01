# Issue 308 EventBridge Core Wrappers

## Context

Issue #308 adds EventBridge core support before higher-level framework integration.

## Decision

Keep the first EventBridge surface thin: client factories, request builders, one-request helpers, and raw SDK responses. Do not add hidden batching, retry, cleanup, Scheduler support, global endpoints, cross-account orchestration, or framework integration in this issue.

## Outcome

Java SDK v2 now has sync, async, and coroutine EventBridge helpers. AWS Kotlin SDK now has native suspend EventBridge helpers. Both modules document the runtime EventBridge SDK dependency and partial-failure response contract.

## Verification

- Targeted EventBridge tests passed for Java and AWS Kotlin modules.
- `compileTestKotlin --warning-mode all` passed for both modules.
- No repository-local `*EventBridgeEmulator*` smoke exists, so live emulator support was recorded as unsupported instead of claimed.

## Future Guidance

When adding #309 framework integration, reuse these core helpers and preserve raw partial-failure responses. Add emulator smoke only after proving Floci or LocalStack supports the exact EventBridge event bus/rule/target workflow used by the test.
