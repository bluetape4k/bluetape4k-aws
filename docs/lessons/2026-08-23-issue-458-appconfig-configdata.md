# Issue #458 AppConfig ConfigData와 runtime reload 경계

## 배경

Spring Boot `ConfigData` 초기 로드는 bootstrap context에서 실행되지만,
AppConfig Data의 long-polling은 application context 수명 동안 계속된다. 두
경로가 같은 SDK client를 공유하면 bootstrap 종료 시 runtime poller가 닫힌
client를 사용할 수 있고, 반대로 runtime lifecycle이 context-owned client를
닫으면 Spring의 bean 수명 주기와 충돌한다.

## 결정

- 초기 `StartConfigurationSession`/`GetLatestConfiguration` 호출은 bootstrap
  client로 수행한다.
- runtime lifecycle은 application-context-owned `AppConfigDataClient`를
  감싼 별도 session adapter로 교체한 뒤 poller를 시작한다.
- application context가 실제 SDK client를 닫고, adapter의 `close()`는
  context-owned client를 닫지 않는다.
- 각 poll은 현재 token을 먼저 전진시키고, 빈 응답은 기존 immutable map을
  유지한다. decode 실패도 현재 map을 유지하되 token 전진은 보존한다.
- transport 오류만 session을 폐기하고 bounded full-jitter backoff 후 새
  session을 연다. scheduler는 source마다 하나의 fixed-delay 작업만 둔다.

초기 값과 runtime 값을 하나의 mutable map으로 직접 갱신하는 방식보다
immutable 기준 데이터를 원자적으로 교체하는 방식이 Spring `Environment` 조회와
동시 poll 사이의 일관성을 보장하고, 기존에 바인딩된 객체가 자동 재바인딩되지
않는다는 계약도 명확히 한다.

## 검증 증거

- `AppConfigReloadLifecycleTest`에서 bootstrap client에는 runtime poll이
  발생하지 않고 context-owned client가 현재 token으로 poll하는지 확인했다.
- 빈 응답, decode 실패, transport 재시도, 취소 시 재예약 금지, 서버 poll
  간격 fallback, startup rollback client 정리를 회귀 테스트로 고정했다.
- AppConfig Data API를 제공하지 않는 Floci 경로에서는 전체 모듈 테스트가
  변경 없이 통과했으며, 실서비스 smoke는 환경별 identifier와 credential이
  없어 실행하지 않았다.

## 향후 보호 장치

새 AWS ConfigData backend를 추가할 때도 SDK가 없는 공통 SPI 경계와 실제 SDK
adapter를 분리하고, bootstrap 소유 자원과 application-context 소유 자원의
닫힘 주체를 먼저 명시한다. Spring Cloud Context를 복제해 기존 binding 객체를
강제 재바인딩하는 방식은 이번 계약에 포함하지 않는다.
