# README 다이어그램 infographic

## 배경

README는 architecture, class, sequence, ERD 등의 다이어그램에 Mermaid code block을
사용했습니다. workspace 전체의 시각적 방향을 검토된 pastel infographic PNG로 바꾸고,
재사용할 수 있도록 SVG source asset을 함께 보관하기로 했습니다.

## 결정

README의 Mermaid block을 생성한 PNG image link로 교체하고, 대응하는 SVG source를 PNG
옆에 저장합니다. 다이어그램 문구는 영문으로 유지하고, 큰 label에는 Architects Daughter,
세부 문구에는 Comic Mono를 사용하며, architecture, class, sequence, ERD에 맞는 layout을
각각 적용합니다.

## 결과

`bluetape4k.github.io/docs/readme-diagram-samples`의 공통 2026-05-19 style guide로 README
다이어그램을 rendering했습니다. root README asset은 저장소별 asset 배치 규칙이 있으면
해당 규칙을 따릅니다.

## 검증

저장소 간 변환 과정에서 rsvg-convert로 PNG/SVG asset을 생성하고 README link를
검사했습니다.

## 향후 지침

README 다이어그램은 PNG로 embed하고 편집용 SVG source를 함께 유지합니다. 시각적
일관성이 중요할 때 raw Mermaid나 단순한 Mermaid theme recoloring으로 되돌리지 않습니다.
