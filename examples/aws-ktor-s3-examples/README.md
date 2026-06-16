# aws-ktor-s3-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for the `aws-ktor` S3 REST client. The module keeps two usage
paths side by side: `S3KtorExamples` for copyable client scenarios, and
`s3KtorExampleModule` for server routes that expose upload, download, streaming
download, listing, delete, presigned URL, content-type detection, and S3-backed
config object endpoints. Tests use `bluetape4k-ktor-testing` for response
assertions while keeping the Ktor `MockEngine` behavior explicit for S3 request
verification.

## Architecture

![aws ktor s3 examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.png)

## Client Example

```kotlin
S3KtorExamples.localStackClient().use { s3 ->
    s3.putObject("demo-bucket", "docs/hello.txt", "hello".encodeToByteArray())
    val text = s3.getObjectBytes("demo-bucket", "docs/hello.txt").decodeToString()
}
```

## Advanced Scenario

Use `S3KtorExamples.storeAndLoadConfig` to bootstrap a text Ktor config object
from S3, `uploadWithDetectedContentType` when the inbound request has no trusted
`Content-Type`, and `encryptAndDecryptText` to demonstrate client-side envelope
encryption with an in-memory demo data-key provider. The provider is intentionally
local-only; production encryption providers should wrap KMS or another
application-owned key service.

## Server Routes

| Method | Path | Description |
|---|---|---|
| `PUT` | `/s3/detected-objects/{key...}` | Upload bytes and detect content type from key/payload |
| `PUT` | `/s3/objects/{key...}` | Upload request body bytes |
| `GET` | `/s3/objects/{key...}` | Download object bytes |
| `GET` | `/s3/objects/{key...}/stream` | Download through `getObjectStream` |
| `GET` | `/s3/objects?prefix={prefix}` | List object keys |
| `PUT` | `/s3/config/{key...}` | Store a text config object |
| `GET` | `/s3/config/{key...}` | Load a text config object |
| `GET` | `/s3/presigned-get/{key...}` | Create a presigned download URL |
| `GET` | `/s3/presigned-put/{key...}` | Create a presigned upload URL |
| `DELETE` | `/s3/objects/{key...}` | Delete an object |

## Configuration

For LocalStack, use path-style addressing and an endpoint override:

```kotlin
val s3 = s3KtorClientOf(
    region = "ap-northeast-2",
    endpointOverride = Url("http://localhost:4566"),
    addressingStyle = S3KtorAddressingStyle.Path,
)
```

## Test

```bash
./gradlew :aws-ktor-s3-examples:test
```

The tests verify deterministic presigned URL generation, the in-memory data-key
provider, and route behavior through a Ktor `MockEngine` that returns explicit S3
responses.
