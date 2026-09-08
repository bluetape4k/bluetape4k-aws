# #648 Ktor 서비스 클라이언트 종료 통합

## 승인 범위

이슈 #648을 `refactor/issue-648-ktor-lifecycle`에서 구현하고 `develop` 대상 PR을 생성한다. 머지는 보류한다.

## 설계

STS, SNS, SES v2, EventBridge, Kinesis, S3 Vectors, CloudWatch metrics, IMDS, S3 Access Grants의 단순 클라이언트 종료를 기존 `ApplicationResourceRegistry`에 등록한다. 각 플러그인은 `installApplicationResourceLifecycle()`를 호출하고 runtime의 internal 등록 메서드에 registry를 전달한다. 플러그인 소유 클라이언트만 등록한다. 각 runtime의 기존 public 생성자와 stop 서명은 유지한다.

DynamoDB adapter처럼 등록 중복을 막는 atomic과 AutoCloseable adapter를 사용한다. 직접 stop과 registry close는 기존 closed atomic을 공유해 실제 클라이언트를 한 번만 닫는다. suspend stop의 dispatcher bridge는 adapter에 둔다. IMDS는 registry adapter 경로에서만 동기 stop을 IO에서 실행하고, 직접 public stop 호출의 동기 동작은 유지한다. 기존에 없던 timeout 정책이나 새 public 설정은 도입하지 않는다. SDK close가 블로킹되면 종료 지연이 가능하며 강제 종료 시간은 보장하지 않는다.

공통 registry의 단일 ApplicationStopped 구독, 역순 종료, 예외 격리를 재사용한다. 새 AWS 공통 lifecycle 클래스나 의존성은 추가하지 않는다. ApplicationStopping에서 닫던 단순 클라이언트는 ApplicationStopped에서 닫히므로 종료 이벤트 순서가 바뀐다는 점을 문서화한다.

## 이번 변경에서 유지하는 서비스

CloudWatch Logs는 주기 flush와 shutdownFlushTimeout, SQS는 admission 차단과 handler drain, Exposed는 DB/health stop을 수행한다. 이 세 경로는 ApplicationStopping에 남는다. 해당 처리 중 사용하는 클라이언트를 먼저 닫지 않는다.

## 대안

generic AWS lifecycle wrapper는 서비스별 소유권을 숨기고 새 추상화 검토를 요구하므로 채택하지 않는다. 복잡한 flush/drain 경로의 일괄 이관도 제외한다. runtime마다 작은 adapter를 두어 기존 서비스 경계를 유지한다.

## 수용 기준

9개 서비스의 caller-owned 클라이언트는 등록 및 close하지 않는다. plugin-owned 클라이언트는 중복 등록/직접 stop/앱 종료 조합에서도 한 번만 닫는다. 여러 플러그인이 하나의 registry를 공유하며 역순 종료 중 예외가 나도 나머지를 닫는다. 실제 ApplicationStopping 시점에는 아직 열려 있고 ApplicationStopped 뒤 닫힌다는 회귀 테스트로 이벤트 이관을 입증한다. 기존 complex 서비스 테스트도 실행한다.

## 근거

`dynamodb/DynamoDbKtorRuntime.kt`의 등록 adapter와 `DynamoDbKtorPlugin.kt`를 기준으로 한다. sibling projects의 `ktor/core/.../ApplicationResourceLifecycle.kt`가 종료 순서와 예외 격리를 소유한다. public constructor/stop ABI는 변경하지 않는다.

운영 계약: registry 종료 후 late registration은 core registry 계약에 따라 등록한 adapter를 즉시 닫는다. 거부 후 누수시키지 않는다. closeReport와 기존 registry 로그로 실패를 확인하며 새 민감 정보 로그는 추가하지 않는다. 이벤트 순서 변경에 문제가 있으면 애플리케이션 배포 전체를 이전 버전으로 되돌린다.
