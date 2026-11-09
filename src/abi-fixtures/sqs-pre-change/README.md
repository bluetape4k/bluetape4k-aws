# SQS legacy ABI fixture

이 fixture는 Issue #455 변경 전 `SqsOperations` 공개 ABI를 고정한다. 검증 작업은
깨끗한 `aws-spring-boot` JAR의 클래스 바이트와 현재 소스 해시, `javap -public`
출력을 함께 비교한다. 확장 SQS SDK 타입은 이 fixture classpath에 추가하지 않는다.

`javap.txt`는 Issue #455의 공개 signature 기준을 유지한다. `source.sha256`와
`bytecode.sha256`는 fixture 생성 이후 승인된 #460 train의 additive
`SqsQueueAttributesOperations`와 후속 source 정리(`86f834ae`, `6e3b4d3b`)를
반영한 현재 implementation baseline이다. 따라서 implementation baseline을
갱신할 때는 public signature가 그대로인지 먼저 확인한 뒤, 현재 JAR에서 다음
두 값을 다시 캡처한다.

```bash
sha256sum aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsOperations.kt
unzip -p aws-spring-boot/build/libs/bluetape4k-aws-spring-boot-1.1.0.jar \
  io/bluetape4k/aws/spring/sqs/SqsOperations.class | shasum -a 256
```
