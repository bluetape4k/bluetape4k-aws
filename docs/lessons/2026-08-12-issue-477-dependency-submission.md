---
title: Dependency Submission과 Dependency Graph capability 경계
date: 2026-08-12
issue: 477
module: ci
---

# Issue #477 Dependency Submission과 Dependency Graph capability 경계

## 배경

`Dependency Submission` workflow의 Gradle dependency graph 생성과 artifact 업로드는
성공했지만, 저장소의 Dependency Graph가 비활성화되어 GitHub 제출 단계만 실패했다.
따라서 Gradle 성공을 전체 workflow 성공으로 해석할 수 없었고, 실패 원인과 생성된
artifact를 함께 보존할 수 있는 capability 경계가 필요했다.

## 결정 또는 발견 사항

- `push`와 `workflow_dispatch` 모두 이벤트의 `github.sha`를 checkout해 실행 대상
  커밋을 고정한다.
- 제출 전에 GitHub API로 `security_and_analysis.dependency_graph.status`를 판정한다.
- 상태가 `enabled`일 때만 기존 Gradle snapshot 생성·업로드·제출 action을 실행한다.
- 상태가 `disabled`이면 workflow를 거짓 성공처럼 보이지 않도록 notice와 step summary에
  비활성 원인과 submission skip 정책을 남긴다.
- 알 수 없는 상태는 조용히 건너뛰지 않고 오류로 종료한다.
- 저장소 설정 API에서 Dependency Graph만 직접 활성화하는 요청은 실제 상태를 바꾸지
  않았으며, Dependabot 경고 등 범위를 넓히는 API는 사용하지 않았다. 현재 정책은
  capability가 비활성화된 저장소에서 명시적으로 건너뛰는 것이다.

## 결과

Dependency Graph가 비활성화된 환경에서는 원격 제출 실패가 반복되지 않고, 실행 결과에
skip 사유가 남는다. 관리자가 Dependency Graph를 활성화하면 동일한 workflow의 enabled
경로가 자동으로 snapshot 제출을 재개한다. 활성화 경로에서 action이 실패할 경우에는
기존처럼 workflow 실패와 dependency snapshot artifact를 모두 확인할 수 있다.

## 검증

- GNO에서 과거 `bluetape4k-aws` issue #477과 `bluetape4k-text` PR #252의 capability
  preflight·exact-head checkout 패턴을 검색해 기준으로 삼았다.
- live GitHub run `31586993560`에서 Gradle build와 artifact upload는 성공했지만
  `The Dependency graph is disabled for this repository`로 submission이 실패한 것을
  확인했다.
- live repository API에서 `permissions.admin=true`, Dependency Graph 상태 미설정,
  SBOM endpoint 404를 확인했다.
- actionlint와 변경 workflow의 YAML/static 검사를 통과시킨다.
- exact-head `workflow_dispatch` 실행에서 capability 상태, summary, action 실행 여부,
  artifact/SBOM read-back을 확인한다.

## 향후 지침

Dependency Submission을 추가하거나 변경할 때는 먼저 저장소 capability를 확인하고,
disabled·enabled 양쪽 경로를 별도로 검증한다. 생성 단계와 제출 단계의 성공을 하나의
성공 신호로 묶지 말고, skip·실패 원인·artifact 보존 여부를 workflow summary와 run log에서
구분해 기록한다. 제출 workflow의 checkout은 branch 이름보다 이벤트의 exact SHA를
우선한다.
