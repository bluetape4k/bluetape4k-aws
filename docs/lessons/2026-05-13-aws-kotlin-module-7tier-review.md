# AWS Kotlin 모듈 7단계 검토

## 배경

두 번째 모듈별 7단계 검토에서는 `:aws` 검토와 PR #64 병합에 이어 `:aws-kotlin`을
다뤘다. 같은 기준으로 5단계의 테스트/타입/조용한 실패와 6단계의 성능/안정성 신호를
중점 검토했다.

- 범위: `aws-kotlin/src/test/kotlin`
- 변경 파일: 37개
- 현재까지 검토 횟수: 로컬 6-R/7단계 1회, Claude 조언자 2회
- 수정한 검토 지적: P0=0, P1=0, P2=4

## 결정

AWS Kotlin SDK 테스트에도 Java SDK 모듈과 같은 정책을 적용한다. 저장소 표준 assertion을
사용하고, LocalStack을 사용하는 suspend 테스트에는 실제 IO dispatcher를 쓰며, 이미
관찰한 SDK 작업 뒤에는 고정 coroutine sleep을 두지 않는다.

## 결과

- `kotlin.test.assertFailsWith` import 36개를
  `io.bluetape4k.assertions.assertFailsWith`로 교체했다.
- Claude가 더 좁은 scan의 누락을 발견한 뒤 `DynamoDbModelSupportTest`에서 변경한
  `kotlin.test.assertNull` / `assertNotNull` 사용을 `shouldBeNull` / `shouldNotBeNull`로
  교체했다.
- LocalStack을 사용하는 KMS/STS suspend 테스트 21개를 `runTest`에서 `runSuspendIO`로
  전환했다.
- `getAll`에서 예상한 객체 본문을 이미 수집하고 확인한 뒤 실행하던 S3의 고정
  `delay(1.seconds)` 1개를 제거했다.
- LocalStack 네트워크를 호출하지 않고 명시적인 10초 종료 시간제한을 확인하는 수명 주기
  테스트에는 `runTest`를 유지했다.

## 검증

- `./gradlew :aws-kotlin:compileTestKotlin`
- `./gradlew :aws-kotlin:test`
- `git diff --check`
- `./gradlew detekt`는 `NO-SOURCE`로 완료됐다.
- 금지된 assertion/고정 delay scan:
  `rg "kotlin\\.test\\.|org\\.junit\\.jupiter\\.api\\.assertThrows|assertThrows<|assertThat\\(|org\\.assertj|org\\.amshove\\.kluent|delay\\(" aws-kotlin/src/test/kotlin`
- Claude 조언자 검토:
  `.omx/artifacts/ask-claude-code-review-aws-kotlin-20260513-200423.md`
- Claude 조언자 재검토:
  `.omx/artifacts/ask-claude-code-review-aws-kotlin-rereview-20260513-200734.md`
- 잔여 `runTest` 검사:
  `rg "import kotlinx\\.coroutines\\.test\\.runTest|runTest\\(" aws-kotlin/src/test/kotlin -g '*.kt'`

결과적으로 `:aws-kotlin` 테스트 443개가 통과하고 5개가 pending이었으며, 금지 항목
scan은 0건을 반환했다. Claude의 첫 검토 결과는 P0=0, P1=0, P2=1, P3=1이었고 PR을
만들기 전에 남아 있던 P2 `kotlin.test` assertion 지적을 수정했다. 재검토 결과는
P0=0, P1=0, P2=0, P3=1로 승인됐다. 잔여 `runTest` 사용은 LocalStack이나 네트워크
API를 호출하지 않고 10초 종료 시간제한을 확인한다고 명시한 `ClientLifecycleTest`로
한정된다.

## 향후 보호 장치

`:aws-kotlin`에서는 API가 suspend 우선이라는 이유만으로 네이티브 AWS Kotlin SDK
suspend API를 가상 시간으로 취급하지 않는다. 테스트가 LocalStack과 통신하거나
에뮬레이터 엔드포인트용 AWS client를 생성하거나 실제 SDK IO를 실행하면
`runSuspendIO`를 사용한다. 순수 수명 주기/시간제한 또는 mock 전용 테스트에서 의도적으로
backend IO를 실행하지 않을 때만 `runTest`를 유지한다.

변경한 파일에 `assertNull`이나 `assertNotNull`이 남을 수 있으므로
`assertFailsWith`뿐 아니라 전체 `kotlin.test.` 접두사를 scan한다.
