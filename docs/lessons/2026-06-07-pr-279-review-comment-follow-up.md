# PR 279 검토 의견 후속 작업

## 배경

PR #279는 review comment가 제출된 뒤 병합됐다. Merge gate에서 CI와 PR body evidence는
확인했지만 병합 직전에 unresolved review thread를 다시 확인하지 않은 process 누락이었다.

Review comment는 새 observability code에 Micrometer magic string이 반복되는 문제도
지적했다.

## 결정

별도 후속 PR에서 검토 의견을 반영한다.

- Micrometer service name, tag key, outcome, operation name을 constant로 모은다.
- Ktor S3 Micrometer wrapper에 operation별 record helper method를 추가한다.
- 게시하는 metric name과 tag value를 안정적으로 유지한다.

## 결과

이제 Ktor와 Spring Boot Micrometer code는 support와 adapter layer에 흩어진 inline string
대신 안정적인 constant를 공유한다. Contract style test는 test 측의 예상 tag name과
value로 Micrometer meter를 조회한다. 따라서 production constant의 이름을 바꾸더라도
공개 metric label이 달라지면 test가 실패한다.

## 검증

- Ktor와 Spring Boot compile 통과
- Micrometer 대상 test 통과
- 영향받은 module 전체 test 통과: Ktor 테스트 85개, Spring Boot 테스트 195개
- `git diff --check` 통과

## 향후 지침

CI가 성공한 뒤 PR을 병합하기 전에 review와 review thread를 다시 읽는다. 사용자가 이전에
병합을 지시했더라도 해결되지 않았거나 더 최근에 작성된 review comment가 있으면 merge
gate를 다시 연다.

Production code나 test에 반복되는 magic string을 남기지 않는다. Metric name, tag key,
tag value, operation name, outcome name, queue/bucket fallback 등 외부에서 관찰되는 literal은
소유 boundary 가까이에서 이름 있는 constant, enum, property reference 또는 다른 type-safe
표현으로 승격한다. Literal이 실제로 local이고 자명하며 공개 contract에 속하지 않을 때만
한 번 사용하는 값을 inline으로 유지한다. 예외가 분명하지 않으면 review evidence에
기록한다.
