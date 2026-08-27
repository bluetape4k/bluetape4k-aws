---
title: Auto-configuration
description: Understand conditional AWS service beans, properties, and back-off rules.
manualId: bluetape4k-aws-spring-boot
chapterId: auto-configuration
---

# Auto-configuration

The Spring module uses conditional auto-configuration: a service integration appears only when its SDK classes and enabling properties are present. This keeps the library broad while the application's runtime classpath stays selective.

## Dependency boundary

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("software.amazon.awssdk:s3")
}
```

The application chooses the central BOM version and service SDKs. It does not choose a separate AWS repository library version.

## Shared defaults and service overrides

`AwsProperties` under `bluetape4k.aws` supplies enabled, region, endpoint override, and optional web-identity credentials. Service-specific properties override the shared defaults. An endpoint override requires a region because signed requests still need a credential scope.

For SNS, `bluetape4k.aws.sns.enabled` controls the complete auto-configuration
and defaults to `true`. When no application bean is present, the module creates
an `SnsTopicArnCache`, an `SnsTopicArnResolver`, and the coroutine template.
`topic-arn-cache.enabled` defaults to `true` with a 256-entry, five-minute
bounded cache; setting it to `false` disables persistent entries but keeps
per-topic single-flight. Set `account-id` to enable same-account ARN checks.
When it is absent, an explicit ARN is rejected unless
`allow-cross-account-topic-arn=true` is deliberately enabled; an effective
`region` is also required for explicit ARN validation. A custom cache or
resolver bean is a scoped configuration override, not a behavior-preserving
rollback. For that rollback, provide a custom `SnsOperations` implementation or
redeploy the last-known-good artifact; set `enabled=false` to disable all SNS
beans. Lookup failures log only hashed scope/topic dimensions and exception type.
AWS client customizers must preserve explicitly configured SNS endpoint and region
after defaults are applied; an identity-changing customizer fails fast. When no
region is configured, the resolver uses the final region selected by the AWS SDK
provider chain. Provide a custom `SnsTopicArnResolver` when a different client
identity is intentional. A custom client whose endpoint or region cannot be
inspected also fails fast and requires an explicitly supplied resolver. Directly
constructed `SnsCoroutinesTemplate` instances require the client endpoint/region
to match `SnsProperties`; inject a resolver explicitly for a different or
uninspectable client.

## Modulith event auto-configuration (Unreleased/develop)

`AwsModulithEventsAutoConfiguration` is additive and disabled by default. It
loads only when all three Spring Modulith classes
`EventExternalizationTransport`, `EventSerializer`, and
`EventExternalizerModuleListener` are present and
`bluetape4k.aws.modulith.events.enabled=true`.

Producer and consumer directions have independent opt-ins:

| Condition | Result |
| --- | --- |
| Root disabled or Modulith classes absent | No adapter properties, transport, or consumer beans. |
| `producer.enabled=true` | Requires a registry, serializer, at least one logical target, and the operations capability for each target service. |
| SNS-only target | Creates only the SNS publisher; SQS operations or SDK classes are not required. |
| SQS-only target | Requires `SqsFullRequestOperations` so FIFO keys and message attributes are not discarded. |
| `consumer.enabled=true`, `source-mode=DIRECT` | Requires SQS operations, registry, serializer, externalization configuration, queue name, and redrive policy unless explicitly disabled. |
| `consumer.enabled=true`, `source-mode=SNS` | Adds `sns-message-manager`, `SnsHttpMessageVerifier`, and a non-empty exact TopicArn allowlist to the DIRECT requirements. |

The built-in producer backs off when the application supplies an
`EventExternalizationTransport`. The built-in in-memory idempotency store backs
off for an `AwsModulithEventIdempotencyStore` bean. A custom
`SnsHttpMessageVerifier` replaces the verifier created by SNS verification
auto-configuration. These are separate boundaries: a custom outbound transport
does not disable the inbound consumer.

One built-in consumer binds one
`bluetape4k.aws.modulith.events.consumer.queue` and one `source-mode` in an
application context. Multi-queue or mixed DIRECT/SNS consumption needs separate
contexts or an application-owned listener. Invalid cross-property combinations
fail startup with `BT4K-MOD-101`; they do not defer validation until the first
message.

## Back-off is a feature

If an expected bean is missing, inspect the condition report before adding manual beans. Common causes are a missing `compileOnly` service SDK, disabled property, or an application-provided bean that intentionally makes auto-configuration back off.

## Testcontainers ServiceConnection

For Floci and LocalStack tests, migrate endpoint and credential assignments from
`DynamicPropertySource` to a named Spring Boot `@ServiceConnection`. In Boot 4.1
the annotation takes one service name, and the test class adds the optional
dependency alias:

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(bt4k.bluetape4k.testcontainers)

@Container
@ServiceConnection(name = "s3")
val floci: FlociServer = FlociServer.Launcher.floci
```

The details contain only endpoint, region, and test credentials. Without the
optional dependencies or the annotation, the existing properties-only fallback
continues to work. `bluetape4k.aws.emulator` selects the backend launcher; it
does not provide a resource URL. An unnamed `@ServiceConnection` is an explicit
all-services opt-in and is not combined with a named declaration.

Factories do not create application resources. A fixture creates and owns the
SQS queue URL, SNS topic ARN, DynamoDB table name, and Kinesis stream name, then
cleans each literal it created. Keep S3 tests to one bucket and include an
`owner-token` in the bucket and object key. Reject a `wildcard` or foreign
literal before making an AWS call. The lifecycle order is `cleanup` of the
fixture, application context close, and Testcontainers teardown. Cleanup errors
are sanitized and suppressed; cancellation is rethrown. If an optional factory
dependency is missing, fail with `FACTORY_LINKAGE` and fix the test classpath or
remove the annotation instead of silently switching credentials.

## Customization

Use the provided client-builder customization hooks when region and endpoint properties are insufficient. Prefer one customization boundary over post-processing individual service beans.

## Startup validation

Fail early on invalid endpoint/region combinations, queue settings, pool sizes, or mutually exclusive credential modes. Environment post-processors should fetch remote configuration once during startup, not on request paths.

## Sources

- [Auto-configuration imports](../../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [Shared AWS properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsProperties.kt)
- [AWS auto-configuration](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt)
- [Modulith auto-configuration](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsAutoConfiguration.kt)
