# README 구조 정렬 — aws-kotlin 및 aws-ktor

**날짜**: 2026-05-16
**이슈**: #103
**브랜치**: docs/readme-alignment

## 근본 원인

`aws-kotlin/README.ko.md`에는 영문 README에 있는 `## 아키텍처` (Architecture) section이
빠져 있었다. 대신 architecture diagram 하위 section 네 개가 문서 아래에서 독립적인
`##` heading으로 노출됐다.

- `## 클라이언트 패턴 클래스 다이어그램`
- `## Java SDK v2 vs Kotlin SDK 비교 다이어그램`
- `## DSL 지원 서비스`

이 때문에 heading 수가 영문 7개 `##`, 한국어 10개 `##`로 달라 구조가 어긋났다.

`aws-ktor/README.ko.md`는 이미 구조가 정렬되어 영문과 한국어 모두 동일한 `##` heading
7개가 있었다. 변경할 필요가 없었다.

## 결정

`aws-kotlin/README.ko.md`를 다음과 같이 고쳤다.

1. 영문의 `## Architecture` 위치와 맞도록 소개 문단 뒤, `## 제공 서비스` 앞에
   `## 아키텍처` section을 추가했다.
2. 영문에는 있지만 한국어에는 없던 `### 클라이언트 생성 패턴 다이어그램` 하위 section을
   추가했다.
3. Diagram section 세 개를 `## 아키텍처` 아래의 `###` 하위 section으로 옮겼다.
4. 기존 내용은 모두 보존하고 heading hierarchy만 재구성했다.

## 결과

이제 두 언어 문서의 `##` heading 구조가 7개로 일치한다.

| English | 한국어 |
|---------|--------|
| `## Architecture` | `## 아키텍처` |
| `## Supported Services` | `## 제공 서비스` |
| `## Java SDK v2 vs Kotlin SDK Comparison` | `## Java SDK v2 vs Kotlin SDK 비교` |
| `## Client Creation Patterns` | `## 클라이언트 생성 패턴` |
| `## Usage Examples` | `## 사용 예시` |
| `## Test Environment` | `## 테스트 환경` |
| `## Adding the Dependency` | `## 설치` |

## 향후 지침

- README의 한 언어 파일에 새 `##` section을 추가하면 다른 언어 파일에도 즉시 같은
  heading을 반영한다.
- CLAUDE.md 규칙인 "Keep `README.md` and `README.ko.md` structurally aligned."에 따라
  PR 전에 heading 수가 일치하는지 확인한다.
- 빠른 정렬 검사에는 `grep "^## " README.md README.ko.md`를 사용한다.
