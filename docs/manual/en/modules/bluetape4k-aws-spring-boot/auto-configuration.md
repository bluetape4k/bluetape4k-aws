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
