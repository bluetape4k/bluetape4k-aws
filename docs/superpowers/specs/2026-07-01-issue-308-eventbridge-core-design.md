# Issue #308 Design - EventBridge Core Wrappers

## Context

Issue #308 targets milestone `0.5.0` and asks for first-class Amazon
EventBridge support in the core AWS modules:

- `bluetape4k-aws-java`
- `bluetape4k-aws-kotlin`

This is foundation work for later framework integrations such as Spring Boot and
Ktor EventBridge support. The core modules should make common EventBridge
workflows easier without hiding the raw AWS SDK for policy-sensitive or uncommon
operations.

## Evidence From Current Code

- `aws-java` already wraps service-specific Java SDK v2 clients with support,
  request builder, async `CompletableFuture`, and coroutine `await()` layers.
  Kinesis is the closest shape for create/put/list/delete style operations.
- `aws-kotlin` already wraps native AWS Kotlin SDK services with suspend
  extension functions and builder lambdas. Kinesis and SQS are the closest
  patterns for validation, builder overrides, and response passthrough.
- `gradle/libs.versions.toml` has aliases for Kinesis and STS but does not have
  EventBridge aliases:
  - missing `aws2-eventbridge`
  - missing `aws-kotlin-eventbridge`
- `aws-java/build.gradle.kts` and `aws-kotlin/build.gradle.kts` use
  `compileOnly` for optional AWS service SDK dependencies and add service
  modules to `testImplementation` only when tests need the generated types.
- `bluetape4k-dependencies/gradle/libs.versions.toml` currently governs shared
  external versions and only exposes a small managed AWS core subset. The
  service aliases consumed by this repository still live in the repo-local
  catalog.
- Context7 confirmed the official Java SDK v2 client/builder pattern:
  `XxxClient.builder()` / `XxxAsyncClient.builder()` with optional HTTP client,
  endpoint, region, and async configuration hooks.
- Context7 confirmed the official AWS Kotlin SDK pattern:
  `XxxClient.fromEnvironment()` or client construction plus suspend operations
  with request builder lambdas.
- AWS EventBridge public API constraints relevant to this wrapper:
  - `PutEvents` is a common custom event publishing operation. AWS allows
    batching for efficiency, but one request must keep the total event entry
    size under 1 MB and must not exceed 10 entries.
  - `PutTargets` accepts at most 10 targets per request.
  - Each rule can have up to five targets associated with it at one time; this
    is an AWS-side rule state quota, not a per-request helper batching rule.
  - `PutRule` creates or updates a rule and requires at least one matching
    mechanism: `eventPattern` or `scheduleExpression`.
  - Event bus, rule, and target mutations are AWS-side control-plane
    operations; helpers must keep deletion explicit and not add hidden cleanup.

## Goals

1. Add focused EventBridge helpers to `bluetape4k-aws-java`.
2. Add focused EventBridge helpers to `bluetape4k-aws-kotlin`.
3. Cover common event bus, rule, target, and `PutEvents` workflows.
4. Preserve raw SDK escape hatches for uncommon or policy-sensitive operations.
5. Keep service dependencies optional for consumers with `compileOnly`.
6. Update README locale set so the new service coverage is discoverable.

## Non-Goals

- Do not add Spring Boot or Ktor EventBridge integration in this PR. That belongs
  to follow-up issue #309.
- Do not add EventBridge Scheduler support. That belongs to issue #310.
- Do not wrap the entire EventBridge service surface.
- Do not add hidden retry, batching, pagination, or background workers.
- Do not add global endpoint, cross-account event bus target orchestration,
  built-in console-only targets, or target-specific parameter validation beyond
  generated SDK model mapping. These remain raw SDK responsibilities.
- Do not add destructive delete convenience wrappers beyond direct event bus and
  rule deletion requested by common lifecycle tests. Target removal remains an
  explicit `RemoveTargets` helper with caller-supplied ids.
- Do not add live-AWS smoke tests. Emulator smoke tests are in scope when the
  configured Floci-first emulator path exposes reliable EventBridge coverage;
  otherwise the implementation must record the unsupported-emulator evidence and
  keep local verification at unit/request-mapping level.

## Approach Options

### Option A - Request Builders Only

Add only request DSL builders for EventBridge generated SDK types.

Rejected because issue #308 explicitly asks for core wrappers and coroutine
DSLs. Builders alone would not give direct SDK users the first-class operations
already available for Kinesis, SQS, and SNS.

### Option B - Focused Core Helpers Per SDK

Add service-specific helpers for common EventBridge workflows in both core
modules:

- Java SDK v2: client factories, request builders, sync extensions, async
  `CompletableFuture` extensions, and coroutine adapters.
- AWS Kotlin SDK: client factories and native suspend helpers.

Selected because it matches existing repository patterns, keeps dependencies
optional, and gives a useful subset without pretending to own every EventBridge
operation.

