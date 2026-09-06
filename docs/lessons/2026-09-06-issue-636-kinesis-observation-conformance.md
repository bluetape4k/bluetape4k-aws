# 관측 계약은 sealed API를 합치지 않고 wire shape를 합친다

## 배경

[Issue #636](https://github.com/bluetape4k/bluetape4k-aws/issues/636)은 Java SDK v2와
AWS SDK for Kotlin Kinesis consumer가 같은 lifecycle을 서로 다른 event model과 token 길이로
발행하는 문제를 다룬다. Java는 여러 sealed subtype과 문자열 vocabulary를 사용하고 Kotlin은
단일 `Observation` subtype과 enum vocabulary를 사용한다.

## 결정

- 기존 sealed hierarchy는 유지하고 각 module에 같은 wire-shaped
  `KinesisCanonicalObservation`을 추가한다.
- 양쪽 test source set이 하나의 properties manifest를 읽어 vocabulary, bounds, token vector와
  lifecycle mapping을 정확히 비교한다.
- adapter는 이미 redacted된 token만 받아 24자 prefix로 정규화하고 raw stream, shard, owner나
  payload를 입력으로 받지 않는다.
- Java의 `Shard(completed)`는 canonical에서 checkpoint 종료와 shard 성공 두 event로 확장한다.
  Kotlin의 기존 checkpoint와 shard event는 같은 canonical sequence가 된다.

## 결과

기존 `KinesisFlowEvent`와 callback ABI를 바꾸지 않고 exporter가 같은 event vocabulary와 field
shape를 받을 수 있게 됐다. 실제 consumer flow test도 양 module에서 동일한 canonical lifecycle
순서를 확인한다. 취소, lease loss, retry, discovery와 shard completion vector는 shared manifest에
고정됐다.

## 검증

- targeted conformance/flow: Java 6건, Kotlin 6건 통과
- affected full tests: Java 539건, Kotlin 827건, failure/error 0
- 양 module `detekt`: 통과
- root `compatibilityCheck --no-configuration-cache`: 통과
- 양 module publication metadata/POM 생성: 통과

## 놓친 점

첫 flow 회귀 테스트는 모든 실제 event가 동일한 stream token을 갖는다고 가정해 기존 production
emission 차이까지 바꾸려 했다. 승인된 설계는 legacy emission을 유지하고 exporter 경계만 수렴하는
것이므로 해당 변경과 assertion을 제거했다. conformance token vector가 양 module의 정규화 계약을
검증하고, actual flow test는 lifecycle ordering만 검증하도록 경계를 분리했다.

## 향후 지침

- 서로 다른 SDK의 sealed model을 통합할 때 기존 hierarchy에 공통 subtype을 추가하지 않는다.
- 공통 dashboard 계약은 production dependency가 아니라 shared fixture와 module-local adapter로 고정한다.
- redaction validator의 오류 메시지에도 거부된 원문을 포함하지 않는다.
- hash prefix는 관측 cardinality 제어용임을 문서화하고 보안 identity로 재사용하지 않는다.
