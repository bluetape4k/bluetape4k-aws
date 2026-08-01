# AWS Ktor 모듈 7단계 검토

## 배경

네 번째 모듈별 7단계 검토에서는 `:aws`, `:aws-kotlin`, `:aws-spring-boot` 검토 PR을
병합한 뒤 `:aws-ktor`를 다뤘다. Ktor client MockEngine 테스트와 SQS consumer runtime
테스트를 대상으로 5단계의 테스트/타입/조용한 실패와 6단계의 성능/안정성을 중점
검토했다.

- 범위: `aws-ktor/src/test/kotlin`
- 변경 파일: 5개
- 현재까지 검토 횟수: 로컬 6-R/7단계 1회, Claude 조언자 1회
- 수정한 검토 지적: P0=0, P1=0, P2=2

## 결정

Ktor 테스트는 JUnit 5와 bluetape4k assertion API를 일관되게 사용한다. Suspend
MockEngine 테스트에는 가상 시간이 필요하지 않으므로 `runSuspendIO`를 공통 runner로
사용해 이 모듈을 다른 AWS 검토 범위와 맞춘다.

## 결과

- 남아 있던 `kotlin.test.Test` import를 JUnit 5 `@Test`로 교체했다.
- 남아 있던 `kotlin.test.assertFailsWith` import를
  `io.bluetape4k.assertions.assertFailsWith`로 교체했다.
- MockEngine의 `runTest` 사용을 `runSuspendIO`로 교체했다.
- 변경한 boolean 비교 `shouldBeEqualTo true/false`를 `shouldBeTrue()` /
  `shouldBeFalse()`로 교체했다.
- Claude가 import 순서 P3를 지적한 뒤 변경한 assertion import를 정렬했다.
- Ktor monitoring event가 동기식이고 SQS handler 종료를 IO에서 비운다고 코드에 이미
  설명되어 있어 운영 코드 `SqsConsumer`의 `runBlocking(Dispatchers.IO)`는 유지했다.
- 운영 코드의 retry/heartbeat `delay(...)`는 테스트 sleep이 아니라 제한된 suspend
  timer이므로 유지했다.

## 검증

- `./gradlew :aws-ktor:compileTestKotlin`
- `./gradlew :aws-ktor:test`
- `./gradlew detekt`는 `NO-SOURCE`로 완료됐다.
- `git diff --check`
- 금지된 assertion/runtime scan:
  `rg "kotlin\\.test\\.|kotlinx\\.coroutines\\.test\\.runTest|runTest\\(|shouldBeEqualTo true|shouldBeEqualTo false|org\\.assertj|org\\.amshove\\.kluent|assertThat\\(|org\\.junit\\.jupiter\\.api\\.Assertions|assertThrows|delay\\(" aws-ktor/src/test/kotlin`
- 검토한 운영 runtime scan:
  `rg "runBlocking\\(|delay\\(|GlobalScope|synchronized\\(|@Synchronized|runCatching\\s*\\{" aws-ktor/src/main/kotlin`
- Claude 조언자 검토:
  `.omx/artifacts/ask-claude-code-review-aws-ktor-20260513-204834.md`

결과적으로 `:aws-ktor` 테스트 33개가 통과했고 금지된 테스트 scan은 0건을 반환했다.
운영 코드 scan에서는 검토한 경계인 Ktor의 동기식 종료 event bridge와 SQS runtime의
retry/heartbeat timer만 발견됐다. Claude는 P0=0, P1=0, P2=0, P3=1, APPROVE로
보고했으며 PR을 만들기 전에 P3 import 순서 지적을 수정했다.

## 향후 보호 장치

`:aws-ktor`의 MockEngine 및 Ktor plugin 테스트는 JUnit 5와
bluetape4k-assertions를 함께 사용한다. 테스트에 가상 시간이 꼭 필요하지 않으면
`runSuspendIO`를 우선한다. Consumer runtime 테스트의 suspend polling에는 계속
`untilSuspending`을 사용하며 운영 retry/heartbeat의 `delay(...)`와 불안정한 테스트
sleep을 구분한다.
