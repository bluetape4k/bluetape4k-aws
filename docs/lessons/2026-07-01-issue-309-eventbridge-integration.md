# Issue 309 EventBridge Framework Integration

## Context

Issue #309 adds Spring Boot and Ktor integration on top of the EventBridge core wrappers from #308.

## Decision

Keep the framework layer thin. Spring Boot exposes an `EventBridgeOperations` coroutine template through auto-configuration, and Ktor exposes an `EventBridgeKtorPlugin` with coroutine and future operations. Both layers preserve raw AWS SDK responses for partial-failure APIs and avoid hidden batching, retry, cleanup, Scheduler support, listener runtimes, global endpoints, or cross-account orchestration.

## Outcome

Spring Boot now creates an optional EventBridge async client and operations template with region, endpoint, credentials, shared defaults, and customizer support. Ktor now installs EventBridge operations with clear ownership semantics: injected operations or clients are application-owned, and plugin-owned clients are closed once with the application lifecycle. Root and module README locale pairs document usage and non-goals.

## Verification

- Spring Boot and Ktor EventBridge compile verification passed.
- Targeted Spring Boot and Ktor EventBridge tests passed.
- `git diff --check` passed.
- No repository-local `*EventBridge*Emulator*` scaffold exists, so live emulator smoke support was not claimed.

## Future Guidance

Do not hide EventBridge partial failures behind Boolean helper APIs. Add emulator smoke only after proving Floci or LocalStack supports the exact event bus, rule, target, and `PutEvents` workflow. Scheduler, global endpoint, and richer target validation belong in separate issues.
