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

## Customization

Use the provided client-builder customization hooks when region and endpoint properties are insufficient. Prefer one customization boundary over post-processing individual service beans.

## Startup validation

Fail early on invalid endpoint/region combinations, queue settings, pool sizes, or mutually exclusive credential modes. Environment post-processors should fetch remote configuration once during startup, not on request paths.

## Sources

- [Auto-configuration imports](../../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [Shared AWS properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsProperties.kt)
- [AWS auto-configuration](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt)

