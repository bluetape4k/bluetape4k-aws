# 요청별 설정은 SDK가 소유한 구성 범위를 넘을 수 없다

## 배경

[`outputStreamPartSizeBytes`](https://github.com/bluetape4k/bluetape4k-aws/issues/615)는
`S3OutputStream`의 multipart part 크기를 조절하는 설정으로 추가됐다. 그러나 값을 변경해도
실제 업로드 요청에는 반영되지 않았다.

## 원인과 실패 증거

저장소가 사용하는 AWS SDK for Java v2 `2.51.3`의
[`UploadRequest`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services-custom/s3-transfer-manager/src/main/java/software/amazon/awssdk/transfer/s3/model/UploadRequest.java)와
[`UploadFileRequest`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services-custom/s3-transfer-manager/src/main/java/software/amazon/awssdk/transfer/s3/model/UploadFileRequest.java)는
요청별 part 크기 API를 제공하지 않는다. part 크기는
[`S3CrtAsyncClientBuilder.minimumPartSizeInBytes`](https://github.com/aws/aws-sdk-java-v2/blob/2.51.3/services/s3/src/main/java/software/amazon/awssdk/services/s3/S3CrtAsyncClientBuilder.java)나
`S3AsyncClientBuilder.multipartConfiguration`에서 클라이언트 단위로 정한다.

기존 `S3TransferTemplate`은 설정값을 `S3OutputStream` 생성자에 전달했지만,
`S3OutputStream.complete()`은 그 값을 `UploadRequest`나 `UploadFileRequest`에 전달할 수
없었다. 회귀 테스트는 공개 API의 deprecation 정보와 Spring configuration metadata가
없어서 각각 `NoSuchMethodException`과 `NoSuchElementException`으로 실패했다.

## 결정

- `S3Properties.Transfer.outputStreamPartSizeBytes`와 `S3OutputStream.partSizeBytes`는 즉시
  제거하지 않고 warning deprecation으로 유지한다. 기존 생성자 descriptor와 설정 바인딩은
  보존한다.
- Spring configuration metadata에도 같은 deprecation과 CRT 대체 설정
  `bluetape4k.aws.s3.crt.minimum-part-size-in-bytes`를 기록한다.
- Java multipart 클라이언트를 주입하는 사용자는 호출자가 소유한 `S3AsyncClient`의
  `MultipartConfiguration.minimumPartSizeInBytes`를 구성한다.
- 기존 설정을 기본 공유 클라이언트에 연결하지 않는다. 그렇게 하면 이름과 달리 output stream
  외의 `putObject`와 `copyObject`에도 적용 범위가 넓어진다.

## 결과

기존 바이너리와 설정 파일은 계속 동작하면서, Kotlin 호출자와 Spring metadata 소비자는
해당 값이 전송에 반영되지 않는다는 경고와 실제 클라이언트 수준 대안을 확인할 수 있다. 요청별
part 크기 지원은 별도 클라이언트/transfer-manager 수명주기 설계 없이는 제공하지 않는다.

## 검증

- RED: `S3TransferStreamingPropertiesTest`의 신규 회귀 2개가 기존 구현에서 실패
- targeted: `S3TransferStreamingPropertiesTest`, `S3OutputStreamTest` — 9개 통과
- S3 emulator: `io.bluetape4k.aws.spring.s3.*AwsEmulatorTest` — 12개 통과
- 전체 모듈: `:bluetape4k-aws-spring-boot:cleanTest
  :bluetape4k-aws-spring-boot:test --no-build-cache` — 1,616개 통과, 6개 pending
- SDK 격리 호환성: `:bluetape4k-aws-spring-boot:compatibilityTest` — 64개 통과
- 정적 분석: `:bluetape4k-aws-spring-boot:detekt` — 성공
- 패키징: `:bluetape4k-aws-spring-boot:assemble` — 성공, JAR의 deprecation metadata 확인
- API/ABI: 기준 `develop`과 후보 class의 public JVM descriptor 집합 일치

hosted GitHub CI는 PR exact-head 단계에서 별도로 확인한다.

## 놓친 점

설정 이름과 KDoc만 보고 실제 SDK builder와 request 모델이 값을 받을 수 있는지 검증하지
않았다. 래퍼가 값을 보관한다는 사실은 외부 SDK가 그 값을 소비한다는 증거가 아니다.

## 향후 지침

- 새 설정을 공개하기 전에 SDK의 실제 적용 지점이 request, client, manager 중 어디인지
  source와 테스트로 확인한다.
- client-wide 설정을 요청별 이름으로 노출하지 않는다. 적용 범위가 다르면 설정을
  분리하거나 지원하지 않는다고 명시한다.
- 외부 SDK에 전달할 수 없는 호환성용 값은 deprecation, metadata, 대체 경로를 함께
  제공하고 다음 breaking release에서 제거한다.
