---
manualId: "aws-ktor-s3-examples"
id: "aws-ktor-s3-examples"
title: "Ktor S3 Workshop"
locale: "en"
kind: "example"
gradlePath: ":aws-ktor-s3-examples"
sourceDir: "examples/aws-ktor-s3-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor S3 Workshop

> A runnable workshop grounded in the 0.5.0 release source.

## Learning goal {#problem}

Use the Ktor-based SigV4 S3 client both directly and behind server routes. The workshop covers object upload/download, streaming, listing, deletion, presigning, content-type detection, configuration objects, and client-side envelope encryption.

## When to use this workshop {#when-to-use}

Use it when a Ktor service wants an HTTP-client-native S3 path or needs to understand `S3KtorClient` ownership before exposing object routes.

## Project coordinates {#coordinates}

This example is not published. Run `./gradlew :aws-ktor-s3-examples:test`. Applications import the central BOM and `bluetape4k-aws-ktor`; the client signs Ktor HTTP requests with AWS credentials.

## Concepts to learn {#concepts}

`S3KtorExamples` contains copyable client scenarios. `s3KtorExampleModule` exposes them as routes. Path-style addressing is used for endpoint overrides; factory-created clients and credential providers close with `S3KtorClient`.

## Staged walkthrough {#quick-start}

1. Run the direct put/get example inside `use`.
2. Trace upload, download, streaming, list, and delete routes.
3. Freeze the clock and inspect deterministic presigned URLs.
4. Add content detection and config objects.
5. Study the demo envelope-encryption provider, then replace it with an application-owned KMS path.

## Entry points and expected behavior {#api-by-task}

`S3KtorExamples.kt` owns direct scenarios; `S3KtorServerExamples.kt` owns routes under `/s3`. The routes return bytes or streams for objects, key lists for prefixes, text config values, presigned GET/PUT URLs, and delete results.

## Recommended exercise order {#patterns}

Keep bucket/key validation at the HTTP edge and S3 behavior in the client. Stream large downloads rather than buffering them, and treat move as copy followed by delete with an explicit partial-failure policy.

## Integration boundary {#integrations}

`S3KtorClient` uses Ktor `HttpClient` plus `AwsSigV4Plugin`. A supplied HTTP client or credentials provider remains caller-owned; factory-created resources are wrapper-owned.

## Configuration checkpoints {#configuration}

Local endpoints need a region, endpoint override, test credentials, and path addressing. Real AWS normally uses virtual-hosted addressing and the deployment credential chain. Presign duration and signing clock are part of the request contract.

## Failure modes {#failures}

Wrong addressing style, clock skew, expired credentials, invalid bucket/key values, partial streams, and unclosed clients are common failures. The in-memory encryption key provider is intentionally unsafe for production.

## Operations {#operations}

Record operation latency, status, retry count, transferred bytes, and presign failures without logging signed URLs or object payloads. Bound buffers and multipart concurrency, and close owned resources on shutdown.

## Testing the boundary {#testing}

`S3KtorExamplesTest` verifies direct helpers, deterministic presigning, and encryption behavior. `S3KtorServerExamplesTest` uses a Ktor `MockEngine` with explicit S3 responses to verify route mapping without hiding HTTP requests.

## Next learning path {#workshops}

Continue to the Spring Boot S3 workshop for auto-configured SDK operations and Transfer Manager, then add real emulator coverage for the operations used by the application.

## Limitations {#limitations}

MockEngine tests do not prove S3 IAM, network behavior, multipart recovery, bucket policy, KMS integration, or production throughput.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### aws ktor s3 examples Architecture diagram

[![aws ktor s3 examples Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.svg)

_Release README: [`examples/aws-ktor-s3-examples/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-s3-examples/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Direct S3 scenarios](../../../../examples/aws-ktor-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorExamples.kt)
- [Ktor server routes](../../../../examples/aws-ktor-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorServerExamples.kt)
- [Route tests](../../../../examples/aws-ktor-s3-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorServerExamplesTest.kt)

