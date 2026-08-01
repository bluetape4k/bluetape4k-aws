# Issue #201 Step 3-R 계획 검토

Date: 2026-06-08
Plan: `docs/superpowers/plans/2026-06-08-issue-201-ktor-cloudwatch-plan.md`
Spec: `docs/superpowers/specs/2026-06-08-issue-201-ktor-cloudwatch-design.md`
References:

- `bluetape4k-full-feature/references/step-3r-plan-review-perspectives.md`
- `bluetape4k-full-feature/references/step-3r-plan-review.md`

## 검토 범위

- 의존성, 공유 기본값, metrics plugin, Micrometer snapshot bridge, logs plugin/runtime,
  README, 교훈, 검증에 대한 작업-명세 대응 범위
- lifecycle 소유권, cancellation, 테스트, 문서 parity, 의존성 범위, rollback에 필요한
  Step 3-R 검사

## 관점별 검토 결과

| 관점 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| 구현자 | 0 | 0 | 0 | 0 | 작업은 build/defaults, metrics, bridge, logs, docs, verification 의존 순서로 배치됐다. |
| 테스트 엔지니어 | 0 | 0 | 0 | 0 | 불완전한 suspend cancellation 범위에 대한 초기 P1을 게이트 종료 전에 해결했다. |
| 아키텍트 | 0 | 0 | 0 | 0 | 모듈 경계는 추가 방식이며 `aws-java` coroutine 도우미를 재사용한다. |
| 전달 | 0 | 0 | 0 | 0 | README parity, KDoc, 교훈, dependency insight, 대상 Gradle 명령을 다룬다. |

## 7단계 검토 결과

| 단계 | 범위 | P0 | P1 | P2 | P3 | 근거 |
|---|---|---:|---:|---:|---:|---|
| 1 보안 | 자격 증명, endpoint override, 우발적 publish | 0 | 0 | 0 | 0 | 계획은 자격 증명을 호출자가 소유하게 하고 setup/publish를 opt-in으로 둔다. |
| 2 Ops/SRE 신뢰성 | startup/shutdown, timeout, retry 소유권 | 0 | 0 | 0 | 0 | 계획은 `ApplicationStarted`와 `ApplicationStopping` lifecycle 지점을 지정한다. |
| 3 구조 영향 | `AwsKtorCore`, `aws-ktor`, `aws-java` 재사용 | 0 | 0 | 0 | 0 | 새 모듈과 BOM 또는 CI 등록 변경이 필요하지 않다. |
| 4 Kotlin/API 품질 | public API, 검증, KDoc | 0 | 0 | 0 | 0 | 계획은 영문 KDoc과 기존 Ktor 이름 규칙을 요구한다. |
| 5 테스트 가능성/타입/조용한 실패 | 성공, 실패, 경계, lifecycle, cancellation | 0 | 0 | 0 | 0 | 각 suspend 작업 그룹의 cancellation 테스트를 지정한다. |
| 6 성능/안정성 | batching, 빈 no-op, mutex, shutdown timeout | 0 | 0 | 0 | 0 | 동시 flush와 제한된 stop 테스트를 포함한다. |
| 7 문서/릴리스/증거 | README 언어판, 교훈, 검증 명령 | 0 | 0 | 0 | 0 | README.md, README.ko.md, 교훈, 구체적 Gradle 검사를 포함한다. |

## 통합 검토 결과

| 심각도 | 영역 | 결과 | 처리 |
|---|---|---|---|
| P0 | 없음 | P0 결과 없음. | N/A |
| P1 | 테스트 | Cancellation 전파가 처음에는 대표 suspend 호출에만 지정됐지만 Step 3-R은 모든 suspend API 작업에 명시적 cancellation 증거를 요구한다. | `putMetricData`, `listMetrics`, `createLogGroup`, `createLogStream`, `putLogEvents`, `describeLogGroups`, `describeLogStreams`, buffered `flush` cancellation 검사를 추가해 해결했다. |
| P2 | 없음 | 남은 P2 결과 없음. | N/A |
| P3 | 없음 | P3 결과 없음. | N/A |

## 제외한 항목

- 이 issue에 CloudWatch emulator 통합 테스트 추가: #201이 CI와 로컬 개발에서 CloudWatch를
  요구하지 않도록 명시했으므로 거부했다.
- plugin 수준 retry 로직 추가: 명세가 retry 정책을 AWS SDK client 구성에 위임하므로 거부했다.
- 전역 Micrometer registry/exporter 검증 추가: 승인된 설계가 명시적 snapshot publishing만
  허용하므로 거부했다.

## 열린 질문

차단 항목이 없다. 구현을 진행할 수 있다.

## 게이트 판정

P0 = 0
P1 = 0

Step 3-R을 닫았고 Step 4 구현의 차단이 해제되었다.
