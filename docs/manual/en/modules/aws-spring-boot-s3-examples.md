---
manualId: "aws-spring-boot-s3-examples"
id: "aws-spring-boot-s3-examples"
title: "Spring Boot S3 Workshop"
locale: "en"
kind: "example"
gradlePath: ":aws-spring-boot-s3-examples"
sourceDir: "examples/aws-spring-boot-s3-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot S3 Workshop

> A runnable workshop grounded in the 0.5.0 release source.

## Learning goal {#problem}

Expose S3 object operations through a small Spring Boot WebFlux controller while auto-configured operations own SDK details. The workshop adds listing, deletion, presigning, and optional client-side encryption to a basic upload/download path.

## When to use this workshop {#when-to-use}

Use it when a Spring service needs S3 templates, presigned URLs, or envelope encryption and must understand which SDK modules and beans activate each feature.

## Project coordinates {#coordinates}

This example is not published. Run `./gradlew :aws-spring-boot-s3-examples:test`. Applications import the central BOM, `bluetape4k-aws-spring-boot`, and `software.amazon.awssdk:s3`; encrypted routes also need KMS support.

## Concepts to learn {#concepts}

`S3DocumentController` delegates to `S3Operations`/`S3CoroutinesTemplate`. Optional `S3ClientSideEncryptionOperations` stores encrypted data with envelope metadata. Auto-configuration creates and closes service clients.

## Staged walkthrough {#quick-start}

1. Upload and download one object.
2. List by prefix and delete the object.
3. Generate presigned GET and PUT URLs and inspect their expiry.
4. Enable the encrypted route with a deterministic test KMS implementation.
5. Run AOT tasks after changing configuration or controller types.

## Entry points and expected behavior {#api-by-task}

`SpringBootS3ExampleApplication` starts the service and `S3DocumentController` exposes `/s3/documents`. It supports plain/encrypted upload and download, object listing, presigned URLs, and delete.

## Recommended exercise order {#patterns}

Keep bucket/key validation at the HTTP boundary and stream payloads when size is unbounded. Separate object naming, retention, encryption context, and authorization from the transport helper.

## Integration boundary {#integrations}

Spring creates the S3 client, presigner, templates, and optional encryption operations when their classes and properties are present. The controller does not own or close them.

## Configuration checkpoints {#configuration}

Set region and optional endpoint override/path-style access for emulators. Configure presign duration. Encryption needs a key ID, stable encryption context, `KmsOperations`, and the AWS KMS runtime module.

## Failure modes {#failures}

Missing bucket, wrong addressing style, expired presigned URL, partial upload, absent KMS bean, mismatched encryption context, and oversized buffering are likely failures.

## Operations {#operations}

Measure latency, bytes, failures, retries, and presign counts without logging object content or signed query strings. Define multipart, retention, versioning, and partial-failure policy outside the controller.

## Testing the boundary {#testing}

`S3DocumentControllerLocalStackTest` selects the emulator, creates a bucket, and verifies upload, download, listing, presigned URLs, delete, and encryption using a deterministic KMS test double.

## Next learning path {#workshops}

Compare the Ktor S3 workshop for direct SigV4 HTTP behavior, then add service-specific IAM, large-object transfer, and real KMS smoke tests.

## Limitations {#limitations}

The deterministic KMS helper does not prove AWS KMS permissions or ciphertext behavior. The example does not benchmark large transfers, multipart recovery, versioned deletion, or bucket policy.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### aws spring boot s3 examples Architecture diagram

[![aws spring boot s3 examples Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-architecture-01.svg)

_Release README: [`examples/aws-spring-boot-s3-examples/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-s3-examples/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [S3 controller](../../../../examples/aws-spring-boot-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/s3/S3DocumentController.kt)
- [Application entry point](../../../../examples/aws-spring-boot-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/s3/SpringBootS3ExampleApplication.kt)
- [Emulator integration test](../../../../examples/aws-spring-boot-s3-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/s3/S3DocumentControllerLocalStackTest.kt)

