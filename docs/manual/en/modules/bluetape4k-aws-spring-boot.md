---
manualId: "bluetape4k-aws-spring-boot"
id: "bluetape4k-aws-spring-boot"
title: "AWS Spring Boot Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-aws-spring-boot"
sourceDir: "aws-spring-boot"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-spring-boot
---

# AWS Spring Boot Integration

> Library manual grounded in the 0.5.0 release source.

## Problem {#problem}

Spring Boot 4 auto-configuration, coroutine templates, repositories, listeners, configuration sources, and Micrometer instrumentation for selected AWS services.

## When to use it {#when-to-use}

Use it when Spring should own AWS clients and application-facing templates while the application keeps explicit control of service SDK dependencies.

## Coordinates {#coordinates}

Applications select one central BOM version.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
}
```

AWS service SDKs follow a `compileOnly` policy; add the services actually used as runtime dependencies.

## Core concepts {#concepts}

Conditional auto-configurations back off when a service SDK is absent. Properties customize region, endpoint, credentials, and service behavior; templates expose suspend operations; listener containers own background jobs.

## Quick start {#quick-start}

```kotlin
@Service
class ObjectStore(private val s3: S3Operations) {
    suspend fun put(bucket: String, key: String, bytes: ByteArray) =
        s3.upload(bucket, key, bytes)
}
```

## API by task {#api-by-task}

S3 object and Transfer Manager operations, provider-based client-side
encryption, DynamoDB repositories and DAX, SQS listener/runtime, SNS, SES,
KMS, CloudWatch, IMDS, Secrets Manager, Parameter Store, S3 Access
Grants/Vectors, and Exposed.

## S3 client-side encryption providers {#s3-client-side-encryption}

> Unreleased/develop: this section describes the Issue #475 API and is not part of the `0.5.0` release source.

`S3ClientSideEncryptionOperations` is opt-in. `provider=KMS` remains the
default and keeps the existing `KmsOperations`-backed byte-array contract.
KMS does not expose the provider streaming adapter. Set `provider=AES` or
`provider=RSA` to use application-supplied key material:

```yaml
bluetape4k:
  aws:
    s3:
      client-side-encryption:
        enabled: true
        provider: AES
        key-id: orders-key
        key-version: 2026-08
        encryption-context:
          service: order-api
      transfer:
        enabled: true
```

### Provider selection {#s3-cse-provider-selection}

| `provider` | Required bean | Contract |
| --- | --- | --- |
| `KMS` (default) | `KmsOperations` | Existing `S3ClientSideEncryptionTemplate`; byte-array operations |
| `AES` | Exactly one `S3AesProvider` | `S3ClientSideEncryptionProviderTemplate` with an AES secret key |
| `RSA` | Exactly one `S3RsaProvider` | `S3ClientSideEncryptionProviderTemplate` with an RSA key pair |

The selected AES/RSA provider must be unique. A missing or ambiguous provider
fails application startup with a message that names the required provider and
the configured `provider` value. The configuration never silently switches to
KMS or to another provider.

The application loads key material from its own secret-management boundary and
registers the matching provider:

```kotlin
import io.bluetape4k.aws.spring.s3.S3AesProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.crypto.SecretKey

