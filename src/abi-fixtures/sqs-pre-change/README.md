# SQS legacy ABI fixture

이 fixture는 Issue #455 변경 전 `SqsOperations` 공개 ABI를 고정한다. 검증 작업은
깨끗한 `aws-spring-boot` JAR의 클래스 바이트와 현재 소스 해시, `javap -public`
출력을 함께 비교한다. 확장 SQS SDK 타입은 이 fixture classpath에 추가하지 않는다.
