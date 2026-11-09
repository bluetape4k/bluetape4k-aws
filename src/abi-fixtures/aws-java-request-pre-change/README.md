# aws-java request helper legacy ABI fixture

이 fixture는 PR #668 이전에 배포된 `aws-java` request helper의 정적 JVM
호출을 고정합니다. Stub으로 사전 컴파일된 Java consumer를 만든 뒤 현재
`bluetape4k-aws-java` JAR로 실행해 `NoSuchMethodError` 회귀를 검증합니다.
SQS/KMS/S3 request helper, `PutRecord`, 그리고 SQS/SNS inline helper bridge의
기존 descriptor를 함께 확인합니다.
