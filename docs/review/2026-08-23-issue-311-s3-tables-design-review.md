# Issue #311 S3 Tables 설계 독립 검토

## 검토 기준

- 검토일: 2026-08-23
- 대상: `docs/superpowers/specs/2026-08-23-issue-311-s3-tables-design.md`
- 독립 관점: 아키텍처, 보안, 성능, 안정성/운영, 개발자/API, 사용자/호출자
- 외부 근거: AWS S3 Tables Java/Kotlin SDK와 S3 Tables API 공식 문서

## 검토 결과와 수정

초기 검토에서 `CreateTable.format`, `GetTable` selector, 목록 한 페이지 의미, mutating smoke cleanup 계약이
불충분하다고 판정했다. 수정본은 `format=ICEBERG` 기본값과 callback 후 최종 검증, selector XOR,
continuation/max/filter 한 페이지 계약, read-only/mutating smoke 분리와 실행 소유 리소스 역순 삭제를 고정한다.

후속 검토에서 credential account 검증과 정확한 property/env 계약, Kotlin 대칭 테스트가 필요하다고 판정했다.
최종 문서는 첫 생성 전 STS `GetCallerIdentity.account`와 기대 account ID를 비교하고 불일치 시 fail-closed 하며,
다음 실행 계약을 고정한다.

- read-only: `-Ps3TablesReadOnlySmoke`, `S3_TABLES_READ_ONLY_REGION`, `S3_TABLES_READ_ONLY_TABLE_BUCKET_ARN`
- mutating: `-Ps3TablesMutatingSmoke`, `S3_TABLES_EXPECTED_ACCOUNT_ID`, `S3_TABLES_MUTATING_REGION`, `S3_TABLES_MUTATING_PREFIX`

## 최종 판정

| 관점 | 상태 | 근거 |
|---|---|---|
| 아키텍처 | PASS | raw SDK boundary와 selector/format invariant가 명확함 |
| 보안 | PASS | credential account 일치 확인과 fail-closed mutating gate를 명시함 |
| 성능 | PASS | 한 페이지 계약으로 숨은 반복 호출을 방지함 |
| 안정성/운영 | PASS | lifecycle, timeout, 역순 cleanup, sanitized evidence를 명시함 |
| 개발자/API | PASS | Java/Kotlin 대칭 public surface와 compileOnly 경계를 명시함 |
| 사용자/호출자 | PASS | 관리 API와 Iceberg data-plane 경계를 설명함 |

P0/P1: 0. P2: Java application-scoped `ShutdownQueue` 등록 의미와 stale `WIP.md`는 구현 evidence에서 다시 확인한다.