@Configuration
class S3EncryptionConfiguration {
    @Bean
    fun s3AesProvider(secret: SecretKey): S3AesProvider =
        S3AesProvider.of(secret)
}
```

Use `S3RsaProvider.of(keyPair)` for RSA. AES material must expose 16, 24, or
32 encoded bytes. RSA public and private keys must use the RSA algorithm, have
the same modulus, and be at least 2048 bits. The provider template fixes the
material at construction time so encryption and decryption use the same key.

### Data and metadata contract {#s3-cse-envelope}

Each provider object gets a random 32-byte AES data key and a 12-byte payload
nonce. The payload uses `AES/GCM/NoPadding` with a 128-bit authentication tag.
The AES provider wraps the data key with AES-GCM; the RSA provider uses
`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`.

The object stores the following Bluetape4k-reserved metadata. User metadata may
not reuse these keys, case-insensitively:

| Metadata | Meaning |
| --- | --- |
| `bt4k-cek-version` | Provider envelope version `2` |
| `bt4k-cek-provider` | `aes` or `rsa` |
| `bt4k-cek-alg` | `AES/GCM/NoPadding` |
| `bt4k-cek-wrap-alg` | Provider key-wrapping algorithm |
| `bt4k-cek-encoding` | `base64` |
| `bt4k-cek`, `bt4k-cek-nonce` | Wrapped data key and payload nonce |
| `bt4k-cek-wrap-nonce` | AES wrapping nonce; absent for RSA |
| `bt4k-cek-key-id`, `bt4k-cek-key-version` | Provider identity and optional version |

`key-id` and `key-version` identify the provider material. When `key-id` is
omitted, the template uses a fingerprint derived from the provider material.
For provider envelopes, a configured `key-version` must match on decryption.
The merged encryption context is canonicalized as AES-GCM additional
authenticated data; it is not written to metadata, logs, or temporary files.

Malformed metadata, unsupported algorithms or encoding, invalid key material,
and provider/key identity mismatches fail before plaintext is returned.
Wrapping, unwrapping, and payload authentication failures use
`S3ClientSideEncryptionException`. Provider envelopes are a Bluetape4k wire
format and are not compatible with the AWS Encryption SDK or unrelated S3
clients. Existing KMS metadata remains on the KMS implementation path.

### Typed and transfer APIs {#s3-cse-transfer}

The common `S3ClientSideEncryptionOperations` extensions
`uploadEncryptedObject` and `downloadEncryptedObject` reuse
`S3ObjectConverter` with both the KMS and provider templates. The converter
creates the serialized bytes; the selected encryption operation owns the
encrypted upload and zeroizes its temporary byte arrays after the call.

When `software.amazon.awssdk:s3-transfer-manager` is available,
`bluetape4k.aws.s3.transfer.enabled=true`, and the provider transfer
conditions are satisfied, auto-configuration adds
`S3ClientSideEncryptionTransferOperations`:

```kotlin
class SecureFiles(
    private val encryptedTransfer: S3ClientSideEncryptionTransferOperations,
) {
    suspend fun write(bucket: String, key: String, bytes: ByteArray) {
        encryptedTransfer.encryptedOutputStream(bucket, key).use { output ->
            output.write(bytes)
        }
    }
}
```

`encryptedOutputStream` encrypts before the underlying `S3OutputStream`.
`complete()` is the recommended suspend completion path and writes the GCM
authentication tag; `close()` is a blocking compatibility path. If the stream
spills after its threshold, the temporary file contains ciphertext only.

`downloadEncryptedFile` obtains a size and ETag snapshot, downloads with
`If-Match`, checks the ciphertext size, authenticates the complete ciphertext,
and writes the plaintext destination only after decryption succeeds. The
temporary ciphertext file and decryption buffers are cleaned up on success,
failure, and cancellation. An authentication failure therefore leaves the
existing destination untouched. The final destination write runs in a
non-cancellable I/O boundary; to preserve an existing destination without
creating a plaintext temporary file, an existing destination is bounded by the
same `MAX_CIPHERTEXT_BYTES` limit and is restored in memory if the write fails.

## Recommended patterns {#patterns}

Put client and background-job ownership at one application boundary. Configure region, credentials, and endpoints once instead of rebuilding them per call.

## Integrations {#integrations}

Import the central BOM, add this library without a version, then add only `software.amazon.awssdk:<service>` modules used by enabled auto-configurations.

## Configuration {#configuration}

Use the `bluetape4k.aws` property namespace, endpoint overrides for emulators,
and customizer beans when client-builder control must stay in application code.
For S3 client-side encryption, set
`bluetape4k.aws.s3.client-side-encryption.enabled=true` and select the provider
explicitly when using AES or RSA. Register exactly one matching provider bean;
the KMS default only creates its encryption bean when `KmsOperations` is
present. Transfer-based provider encryption also requires the transfer manager
and `bluetape4k.aws.s3.transfer.enabled=true`.

## Failure modes {#failures}

Most missing-bean failures mean the service SDK is absent or a condition did not
match. For S3 client-side encryption, a KMS default without `KmsOperations`
backs off without replacing it with AES/RSA. An AES/RSA configuration with no
matching provider, or with more than one candidate, fails startup. Invalid key
lengths, RSA key pairs, reserved metadata collisions, malformed envelope
metadata, and provider/key identity mismatches fail closed. Authentication or
wrapping failures surface as `S3ClientSideEncryptionException` and never
return plaintext. SQS visibility, listener concurrency, payload conversion,
and shutdown timeouts require explicit tuning.

## Operations {#operations}

Expose Micrometer metrics, make listener acknowledgement policy explicit, close
custom clients, and keep environment property-source calls out of request paths.
Keep the source `SecretKey`/`KeyPair`, its storage, and its rotation policy in
the application boundary. Spring closes the provider template's internal
material on context shutdown; it does not provide a key store, rotation
service, or HSM integration. Do not log key material, provider context, or
plaintext payloads.

## Testing {#testing}

Use `ApplicationContextRunner` for conditional beans and Floci-backed
integration tests for enabled services. The provider acceptance lane covers
AES byte/typed/stream/file operations, RSA byte operations, ciphertext
metadata, and KMS regression:

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-spring-boot:test \
  --tests '*S3ClientSideEncryptionProviderAwsEmulatorTest'
```

Test listener shutdown and redelivery, not only successful sends. The Floci
test does not prove AWS quotas, production credentials, or HSM behavior.

## Workshops and learning path {#workshops}

Read `auto-configuration`, then `storage-and-messaging`, then `runtime-operations`; run the four released Spring Boot examples.

## Limitations {#limitations}

This is not awspring and does not enable every AWS service automatically.
Optional integrations remain absent until their classes and properties are
present. KMS client-side encryption remains byte-array-based at its primitive
operation boundary and has no provider transfer adapter. The shared typed
extensions work with either KMS or provider implementations; provider
streaming and file APIs use the Bluetape4k envelope described above. The
envelope is not an AWS Encryption SDK format and does not promise
interoperability with other S3 clients. This module does not store, rotate, or
recover keys and does not provide HSM or legal compliance guarantees.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### AWS Spring Boot architecture diagram

[![AWS Spring Boot architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-architecture-01.svg)

_Release README: [`aws-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.md)_

### AWS Spring Boot configuration flow diagram

[![AWS Spring Boot configuration flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-flow-02.svg)

_Release README: [`aws-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.md)_

### AWS Spring Boot SQS listener sequence diagram

[![AWS Spring Boot SQS listener sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-sequence-03.svg)

_Release README: [`aws-spring-boot/README.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Release source: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`](../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [Release source: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [Release test: auto-configuration](../../../../aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/AwsAutoConfigurationTest.kt)
