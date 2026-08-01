# 이슈 #5 KMS Spring Boot 지원 계획

날짜: 2026-05-13
Spec: `docs/superpowers/specs/2026-05-13-issue-5-kms-spring-boot-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/5

## 범위

첫 KMS Spring Boot 작업을 구현한다.

1. `KmsAsyncClient`를 자동 설정한다.
2. `bluetape4k.aws.kms`를 binding한다.
3. coroutine encrypt/decrypt와 data-key 생성을 제공한다.
4. 크기가 제한된 in-memory data key cache를 제공한다.
5. 선택적 Spring Security `TextEncryptor` adapter를 제공한다.
6. README/README.ko.md에 사용자 중심 설명과 UML 다이어그램을 추가한다.

## 작업

### T0 - 기준선

- 기존 #5 spec/plan이 없는지 확인한다.
- `origin/develop`에서 worktree를 만든다.
- S3/SQS/DynamoDB Spring Boot pattern을 검사한다.
- Spring Boot 자동 설정 문서와 AWS SDK v2 KMS 동작을 확인한다.

### T1 - Build 연결

- `aws-spring-boot` compile/test 의존성에 `libs.aws2.kms`를 추가한다.
- `spring-security-crypto` alias와 선택적 compile/test 의존성을 추가한다.
- `AutoConfiguration.imports`에 KMS 자동 설정 class를 등록한다.

### T2 - 핵심 API

- `KmsProperties`를 추가한다.
- `KmsOperations`를 추가한다.
- `KmsDataKey`, `KmsDataKeyCacheKey`, `DataKeyCache`, 기본 in-memory cache를 추가한다.
- `KmsCoroutinesEncryptor`를 추가한다.

### T3 - Spring 설정

- `KmsAutoConfiguration`을 추가한다.
- 선택적 `KmsTextEncryptorAutoConfiguration`을 추가한다.
- custom 사용자 bean이 있으면 자동 설정이 물러나는지 확인한다.
- AWS SDK 서비스 직접 참조를 `@ConditionalOnClass`로 보호한다.

### T4 - 테스트

- 자동 설정, 비활성화 flag, custom bean, endpoint-region 검증을 위한 `ApplicationContextRunner` 테스트를 추가한다.
- encrypt/decrypt와 data-key caching을 위한 LocalStack 테스트를 추가한다.
- `spring-security-crypto`가 test classpath에 있을 때 TextEncryptor adapter 테스트를 추가한다.

### T5 - 문서

- README.md와 README.ko.md 의존성 snippet을 갱신한다.
- KMS 설정 절을 추가한다.
- 작은 secret 암호화와 TextEncryptor의 사용자 예제를 추가한다.
- component와 runtime 흐름 UML 다이어그램을 추가한다.
- KMS payload 제한 주의 사항과 data-key cache 보안 tradeoff를 명시한다.

### T6 - 검증 및 PR

- 범위가 좁은 compile/test를 실행한다.
- 전체 `:aws-spring-boot:test`를 실행한다.
- `git diff --check`를 실행한다.
- Lore trailer를 포함해 commit한다.
- branch를 push하고 `debop`을 담당자로 지정한 PR을 생성한다. CI를 모니터링하고 성공하면 ready로 표시한다.

## 위험

- KMS client 호출은 async/suspend이지만 `TextEncryptor`는 blocking이다. 대응: 의도한 작은 secret 용도를 문서화하고 선택적 adapter로 유지한다.
- Plaintext data-key caching은 민감하다. 대응: 보수적 기본값, 제한된 TTL과 크기, 명시적인 문서를 제공한다.
- LocalStack KMS 동작은 AWS와 다를 수 있다. 대응: 테스트를 SDK request/response 동작에 집중하고 실제 AWS는 테스트하지 않았다고 보고한다.

## 인수 조건

- `KmsOperations`가 LocalStack KMS를 통해 byte를 encrypt/decrypt할 수 있다.
- `generateDataKey`는 cache가 활성화되면 사용하고 비활성화되면 우회한다.
- `KmsTextEncryptor`가 text를 round trip하고 Base64 ciphertext를 생성한다.
- `aws-spring-boot` 테스트가 로컬과 GitHub Actions에서 통과한다.
- README 파일이 사용자 관점에서 기능을 설명하고 UML 다이어그램을 포함한다.
