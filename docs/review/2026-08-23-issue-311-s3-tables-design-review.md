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

P0/P1/P2: 0. Java application-scoped factory가 `ShutdownQueue`에 등록하고 caller가
필요 시 조기 종료한다는 문구와, stale `WIP.md`를 부분 수정하지 않는다는 판단을
구현 문서·evidence에 반영했다. 실제 AWS credential/resource smoke와 emulator fidelity는
의도적으로 미실행 상태다.

## 후속 검토 (commit `3490088`)

최신 구현과 검증 증거를 다시 읽은 결과 아키텍처 차단사항은 없다.

| 항목 | 상태 | 근거 |
|---|---|---|
| Java smoke timeout/cleanup | PASS | 비선점 `assertTimeout`으로 cleanup이 timeout 경계에서 실행되며, table·namespace·bucket 삭제를 독립 시도한다. |
| `GetTable` selector 계약 | PASS | non-null blank selector를 먼저 거부하고 ARN 또는 완전한 path 중 하나만 허용한다. |
| 공개 request factory 문서 | PASS | Java/Kotlin S3 Tables public factory 12개 모두 한국어 KDoc를 제공한다. |
| Kotlin smoke PASS 시점 | PASS | read-only lane을 제외한 mutating PASS는 cleanup 완료 후 한 번만 기록한다. |
| 최신 로컬 검증 | PASS | targeted Java 21/Kotlin 17, full Java 487/15 skipped 및 Kotlin 660/13 skipped, failure/error 0. |

후속 잔여사항은 실제 AWS credential/resource smoke와 emulator fidelity의 의도적 미실행뿐이며,
이는 Issue #311 범위 제한이다. P0/P1은 0건이다.
