# Issue #648 Ktor 단순 AWS client lifecycle 통합 교훈

## 배경

`aws-ktor`의 STS, SNS, SES v2, EventBridge, Kinesis, S3 Vectors,
CloudWatch metric, IMDS, S3 Access Grants plugin은 각자
`ApplicationStopping`에서 plugin-owned client를 닫고 있었다. DynamoDB에 이미
도입한 공통 `ApplicationResourceRegistry`와 lifecycle 경계가 서비스마다 달라,
flush나 drain이 없는 단순 client가 애플리케이션 child 작업보다 먼저 닫힐 수 있었다.

## 결정

- 아홉 개 단순 plugin은 plugin-created client만 공통 registry에 등록한다.
- registry의 `ApplicationStopped` callback이 등록 역순으로 close하고, runtime의
  직접 `stop()`과 registry close는 같은 atomic guard를 공유한다.
- suspend runtime의 close는 adapter 안에서 `runBlocking(Dispatchers.IO)`로 연결한다.
  IMDS는 기존 public 동기 `stop()` 계약을 유지하고, registry adapter에서만
  `runBlocking(Dispatchers.IO)`로 연결한다.
- caller-owned client와 operations facade는 등록하지 않는다.
- CloudWatch Logs, SQS, Exposed는 각각 flush, handler drain, database/health stop을
  수행하므로 `ApplicationStopping` 경계를 유지한다.
- 새 timeout, 공통 AWS wrapper, public constructor 변경은 추가하지 않는다.

## 검증 결과

처음 작성한 RED 테스트는 runtime을 테스트 안에서 직접 만들고
`ApplicationStopping` close handler를 재현했다. 이 테스트는 실제 plugin이
`ApplicationStopping`에 close를 연결했다는 사실을 검증하지 못했다. 이후
`StsKtorPlugin`을 실제로 설치하고 `StsAsyncClient.builder()`만 test double로
대체한 뒤 `startApplication()`을 호출하도록 수정했다. 수정한 테스트는 기존
구현에서 `ApplicationStopping` 시점의 close 횟수가 1이 되어 실패했고, registry
기반 구현에서는 그 시점에 0이고 `ApplicationStopped` 이후 1이 되는 계약을
고정한다.

공통 registry fixture는 아홉 서비스 runtime의 plugin-owned client 등록과 1회 close,
caller-owned client의 미등록을 함께 확인한다. Registry 자체의 역순 종료, late
registration, 예외 격리, 지연 close 계약은 공통 `bluetape4k-ktor-core` 테스트가
계속 검증한다. `AwsKtorLifecycleEdgeCasesTest`는 아홉 plugin을 실제로 설치하고
각 SDK builder만 mock하여 plugin wiring을 함께 검증하며, `ApplicationStopping` 시점의
미종료, 역순 close와 중간 예외 이후의 계속 진행, late registration 즉시 close, 직접
`stop()`과 registry의 중복 close 방지, 지연 close가 끝날 때까지의 순차 대기를 고정한다.

## 향후 지침

Lifecycle 회귀 테스트는 구현 패턴을 복제하는 것만으로 끝내지 않는다. 실제 plugin
설치와 Ktor event 순서를 사용해 `ApplicationStopping`과 `ApplicationStopped` 사이의
소유권 변화를 관찰해야 한다. Client close가 blocking이면 기존 dispatcher bridge를
보존하고, flush나 drain의 경계를 가진 복합 runtime을 단순 registry migration에
포함하지 않는다.

## 최종 검증과 한계

회귀8개, 전체 Ktor267개(실패0·건너뜀0), build/detekt/Kover 및 compatibilityCheck가 통과했다. 최초 전체 검사에서 변경하지 않은 SQS500ms timeout이 실패했고, 해당 클래스6개와 전체모듈 재실행은 통과했다. 시간 민감 테스트의 간헐적 실패 가능성은 기록으로 남긴다. 실제9개 plugin fixture의 IMDS generic builder는 relaxed mock 대신 fluent 반환값을 명시해야 했다.

공통 registry 설치 자체가 실패할 때의 보상 정리, 동시 stop의 완료 join, opaque registrationId의 서비스 상관관계는 기존 helper/runtime 한계로 남는다. 이번 변경은 이를 보장한다고 주장하지 않는다. worktree 교훈은 canonical GNO 수집 제외 정책에 따라 머지 뒤 색인한다.
