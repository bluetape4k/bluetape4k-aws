# 이슈 #617 README 버전 계약과 CI 경로 필터 lesson

## 배경

루트 영어·한국어 README는 안정 버전을 `1.0.0`으로 안내했지만,
`aws-exposed`의 두 README dependency 예제는 `0.2.2`에 머물렀다. 정적 계약
테스트를 추가하더라도 CI가 `**.md`를 모두 제외하면 이후 README-only 변경에서는
그 테스트가 실행되지 않는다.

## 결정

- 루트 README 두 언어의 안정 버전을 기준으로 `aws-exposed` dependency literal을
  검증한다.
- BOM의 `<version>` placeholder와 Spring Boot/Ktor의
  `${bluetape4kAwsVersion}` 예제도 함께 검사해 서로 다른 버전 표현의 역할을
  고정한다.
- 계약 테스트를 CI `changes` job에 연결하고, workflow trigger에서 전역
  `**.md` 제외를 제거한다. `docs/**` 등 기존 문서 제외는 유지한다.

## 결과

계약 테스트는 기존 `0.2.2`에서 실패한 뒤 두 README를 `1.0.0`으로 맞추자 버전
검사를 통과했다. 이어서 CI command가 없다는 두 번째 실패를 확인했고, CI 연결과
trigger 수정을 적용한 뒤 전체 계약이 통과했다.

## 검증

- `python3 .github/scripts/aws_exposed_readme_version_contract_test.py`: 통과
- `ruff format --check`와 `ruff check`: 통과
- `actionlint .github/workflows/ci.yml`: 통과
- `git diff --check`: 통과

## 놓친 점과 경계

스크립트가 로컬에서 통과하는 사실만으로는 README-only pull request에서 실행된다는
보장이 없다. 반대로 모든 Markdown 경로를 CI에 포함하면 `docs/**`까지 불필요하게
검증할 수 있으므로, 이번 변경은 모듈 README를 포함하도록 전역 Markdown 제외만
제거하고 기존 `docs/**` 제외를 보존했다.

## 향후 보호 장치

문서 계약 테스트를 추가할 때는 검사 내용, CI step 등록, workflow event 경로 필터를
하나의 계약으로 검증한다. 대상 문서만 변경한 pull request가 해당 workflow를 실제로
시작하지 못하면 계약 테스트 추가를 완료로 판정하지 않는다.
