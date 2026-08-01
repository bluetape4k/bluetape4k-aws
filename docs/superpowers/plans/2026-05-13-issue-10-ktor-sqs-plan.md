# 이슈 #10 Ktor SQS 소비자 / 게시자 계획

날짜: 2026-05-13
설계: `docs/superpowers/specs/2026-05-13-issue-10-ktor-sqs-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/10

## 작업

### T0 - 조사

- [x] 기존 `aws-ktor` SigV4/S3 패턴을 조사한다.
- [x] 기존 `aws` SQS 코루틴 확장을 조사한다.
- [x] Ktor 3 플러그인 수명 주기 문서를 확인한다.
- [x] AWS SDK v2 SQS 비동기 API 가정을 확인한다.
- [x] 외부 자문 검토를 수행하고 수명 주기, 백오프, DLQ, 종료 관련 발견 사항을 반영한다.

### T1 - 빌드 연결

- [x] `aws-ktor` 컴파일/테스트 의존성에 `software.amazon.awssdk:sqs`를 추가한다.
- [x] 가능한 경우 서버 의존성을 `compileOnly`와 테스트 전용으로 유지한다.

### T2 - 런타임 API

- [x] SQS 플러그인 구성과 모델 클래스를 추가한다.
- [x] 메시지 변환기 인터페이스와 기본 변환기를 추가한다.
- [x] 시작/중지/전송 및 폴링 기능을 갖춘 `SqsConsumerRuntime`을 추가한다.
- [x] 수신 루프용 `SqsPollBackoff`를 추가한다.
- [x] 우아한 종료 제한 시간과 선택적 가시성 하트비트를 추가한다.
- [x] 큐 식별자, 동시성, 수신 범위, 가시성 범위, DLQ/실패 가시성 충돌, 하트비트 제약을 검증한다.

### T3 - Ktor 플러그인

- [x] Ktor 수명 주기 이벤트를 사용하는 `SqsConsumer` 플러그인을 추가한다.
- [x] 시작에는 `ApplicationStarted`, 드레이닝/중지에는 `ApplicationStopping`을 사용한다.
- [x] `SqsKtorPlugin` 별칭을 추가한다.
- [x] 명시적인 게시 접근을 위해 런타임을 애플리케이션 속성에 저장한다.

### T4 - 테스트

- [x] 구성 검증/단위 테스트를 추가한다.
- [x] Testcontainers LocalStack SQS 왕복 테스트를 추가한다.
- [x] Awaitility를 사용하는 다중 코루틴/멀티스레드 게시자 테스트를 추가한다.
- [x] Awaitility를 사용하는 우아한 종료 취소 테스트를 추가한다.
- [x] 메타데이터 검증을 포함한 수동 DLQ 전달 테스트를 추가한다.
- [x] 큐 이름 해석 재시도 회귀 테스트를 추가한다.
- [x] 삭제 실패가 DLQ로 이어지지 않는지 검증하는 회귀 테스트를 추가한다.
- [x] 느린 핸들러의 배압 회귀 테스트를 추가한다.
- [x] 수명 주기 시작 테스트를 추가한다.

### T5 - 문서

- [x] `aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 갱신한다.
- [x] `software.amazon.awssdk:sqs` 의존성 코드 조각을 포함한다.
- [x] Ktor 플러그인 및 게시자 사용법과 동시성/종료 참고 사항을 포함한다.
- [x] 수동 DLQ의 비원자적 의미와 네이티브 redrive 우선 원칙을 문서화한다.

### T6 - 검증 및 PR

- [x] 대상 컴파일/테스트를 실행한다.
- [x] 전체 `:aws-ktor:test`를 실행한다.
- [x] 학습 문서를 추가한다.
- [ ] Lore 트레일러를 포함해 커밋한다.
- [ ] 푸시하고 `debop`에게 할당한 PR을 만든 뒤 CI를 모니터링하고 성공하면 검토 준비 상태로 전환한다.

## 수용 기준

- Ktor 애플리케이션이 `SqsConsumer`를 설치할 수 있다.
- 런타임이 메시지를 소비하고 처리에 성공한 메시지를 삭제한다.
- 런타임이 메시지를 게시할 수 있다.
- 테스트가 Awaitility를 사용해 코루틴 동시 소비를 입증한다.
- 테스트가 LocalStack/Testcontainers SQS 통합을 입증한다.
- README 문서가 멀티스레드/코루틴 동작과 우아한 종료를 설명한다.
- 테스트가 취소 시 처리 중인 메시지를 실수로 삭제하지 않음을 입증한다.
- 실패 의미와 수동 DLQ 주의 사항을 문서화한다.
