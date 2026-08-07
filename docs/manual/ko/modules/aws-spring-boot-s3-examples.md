---
manualId: "aws-spring-boot-s3-examples"
id: "aws-spring-boot-s3-examples"
title: "Spring Boot S3 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":aws-spring-boot-s3-examples"
sourceDir: "examples/aws-spring-boot-s3-examples"
releaseRef: "0.5.0"
artifact: null
---

# Spring Boot S3 워크숍

> 0.5.0 릴리스 소스로 직접 실행하는 워크숍입니다.

## 학습 목표 {#problem}

작은 Spring Boot WebFlux controller에서 S3 object operation을 제공한다. 자동 설정한 operation bean에 SDK 세부 동작을 맡기고, upload/download에 목록, 삭제, presigning과 선택적 client-side encryption을 추가한다.

## 이 워크숍이 맞는 경우 {#when-to-use}

Spring 서비스에 S3 template, presigned URL 또는 envelope encryption이 필요하고 각 기능을 활성화하는 SDK module과 bean을 확인하려는 경우에 적합하다.

## 프로젝트 실행 방법 {#coordinates}

배포하지 않는 예제다. `./gradlew :aws-spring-boot-s3-examples:test`를 실행한다. 실제 서비스는 중앙 BOM, `bluetape4k-aws-spring-boot`와 `software.amazon.awssdk:s3`를 사용하며 encrypted route에는 KMS 지원도 필요하다.

## 익혀야 할 개념 {#concepts}

`S3DocumentController`는 `S3Operations`/`S3CoroutinesTemplate`에 위임한다. 선택적인 `S3ClientSideEncryptionOperations`는 envelope metadata와 함께 암호화한 데이터를 저장한다. 자동 설정이 service client를 만들고 닫는다.

## 단계별 실습 {#quick-start}

1. Object 하나를 upload하고 다시 download한다.
2. Prefix 목록을 읽고 object를 삭제한다.
3. Presigned GET/PUT URL을 만들고 만료 시간을 확인한다.
4. Deterministic test KMS로 encrypted route를 활성화한다.
5. 설정이나 controller type을 바꿨다면 AOT task도 실행한다.

## 진입점과 기대 동작 {#api-by-task}

`SpringBootS3ExampleApplication`이 서비스를 시작하고 `S3DocumentController`가 `/s3/documents`를 제공한다. Plain/encrypted upload와 download, object 목록, presigned URL과 delete를 지원한다.

## 권장 실습 순서 {#patterns}

Bucket/key 검증은 HTTP 경계에서 처리하고 크기를 알 수 없는 payload는 stream으로 다룬다. Object naming, retention, encryption context와 권한 정책은 transport helper와 분리한다.

## 연동 경계 {#integrations}

필요한 class와 property가 있으면 Spring이 S3 client, presigner, template과 선택적 encryption operation을 만든다. Controller는 이 bean을 닫지 않는다.

## 설정 점검 {#configuration}

Region과 emulator용 endpoint/path-style을 설정하고 presign 기간을 정한다. Encryption에는 key ID, 안정적인 encryption context, `KmsOperations`와 AWS KMS runtime module이 필요하다.

## 실패 유형 {#failures}

없는 bucket, 주소 방식 오류, 만료된 presigned URL, 일부 upload 실패, KMS bean 누락, encryption context 불일치와 과도한 buffering을 확인한다.

## 운영 {#operations}

Object 내용이나 signed query는 log에 남기지 않고 latency, byte, 실패, retry와 presign 수를 기록한다. Multipart, retention, versioning과 일부 실패 정책은 controller 밖에서 정한다.

## 경계 테스트 {#testing}

`S3DocumentControllerLocalStackTest`는 emulator를 고르고 bucket을 만든 뒤 upload, download, list, presigned URL, delete와 deterministic KMS encryption을 검증한다.

## 다음 학습 경로 {#workshops}

Ktor S3 워크숍에서 직접 SigV4 HTTP 동작을 비교하고, 실제 IAM과 큰 object 전송, real KMS smoke test를 추가한다.

## 제약 사항 {#limitations}

Test KMS는 AWS KMS 권한과 ciphertext 동작을 증명하지 않는다. 큰 전송, multipart 복구, versioned delete와 bucket policy도 다루지 않는다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS spring boot s3 examples 아키텍처

[![Bluetape4k AWS spring boot s3 examples 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/examples-aws-spring-boot-s3-examples-architecture-01.svg)

_배포본 README: [`examples/aws-spring-boot-s3-examples/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/examples/aws-spring-boot-s3-examples/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 소스 {#sources}

- [S3 controller](../../../../examples/aws-spring-boot-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/s3/S3DocumentController.kt)
- [Application 진입점](../../../../examples/aws-spring-boot-s3-examples/src/main/kotlin/io/bluetape4k/aws/examples/spring/s3/SpringBootS3ExampleApplication.kt)
- [Emulator 통합 테스트](../../../../examples/aws-spring-boot-s3-examples/src/test/kotlin/io/bluetape4k/aws/examples/spring/s3/S3DocumentControllerLocalStackTest.kt)

