# 2026-05-27 - 예제 README 다이어그램 글꼴

## 배경

루트 및 모듈 다이어그램을 현재 스타일로 갱신한 뒤에도 예제 모듈 README
다이어그램은 여전히 Helvetica/Arial 시대의 글꼴 stack을 사용했다.

## 결정

`examples-*` README 다이어그램 asset 전체에서 눈에 띄는 component text에는
`Architects Daughter`, edge label과 세부 text에는 `Comic Mono`를 사용한다. Text
검사에서 글꼴 차이를 확인할 수 있도록 변경한 SVG/DOT source에서 이전 Helvetica,
Arial, Comic Sans fallback stack을 제거한다.

## 결과

갱신한 SVG/DOT source에서 모든 예제 README 아키텍처 PNG를 다시 생성했다. Graphviz
기반 다이어그램은 같은 레이아웃을 유지하면서 예상한 글꼴 모음을 출력한다.

## 검증

- `docs/images/readme-diagrams/examples-*` SVG/DOT asset에서 이전 글꼴 문자열을 검사했다.
- 변경한 모든 예제 SVG 파일에 `xmllint --noout`을 실행했다.
- `rsvg-convert`로 모든 예제 PNG asset을 렌더링했다.
- `.omx/artifacts/examples-diagram-font-contact.png`의 contact sheet를 검토했다.

## 향후 지침

README 다이어그램 글꼴에 대한 feedback을 받으면 가장 최근 PR에서 변경한 다이어그램만
검사하지 말고 접두사별로 모든 asset family를 검사한다.
