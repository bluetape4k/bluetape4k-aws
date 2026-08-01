# Issue 308 코드 검토

## 범위

- Java SDK v2 EventBridge client factory, request builder, sync/async helper, coroutine adapter.
- AWS Kotlin SDK EventBridge client factory, request builder, native suspend helper.
- README locale과 dependency catalog/build 선언.

## 결과

- API/P0: SDK 요청당 helper 호출 한 번과 원본 응답을 유지한다. mock 호출 횟수 테스트로 확인했다.
- 부분 실패/P0: `PutEvents`, `PutTargets`, `RemoveTargets` 응답을 Boolean으로 축약하지 않는다. 응답 동일성 테스트와 README/KDoc로 확인했다.
- 검증/P0: 필수 필드 공백과 EventBridge 10개 항목 제한을 SDK 호출 전에 검사한다.
- 수명 주기/P0: Java client는 `ShutdownQueue`, AWS Kotlin client는 `withEventBridgeClient` 외에는 호출자 소유 규칙을 따른다.
- 취소/P0: Java coroutine helper는 `await()`를 사용하고 취소를 잡지 않는다.
- Emulator/P1: `*EventBridgeEmulator*` smoke가 없어 Floci/LocalStack live smoke를 주장하지 않았다. mock/request 테스트로 core wrapper를 검증했다.
- 문서/P0: README locale에 EventBridge, runtime dependency, 부분 실패, non-goal을 기록했으며 `rg EventBridge/eventbridge/partial`로 확인했다.

## 검증 증거

- `./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache`: PASS
- `./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all`: PASS
- `git diff --check`: PASS
- `find aws-java/src/test aws-kotlin/src/test -name '*EventBridgeEmulator*' -o -name '*EventBridge*'`: emulator smoke 없이 request/client/mock test만 확인한 `rg` 증거
