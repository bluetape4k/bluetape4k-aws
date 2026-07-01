# Issue #309 EventBridge Integration Plan

Goal: add EventBridge Spring Boot and Ktor integration on top of #308 core
wrappers, preserving raw AWS SDK responses and optional runtime service
dependencies.

## Task 1 - RED Tests And Dependency Wiring

- [ ] Add `libs.aws2.eventbridge` to `aws-spring-boot` and `aws-ktor` as
  `compileOnly` and `testImplementation`.
- [ ] Add Spring RED tests for auto-configuration registration, disabled
  backoff, custom bean backoff, endpoint/region validation, shared defaults,
  customizer order, classpath absence, and property binding.
- [ ] Add Ktor RED tests for injected operations, disabled accessor behavior,
  application-owned client preservation, plugin-owned client closure, shared
  versus service customizer order, and default bus binding.
- [ ] Run targeted tests and record the expected failures before production
  implementation closes them.

## Task 2 - Spring EventBridge Integration

- [ ] Add `EventBridgeProperties` with `region`, `endpointOverride`, and
  `defaultEventBusName`.
- [ ] Add `EventBridgeOperations` with suspend methods for create/delete bus,
  put/delete rule, put/remove targets, list rules/targets, and put events.
- [ ] Add `EventBridgeCoroutinesTemplate` delegating to #308 core coroutine
  extensions and applying `defaultEventBusName` only to rule/target/list
  operations.
- [ ] Add `EventBridgeAutoConfiguration` using existing AWS defaults,
  credentials, HTTP client, global customizers, and service customizers.
- [ ] Register the auto-configuration in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Task 3 - Ktor EventBridge Integration

- [ ] Extend `AwsKtorCore` with
  `AwsKtorEventBridgeAsyncClientCustomizer`.
- [ ] Add `EventBridgeKtorOperations`, `EventBridgeKtorTemplate`,
  `EventBridgeKtorRuntime`, `EventBridgeKtorPluginConfig`, and
  `EventBridgeKtorPlugin`.
- [ ] Preserve the SES/SNS plugin lifecycle contract: injected operations win,
  injected client remains application-owned, plugin-created client closes once.
- [ ] Keep operations one SDK request per invocation and return raw SDK
  responses.

## Task 4 - Documentation, Review, And Validation

- [ ] Update root and affected module README locale pairs for EventBridge
  Spring Boot/Ktor coverage and runtime dependency requirements.
- [ ] Check emulator support with repository-local Floci/LocalStack evidence;
  add a smoke only if support is present, otherwise record the gap.
- [ ] Run targeted EventBridge tests, compile checks, and `git diff --check`.
- [ ] Add 7-tier review and lesson artifacts.
- [ ] Commit with Lore trailers and open a PR linked to #309 with issue metadata
  parity and final `## DoD Status`.

## Verification Matrix

| Requirement | Evidence |
|---|---|
| Spring EventBridge beans | `EventBridgeAutoConfigurationTest` |
| Spring raw-response operations | `EventBridgeCoroutinesTemplateTest` |
| Ktor plugin lifecycle | `EventBridgeKtorPluginTest` |
| Ktor raw-response operations | `EventBridgeKtorTemplateTest` |
| Optional runtime SDK dependency | Gradle dependency declarations |
| README locale parity | root, `aws-spring-boot`, and `aws-ktor` README diffs |
| Emulator truthfulness | Floci/LocalStack probe or unsupported evidence |
| Final build health | targeted tests, compileTestKotlin, `git diff --check` |
