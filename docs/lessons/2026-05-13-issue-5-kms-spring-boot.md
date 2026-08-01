# Issue #5 KMS Spring Boot 지원

날짜: 2026-05-13
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/5

## 배경

`aws-spring-boot`에는 awspring을 사용하지 않는 KMS 지원이 필요했다. 첫 범위에서는 AWS
SDK 서비스 의존성을 `compileOnly`로 유지하면서 coroutine 친화적 API, 선택적 Spring
Security `TextEncryptor`, data-key cache 지원을 제공해야 했다.

## 결정

시작 시 auto-configuration과 명시적인 애플리케이션 API를 구현했다.

- KMS SDK classpath 검사로 보호하는 `KmsAsyncClient` bean
- suspend 암호화/복호화/data-key 생성을 위한 `KmsOperations`와 `KmsCoroutinesEncryptor`
- TTL 및 최대 크기 제한이 있는 `InMemoryDataKeyCache`
- `spring-security-crypto`를 선택 사항으로 유지하도록 보호한 선택적 `KmsTextEncryptorAutoConfiguration`
- KMS 직접 암호화와 data-key/envelope 사용의 차이를 설명하고 PlantUML 다이어그램을 포함한 README/README.ko.md

`@KmsEncrypted` field 수준 암호화에는 별도의 serialization/persistence 수명 주기 설계가
필요하므로 뒤로 미뤘다.

## 검증

- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.*'`
- `./gradlew :aws-spring-boot:test`
- `git diff --check`
- Grep으로 새 공개 KMS main-source KDoc에 한국어가 없음을 확인했다.

## 참고

`omx ask claude`로 Claude advisor 검토를 시도했지만 명령이 60초 동안 출력을 내지 않아
종료했다. Spring Boot 공식 문서 검사, AWS SDK v2 로컬 사용 pattern, LocalStack 검증으로
작업을 이어갔다.

향후 agent는 `spring-security-crypto`를 선택 사항으로 유지하고 adapter를 변경할 때마다
`FilteredClassLoader`로 테스트해야 한다.
