# 이슈 #469 설계 명세 관점별 리뷰

**대상**: `docs/superpowers/specs/2026-08-26-issue-469-dynamodb-streams-design.md`
**리뷰일**: 2026-08-26
**범위**: 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자, 통합
**결론**: 통합 후 승인 — 구현 전 P0 0건, P1 0건

## 관점별 확인

| 관점 | 확인한 계약 | 결과와 후속 반영 |
|---|---|---|
| 성능 | shard별 순차 `GetRecords`, `flatMapMerge` concurrency, empty backoff, 무제한 buffer 금지 | 통과. 실행계획에서 root shard 단위 bounded merge와 batchLimit 상한을 고정한다. |
| 안정성 | iterator expiry, trim, throttling, cancellation, checkpoint save 실패, child shard lifecycle | 통과. `TrimmedDataAccessException` fallback 금지와 inclusive checkpoint resume을 public KDoc/test로 고정한다. |
| 보안 | record payload 미로그, sequence/iterator 노출 제한, client/credential 소유권 | 통과. metrics callback에도 payload를 전달하지 않으며 injected client를 Flow가 닫지 않는다. |
| 운영 | metrics no-op 기본값, retry budget, Floci와 AWS-only 경계, close helper | 통과. 실제 AWS 호출을 금지하고 Floci capability 증거와 N/A 증거를 별도 남긴다. |
| 개발자/API | SDK model package 차이, sealed 시작 위치, checkpoint SPI, raw record/envelope 분리 | 통과. Java는 `software.amazon.awssdk.services.dynamodb.model`, Kotlin은 전용 streams model을 사용하고 cross-module model 재수출을 하지 않는다. |
| 사용자/호출자 | at-least-once 중복 범위, 전역 shard 순서 부재, idempotency 책임, helper lifecycle | 통과. `emit` 후 save와 `AtSequenceNumber` inclusive 재생을 README/manual/KDoc에 같은 의미로 설명한다. |
| 통합 | version catalog, root dependency-management, consumer fixture, module registration, docs locales | 통과. Java 별도 `aws2-dynamodbstreams` alias를 만들지 않고 기존 `dynamodb` artifact를 재사용하며 Kotlin `dynamodbstreams` alias와 fixture를 함께 등록한다. |

## 명세 보정

초안의 “child enqueue”는 정적 `flatMapMerge`와 함께 구현할 때 동적 channel을
만들 위험이 있었다. 구현 계획에서는 `DescribeStream` 전체 페이지를 먼저 읽어
`parentShardId -> children` graph를 만들고, parent가 정상적으로 끝난 뒤에만
child tree를 재귀적으로 소비하는 root-shard Flow를 사용한다. root 간 동시성만
`maxShardConcurrency`로 제한하므로 무제한 queue를 만들지 않고 parent/child ordering을
보존한다. page 상한에 도달했는데 `lastEvaluatedShardId`가 남으면 성공으로 취급하지
않고 예외를 전파한다.

## 잔여 위험과 처리 위치

- Floci는 네 Streams operation을 지원하지만 24시간 경과와 실제 throttling 분포를
  재현하지 않는다. 해당 항목은 구현 후 검증표에서 `N/A (AWS-only)`로 분리한다.
- `metrics` callback이 예외를 던지면 consumer Flow도 실패할 수 있다. 이 동작은
  숨은 side effect를 만들지 않는 대신 callback 구현자가 non-throwing adapter를
  선택하도록 KDoc에 명시한다.
- checkpoint 저장과 business side effect의 원자성은 SPI 밖이다. duplicate-safe
  consumer와 저장소 durability를 호출자 책임으로 남긴다.

## SPW gate

- **SPW-01** audience/purpose/evidence: 통과 — public caller와 maintainer를 분리해 기술했다.
- **SPW-02** artifact contract: 통과 — public API, tests, README/manual, Floci evidence 경계를 명시했다.
- **SPW-03** Korean naturalness: 통과 — API/command/URL만 English 보존, 설명은 한국어다.
- **SPW-04** traceability: 통과 — issue, source paths, AWS/Floci 공식 링크, acceptance mapping을 연결했다.
- **SPW-05** read-back/status: 통과 — 본 리뷰에 현재 상태와 잔여 위험을 기록했다.

**승인 상태**: 구현계획 단계로 진행 가능. 설계 명세 외 public 범위 확장은
계획 리뷰를 갱신한 뒤에만 허용한다.
