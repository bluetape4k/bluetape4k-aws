# aws-ktor-s3-examples

[English](./README.md) | 한국어

`aws-ktor` S3 REST client를 사용하는 Ktor 3 예제입니다. 이 모듈은 두 사용 경로를
나란히 보여줍니다. `S3KtorExamples`는 복사해 쓸 수 있는 client scenario를 제공하고,
`s3KtorExampleModule`은 upload, download, streaming download, listing, delete,
presigned URL, content-type detection, S3 기반 config object endpoint를 route로
노출합니다. 테스트는 response assertion에 `bluetape4k-ktor-testing`을 사용하되, S3
request 검증을 위해 Ktor `MockEngine` 동작은 명시적으로 유지합니다.

## 아키텍처

![aws ktor s3 examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.png)

## 클라이언트 예제

```kotlin
S3KtorExamples.localStackClient().use { s3 ->
    s3.putObject("demo-bucket", "docs/hello.txt", "hello".encodeToByteArray())
    val text = s3.getObjectBytes("demo-bucket", "docs/hello.txt").decodeToString()
}
```

## 고급 시나리오

`S3KtorExamples.storeAndLoadConfig` 는 S3에서 Ktor text config object를 bootstrap하는
흐름을 보여줍니다. `uploadWithDetectedContentType` 은 신뢰할 수 있는 `Content-Type`
header가 없을 때 key/payload 기반 감지를 사용하고, `encryptAndDecryptText` 는 in-memory
demo data-key provider로 client-side envelope encryption 흐름을 보여줍니다. 이 provider는
로컬 예제용입니다. 운영용 provider는 KMS 또는 애플리케이션이 소유한 key service를
감싸야 합니다.

## 서버 Route

| Method | Path | 설명 |
|---|---|---|
| `PUT` | `/s3/detected-objects/{key...}` | key/payload 기반 content type 감지 후 업로드 |
| `PUT` | `/s3/objects/{key...}` | 요청 본문 bytes 업로드 |
| `GET` | `/s3/objects/{key...}` | 객체 bytes 다운로드 |
| `GET` | `/s3/objects/{key...}/stream` | `getObjectStream` 기반 다운로드 |
| `GET` | `/s3/objects?prefix={prefix}` | 객체 key 목록 |
| `PUT` | `/s3/config/{key...}` | text config object 저장 |
| `GET` | `/s3/config/{key...}` | text config object 로드 |
| `GET` | `/s3/presigned-get/{key...}` | 다운로드용 presigned URL 생성 |
| `GET` | `/s3/presigned-put/{key...}` | 업로드용 presigned URL 생성 |
| `DELETE` | `/s3/objects/{key...}` | 객체 삭제 |

## 설정

LocalStack에서는 path-style 주소와 endpoint override를 사용합니다.

```kotlin
val s3 = s3KtorClientOf(
    region = "ap-northeast-2",
    endpointOverride = Url("http://localhost:4566"),
    addressingStyle = S3KtorAddressingStyle.Path,
)
```

## 테스트

```bash
./gradlew :aws-ktor-s3-examples:test
```

테스트는 deterministic presigned URL 생성, in-memory data-key provider, 그리고 명시적
S3 response를 반환하는 Ktor `MockEngine` 기반 route 동작을 검증합니다.
