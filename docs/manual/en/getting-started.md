---
manualId: "getting-started"
title: "Getting Started with Bluetape4k AWS"
locale: "en"
releaseRef: "0.5.0"
---

# Getting Started with Bluetape4k AWS

## Import one version boundary

Use the central `bluetape4k-dependencies` BOM. The application chooses that version once, then declares the bluetape4k and AWS service artifacts without individual versions.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))

    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3")
}
```

Replace `<version>` with the `bluetape4k-dependencies` release selected by the application. The `0.5.0` label in this manual identifies the AWS source baseline; it is not a second version the application must coordinate.

AWS service SDKs are `compileOnly` in the wrapper libraries. Add the runtime service artifacts you actually call. For example, an S3 service needs `software.amazon.awssdk:s3` on the Java SDK path or `aws.sdk.kotlin:s3` on the Kotlin SDK path. This keeps unrelated AWS services out of the runtime classpath.

## Choose the SDK path

Use the Java SDK v2 path when an application already uses Java SDK clients, the enhanced DynamoDB client, transfer manager, or libraries that expose `CompletableFuture`. Bluetape4k adds sync helpers, async extensions, and suspending adapters over that model.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")
}
```

Use the Kotlin SDK path when native `suspend` clients and Kotlin request builders are the primary API. Do not add both paths merely because both are available; mixed SDKs mean separate client, engine, configuration, and shutdown decisions.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-kotlin")
    implementation("aws.sdk.kotlin:dynamodb")
    implementation("aws.sdk.kotlin:s3")
    implementation("aws.sdk.kotlin:sqs")
}
```

See [SDK selection](guides/sdk-selection.md) for the complete decision table.

## Add one application integration

Spring Boot applications normally begin with `bluetape4k-aws-spring-boot`. Add the service SDKs that activate the required auto-configuration.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")
}
```

Ktor applications use `bluetape4k-aws-ktor` for SigV4, S3 REST access, SQS consumers, DynamoDB repositories, CloudWatch, IMDS, and AWS-backed Exposed configuration. The Java SQS and Kotlin DynamoDB SDK types used by these public plugin APIs are already exposed transitively by `aws-ktor`; add only application-owned runtime integrations.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-ktor")
    implementation("io.ktor:ktor-server-core") // when installing server plugins
    implementation("io.ktor:ktor-client-cio") // when using the CIO client engine
}
```

Declare `software.amazon.awssdk:sqs` or `aws.sdk.kotlin:dynamodb` directly only
when application code calls those SDKs outside the `aws-ktor` plugin API. Add
`bluetape4k-jackson3`, Micrometer, Exposed, and a JDBC driver only for the
corresponding optional integration.

Use [Spring Boot or Ktor](guides/spring-vs-ktor.md) to choose by lifecycle and configuration ownership, not by syntax preference.

## Verify one narrow path

Start with the example closest to the application boundary instead of running the entire repository.

```bash
./gradlew :aws-ktor-s3-examples:test
./gradlew :aws-spring-boot-dynamodb-examples:test
./gradlew :aws-spring-boot-sqs-examples:test
```

The default emulator is Floci. Use LocalStack only when the operation or integration is not covered by Floci, and record that choice explicitly:

```bash
./gradlew :aws-spring-boot-sqs-examples:test \
  -Dbluetape4k.aws.emulator=localstack
```

## Sources

- [Java SDK module dependencies](../../../aws-java/build.gradle.kts)
- [Kotlin SDK module dependencies](../../../aws-kotlin/build.gradle.kts)
- [Spring Boot module dependencies](../../../aws-spring-boot/build.gradle.kts)
- [Ktor module dependencies](../../../aws-ktor/build.gradle.kts)
