# Issue #455 SQS Extended Client 구현 lesson

## 결정

기존 SQS/S3 adapter를 감추어 변경하지 않고 additive capability marker와
명시적 `SqsExtendedClientOperations`를 사용했다. Extended path는 opt-in이며
기본 wire format과 기존 public ABI를 보존한다.

## 배운 점

1. S3 pointer를 도입할 때는 payload 크기만 확인하는 것보다 queue URL,
   policy fingerprint, content type, encryption identity를 함께 서명해야
   foreign pointer와 설정 drift를 fail-closed로 막을 수 있다.
2. `AsyncResponseTransformer.toPublisher()`를 `asFlow()`로 소비할 때
   `max+1` probe를 ByteArray materialization 전에 적용하고, 누적 크기는
   `maxBytes - size` 비교로 정수 overflow까지 차단해야 한다.
3. ACK cleanup은 SQS delete와 S3 delete를 분리해야 한다. marker의
   conditional create/HEAD 결과를 확인한 뒤에만 payload를 삭제하고, 실패한
   경우 재시도 가능한 opaque handle을 남기는 편이 운영 복구에 안전하다.
4. Jackson 지원은 임의 ObjectMapper를 전역으로 보장하지 않고 supported
   Jackson 3 auto-configuration 경계에서 safe DTO module만 제공해야 raw
   payload와 AWS SDK model의 노출을 피할 수 있다.
5. legacy ABI는 현재 컴파일 결과만 보는 것보다 pre-change source/bytecode와
   normalized signature fixture를 저장하고 task로 재검증해야 additive API
   변경의 경계를 반복해서 확인할 수 있다.

## 후속

외부 publisher 지연·cleanup telemetry 및 실제 heap·throughput 측정은
Issue [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)에서
환경·baseline·회귀 기준을 고정한 뒤 수행한다. 이 수치를 현재 기능의 제품
계약으로 앞당겨 해석하지 않는다.
