# aws-ktor-s3-examples

English | [한국어](./README.ko.md)

Ktor 3 examples for the `aws-ktor` S3 REST client. The module includes a
LocalStack-oriented client helper and server routes that expose upload, download,
stream download, object listing, delete, and presigned URL endpoints.

## Architecture

![Architecture 1](../../docs/images/readme-diagrams/examples-aws-ktor-s3-examples-diagram-01.svg)

## Client Example

```kotlin
S3KtorExamples.localStackClient().use { s3 ->
    s3.putObject("demo-bucket", "docs/hello.txt", "hello".encodeToByteArray())
    val text = s3.getObjectBytes("demo-bucket", "docs/hello.txt").decodeToString()
}
```

## Server Routes

| Method | Path | Description |
|---|---|---|
| `PUT` | `/s3/objects/{key...}` | Upload request body bytes |
| `GET` | `/s3/objects/{key...}` | Download object bytes |
| `GET` | `/s3/objects/{key...}/stream` | Download through `getObjectStream` |
| `GET` | `/s3/objects?prefix={prefix}` | List object keys |
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

The tests verify presigned URL generation and Ktor route behavior through a
Ktor `MockEngine`.
