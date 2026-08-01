# 교훈 — Nightly 전체 schedule condition (2026-06-04)

**관련 이슈**: #253

## 배경

Central snapshot metadata contention을 줄이도록 Nightly cron의 분 값을 분산했습니다. 하지만
전체 범위 scheduled job이 여전히 `github.event.schedule`을 이전 Sunday cron string과
비교해 주간 전체 job을 건너뛸 수 있었습니다.

## 결정

분산한 cron은 유지하고, 전체 범위 job condition이 저장소의 현재 Sunday schedule과
비교하도록 갱신합니다.

## 검증

- `actionlint .github/workflows/nightly-tests.yml`
- `git diff --check`
- schedule-condition audit: 이전 `0 19 * * 0` 전체 job condition이 남아 있지 않습니다.

## 향후 지침

scheduled cron string을 변경할 때는 같은 workflow의 모든 `github.event.schedule` 비교도
함께 갱신합니다.
