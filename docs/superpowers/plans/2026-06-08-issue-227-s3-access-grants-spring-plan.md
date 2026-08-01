# 이슈 #227 Spring S3 Access Grants 계획

## 목표

기본 S3 사용자에게 runtime dependency나 기본 bean을 추가하지 않으면서 `aws-spring-boot`에 선택형 Spring Boot S3 Access Grants 지원을 제공한다.

## gate 순서

1. 이슈 접수 및 갱신.
2. 명세 작성.
3. 명세 검토, `P0=0`, `P1=0` 필수.
4. 계획 작성.
5. 계획 검토, `P0=0`, `P1=0` 필수.
6. 구현.
7. 로컬 검증.
8. 7단계 code review, `P0=0`, `P1=0` 필수.
9. PR 본문 검증 및 CI.

## 구현 단계

### 단계 1 - dependency alias

- `gradle/libs.versions.toml`에 `aws2-s3control`을 추가한다.
- `aws-spring-boot/build.gradle.kts`에 `compileOnly(libs.aws2.s3control)`과 `testImplementation(libs.aws2.s3control)`을 추가한다.
- 다음 명령으로 검증한다.
  `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`.

DoD:

- `s3control`이 compile/test scope에만 나타난다.
- `api` 또는 `runtimeOnly` dependency를 추가하지 않는다.

### 단계 2 - property

- `io.bluetape4k.aws.spring.s3.accessgrants` 아래에 `S3AccessGrantsProperties`를 추가한다.
- 접두사: `bluetape4k.aws.s3.access-grants`.
- 기본값:
  - `enabled=false`
  - `region=null`
  - `endpointOverride=null`
- string value가 있으면 blank region을 validation한다.
- data class를 `serialVersionUID`가 있는 `Serializable`로 유지한다.

DoD:

- 기본 S3와 함께 Access Grants가 활성화되지 않도록 property를 `S3Properties`와 분리한다.

### 단계 3 - operation과 template

- `S3AccessGrantsOperations`를 추가한다.
- `S3ControlAsyncClient` 기반 `S3AccessGrantsCoroutinesTemplate`을 추가한다.
- 기존 AWS Java 지원을 통해 transitively 사용할 수 있는 `kotlinx-coroutines-jdk8`의 `CompletableFuture.await()`를 사용한다.
- 최소한의 application access workflow method를 제공한다.
  - `getDataAccess(GetDataAccessRequest)`
  - `listCallerAccessGrants(ListCallerAccessGrantsRequest)`
  - `listAccessGrants(ListAccessGrantsRequest)`
  - `listAccessGrantsInstances(ListAccessGrantsInstancesRequest)`
  - `listAccessGrantsLocations(ListAccessGrantsLocationsRequest)`
- 관리용 create/delete/update operation은 raw caller-owned `S3ControlClient` / `S3ControlAsyncClient` bean을 통해 사용할 수 있게 유지한다.

DoD:

- template method는 suspend function이며, suspend 호출을 `runCatching`으로 감싸지 않아 coroutine cancellation을 다시 던진다.
- 이 이슈에서는 광범위한 S3 Control compatibility surface를 확정하지 않는다.

### 단계 4 - auto-configuration

- `S3AccessGrantsAutoConfiguration`을 추가한다.
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에서 `S3AutoConfiguration` 뒤에 등록한다.
- 다음 항목에 string `@ConditionalOnClass` guard를 사용한다.
  - `software.amazon.awssdk.services.s3control.S3ControlClient`
  - `software.amazon.awssdk.services.s3control.S3ControlAsyncClient`
- 다음 property guard를 사용한다.
  - `bluetape4k.aws.s3.enabled=true` 또는 누락.
  - `bluetape4k.aws.s3.access-grants.enabled=true`.
- 다음 조건으로 sync 및 async S3 Control client를 생성한다.
  - 공유 AWS 기본값
  - 자격 증명 provider fallback
  - 선택형 sync/async HTTP client bean
  - service name `s3control`을 사용하는 global customizer
  - `S3ControlClientBuilder`와 `S3ControlAsyncClientBuilder`를 위한 service-specific customizer
- 없을 때 `S3AccessGrantsOperations` bean을 추가한다.

DoD:

- caller-provided client와 operation이 올바르게 back off한다.
- 소유한 client는 Spring이 `destroyMethod="close"`를 통해 닫는다.

### 단계 5 - test

`S3AccessGrantsAutoConfigurationTest`와 `S3AccessGrantsCoroutinesTemplateTest`를 추가한다.

auto-configuration test 사례:

- 기본적으로 비활성화된다.
- 활성화하면 `S3ControlClient`, `S3ControlAsyncClient`, `S3AccessGrantsProperties`, `S3AccessGrantsOperations`, template을 등록한다.
- `s3control` class가 없으면 `FilteredClassLoader`로 back off한다.
- 기본 S3가 비활성화되면 Access Grants도 비활성화된다.
- caller-provided sync/async client를 재사용한다.
- caller-provided operation이 있으면 template이 back off한다.
- region 없는 endpoint override는 공유 기본값을 통해 실패한다.
- global 및 service customizer를 결정적인 순서로 적용한다.

template test 사례:

- `getDataAccess`가 async client에 위임하고 결과를 기다린다.
- list method가 async client에 위임하고 결과를 기다린다.

DoD:

- test는 bluetape4k assertion, MockK, `runSuspendIO` 또는 적합한 기존 coroutine test helper를 사용한다.
- emulator 또는 실제 AWS dependency를 도입하지 않는다.

### 단계 6 - 문서와 lesson

- 루트 `README.md`와 `README.ko.md`를 갱신한다.
- 선택형 `software.amazon.awssdk:s3control` consumer dependency를 언급한다.
- 짧은 `docs/lessons/2026-06-08-issue-227-s3-access-grants-spring.md`를 추가한다.

DoD:

- 영문 및 한국어 README 항목이 일치한다.
- lesson에 `s3control` 발견과 optional dependency guard를 기록한다.

## 검증 명령

순서대로 실행한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath
./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AutoConfigurationTest' --tests '*S3AccessGrants*' --no-daemon --max-workers=1
git diff --check
```

CI snapshot metadata가 Sonatype 403을 반환하면 실패한 CI job을 한 번 재시도하고, code failure로 처리하기 전에 log에서 failure를 분류한다.

## 검토 확인 목록

- P0/P1 workflow gate 준수.
- optional dependency가 선택 사항으로 유지됨.
- `@ConditionalOnClass(name = [...])`가 모든 compileOnly bean signature를 보호함.
- 새 auto-configuration class에 `@ConditionalOnProperty` 적용.
- 명시적으로 활성화하지 않으면 기존 S3 사용자에게 Access Grants bean을 제공하지 않음.
- coroutine cancellation을 삼키지 않음.
- public KDoc은 영문으로 작성.
- README locale set 갱신.
