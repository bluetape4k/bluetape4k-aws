---
manualId: "bluetape4k-aws-bom"
id: "bluetape4k-aws-bom"
title: "AWS Bill of Materials"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-bom"
sourceDir: "bom"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-bom
---

# AWS Bill of Materials

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

A narrow BOM that aligns the published bluetape4k AWS libraries. Application code should normally import the central `bluetape4k-dependencies` platform instead.

## When to use it {#when-to-use}

Use it only when a build intentionally consumes AWS repository artifacts without the rest of the bluetape4k release train.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-bom")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

The BOM carries dependency constraints; it contains no runtime code. `bluetape4k-dependencies` composes this BOM with the other repository BOMs.

## Quick start {#quick-start}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-java")
    implementation("software.amazon.awssdk:s3")
}
```

`<version>` is the only bluetape4k version an application selects.

## API by task {#api-by-task}

Import the platform and omit versions from `bluetape4k-aws-java`, `-kotlin`, `-exposed`, `-spring-boot`, and `-ktor`.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

The BOM aligns bluetape4k libraries, but service SDK modules remain explicit runtime choices because the library declares them `compileOnly`.

## Configuration {#configuration}

Declare the central platform once in a convention plugin or shared dependency block.

## Failure modes {#failures}

A missing service SDK causes class-loading or bean back-off symptoms. A separately pinned repository BOM can create incompatible cross-repository versions.

## Operations {#operations}

Upgrade the central BOM as one reviewed change and run compile plus integration tests for the services actually enabled.

## Testing {#testing}

Run `./gradlew :bluetape4k-aws-bom:dependencies` in this repository and dependency insight in the consuming application.

## Workshops and learning path {#workshops}

Start with the central platform, choose one library, add one service SDK, and then follow the matching S3, DynamoDB, SQS, or Exposed example.

## Limitations {#limitations}

This BOM does not add AWS SDK service jars, create clients, or configure credentials.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### bom Architecture diagram

[![bom Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bom-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/bom-architecture-01.svg)

_Release README: [`bom/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/bom/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `bom/build.gradle.kts`](../../../../bom/build.gradle.kts)

