# #648 구현 최종 검토

기준은 `55e976bd87ec4a668abf7e80a4804d6d1addbf1d`이며 이 이슈의 worktree diff만 검토했다. 설계·계획 검토와 구현 검토를 분리했다. 각 독립 lane은 하나의 관점에서 두 이슈를 모듈별로 구분해 검토했으며 빌드나 외부 상태 변경을 수행하지 않았다. 아래 provenance는 native tool에 선언된 실행 계약이다.

## 독립 관점과 통합

| 관점 | 독립 agent / role | 선언된 model / effort | 최종 결과 |
|---|---|---|---|
| 성능 | final_performance / code-reviewer | gpt-5.6-luna / max | P0/P1 0; 키의 네트워크 대기 전 소거 재검토 완료 |
| 안정성 | final_stability / verifier | gpt-5.6-luna / max | P0/P1 0; 잔여 수명 한계 분리 |
| 보안 | final_security / code-reviewer | gpt-5.6-luna / max | P0/P1 0; S3 수정 후 APPROVE |
| 운영 | final_ops / verifier | gpt-5.6-luna / max | 배포·종료 문서 수정 후 P0/P1 0 |
| 개발자/API | final_api / code-reviewer | gpt-5.6-luna / max | P0/P1 0; payload 편의 API 소거는 후속 범위 |
| 사용자/호출자 | final_caller / writer | gpt-5.6-luna / max | P0/P1 0; EN/KO 계약 일치 |
| 통합 | main / leader | 모델·effort 식별자 미기록 | 소스·호출부·검증·ABI·문서 근거 통합 |

추가 `architect_lifetimes` (`architect`, `gpt-5.6-sol / high`)는 소유권·공개 API 경계를 검토했다. 최초 clock 실패 및 직접 copy/close 경합 검증 공백은 테스트로 보강했다. `review648`/`review649`의 구현 검토도 수정에 반영했다.

## 근거와 수용 기준

- 9개 Plugin/Runtime: plugin-owned client만 기존 `ApplicationResourceRegistry`에 등록한다. `ApplicationStopping` hook 9개를 제거하고 단일 공통 lifecycle 설치를 재사용한다.
- `AwsKtorApplicationResourceLifecycleTest`: 실제 STS plugin RED, 9개 runtime의 소유권·중복 등록/종료.
- `AwsKtorLifecycleEdgeCasesTest`: 실제 9개 plugin 설치, Stopping 0회/Stopped 1회 close, 역순 종료, 중간 예외 이후 계속 진행, 늦은 등록 즉시 close, 직접 stop과 중복 close 방지, latch 기반 지연 close.
- `javap -public`: 9개 runtime의 기존 생성자·메서드 서명 누락 0. Kotlin internal 등록 메서드만 JVM mangled 이름으로 추가된다.
- `runBlocking(Dispatchers.IO)` 9곳은 동기 AutoCloseable과 suspend stop의 명시적 연결이다. IMDS의 기존 동기 public stop은 유지한다. CloudWatch Logs/SQS/Exposed production 경로는 수정하지 않았다.
- README EN/KO는 이관 9개 서비스와 유지 서비스, 호출자 소유권, Stopping에서 최종화해야 하는 호출부, Stopped 이후 사용 제약을 같은 표와 설명으로 제공한다.

## 발견 사항과 처리

| 우선순위 | 항목 | 처리 |
|---|---|---|
| P2 | 실제 plugin wiring·종료 edge 테스트 공백 | 8개 회귀와 actual 9-plugin fixture로 보강 |
| P2 | SDK close 지연과 강제 종료 한계 | 새 timeout은 승인 범위 밖; 순차 대기 테스트·README로 공개 |
| P2 | 동시 직접 stop은 이미 진행 중인 close를 join하지 않음 | 기존 atomic stop 계약 유지; 종료 완료까지 동시 stop을 join하는 보장은 제공하지 않음 |
| P2 | lifecycle subscription 설치 자체가 실패할 때 생성된 client 보상 정리 | 정상 설치·late registration은 검증; 공통 helper 설치 예외 주입/보상은 후속 범위로 유보 |
| P2 | registry의 opaque registrationId만으로 서비스 close 실패 구분 어려움 | 기존 공통 helper 관측 한계; stable service label API는 이번 PR에서 추가하지 않음 |

## 검증

회귀 8개와 detekt PASS. 전체 Ktor 테스트 267개·실패0·건너뜀0 PASS. 최초 전체 실행에서 변경하지 않은 SQS 500ms timeout 테스트가 실패했으며, 해당 클래스 6개와 전체 267개를 재실행해 모두 통과했다. 이 결과로 시간 민감 테스트의 간헐적 실패 가능성이 제거됐다고 주장하지 않는다.

최종 build/detekt/Kover/compatibilityCheck PASS. `build/reports/compatibility/compatibility-check.json`은 passed이며 별도 compatibilityTest64개도 통과했다. CI와 머지 준비 상태는 PR의 마지막 DoD 표를 기준으로 한다.

## 통합 판정과 범위

구현 검토 P0=0, P1=0. 남은 P2/P3는 위에서 이유와 한계를 명시했다. 구조적 재사용·호출부·테스트·API/ABI·문서·CI·설계 위험을 서로 대체하지 않는다. 새 의존성·CI workflow·release version·암호문 포맷은 변경하지 않았으며 CHANGELOG/release publication은 이번 PR 준비 범위가 아니다. 자동·수동 머지는 실행하지 않는다.

## 문서 검증

이 검토, 설계, 계획, 교훈의 독자는 유지보수자이며 한국어 기술 문서로 작성한다. SPW-01(독자/근거), SPW-02(문서 계약), SPW-03(한국어 기술 문체), SPW-04(소스·수용 기준 추적), SPW-05(최종 읽기) 결과를 PR 준비 evidence에서 각 문서별 확인한다. 식별자·오류·검증 한계를 보존한다.

| 문서 | SPW-01 | SPW-02 | SPW-03 | SPW-04 | SPW-05 |
|---|---|---|---|---|---|
| 설계 | PASS | PASS | PASS | PASS | PASS |
| 구현 계획 | PASS | PASS | PASS | PASS | PASS |
| 설계·계획 검토 | PASS | PASS | PASS | PASS | PASS |
| 이 최종 검토 | PASS | PASS | PASS | PASS | PASS |
| 교훈 | PASS | PASS | PASS | PASS | PASS |

한국어 용어 감사의 `snapshot-loanword` 1건은 KMS 키의 독립 복사본을 뜻하는 기술 용어이므로 문맥상 예외로 유지했다. 코드 식별자·언어별 README 연결·표·명령·수용 기준과 현재 source를 읽어 대조했다. 이 결과는 미래 CI 성공을 포함하지 않는다.
