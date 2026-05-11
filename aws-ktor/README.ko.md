# Module bluetape4k-aws-ktor

[English](README.md) | [한국어](README.ko.md)

bluetape4k AWS 모듈을 위한 Ktor 3 통합 모듈입니다. Ktor `HttpClient`의 outgoing
AWS HTTP 요청에 Signature Version 4 서명을 적용하는 플러그인과, 그 위에 구축한
coroutine 친화적 S3 REST client를 제공합니다.

## 기능

- Ktor `HttpClient`용 `AwsSigV4Plugin`.
- Static, Default, Profile, Session provider를 포함한 AWS SDK Java v2
  `AwsCredentialsProvider` 연동.
- 헤더 서명과 쿼리 문자열 서명.
- region, service, path normalization, URL encoding, payload signing, clock
  주입 옵션.
- PutObject, GetObject, DeleteObject, ListObjectsV2, multipart upload,
  presigned GET/PUT URL을 지원하는 `S3KtorClient`.

## 의존성

`aws-ktor` 는 Ktor client core와 AWS auth API를 노출하지만 CIO engine은
`compileOnly` 로 둡니다. 애플리케이션은 실제 실행에 사용할 Ktor engine을 직접
추가해야 합니다.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.aws:aws-ktor:${bluetape4kAwsVersion}")
    implementation("io.ktor:ktor-client-cio")
}
```

## 사용법

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

애플리케이션이 직접 만든 `HttpClient` 는 애플리케이션 scope 종료 시 닫아야 합니다.

## Payload 서명

플러그인은 body가 없는 요청과 replay 가능한 `OutgoingContent.ByteArrayContent`
payload를 직접 서명합니다. 임의의 Ktor streaming content는 엔진 전송 전에 안전하게
소비하고 재생할 수 없으므로 `payloadSigningEnabled=true`일 때 거부합니다.

대상 AWS 서비스가 해당 요청에서 unsigned payload를 허용할 때만
`payloadSigningEnabled = false`를 설정하세요.

```kotlin
install(AwsSigV4Plugin) {
    region = "ap-northeast-2"
    service = "execute-api"
    payloadSigningEnabled = false
}
```

## S3 Client

`S3KtorClient`는 동일한 SigV4 플러그인을 사용하며 S3 전용 signing flag
(`doubleUrlEncode=false`, `normalizePath=false`, unsigned payload)를 적용합니다.
LocalStack 같은 path-style endpoint와 DNS-safe bucket의 virtual-hosted AWS S3
endpoint를 모두 지원합니다. `endpointOverride` 를 지정하면 path-style URL을
사용합니다.

```kotlin
import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
import io.bluetape4k.aws.ktor.s3.S3KtorAddressingStyle
import io.ktor.http.Url
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

val s3 = s3KtorClientOf(
    region = "ap-northeast-2",
    credentialsProvider = DefaultCredentialsProvider.builder().build(),
    endpointOverride = Url("http://localhost:4566"), // LocalStack 사용 시
    addressingStyle = S3KtorAddressingStyle.Path,
)

suspend fun roundTrip(bucket: String, key: String): String {
    s3.putObject(
        bucket = bucket,
        key = key,
        bytes = "hello".encodeToByteArray(),
        contentType = "text/plain; charset=utf-8",
    )
    return s3.getObjectBytes(bucket, key).decodeToString()
}

val download = s3.presignGetObject(bucket = "demo-bucket", key = "hello.txt", expires = java.time.Duration.ofMinutes(15))
```

`s3KtorClientOf` 는 내부에서 만든 `HttpClient` 를 소유하므로 짧게 쓰는 client는
`close()` 또는 Kotlin `use { ... }` 로 닫습니다. Presigned URL 만료 시간은 1초 이상
7일 이하여야 합니다.

실행 가능한 예제는 `examples/aws-ktor-s3-examples`에 있으며 Nightly workflow에
포함됩니다.
