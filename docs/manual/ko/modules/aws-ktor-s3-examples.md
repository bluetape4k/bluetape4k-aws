---
manualId: "aws-ktor-s3-examples"
id: "aws-ktor-s3-examples"
title: "Ktor S3 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-ktor-s3-examples"
sourceDir: "examples/aws-ktor-s3-examples"
releaseRef: "0.5.0"
artifact: null
---

# Ktor S3 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

SigV4 기반 Ktor S3 client를 직접 쓰고 server route 뒤에도 배치한다. Upload/download, streaming, 목록, 삭제, presigning, content-type 감지, 설정 object와 client-side envelope encryption을 순서대로 익힌다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Ktor HTTP client를 중심으로 S3를 연동하거나 object route를 열기 전에 `S3KtorClient` 소유권을 확인하려는 경우에 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-ktor-s3-examples:test`를 실행한다. 실제 서비스는 중앙 BOM과 `bluetape4k-aws-ktor`를 사용하며 Ktor HTTP 요청에 AWS 서명을 붙인다.

## 익혀야 할 개념 {#concepts}

`S3KtorExamples`에는 복사해 볼 client 시나리오가 있고 `s3KtorExampleModule`은 이를 route로 제공한다. Endpoint override에서는 path-style 주소를 사용한다. Factory가 만든 client와 credential provider는 `S3KtorClient`가 닫는다.

## 단계별 실습 {#quick-start}

1. `use` 블록에서 put/get 예제를 실행한다.
2. Upload, download, stream, list와 delete route를 따라간다.
3. Clock을 고정하고 presigned URL을 검증한다.
4. Content 감지와 config object를 추가한다.
5. Demo envelope encryption을 읽은 뒤 운영에서는 KMS 기반 provider로 바꾼다.

## 진입점과 기대 동작 {#api-by-task}

직접 실행 예제는 `S3KtorExamples.kt`, route는 `S3KtorServerExamples.kt`에 있다. `/s3` 아래에서 object byte/stream, prefix 목록, text config, presigned GET/PUT URL과 delete를 제공한다.

## 권장 실습 순서 {#patterns}

Bucket/key 검증은 HTTP 경계에 둔다. 큰 download는 전부 메모리에 올리지 말고 stream으로 보낸다. Move가 copy 후 delete라는 점을 반영해 일부 실패 정책을 정한다.

## 연동 경계 {#integrations}

`S3KtorClient`는 Ktor `HttpClient`와 `AwsSigV4Plugin`을 사용한다. 외부에서 준 HTTP client와 credentials provider는 호출자가 계속 소유한다.

## 설정 점검 {#configuration}

로컬 endpoint에는 region, endpoint override, 테스트 credential과 path addressing이 필요하다. 실제 AWS에서는 대개 virtual-hosted 주소와 배포 credential chain을 사용한다. Presign 기간과 signing clock도 요청 계약에 포함된다.

## 실패 유형 {#failures}

주소 방식 불일치, clock skew, 만료 credential, 잘못된 bucket/key, 끊긴 stream과 닫지 않은 client를 확인한다. In-memory encryption key provider는 운영에 쓰면 안 된다.

## 운영 {#operations}

Payload나 signed URL은 log에 남기지 않고 operation latency, status, retry, 전송 byte와 presign 실패를 기록한다. Buffer와 multipart 동시성을 제한하고 종료 시 소유 resource를 닫는다.

## 경계 테스트 {#testing}

`S3KtorExamplesTest`는 helper, deterministic presigning과 encryption을 확인한다. `S3KtorServerExamplesTest`는 명시적인 S3 응답을 주는 Ktor `MockEngine`으로 route mapping을 검증한다.

## 다음 학습 경로 {#workshops}

Spring Boot S3 워크숍에서 자동 설정한 SDK operation과 Transfer Manager를 비교하고, 실제 사용할 operation에는 emulator 통합 테스트를 더한다.

## 제약 사항 {#limitations}

MockEngine은 S3 IAM, network, multipart 복구, bucket policy, KMS 연동이나 운영 처리량을 증명하지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS ktor s3 examples 아키텍처

[![Bluetape4k AWS ktor s3 examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-ktor-s3-examples-architecture-01.svg)

_배포본 README: [`examples/aws-ktor-s3-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-ktor-s3-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [직접 실행 S3 시나리오](../../../../examples/aws-ktor-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorExamples.kt)
- [Ktor server route](../../../../examples/aws-ktor-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorServerExamples.kt)
- [Route 테스트](../../../../examples/aws-ktor-s3-examples/src/test/kotlin/io/bluetape4k/aws/examples/ktor/s3/S3KtorServerExamplesTest.kt)

