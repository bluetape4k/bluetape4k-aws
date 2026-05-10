# aws-ktor

[English](README.md) | [한국어](README.ko.md)

Ktor 3 integration for bluetape4k AWS modules. The first feature is a Ktor
`HttpClient` plugin that signs outgoing AWS HTTP requests with Signature
Version 4.

## Features

- `AwsSigV4Plugin` for Ktor `HttpClient`.
- AWS SDK Java v2 `AwsCredentialsProvider` integration, including static,
  default, profile, and session providers.
- Header signing and query-string signing.
- Deterministic signing options for region, service, path normalization, URL
  encoding, payload signing, and clock injection.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:aws-ktor:${bluetape4kAwsVersion}")
    implementation("io.ktor:ktor-client-cio")
}
```

## Usage

```kotlin
import io.bluetape4k.aws.ktor.client.AwsSigV4Plugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

val client = HttpClient(CIO) {
    install(AwsSigV4Plugin) {
        region = "ap-northeast-2"
        service = "execute-api"
        credentialsProvider = DefaultCredentialsProvider.builder().build()
    }
}

val response = client.get("https://example.execute-api.ap-northeast-2.amazonaws.com/prod/orders")
```

## Payload Signing

The plugin signs no-body requests and replayable `OutgoingContent.ByteArrayContent`
payloads directly. Streaming content is rejected while `payloadSigningEnabled`
is `true`, because a client plugin cannot safely consume and replay arbitrary
Ktor streams before the engine sends them.

Set `payloadSigningEnabled = false` only when the target AWS service accepts an
unsigned payload for the request shape.

```kotlin
install(AwsSigV4Plugin) {
    region = "ap-northeast-2"
    service = "execute-api"
    payloadSigningEnabled = false
}
```