### Option C - Framework Integration First

Start with Spring Boot or Ktor abstractions and let them drive the core API.

Rejected because #309 is explicitly downstream of #308. Framework integrations
should consume stable core helpers, not define them indirectly.

## Selected Design

### Java SDK Module

Add packages:

- `io.bluetape4k.aws.eventbridge`
- `io.bluetape4k.aws.eventbridge.model`

Add service dependency aliases and declarations:

- `libs.aws2.eventbridge` as `compileOnly` and `testImplementation`

Add public APIs:

- `eventBridgeClient { }`
- `eventBridgeClientOf(region, httpClient, builder)`
- `eventBridgeClientOf(endpoint, region, credentialsProvider, httpClient, builder)`
- async equivalents with `EventBridgeAsyncClient`
- request builders for:
  - `CreateEventBusRequest`
  - `DeleteEventBusRequest`
  - `PutRuleRequest`
  - `DeleteRuleRequest`
  - `PutTargetsRequest`
  - `RemoveTargetsRequest`
  - `ListRulesRequest`
  - `ListTargetsByRuleRequest`
  - `PutEventsRequest`
  - `PutEventsRequestEntry`
- sync extension functions for the same operations
- async extension functions returning `CompletableFuture`
- coroutine extension functions on async clients using `.await()`
- Java client factories must follow existing module ownership: created clients
  are registered with `ShutdownQueue`, and tests should mirror the nearest
  existing service-factory construction checks.

Validation rules:

- Event bus names, rule names, target ids, target ARNs, event source, detail
  type, and detail must reject blank values with bluetape4k validation helpers.
- Optional `PutEvents` resources may be absent, but supplied resource values must
  not contain blanks.
- `PutRule` convenience helpers must require at least one non-blank
  `eventPattern` or `scheduleExpression`. Helpers should document that
  `PutRule` is an AWS create-or-update operation and does not merge omitted
  fields on behalf of the caller.
- `PutEvents` entries must be non-empty and must not exceed the AWS request
  limit of 10 entries. Helpers document the 1 MB total entry-size limit, but do
  not attempt JSON byte-size estimation because SDK request entries can be
  extended by caller-supplied fields and AWS remains the final authority.
- `PutTargets` targets must be non-empty, must not exceed the AWS request limit
  of 10 targets, and each target helper must validate non-blank id and ARN.
- `RemoveTargets` ids must be non-empty and must not exceed the AWS request
  limit of 10 ids.
- Delete helpers exist only for event buses and rules because they are common
  lifecycle operations already paired with create/put helpers. Target deletion
  remains `removeTargets` so the action name mirrors AWS semantics.

### AWS Kotlin SDK Module

Add packages:

- `io.bluetape4k.aws.kotlin.eventbridge`
- `io.bluetape4k.aws.kotlin.eventbridge.model`

Add service dependency aliases and declarations:

- `libs.aws.kotlin.eventbridge` as `compileOnly` and `testImplementation`

Add public APIs:

- `eventBridgeClientOf(...)`
- `withEventBridgeClient { }`
- request builder helpers matching AWS Kotlin generated builder shapes
- native suspend helpers for:
  - `createEventBus`
  - `deleteEventBus`
  - `putRule`
  - `deleteRule`
  - `putTargets`
  - `removeTargets`
  - `listRules`
  - `listTargetsByRule`
  - `putEvents`
- `eventBridgeClientOf(...)` returns a caller-owned client.
- `withEventBridgeClient { }` owns and closes the short-lived client through
  `useSafe`, matching the existing AWS Kotlin client lifecycle pattern.

The Kotlin module must not depend on `aws-java`. The two modules may expose the
same operation names because their package roots and SDK client types are
different.

### Event Entry Mapping

`PutEventsRequestEntry` convenience builders must cover the common required and
optional fields without forcing callers to drop to raw builders:

- required by helper: `source`, `detailType`, `detail`
- optional direct parameters: `eventBusName`, `resources`, `time`, `traceHeader`
- override builder lambda: last-mile generated SDK fields that the wrapper does
  not model explicitly
- `detail` must be a non-blank JSON string from the caller. The wrapper does not
  serialize arbitrary objects because that would add codec and schema semantics
  outside the core AWS helper boundary.

The helper must assign caller-provided `List` values directly when the generated
SDK accepts them. Vararg overloads may exist for ergonomics, but the list-based
helpers are the primary hot-path API for `PutEvents`, `PutTargets`, and
`RemoveTargets`.

### Cancellation, Lifecycle, And Delete Ordering

Suspend and coroutine helpers must not wrap suspend calls in `runCatching`.
Caller cancellation, timeout, and SDK exceptions must propagate with their
original type and cause. Tests must cover Java coroutine adapter cancellation
where practical and Kotlin `withEventBridgeClient` close behavior.

