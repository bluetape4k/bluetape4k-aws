# 이슈 #196 - Spring Boot IMDS 통합 계획

날짜: 2026-06-07
Spec: `docs/superpowers/specs/2026-06-07-issue-196-imds-spring-boot-design.md`

## 실행 순서

1. AWS SDK IMDS 의존성 alias를 추가한다.
   - `aws2-imds = software.amazon.awssdk:imds`를 추가한다.
   - `aws-spring-boot`에 `compileOnly(libs.aws2.imds)`와 `testImplementation(libs.aws2.imds)`를 추가한다.

2. IMDS 설정 모델을 추가한다.
   - `io.bluetape4k.aws.spring.imds` 아래에 `ImdsProperties`를 만든다.
   - 기본적으로 활성화하되 startup 시에는 수동적으로 동작하게 한다.
   - 양수 duration과 음수가 아닌 retry 수를 검증한다.

3. Operation facade를 추가한다.
   - `ImdsOperations`를 만든다.
   - `Ec2MetadataAsyncClient` 기반 `ImdsCoroutinesTemplate`을 만든다.
   - 모든 metadata 요청에 `withTimeout(properties.requestTimeout)`을 사용한다.
   - 경로 검증에 bluetape4k 검증 helper를 사용한다.
   - instance id, availability zone, region, instance type, local IPv4, IAM role 이름 helper를 추가한다.

4. Spring Boot 자동 설정을 추가한다.
   - `ImdsAutoConfiguration`을 만든다.
   - `Ec2MetadataAsyncClient`와 `SdkAsyncHttpClient`를 조건으로 보호한다.
   - endpoint, endpoint mode, token TTL, retry policy, 선택적 공유 async HTTP client를 설정한다.
   - 기존 `Ec2MetadataAsyncClient` 또는 `ImdsOperations`가 있으면 물러난다.
   - `AutoConfiguration.imports`에 등록한다.

5. 테스트를 추가한다.
   - 자동 설정 등록, 비활성화 상태, classpath 보호, custom bean backoff, property binding, startup no-call 동작을 검사한다.
   - Template string/list 변환과 timeout 동작을 검사한다.

6. 문서를 갱신한다.
   - root README와 README.ko의 서비스/의존성/설정/사용법 절을 갱신한다.
   - `aws-spring-boot/README.md`와 `README.ko.md`의 기능/설정/사용법 절을 갱신한다.

7. 검토하고 검증한다.
   - 범위가 좁은 IMDS 테스트를 실행한다.
   - 전체 `:bluetape4k-aws-spring-boot:test`를 실행한다.
   - `:bluetape4k-aws-spring-boot:compileKotlin`을 실행한다.
   - `imds`의 dependencyInsight를 실행한다.
   - `git diff --check`를 실행한다.
   - 구현 검토와 lesson을 작성한다.

## 위험 통제

- 자동 설정에서 `Ec2MetadataAsyncClient.get(...)`을 호출하지 않는다.
- 일급 helper를 통해 IMDS security-credentials 값을 노출하지 않는다.
- 실제 EC2 동작은 로컬에서 테스트하지 않는다. Mock async client와 제한된 future를 통해 SDK 상호작용을 다룬다.
- #200이 Spring Boot type에 의존하지 않고 Ktor에 같은 이름 모델을 재사용할 수 있도록 새 API package 범위를 좁게 유지한다.

## 예상 변경 파일

- `gradle/libs.versions.toml`
- `aws-spring-boot/build.gradle.kts`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/imds/*`
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/imds/*`
- `README.md`, `README.ko.md`
- `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`
- `docs/review/*`
- `docs/lessons/*`

## 검증 명령

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency imds --configuration compileClasspath
./gradlew :bluetape4k-aws-spring-boot:compileKotlin
./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.imds.*'
./gradlew :bluetape4k-aws-spring-boot:test
git diff --check
```

## 중단 조건

코드, 문서, 검토 artifact, lesson, 로컬 검증, PR 본문, PR 검토, CI 증거가 모두 `P0=0`, `P1=0`으로 통과하면 중단한다. 병합은 PR 생성 후 별도로 사용자 승인을 받는 단계다.
