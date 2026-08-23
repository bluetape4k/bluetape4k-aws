# Issue #458 AppConfig ConfigData 설계 사양 6관점 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-23-issue-458-appconfig-configdata-design.md`
- 이슈: [#458](https://github.com/bluetape4k/bluetape4k-aws/issues/458), 상위 Epic [#500](https://github.com/bluetape4k/bluetape4k-aws/issues/500)
- 관점: 성능, 안정성, 보안, 운영, 개발/API, 사용자·호출자
- 경계: 읽기 전용 사양 검토. production 코드와 public GitHub 상태는 변경하지 않았다.
- 근거: 현재 `develop`의 ConfigData 공통 구조, 공식 Spring Boot ConfigData API와 AWS AppConfig Data API/Service Authorization 문서

## 판정

| 항목 | 결과 | 근거 |
|---|---|---|
| P0 | 0건 | 데이터 손상·보안 우회·필연적 기동 장애를 만드는 설계 결함 없음 |
| P1 | 0건(수정 후) | 성능 4건과 운영/IAM 1건을 사양에 반영하고 해당 관점을 재검토함 |
| P2 | 5건 | 구현·문서 계획에서 고정할 비용, 관찰성, API 회귀, discoverability 세부사항 |
| P3 | 0건 | 승인 차단이 아닌 선택적 개선만 남음 |

## 관점별 결과

### 성능

| 우선순위 | 근거 | 조치 | 재검토 |
|---|---|---|---|
| P1 | `refresh-interval`과 서버 poll interval의 하한·범위·overflow가 없었음 | 15초~24시간 범위, overflow-safe 초 정규화, 서버 값 이탈 fallback, `RequiredMinimumPollIntervalInSeconds` 전달을 §5.2에 추가 | 수정 후 P1 없음 |
| P1 | scheduler worker 수와 예약 방식이 불명확했음 | 활성 resource 수와 8 중 작은 값(최소 1)의 단일 `ScheduledThreadPoolExecutor`, resource당 하나의 fixed-delay self-reschedule, `scheduleAtFixedRate` 금지와 queue 상한을 §6.2에 추가 | 수정 후 P1 없음 |
| P1 | transport/token-invalid와 decoder 실패의 재시도 폭주 경계가 없었음 | decoder는 새 token·마지막 정상 map을 유지하고, transport/session 오류는 1초 시작·5분 상한 full jitter backoff 후 새 session을 시작하도록 §6.1/§7.3에 추가 | 수정 후 P1 없음 |
| P1 | close 시 drain/강제 종료 경계가 없었음 | 신규 예약 차단→future cancel→5초 bounded drain→`shutdownNow` fallback→client close, daemon/remove-on-cancel/interrupt 보존을 §6.2에 추가 | 수정 후 P1 없음 |
| P2 | payload와 중간 flatten 구조의 메모리 예산이 없었음 | 1 MiB, depth 32, property 10,000 예산과 초과 시 기존 값 보존을 §7.1에 추가 | 구현 경계 테스트로 확인 |

### 안정성

P0/P1은 확인되지 않았다. 초기 `StartConfigurationSession`→첫 `GetLatestConfiguration`, 매 응답의 단일 사용 next token, 빈 응답의 기존 값 보존, decoder 실패의 token 진행, transport/session 실패의 session 재시작, cancellation 전파와 context 종료 순서를 §6.1~§6.3에 고정했다. `Environment`는 다음 조회부터 최신 값을 읽고 `@ConfigurationProperties`는 자동 rebind하지 않는다는 경계도 유지한다.

### 보안

P0/P1은 확인되지 않았다. token, body, content와 raw identifier를 로그·예외에 복사하지 않고 opaque identity와 예외 class만 남긴다. query control character·중복·unknown key를 거부하며, SDK class guard와 조건부 bean으로 classpath 경계를 유지한다. 운영 재검토에서 확인된 IAM resource 경계는 아래와 같이 수정했다.

### 운영

| 우선순위 | 근거 | 조치 | 재검토 |
|---|---|---|---|
| P1 | AppConfig Data의 `StartConfigurationSession`/`GetLatestConfiguration` action은 AWS Service Authorization 표에서 resource type을 제공하지 않음 | application/environment/profile ARN을 요구하던 문구를 제거하고 두 action과 `Resource: "*"`를 문서화한다. account/region 제한은 role boundary·조직 정책·네트워크 경계로 안내한다 | 수정 후 P1 없음 |
| P2 | 직접 data-plane polling은 호출 비용과 관찰성 운영 판단이 필요함 | 기본 refresh disabled, server interval 존중, backoff/cost/IAM 예시, opaque identity·exception class 관찰성, opt-in real smoke와 fake contract를 README/manual에 반영 | 계획/문서 검증 |

### 개발/API

P0/P1은 확인되지 않았다. 구현 계획에서 `AwsConfigDataBackend`/location source의 exhaustive 확장, `AwsConfigDataSupport` binder와 bootstrap bridge 재사용, `appconfigdata` compileOnly 및 `@ConditionalOnClass` 경계, 기존 global/service typed customizer 순서, public `AppConfigProperties`의 binary/API 회귀 테스트를 고정한다. fake `AppConfigDataSessionClient`가 SDK adapter와 분리된 테스트 경계를 제공한다.

### 사용자·호출자

P0/P1은 확인되지 않았다. 문서에는 `spring.config.import=optional:aws-app-config:orders#production#prod`, 사용자 정의 `separator`, `format`/`prefix`, optional·fail-fast, region/endpoint, refresh 기본값과 `Environment`/`@ConfigurationProperties` 의미를 동일한 한국어·영어 구조로 추가한다. Spring Cloud Context 자동 rebind를 제공하지 않는다는 점과 runtime failure 시 마지막 정상 값을 유지한다는 점을 명시한다.

## 설계 수정 후 확인

- 성능 P1 네 건: 하한·worker·backoff·shutdown 계약을 설계에 반영했다.
- 운영 P1 한 건: AWS Service Authorization 근거에 맞춰 IAM `Resource: "*"` 경계를 반영했다.
- 안정성·보안·개발/API·사용자 관점: P0/P1 없음. P2는 구현 계획과 문서 DoD에 이관했다.
- 한국어 용어 감사: `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json docs/superpowers/specs/2026-08-23-issue-458-appconfig-configdata-design.md` → `findings: []`.
- 변경 범위: 사양 파일과 이 검토 기록만 추가했으며 production 파일은 아직 변경하지 않았다.

## 승인 게이트

P0=0, P1=0 상태이며 P2는 plan·TDD·문서 검증 항목으로 추적한다. 따라서 설계 사양 검토를 통과시키고, 다음 단계에서 구현 계획 6관점 검토를 진행한다.