Delete helpers do not perform hidden cleanup. Public KDoc must state that
callers should remove targets first, inspect any failed target removals, and
then delete a rule; likewise, callers should delete rules before deleting a
custom or partner event bus. The raw AWS response remains visible so partial
target failure handling stays with the caller.

## Failure Modes And Mitigations

1. **SDK surface drift**: EventBridge generated method names or builder property
   names may differ between Java SDK v2 and AWS Kotlin SDK.
   - Mitigation: validate through compile-first TDD and inspect local dependency
     classes/source when compile errors appear.
2. **Over-wrapping AWS policy surface**: wrapping every operation would create a
   maintenance-heavy abstraction and blur caller responsibility.
   - Mitigation: keep only event bus, rule, target, list, and `PutEvents`
     workflows in scope.
3. **Hidden batching semantics**: automatically splitting targets or events
   could hide partial failures or change AWS request semantics.
   - Mitigation: one helper invocation makes one SDK request; callers handle
     batching and partial failures explicitly. Tests must assert that repeated
     helper calls invoke the SDK exactly once per helper call and do not launch
     retry, background dispatch, or `CompletableFuture.allOf` fan-out.
4. **Optional dependency leak**: using `api` or `implementation` for service
   modules would force EventBridge on all consumers.
   - Mitigation: use `compileOnly` plus `testImplementation`, matching the repo
     service dependency policy.
5. **Lifecycle leaks**: newly introduced service clients could bypass existing
   Java `ShutdownQueue` or AWS Kotlin `useSafe` closure patterns.
   - Mitigation: copy the existing service factory contracts and add tests or
     explicit construction evidence for Java and Kotlin client lifecycles.
6. **Release evidence gap**: control-plane helpers can compile while still
   failing against the configured local AWS emulator.
   - Mitigation: run a Floci-first EventBridge smoke probe when emulator support
     exists. If Floci and the explicit LocalStack fallback do not support the
     required EventBridge workflow reliably, record the probe result and keep the
     PR honest about unit-only local evidence.
7. **Partial failures**: `PutEvents`, `PutTargets`, and `RemoveTargets` may
   return per-entry failures even when the request itself succeeds.
   - Mitigation: helpers return raw SDK responses and KDoc directs callers to
     inspect failed entry counts or failed target entries before compensating.
     No helper should collapse partial success into a Boolean success value.

## Acceptance Criteria

- `bluetape4k-aws-java` exposes EventBridge client factories, request builders,
  sync extensions, async extensions, and coroutine adapters for the selected
  operations.
- `bluetape4k-aws-kotlin` exposes EventBridge client factories, request
  builders, and native suspend helpers for the selected operations.
- Tests prove validation, request mapping, async/coroutine passthrough, and
  no-hidden-batching behavior, including `PutEvents` and `PutTargets` request
  count limits.
- Tests prove `PutEvents`, `PutTargets`, and `RemoveTargets` helpers preserve raw
  SDK response objects so failed-entry counts and per-entry failure details
  remain visible to callers.
- Tests prove `PutRule` convenience helpers reject blank or missing
  `eventPattern`/`scheduleExpression` inputs.
- Tests or review evidence prove Java client factory ownership and Kotlin
  `withEventBridgeClient` closure semantics match existing service patterns.
- KDoc documents cancellation propagation and the `removeTargets` before
  `deleteRule` plus delete-rules-before-delete-event-bus operational sequence.
- Public API KDoc is written in English and includes summary, contract, and a
  realistic usage example for new durable helper families.
- Root `README.md` / `README.ko.md` and module README pairs
  `aws-java/README.md`, `aws-java/README.ko.md`, `aws-kotlin/README.md`, and
  `aws-kotlin/README.ko.md` document EventBridge coverage, runtime dependency
  requirements, partial-failure inspection, and unsupported edge capabilities.
- A Floci-first EventBridge smoke probe either passes for a minimal event bus /
  rule / target / `PutEvents` workflow, or the PR records the exact emulator
  support gap and the LocalStack fallback result.
- KDoc documents that `PutEvents`, `PutTargets`, and `RemoveTargets` can return
  partial failures and that callers must inspect the raw SDK response.
- README and README.ko describe EventBridge service coverage and mention the
  optional runtime service dependencies.
- Targeted compile and tests pass for `bluetape4k-aws-java` and
  `bluetape4k-aws-kotlin`.
- 7-Tier review finds P0/P1 = 0 before PR creation.

## DoD

- Spec and implementation plan are committed before implementation.
- #308 stays assigned to `debop` and milestone `0.5.0`.
- `git diff --check` passes.
- `./gradlew :bluetape4k-aws-java:compileTestKotlin --warning-mode all` passes.
- `./gradlew :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all` passes.
- Targeted EventBridge tests pass in both affected modules.
- PR body final section is `## DoD Status`.
