# Issue #191 스펙 검토

날짜: 2026-06-07
문서: `docs/superpowers/specs/2026-06-07-issue-191-dynamodb-dax-spring-boot-design.md`

## 검토 범위

#191 설계를 현재 `DynamoDbAutoConfiguration`/`DynamoDbProperties`, Spring 조건부 자동 구성, optional dependency/README 규칙, AWS DAX Java 2.x 문서, `amazon-dax-client:2.0.9` metadata/`javap`와 대조했다.

## 결과

| 심각도 | 수 | 메모 |
|---|---:|---|
| P0 | 0 | 차단 없음 |
| P1 | 0 | 고심각도 공백 없음 |
| P2 | 2 | 구현 중 추적 |

### P2-1 DAX SDK customizer는 그대로 재사용할 수 없다

`ClusterDaxAsyncClient.Builder`는 `DynamoDbAsyncClientBuilder`가 아니며 `software.amazon.dax.Configuration`만 받는다. `AwsAsyncClientCustomizer`/`AwsClientCustomizer<DynamoDbAsyncClientBuilder>` 재사용을 거부하고 typed property로 조정한다.

### P2-2 DAX 전이 AWS SDK version을 검증해야 한다

`amazon-dax-client:2.0.9`는 `software.amazon.awssdk:dynamodb:2.38.5`를 선언한다. Repository BOM/catalog가 우선함을 `dependencyInsight`로 증명해야 한다.

## Gate 판정

PASS (`P0=0`, `P1=0`). 계획으로 진행한다.
