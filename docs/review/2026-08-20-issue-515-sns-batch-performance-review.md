# Issue #515 SNS 배치 성능 self-improve 검토

> 대상 이슈: [#515](https://github.com/bluetape4k/bluetape4k-aws/issues/515)
> 기준선: `5fdb685db89972af1b8c0e01dea067532d54e4ec`
> 검토일: 2026-08-20

## 종합 판정

fake publisher 기반의 반복 가능한 benchmark 계약과 두 후보의 비교 결과를
검토했다. 3회 기준선과 각 후보의 36셀 raw output, parser summary, cleanup 관측을
대조했으며, aggregate throughput 상승만으로 후보를 통합하지 않았다.

**self-improve 판정: PASS (측정·거부·복구 절차), winner 없음.**

| 영역 | 판정 | 근거 |
|---|---|---|
| benchmark contract | PASS | 36셀 complete matrix, 3회 기준선, 환경·커밋·parser·threshold·guard를 고정했다. |
| cleanup/concurrency | PASS | 기준선과 후보 모두 `activeAfter=0`, `maxActive<=maxInFlight`, chunk 수 계약을 검증했다. |
| candidate acceptance | PASS | 후보 1은 24개, 후보 2는 31개의 행별 throughput/heap guard 실패를 기록하고 통합하지 않았다. |
| branch isolation | PASS | 후보 1은 되돌림을 기록했고 후보 2는 별도 candidate worktree에서 검증했다. |
| public API/scope | PASS | public API와 sealed benchmark/parser 경계를 바꾸지 않고 내부 관측 hook과 benchmark 문서만 추가했다. |
| external/production evidence | PENDING | 실제 AWS publisher latency·cleanup telemetry와 실제 heap·throughput은 이번 fake benchmark에 포함하지 않았다. |

1인 개발자 저장소이므로 human review gate는 N/A다. PR 생성, exact-head CI, merge
승인, local sync/cleanup은 이 검토와 분리된 delivery gate다.

## 핵심 수치

| 측정 | 기준선 | 후보 1 | 후보 2 |
|---|---:|---:|---:|
| throughput median (messages/s) | 2720212.0095569203 | 2761813.7435756726 (+1.53%) | 2778196.1402219627 (+2.13%) |
| 최대 p95 (ns) | 24736.61003365924 | 24493.86221823365 | 24112.421208654043 |
| 최대 peak-heap sample (bytes) | 536870912 | 536870912 | 570425344 (+6.25%) |
| 반복 | 3 | 3 | 3 |
| 셀 | 36 | 36 | 36 |

후보 1은 일부 행 throughput 최소 개선 및 heap guard가 실패했고, 후보 2는
throughput 최소 개선과 heap guard가 실패했다. 각 결과는
`.omx/self-improve/tracking/raw/`에 보존했다.

## 후속 범위와 중단 조건

실제 heap 수치나 외부 publisher 결과를 fake benchmark에서 추정하지 않는다. Issue
#515는 다음 증거가 생길 때까지 열린 후속 범위로 유지한다.

- controlled external publisher의 latency/cleanup telemetry와 cancellation/close
  barrier 측정
- Floci 및 실제 AWS에서 분리한 throughput·실제 heap profile과 반복 기준

현재 approved scope에서 새로운 병목 근거 없이 세 번째 후보를 만들지 않고,
`tracking/baseline.json`을 마지막 유효 checkpoint로 보존한다. 다음 후보는 새
approach family와 rollback 증거를 먼저 추가해야 한다.

## DoD Status

- [x] 기준선·후보 raw evidence와 parser summary 보존
- [x] 행별 acceptance rule로 후보 거부 및 winner 미선정
- [x] 기준선 유지와 self-improve stop reason 기록
- [x] 실제 publisher/heap 후속 범위를 Issue #515에 남김
- [ ] PR·CI·merge·local sync·cleanup

**최종 상태: PENDING — 측정 루프는 검증된 기준선에서 종료했으며 delivery 및 실제
운영 측정 gate가 남아 있다.**
