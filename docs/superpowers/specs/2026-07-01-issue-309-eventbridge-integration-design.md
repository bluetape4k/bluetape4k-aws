# Issue #309 Design - EventBridge Spring Boot And Ktor Integration

## Context

Issue #309 targets milestone `0.5.0` and follows #308, which added focused
EventBridge core wrappers to `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`.
The framework layer should expose those helpers through Spring Boot
auto-configuration and a Ktor plugin without adding new EventBridge semantics.

## Evidence

- #308 core helpers already provide Java SDK v2 request builders and coroutine
  adapters for event bus, rule, target, list, and `PutEvents` workflows.
- `aws-spring-boot` Kinesis is the closest Spring pattern: optional service SDK
  dependency, `@ConditionalOnClass`, `@ConditionalOnProperty`,
  client/customizer ordering, properties binding, and a coroutine template bean.
- `aws-ktor` SES/SNS style plugins are the closest Ktor pattern: optional
  Java SDK v2 async client, injected operations override, application-owned
  client handling, plugin-owned client closure, and shared `AwsKtorCore`
  defaults/customizers.
- EventBridge `PutEvents`, `PutTargets`, and `RemoveTargets` can succeed at the
  request level while returning per-entry failures. Framework integrations must
  return raw SDK responses so callers inspect failed counts/details.
- No reliable repository-local EventBridge emulator smoke scaffold exists yet;
  this work must either prove a narrow Floci/LocalStack smoke or record the
  unsupported gap honestly.

## Goals

1. Add Spring Boot auto-configuration for EventBridge.
2. Add a coroutine-friendly Spring `EventBridgeOperations` template.
3. Add a Ktor EventBridge plugin with shared defaults/customizer support.
4. Reuse #308 core helpers and preserve raw AWS SDK response objects.
5. Keep EventBridge service SDK optional for consumers.
6. Update English and Korean README files for the affected modules.

## Non-Goals

- Do not add EventBridge Scheduler support; that belongs to #310.
- Do not add global endpoints, cross-account target orchestration, archive,
  replay, pipes, schema registry, or target-specific validation.
- Do not add hidden batching, retry, cleanup, background publishing, or response
  collapsing.
- Do not add live-AWS tests.

## Selected Design

### Spring Boot

Add package `io.bluetape4k.aws.spring.eventbridge`.

Public API:

- `EventBridgeProperties`
- `EventBridgeOperations`
- `EventBridgeCoroutinesTemplate`
- `EventBridgeAutoConfiguration`

Configuration prefix:

- `bluetape4k.aws.eventbridge.enabled`
- `bluetape4k.aws.eventbridge.region`
- `bluetape4k.aws.eventbridge.endpoint-override`
- `bluetape4k.aws.eventbridge.default-event-bus-name`

The auto-configuration registers `EventBridgeAsyncClient` and
`EventBridgeOperations` only when the Java SDK v2 EventBridge runtime class is
present and the integration is not disabled. It uses the same customization
order as other Spring integrations:

1. repo-wide AWS defaults
2. optional async HTTP client
3. global async client customizers with service name `eventbridge`
4. service-specific `AwsClientCustomizer<EventBridgeAsyncClientBuilder>`

`EventBridgeCoroutinesTemplate` delegates to the #308 core coroutine adapters.
For rule and target operations, a configured `defaultEventBusName` is used only
when the caller omits `eventBusName`. `PutEvents` entries are not rewritten
because each entry owns its event bus selection.

### Ktor

Add package `io.bluetape4k.aws.ktor.eventbridge`.

Public API:

- `EventBridgeKtorOperations`
- `EventBridgeKtorTemplate`
- `EventBridgeKtorPluginConfig`
- `EventBridgeKtorRuntime`
- `EventBridgeKtorPlugin`
- `Application.eventBridge()`
- `Application.eventBridgeOrNull()`

Extend `AwsKtorCore` with `AwsKtorEventBridgeAsyncClientCustomizer`.

The plugin supports:

- injected operations, which bypass client creation
- injected application-owned `EventBridgeAsyncClient`
- plugin-created client with shared Ktor AWS defaults
- service customizers after shared customizers
- plugin-owned client closure on `ApplicationStopping`

The Ktor template mirrors the Spring operations contract and delegates to #308
core helpers. It has no Spring dependency.

## Acceptance Criteria

- Spring auto-configuration registers client/properties/operations/template and
  backs off for disabled config, missing SDK classpath, custom client, and
  custom operations.
- Spring property binding validates nonblank `defaultEventBusName`.
- Spring and Ktor operations expose raw EventBridge responses for partial
  failure inspection.
- Ktor plugin stores injected operations, handles disabled accessors, preserves
  application-owned clients, closes plugin-owned clients once, and applies
  shared customizers before service customizers.
- `aws-spring-boot` and `aws-ktor` declare `libs.aws2.eventbridge` as
  `compileOnly` plus `testImplementation`.
- README locale pairs document EventBridge coverage and runtime dependency
  requirements.
- Targeted tests and `compileTestKotlin` pass for both affected modules.

## DoD

- Spec and implementation plan exist before production source edits.
- #309 remains assigned to `debop` with milestone `0.5.0`.
- `git diff --check` passes.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all` passes.
- Targeted EventBridge tests pass in `aws-spring-boot` and `aws-ktor`.
- Emulator support is either verified or explicitly recorded as unsupported.
- PR metadata mirrors issue assignee, milestone, and labels, with final
  `## DoD Status` section.
