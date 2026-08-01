# README 다이어그램 시각 검증 게이트

## 배경

README diagram 갱신은 기본 SVG 검증을 반복해서 통과했지만 독자가 볼 수 있는 품질 문제는
남아 있었다. AWS icon을 service name matching으로 추가해 실제 service ownership을
반영하지 못했고, artwork를 추가한 뒤 icon card 크기를 조정하지 않았으며, orthogonal
connector가 card edge와 평행하게 진입하는 경우도 있었다.

## 결정 또는 발견

README diagram 검토에는 machine validation 뒤의 visual gate가 필요하다. SVG validator는
containment, overlap, font, endpoint attachment를 입증하지만 semantic icon 사용, card
내부 spacing, connector entry angle까지 입증하지는 못한다.

## 결과

실제 service target card에만 AWS service icon을 사용하도록 제한하고 text 공간이 필요한
service card와 flow card 높이를 늘렸다. Orthogonal line이 target card에 수직으로
진입하도록 architecture connector route도 수정해 AWS Java module diagram을 바로잡았다.

## 검증

- 변경한 AWS Java SVG 파일에 `xmllint --noout`을 실행했다.
- 변경한 AWS Java SVG 파일에
  `node docs/diagram-validation/validate-readme-diagram-svg.mjs`를 실행했다.
- 변경한 diagram을 CairoSVG로 PNG rendering했다.
- Contact sheet와 대상 PNG를 시각 검사했다.
- `git diff --check`

## 향후 지침

- Keyword matching으로 AWS icon을 추가하지 않는다. Card가 실제 AWS service 또는
  resource target을 나타낼 때만 추가한다.
- Icon을 추가한 뒤에는 diagram을 승인하기 전에 card 크기와 text 위치를 조정한다.
- Straight 또는 orthogonal connector의 마지막 segment가 card edge에 평행하게 닿으면
  거부한다. Target이 layer/lane boundary가 아니라면 connector가 edge에 수직으로
  진입해야 한다.
- Validator PASS는 필요조건일 뿐 충분조건이 아니다. Icon 의미, text fit, connector
  entry angle, label, margin을 시각적으로 확인해 마무리한다.
