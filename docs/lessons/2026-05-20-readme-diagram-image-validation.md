# README 다이어그램 이미지 검증

## 배경

README Mermaid diagram을 생성한 PNG embed로 교체하고 생성한 SVG와 README용 PNG 파일은
`docs/images/readme-diagrams` 아래에 유지했다.

## 결정

Architecture, class, sequence diagram에는 공통 pastel infographic renderer를 사용한다.
Diagram text는 영문 전용으로 유지하고 README에는 PNG를 사용하며, 향후 재생성 또는
검사를 위해 SVG도 보존한다.

Root README asset에서 module subtitle이 잘리지 않도록 rendering 전에 큰 diagram title을
사용 가능한 너비에 맞춘다.

## 결과

AWS README diagram 모음을 content 크기에 맞춘 canvas로 다시 생성했다.

- Rendering한 artifact 30개
- PNG 파일 15개
- SVG source file 15개
- 누락된 README image link 없음
- README 파일의 local SVG image embed 없음
- 남은 Mermaid code block 없음

## 검증

- `node /Users/debop/work/bluetape4k/.omx/scripts/refine-readme-diagrams.mjs .`
- README image link 및 Mermaid residue checker
- PNG/SVG 형상 검사
- Visual sample sheet 검토
- `git diff --check`

## 향후 지침

README diagram을 다시 생성할 때 PR을 만들기 전에 contact sheet를 검사한다. Architecture
diagram은 content 기반 dimension을 사용하고, class diagram은 inheritance/use arrow가
보여야 하며, sequence diagram을 고정 height에 맞추지 않는다.
