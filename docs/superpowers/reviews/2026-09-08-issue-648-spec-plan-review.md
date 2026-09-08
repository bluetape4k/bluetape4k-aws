# #648 설계·계획 독립 검토

검토 대상은 같은 이슈의 `2026-09-08` 설계와 구현 계획이며, 각 관점이 두 문서를 함께 검토했다. 아래 모델/effort는 native role tool에 선언된 실행 계약이다. 리더 모델의 정확한 식별자는 이 기록에서 추정하지 않는다.

| 독립 관점 | native agent | role | 선언된 model / effort | 결과 |
|---|---|---|---|---|
| 성능 | performance_lens | code-reviewer | gpt-5.6-luna / max | P0/P1 0, P2 검증 보강 반영 |
| 안정성 | design648 후속 검토 | architect | gpt-5.6-sol / high | 수정 후 재검토 P0/P1 0 |
| 보안 | review_design | code-reviewer | gpt-5.6-luna / max | 수정 후 재검토 P0/P1 0 |
| 운영 | ops_lens | code-reviewer | gpt-5.6-luna / max | 수정 후 재검토 P0/P1 0 |
| 개발자/API | api_lens | architect | gpt-5.6-sol / high | 수정 후 재검토 P0/P1 0 |
| 사용자/호출자 | caller_lens | code-reviewer | gpt-5.6-luna / max | 수정 후 재검토 P0/P1 0 |
| 통합 | main | leader | 런타임 식별자 미기록 | 모순 정리 및 아래 결정 |

## 통합 결정

- #648: 단순 9개 서비스만 registry로 이관한다. 기존 public ABI와 stop을 유지하고 새 timeout을 도입하지 않는다. 느린 SDK close의 종료 지연은 기존 한계로 문서화한다. late registration 즉시 close, 실패 격리, 소유권과 이벤트 순서, 지연 close 테스트를 계획에 반영했다.
- #649: 독립 키 소유권과 명시 소거, lock 내 snapshot/퇴출 분리 및 lock 밖 close를 채택했다. Spring cache Bean clear, put 실패 원자성, 공개 API migration, lazy TTL 한계, 전체 릴리스 rollback을 보강했다. 취소 테스트는 실제 suspension 경계에 맞춰 정의했다.
- API binary compatibility와 행동 이관을 구분한다. 기존 custom cache/operations의 독립 소유권 이관 문서 없이는 PR 단계로 진행하지 않는다.
- 성능 P2는 동시성·지연 fixture로 보강한다. throughput이나 SDK 강제 종료 상한은 측정·보장한다고 주장하지 않는다.

설계·계획 단계 P0=0, P1=0. 이 기록은 구현·테스트·ABI·CI 통과 증거를 대신하지 않는다. 구현 후 독립 코드/아키텍처 리뷰와 실제 회귀 결과가 별도로 필요하다.
