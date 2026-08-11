# #453 SQS visibility heartbeat 구현 교훈

## 결정

- `messageVisibilityHeartbeatIntervalSeconds`와 `messageVisibilityHeartbeatSeconds`를 전역 listener 속성과
  `@SqsListener`에 각각 추가하고, 두 값이 함께 설정된 경우에만 heartbeat를 활성화한다. 기본값은 비활성화다.
- 두 값은 `1..43_200` 범위의 양수이고 interval은 heartbeat timeout보다 짧아야 한다. 애노테이션의 유효 설정도
  endpoint 생성 시 다시 검증해 전역 설정과 동일한 계약을 적용한다.
- heartbeat는 handler coroutine의 child로 실행하고 listener generation fence를 호출 직전에 확인한다. 성공·실패·
  retry·취소·graceful stop은 공통 `finally`에서 child job을 취소하고 join한다. `GlobalScope`와 독립 executor는 사용하지 않는다.
- 단건은 기존 `SqsOperations.changeVisibility`를, batch는 pending 메시지에 대한 기존 `changeVisibilityBatch`를 사용한다.
  partial acknowledgement로 완료된 메시지는 다음 heartbeat snapshot에서 제외해 FIFO batch의 아직 pending인 항목도 보호한다.
- AWS 호출은 `Dispatchers.IO`로 이동하고 heartbeat 예외는 cancellation만 재전파한다. 그 밖의 실패는 로그와 기존
  Micrometer operations 관측에 남기되 handler 결과에는 영향을 주지 않는다.

## 검증 증거

- `SqsPropertiesTest`: 두 설정의 동시 요구, 양수·상한·interval 순서 검증을 통과했다.
- `SqsMessageListenerContainerTest`: 단건 주기 호출과 성공 후 정리, heartbeat 실패의 handler 결과 보존, batch pending
  snapshot과 partial acknowledgement 제외를 검증했다. 테스트 dispatcher의 가상 시간과 IO 경계의 실제 대기를 분리했다.
- `:bluetape4k-aws-spring-boot:compileKotlin` 및 `compileTestKotlin`이 통과했다.
- awspring PR #1622의 두 설정 이름과 FIFO batch visibility review를 참고했지만, 현재 컨테이너의 generation/acknowledgement
  계약에 맞춰 독립 구현했다.

## 남은 위험과 guard

- heartbeat는 메시지마다 추가 SQS 요청을 발생시키므로 interval이 짧거나 동시성이 높으면 비용과 throttling이 증가한다.
  기본 비활성화와 timeout 전 여유를 남기는 interval 권장값을 유지한다.
- AWS 호출이 이미 시작된 stop race에서는 해당 in-flight 요청이 완료될 수 있지만, generation 취소 뒤 새 주기를 시작하지 않는다.
  운영 지표에서 heartbeat 실패와 redelivery를 함께 확인한다.
- SQS at-least-once 전달과 FIFO ordering은 여전히 존재하므로 handler side effect는 idempotent 또는 deduplicated여야 한다.

## 운영 rollback

heartbeat 두 설정 제거 또는 `null`로 되돌림 → 새 listener generation 배포 → in-flight drain과 `STOPPED` 확인 → redelivery/DLQ와
handler idempotency 검증 순서를 지킨다.
