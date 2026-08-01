# Issue 206 Spring AWSpring parity 예제

## 배경

Issue #206에서는 0.3.0 AWSpring parity 작업 중 Spring Boot 예제 범위를 마무리한다.
마일스톤 범위는 S3/SQS와 공통 기본값이며 CloudWatch, IMDS, DAX는 0.4.0 버전으로
미뤘다.

## 결정

다른 모듈을 만들지 않고 기존 Spring Boot S3/SQS 예제를 확장한다. 이제 예제에서 선택적
S3 client-side 암호화, typed SQS payload 변환, 수동 acknowledgement, 재시도, listener
interceptor event를 다룬다.

## 결과

예제에서 발견한 SQS listener 통합 공백 두 가지도 수정했다.

- Kotlin suspend listener의 synthetic/static helper method를 listener method로 등록해서는 안 된다.
- SQS listener 후처리기를 만들기 전에 Jackson 기반 SQS 변환을 auto-configure해야 한다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*SqsAutoConfigurationTest' --tests '*SqsListenerAwsEmulatorTest' :aws-spring-boot-s3-examples:test :aws-spring-boot-sqs-examples:test --no-daemon --max-workers=1`
- `git diff --check`

## 향후 보호 장치

예제 issue는 adoption 테스트로 취급한다. 예제에 공개 auto-configuration 계약의
workaround가 필요하다면 예제 전용 연결에 문제를 숨기지 말고 계약을 수정한 뒤 예제
테스트로 검증한다.
