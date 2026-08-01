# Issue #309 스펙 검토

## 판정

PASS - P0/P1 문제를 발견하지 못했다.

## 검토 내용

- 범위는 #308 EventBridge core wrapper의 후속 작업으로 올바르게 한정했다.
- Spring/Ktor API는 원본 AWS SDK 응답을 유지하며 숨은 배치, 재시도, 정리, 백그라운드 게시를 추가하지 않는다.
- `libs.aws2.eventbridge`는 소비자 `compileOnly`, 로컬 검증 `testImplementation`으로 선택적 의존성 소유권을 명시한다.
- 기본 event bus는 좁게 적용하며 `PutEvents` 항목을 다시 쓰지 않는다.
- Emulator는 실제 probe 또는 미지원 공백을 요구해 증거를 과장하지 않는다.

## 잔여 위험

- 생성된 AWS SDK model 메서드 이름은 컴파일로 검증해야 한다.
- README는 간결해야 하며 Scheduler 지원을 암시하면 안 된다.
