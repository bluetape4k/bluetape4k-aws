# Issue #458 구현 계획 6관점 검토

## 대상과 판정

- 대상: `docs/superpowers/plans/2026-08-23-issue-458-appconfig-configdata-plan.md`
- 기준: 승인된 설계와 [사양 검토](2026-08-23-issue-458-appconfig-configdata-spec-review.md)의 P0/P1 해소 상태
- 결과: P0=0, P1=0, P2=6, P3=0
- 범위: production 코드와 GitHub 상태를 변경하지 않은 계획 검토

## 6관점 결과

| 관점 | P0/P1 | 확인한 계약 | P2 후속 조치 |
|---|---:|---|---|
| 성능 | 0 | pool size `min(active resources, 8)`, resource당 하나의 fixed-delay task, server/config interval max, bounded jitter/backoff, payload budget | scheduler queue/worker 수와 1 resource 1 task를 runtime test에서 계측 |
| 안정성 | 0 | Start/Get 초기 순서, empty retention, token/session 분리, startup rollback, bounded close와 interrupt 보존 | clock/scheduler seam으로 flaky time test를 고정 |
| 보안 | 0 | SDK class guard, token/body 비로그, opaque identity, sanitized exception, IAM `Resource: "*"` 근거 | user endpoint 신뢰 경계와 payload 수명 문서를 redaction test에 연결 |
| 운영 | 0 | refresh disabled 기본값, server interval 존중, IAM/cost/runbook, Floci와 real smoke 분리 | 실제 AWS 호출 비용과 Agent 대안은 manual 운영 절에 기록 |
| 개발/API | 0 | compileOnly SDK, exhaustive backend dispatch, typed customizer 순서, fake session contract, ABI/classpath 회귀 | public `AppConfigProperties`의 configuration metadata와 binary compatibility 기준 확인 |
| 사용자·호출자 | 0 | import/custom separator, name/identifier, optional/fail-fast/format/prefix, Environment와 binding semantics, no Spring Cloud Context | 양언어 예제의 literal/anchor parity와 migration wording 확인 |

## 계획 보강 사항

1. lifecycle scheduler/resource 등록 중 실패할 때 이미 등록한 task와 context client를
   close 순서로 rollback하도록 구현·테스트 항목을 추가했다.
2. parser/decoder/session/loader/lifecycle/auto-configuration/문서 acceptance를
   수용 기준 추적표로 연결했다.
3. fake contract를 필수로 두고 emulator가 AppConfig Data를 지원하지 않을 경우
   N/A 사유와 opt-in real smoke 조건을 별도 증거로 남긴다.
4. 문서의 IAM 예제는 AppConfig data-plane action의 resource type 부재에 맞춰
   `Resource: "*"`로 고정하고 ARN 범위를 가정하지 않는다.

## 검증 명령과 중단 규칙

- RED → targeted GREEN → 기존 ConfigData 회귀 → module full → detekt/classpath/ABI
  → Floci 순차 실행 → explicit real smoke → manual contract 순서를 고정한다.
- skipped/path-filtered CI와 emulator 미지원은 성공으로 세지 않고 N/A/PENDING으로
  분리한다.
- 실패한 단계의 원인·로그·재현 명령을 기록하고, 의존 단계는 중단한다.
- 모든 문서 변경은 `audit-korean-terms.mjs`와 `git diff --check`를 통과해야 한다.

## 계획 게이트 결론

6개 관점에서 P0/P1이 없고, P2는 구현·테스트·문서 DoD에 연결되어 있다. 계획을
승인 가능한 실행 순서로 확정하고, 다음 단계는 설계·계획·검토 문서 Lore commit 후
TDD RED부터 시작한다.
