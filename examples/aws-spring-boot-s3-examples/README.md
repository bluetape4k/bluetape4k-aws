# aws-spring-boot-s3-examples

English | [한국어](./README.ko.md)

Spring Boot 4 WebFlux examples for `aws-spring-boot` S3 auto-configuration.
The module demonstrates `S3CoroutinesTemplate` through the `S3Operations`
interface with upload, download, object listing, delete, and presigned URL APIs.

## Architecture

![Architecture 1](../../docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-diagram-01.svg)

## API

| Method | Path | Description |
|---|---|---|
| `PUT` | `/s3/documents?bucket={bucket}&key={key}` | Upload request body bytes |
| `GET` | `/s3/documents?bucket={bucket}&key={key}` | Download object bytes |
| `GET` | `/s3/documents/objects?bucket={bucket}&prefix={prefix}` | Stream object keys |
| `GET` | `/s3/documents/presigned-get?bucket={bucket}&key={key}` | Create a presigned download URL |
| `GET` | `/s3/documents/presigned-put?bucket={bucket}&key={key}` | Create a presigned upload URL |
| `DELETE` | `/s3/documents?bucket={bucket}&key={key}` | Delete an object |

## Configuration

```yaml
bluetape4k:
  aws:
    s3:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      path-style-access-enabled: true
      presign:
        duration: PT15M
```

`endpoint-override` and `path-style-access-enabled` are useful for LocalStack.
For real AWS S3, omit `endpoint-override` and let the AWS SDK resolve credentials.

## Run

```bash
./gradlew :aws-spring-boot-s3-examples:bootRun
```

## Test

```bash
./gradlew :aws-spring-boot-s3-examples:test
```

The test starts LocalStack with Testcontainers, creates a bucket, then verifies
upload, download, list, presigned GET/PUT URL generation, and delete behavior.

## AOT

All Spring Boot examples are wired for Spring AOT through GraalVM Native Build
Tools. Verify this example with:

```bash
./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-s3-examples:processTestAot
```
