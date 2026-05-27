# aws-spring-boot-s3-examples

[English](./README.md) | 한국어

`aws-spring-boot` S3 자동설정을 사용하는 Spring Boot 4 WebFlux 예제입니다.
`S3Operations` 인터페이스를 통해 `S3CoroutinesTemplate` 기반 업로드, 다운로드,
객체 목록, 삭제, presigned URL API를 보여줍니다.
선택적으로 `S3ClientSideEncryptionOperations` route도 제공해 KMS 기반 envelope
metadata와 함께 payload를 암호화 저장하는 흐름을 확인할 수 있습니다.

## 아키텍처

![aws spring boot s3 examples Architecture diagram](../../docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-architecture-01.png)

## API

| Method | Path | 설명 |
|---|---|---|
| `PUT` | `/s3/documents?bucket={bucket}&key={key}` | 요청 본문 bytes 업로드 |
| `PUT` | `/s3/documents/encrypted?bucket={bucket}&key={key}&tenant={tenant}` | client-side envelope encryption과 metadata로 bytes 업로드 |
| `GET` | `/s3/documents?bucket={bucket}&key={key}` | 객체 bytes 다운로드 |
| `GET` | `/s3/documents/encrypted?bucket={bucket}&key={key}&tenant={tenant}` | 암호화 객체 다운로드 및 복호화 |
| `GET` | `/s3/documents/objects?bucket={bucket}&prefix={prefix}` | 객체 key 스트리밍 |
| `GET` | `/s3/documents/presigned-get?bucket={bucket}&key={key}` | 다운로드용 presigned URL 생성 |
| `GET` | `/s3/documents/presigned-put?bucket={bucket}&key={key}` | 업로드용 presigned URL 생성 |
| `DELETE` | `/s3/documents?bucket={bucket}&key={key}` | 객체 삭제 |

## 설정

```yaml
bluetape4k:
  aws:
    s3:
      region: ap-northeast-2
      endpoint-override: http://localhost:4566
      path-style-access-enabled: true
      presign:
        duration: PT15M
      client-side-encryption:
        enabled: true
        key-id: alias/example-s3
        encryption-context:
          app: spring-s3-example
```

`endpoint-override`와 `path-style-access-enabled`는 LocalStack에서 유용합니다.
실제 AWS S3에서는 `endpoint-override`를 생략하고 AWS SDK credential chain을 사용합니다.
암호화 route는 `KmsOperations` bean과 AWS SDK KMS runtime module이 필요합니다.

## 실행

```bash
./gradlew :aws-spring-boot-s3-examples:bootRun
```

## 테스트

```bash
./gradlew :aws-spring-boot-s3-examples:test
```

테스트는 Testcontainers로 LocalStack을 시작하고 bucket을 만든 뒤 업로드, 다운로드,
목록, presigned GET/PUT URL 생성, 삭제 동작과 deterministic test KMS 구현을 통한
client-side encryption helper를 검증합니다.

## AOT

모든 Spring Boot 예제는 GraalVM Native Build Tools 를 통해 Spring AOT 태스크가
생성되도록 구성합니다. 이 예제는 다음 명령으로 검증합니다.

```bash
./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-s3-examples:processTestAot
```
