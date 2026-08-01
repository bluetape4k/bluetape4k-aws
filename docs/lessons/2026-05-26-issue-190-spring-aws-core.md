# Issue #190 Spring AWS 핵심 기반

## 배경

`aws-spring-boot`의 서비스 auto-configuration마다 credentials, region, endpoint,
client-builder 연결을 반복했다. 0.3.0 계획에서 S3/SQS 강화 작업보다 먼저 공통 기반이
필요했다.

## 결정

공통 `bluetape4k.aws` 기본값과 builder customizer hook을 추가하되 서비스별 property에
더 높은 우선순위를 둔다. Spring Boot auto-configuration 단계 규칙에 맞춰
`AwsAutoConfiguration`을 `bluetape4k.aws.enabled` 뒤에 둔다. Web-identity credentials는
계속 opt-in이며 STS classpath 검사로 보호한다.

## 결과

이제 S3, SQS, SNS, SES, KMS, DynamoDB auto-configured client는 같은 helper를 통해
공통 region/endpoint 기본값을 해석한다. S3/SQS는 0.3.0의 우선 서비스이므로 테스트에서
상속과 customizer 순서를 검증한다.

## 검증

- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test --tests '*AwsAutoConfigurationTest' --tests '*S3AutoConfigurationTest' --tests '*SqsAutoConfigurationTest'`

## 향후 보호 장치

새 Spring Boot AWS 서비스 client는 먼저 공통 defaults/customizer helper를 통해 추가한
뒤 서비스별 동작을 추가한다. 서비스마다 credential/region/endpoint 연결을 다시
중복하지 않는다.
