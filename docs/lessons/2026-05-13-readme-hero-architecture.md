# README 대표 이미지 및 아키텍처 갱신

## 배경

루트 README에도 leader 프로젝트와 같은 수준의 시각적 진입점과 AWS 저장소의 목적을
더 명확히 설명하는 문구가 필요했다.

## 결정

생성한 AWS workbench 이미지를 `docs/assets/aws-workbench.png`에 저장하고 두 README
언어 문서에서 함께 참조한다. 기존 Mermaid 아키텍처 다이어그램은 유지하고 모듈 목록
앞에 목적과 기능을 명시하는 섹션을 추가한다.

## 결과

이제 README 진입점은 설치 세부 사항보다 앞에서 coroutine, Spring Boot, Ktor, AWS
서비스 통합을 강조한다.

## 검증

- 생성한 asset이 `docs/assets` 아래에 PNG로 존재하는지 확인했다.
- 두 README 언어 문서가 공통 이미지 경로를 참조하는지 검증했다.

## 향후 지침

저장소 수준의 시각 asset을 추가할 때는 `docs/assets`에 보관하고 같은 PR에서 기존
README 언어 문서를 모두 갱신한다.
