# Issue #192 Spring S3 고급 기능

## 배경

Milestone 0.3.0 버전에서는 AWSpring S3 parity issue의 범위를 즉시 유용한 운영 환경
강화 기능으로 좁혔다. S3 기반 Spring Environment config reload와 KMS 기반 S3
client-side 암호화를 포함한다. S3 Access Grants와 S3 Vector에는 추가 선택적 SDK/client
API가 필요하므로 이 범위에서 제외했다.

## 결정

기존 AWS property-source refresh 지원을 재사용하는 `EnvironmentPostProcessor`로 S3
config loading을 추가한다. Byte-array envelope 암호화는 `KmsOperations` bean과
`bluetape4k.aws.s3.client-side-encryption.enabled=true`로 보호하는 opt-in
`S3ClientSideEncryptionOperations` bean으로 추가한다.

## 결과

S3 config source는 `properties`, YAML, JSON object를 load할 수 있으며
`refresh-interval`을 설정하면 lazy reload한다. S3 암호화는 KMS data key, AES-GCM 로컬
payload 암호화, 암호화한 data key와 nonce를 위한 S3 metadata를 사용한다. 이 helper는
의도적으로 multipart 또는 streaming client-side 암호화를 지원하지 않는다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AutoConfigurationTest' --tests '*S3ConfigEnvironmentPostProcessorAwsEmulatorTest' --tests '*S3CoroutinesTemplateAwsEmulatorTest' --no-daemon --max-workers=1`

## 향후 보호 장치

애플리케이션이 소유하는 구체적인 통합 형태가 생길 때까지 Access Grants와 S3 Vector를
기본 Spring Boot S3 API에서 제외한다. 나중에 streaming 또는 multipart 암호화가
필요하면 byte-array helper를 확장하지 말고 별도 계약으로 추가한다.
