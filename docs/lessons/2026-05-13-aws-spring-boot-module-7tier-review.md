# AWS Spring Boot 모듈 7단계 검토

## 배경

세 번째 모듈별 7단계 검토에서는 `:aws`와 `:aws-kotlin` 검토 PR을 병합한 뒤
`:aws-spring-boot`를 다뤘다. Spring Boot 4 자동 구성과 LocalStack 통합 테스트를
대상으로 5단계의 테스트/타입/조용한 실패와 6단계의 성능/안정성을 중점 검토했다.

- 범위: `aws-spring-boot/src/test/kotlin`
- 변경 파일: 8개
- 현재까지 검토 횟수: 로컬 6-R/7단계 1회, Claude 조언자 1회
- 수정한 검토 지적: P0=0, P1=0, P2=3

## 결정

Spring Boot LocalStack 테스트에는 하위 AWS 모듈과 같은 backend 인식 test runtime을
사용한다. Suspend AWS 호출에는 `runSuspendIO`, 비동기 상태를 실제로 기다리는 suspend
poll에는 Awaitility `untilSuspending`을 사용한다. 각 test class에서 임의로
`start()`/`stop()` 수명 주기를 관리하지 않고
`LocalStackServer.Launcher.getLocalStack(...)`을 사용한다.

## 결과

- 변경한 `:aws-spring-boot` 테스트의 `runTest` / `runBlocking` 사용을
  `runSuspendIO`로 교체했다.
- 직접 생성하던 `LocalStackServer().withServices(...)` test container 7개를
  `LocalStackServer.Launcher.getLocalStack(...)`으로 교체했다.
- class별 `localStack.start()` / `localStack.stop()` 호출을 제거하고 launcher와 shutdown
  queue가 container 수명 주기를 소유하게 했다.
- SQS listener 삭제 후 suspend poll을 한 번의 blocking receive에서 Awaitility
  `untilSuspending`으로 전환했다.
- Claude가 향후 일반화할 `runSuspendIOReturning<T>` helper에 nullable capture가
  어색하다고 지적해 queue 생성 bridge를 단순화했다.
- Spring Security의 동기식 `TextEncryptor` 계약을 적용하므로 운영 코드
  `KmsTextEncryptor`의 `runBlocking(Dispatchers.IO)`는 유지했다.

## 검증

- `./gradlew :aws-spring-boot:compileTestKotlin`
- `./gradlew :aws-spring-boot:test`
- `./gradlew detekt`는 `NO-SOURCE`로 완료됐다.
- `git diff --check`
- 금지된 assertion/고정 delay/runtime scan:
  `rg "kotlin\\.test\\.|org\\.junit\\.jupiter\\.api\\.Assertions|assertThrows|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(|runTest|runBlocking|LocalStackServer\\(\\)\\.withServices" aws-spring-boot/src/test/kotlin`
- 운영/테스트 concurrency scan:
  `rg "GlobalScope|runBlocking\\(|Thread\\.sleep|delay\\(|synchronized\\(|@Synchronized|runCatching\\s*\\{|Dispatchers\\.Default|Dispatchers\\.IO|CancellationException|while\\s*\\(true\\)" aws-spring-boot/src/main/kotlin aws-spring-boot/src/test/kotlin`
- Claude 조언자 검토:
  `.omx/artifacts/ask-claude-code-review-aws-spring-boot-20260513-202644.md`

결과적으로 `:aws-spring-boot` 테스트 68개가 통과했고 금지된 테스트 scan은 0건을
반환했다. 운영 코드 scan에서는 동기식 `TextEncryptor` adapter, SQS IO coroutine
scope, 명시적 cancellation 처리, `S3Resource.exists()`의 non-suspend `runCatching`처럼
검토한 경계만 발견됐다. Claude는 P0=0, P1=0, P2=2, P3=2, APPROVE로 보고했다.
P2 queue helper 가독성 지적 하나는 수정했다. 남은 P2는 launcher pattern이 시작된
LocalStack container를 Gradle test JVM 종료 시까지 공통 shutdown queue 아래에 두도록
의도했으므로 제한된 수명 주기 절충으로 수용했다.

## 향후 보호 장치

`:aws-spring-boot`에서 실제 AWS SDK, LocalStack, Spring 자동 구성 호출을 `runTest`나
일반 `runBlocking`으로 감싸지 않는다. Suspend AWS 작업에는 `runSuspendIO`를 사용하고,
테스트가 suspend 조건을 polling할 때는 `untilSuspending`을 사용한다. 테스트가 공통
launcher와 별도로 container 종료를 소유하지 않도록
`LocalStackServer.Launcher.getLocalStack(...)`을 우선한다.

공개 암호화 API를 변경할 때 `KmsTextEncryptor`의 blocking bridge를 계속 검토하되
함부로 제거하지 않는다. `TextEncryptor`는 의도적으로 동기식이다.
