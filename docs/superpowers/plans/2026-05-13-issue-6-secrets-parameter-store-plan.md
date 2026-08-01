# 이슈 #6 Secrets Manager / Parameter Store 계획

## 범위

다음 기준으로 `aws-spring-boot`에 이슈 #6을 구현한다.

- 설계: `docs/superpowers/specs/2026-05-13-issue-6-secrets-parameter-store-design.md`
- 브랜치: `issue-6-secrets-parameter-store`
- 기준 브랜치: `origin/develop`

## 단계

### 1. 빌드 구성

- `software.amazon.awssdk:secretsmanager`와 `software.amazon.awssdk:ssm`의 버전 카탈로그 별칭을 추가한다.
- `aws-spring-boot/build.gradle.kts`에 `compileOnly`와 `testImplementation` 의존성을 추가한다.

### 2. 공유 Environment 소스 유틸리티

- 작은 내부 프로퍼티 키 평탄화 도우미를 추가한다.
- 프로퍼티 소스 순서 지정 도우미를 추가한다.
- 선택적 지연 재로딩을 지원하는 갱신 가능 프로퍼티 소스를 추가한다.
- 서비스별 동작을 가리지 않으면서 중복을 제거할 때만 공통 AWS 클라이언트 빌더 도우미를 추가한다.

### 3. Secrets Manager Environment 소스

- `SecretsManagerProperties`를 추가한다.
- `SecretsManagerEnvironmentPostProcessor`를 추가한다.
- `SecretsManagerPropertySourceLoader` 또는 이에 상응하며 테스트 가능한 내부 협력 객체를 추가한다.
- Spring `@Value`를 조합한 애너테이션으로 `@SecretsValue`를 추가한다.
- 후처리기를 `META-INF/spring.factories`에 등록한다.

### 4. Parameter Store Environment 소스

- `ParameterStoreProperties`를 추가한다.
- `ParameterStoreEnvironmentPostProcessor`를 추가한다.
- 페이지 단위 `getParametersByPath` 로딩을 추가한다.
- Spring `@Value`를 조합한 애너테이션으로 `@ParameterStoreValue`를 추가한다.
- 후처리기를 `META-INF/spring.factories`에 등록한다.

### 5. 테스트

- 바인딩, 비활성화/소스 없음 동작, SDK 누락 보호 절차, 검증을 다루는 ApplicationContextRunner 테스트를 추가한다.
- 애너테이션 자리표시자 해석과 갱신 가능 프로퍼티 소스 동작의 단위 테스트를 추가한다.
- JSON 시크릿 하나, 재귀적 파라미터 경로 하나, 원격 값 갱신 후 새로 고침을 다루는 LocalStack 테스트를 추가한다.

### 6. README

- 새로 고침과 애너테이션 사용법을 포함해 `README.md`와 `README.ko.md`의 의존성 코드 조각과 구성 예제를 갱신한다.

### 7. 검증

다음을 실행한다.

1. 공개 KDoc의 언어 기준 이탈을 찾기 위해 `rg '[가-힣]' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/{secretsmanager,parameterstore}`를 실행한다.
2. `git diff --check`
3. `./gradlew :aws-spring-boot:compileKotlin`
4. `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*'`
5. `./gradlew :aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.secretsmanager.*' --tests 'io.bluetape4k.aws.spring.parameterstore.*' --tests 'io.bluetape4k.aws.spring.env.AwsEnvironmentPropertySourceSupportTest' -Dbluetape4k.aws.emulator=localstack`
6. `./gradlew :aws-spring-boot:test`

## 검토 체크리스트

- awspring 또는 Spring Cloud 의존성이 없다.
- 소스를 명시적으로 구성하지 않으면 원격 AWS 호출이 발생하지 않는다.
- 엔드포인트 재정의에는 리전이 필요하다.
- 사용자가 소스를 구성했을 때만 SDK 누락에 대한 명확한 실패 동작을 제공한다.
- 시크릿과 파라미터 값을 로그에 기록하지 않는다.
- 시작 시 로딩을 마치면 AWS 클라이언트를 닫는다.
- 재로딩에 실패하면 이전 값을 유지한다.
- 공개 API KDoc은 영어다.
- 영어 README와 한국어 README의 내용을 일치시킨다.
