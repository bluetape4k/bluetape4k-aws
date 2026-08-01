# Issue #58 KMS Auto-Configuration 검토 후 수정

## 배경

KMS Spring Boot 지원을 포함한 PR #58을 병합한 뒤 검토에서 P1 시작 오류 1건과 P2
테스트 견고성 문제 1건을 발견했다.

- P1 발견 사항: 1
- P2 발견 사항: 1
- 검토 기반 보정 반복: 3

## 결정

`KmsTextEncryptorAutoConfiguration`은 `bluetape4k.aws.kms.text-encryptor.enabled`뿐
아니라 상위 `bluetape4k.aws.kms.enabled` switch도 따라야 한다.

Text-encryptor 단계는 `KmsProperties`도 명시적으로 등록한다. 따라서 사용자가 제공한
`KmsOperations`는 client auto-configuration 단계에 의존하지 않고도 adapter를 사용할
수 있다.

## 결과

- 사용자 정의 `KmsOperations` bean과 `bluetape4k.aws.kms.enabled=false`를 함께 사용하는 회귀 테스트를 추가했다.
- Text-encryptor 단계가 사용자 정의 operations용 `KmsProperties`를 binding할 수 있음을 검증했다.
- KMS package의 AssertJ 사용을 bluetape4k assertion으로 바꿨다.
- 일반 `ByteArray shouldNotBeEqualTo`가 배열 참조만 비교하는 Claude 검토 발견 사항을 수정했다.
- KMS LocalStack의 `runTest` 사용을 `runSuspendIO`로 바꿨다.
- KMS LocalStack 테스트가 `LocalStackServer.Launcher.getLocalStack("kms")`를 사용하도록 바꿨다.

## 검증

- `./gradlew :aws-spring-boot:compileKotlin :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.kms.*'`
- `./gradlew :aws-spring-boot:test`
- `git diff --check`

결과: KMS 테스트 15개가 통과했다.

## 향후 보호 장치

Spring Boot auto-configuration 단계에서는 모든 단계 class에 상위 `enabled` 조건을
적용한다. Context 시작을 테스트할 때는 AssertJ보다 `startupFailure.shouldBeNull()` 같은
직접적인 bluetape4k assertion과 infix 동등성 검사를 우선한다.
