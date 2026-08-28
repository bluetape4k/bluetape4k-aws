# #506 controlled publisher latency·자원 측정 lesson

## 재사용 가능한 패턴

1. 이미 검증된 #505 adapter와 `RecordingSdkPublisher`를 확장하면 production
   coordinator를 바꾸지 않고 callback 경계를 반복할 수 있다.
2. publisher cleanup은 `IMMEDIATE`, `DELAYED`, `BLOCKING`로 분리하고,
   cancel request/completion 시각을 서로 다른 atomic 값으로 기록한다.
3. blocking callback은 operation dispatcher를 고의로 점유하되, 별도 scheduler의
   watchdog와 bounded `CountDownLatch`로 반드시 해제한다.
4. suspend polling에는 `untilSuspending`와 명시적 1ms poll delay를 사용해 event
   volume이 기본 100ms 초기 지연에 곱해지지 않도록 한다.
5. SQS performance precedent처럼 `com.sun.management.ThreadMXBean`를 worker
   thread에 한정해 allocation을 읽고, `Runtime` heap은 보조 관찰값으로만 해석한다.

## 피해야 할 오해

- publisher cleanup 지연을 coordinator 회귀나 AWS 서비스 latency 보장으로 해석하지 않는다.
- heap delta 하나로 누수 결론을 내리지 않는다. raw samples, JVM, dispatcher, warmup,
  measurement, source hash를 함께 보존한다.
- late callback을 생략하지 않는다. terminal 이후 publisher를 주입해 cancel과 pending
  callback 0을 확인해야 한다.
- blocking 테스트에서 watchdog 없는 latch나 무제한 await를 사용하지 않는다.

## 후속 분리

FlociServer에 Bedrock ConverseStream endpoint가 추가되기 전에는 이 harness를 실제
서비스 latency 측정으로 확장하지 않는다. production telemetry, public API, 공통
metrics dependency가 필요해지면 별도 설계와 issue로 분리한다.

## Lesson DoD

- **SPW-01:** 재사용 대상과 독자를 명시했다.
- **SPW-02:** 구현 패턴·오해·후속 경계를 구분했다.
- **SPW-03:** 한국어 lesson과 코드 token 보존을 적용했다.
- **SPW-04:** #505·SQS precedent·Floci 경계를 대조했다.
- **SPW-05:** heading·path·token을 read-back했다.
