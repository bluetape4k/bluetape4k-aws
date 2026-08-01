# AWS 모듈 7단계 검토

## 배경

열려 있던 PR을 `develop`에 병합한 뒤 첫 모듈별 7단계 검토를 `:aws`부터 시작했다.
형제 모듈로 넘어가기 전에 5단계의 테스트/타입/조용한 실패 위험과 6단계의 성능/안정성
위험을 중점 검토했다.

- 범위: `aws/src/test/kotlin`
- 변경 파일: 14개
- 현재까지 검토 횟수: 로컬 6-R/7단계 1회, Claude 조언자 2회
- 수정한 검토 지적: P0=0, P1=0, P2=3

## 결정

변경한 `:aws` 테스트는 저장소 표준 assertion과 실제 IO 테스트 dispatcher를 일관되게
사용한다. `bluetape4k-junit5`가 이미 `untilSuspending`을 제공하므로
LocalStack/DynamoDB의 최종 일관성 상태를 고정 sleep으로 기다리지 않는다.

## 결과

- 변경 범위에 남아 있던 JUnit/kotlin.test exception assertion을
  `io.bluetape4k.assertions.assertFailsWith`로 교체했다.
- IO를 사용하는 coroutine 테스트 본문 26개를 `runTest`에서 `runSuspendIO`로 전환했다.
- Claude가 광범위한 전환을 관례 이탈로 지적한 뒤 IO를 사용하지 않는 coroutine 지원
  테스트와 mock 전용 coroutine wrapper 테스트에는 `runTest`를 유지했다.
- 고정 DynamoDB async sleep 9개를 테이블 활성화와 item 가시성을 확인하는 Awaitility
  `untilSuspending`으로 교체했다.
- 기존 runtime 동작을 보존했다. 이 PR은 테스트 강화만 다룬다.

## 검증

- `./gradlew :aws:compileTestKotlin`
- `./gradlew :aws:test`
- `git diff --check`
- `./gradlew :aws:detekt`를 시도했지만 `:aws`에는 `detekt` task가 없다.
- `./gradlew detekt`는 `NO-SOURCE`로 완료됐다.
- 금지된 assertion/고정 delay scan:
  `rg "org\\.junit\\.jupiter\\.api\\.assertThrows|kotlin\\.test\\.assertFailsWith|assertThrows<|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(" aws/src/test/kotlin`
- Claude 조언자 검토:
  `.omx/artifacts/ask-claude-code-review-aws-20260513-183825.md`
- Claude 조언자 재검토:
  `.omx/artifacts/ask-claude-code-review-aws-rereview-20260513-184404.md`

결과적으로 `:aws` 테스트 252개가 통과하고 2개가 pending이었으며, 금지 항목 scan은
0건을 반환했다. Claude는 P0=0, P1=0으로 승인했고, PR을 만들기 전에 mock 전용
`runSuspendIO`에 관한 P2 관례 지적을 수정했다. 재검토에서는 P0=0, P1=0, P2=0,
P3=3으로 승인했으며 간단한 P3 import 순서도 정리했다.

## 향후 보호 장치

AWS 모듈을 검토할 때 테스트 runtime 선택을 5단계/6단계 신호로 취급한다. `runTest`는
가상 시간 또는 IO를 사용하지 않는 coroutine 테스트에 사용하고, LocalStack, AWS SDK
async client, Ktor, 실제 네트워크/파일 작업에는 `runSuspendIO`를 사용한다. 테스트를
변경하는 패치에서 고정 sleep을 `untilSuspending` 또는 서비스별 상태 polling으로
교체한다.
