# 다이어그램 직교 커넥터 검토

## 배경

README diagram SVG 여러 개가 올바르게 rendering됐지만 대각선 connector segment,
일치하지 않는 arrow marker color, validator에서 보이지 않는 layer class가 남아 있었다.
XML validity 또는 PNG export만 확인하면 놓치기 쉬운 결함이다.

## 결정

README diagram connector는 기본적으로 horizontal, vertical 또는 rounded bent path를
사용한다. 대각선 connector segment에는 source 또는 style상 명시적 이유가 있어야 하며,
marker color는 connector stroke color와 일치해야 한다.

## 결과

AWS README diagram 모음의 대각선 edge route를 orthogonal rounded path로 교체했다. Marker
unit와 marker color를 맞추고 layered card가 저장소 diagram validator에 보이도록 했다.

## 검증

- 변경한 모든 SVG를 PNG로 rendering했다.
- 변경한 각 PNG와 최종 contact sheet를 시각 검사했다.
- 모든 diagram SVG file에 XML validation을 실행했다.
- 모든 diagram SVG file에 저장소 README diagram validator를 실행했다.
- 남은 diagonal edge segment, static SVG hazard, marker color parity, README image
  reference, whitespace error를 확인했다.

## 향후 지침

AWS diagram을 수정할 때 rendering 성공만으로 충분하다고 보지 않는다. 같은 visual 및
static check를 실행하고, 남은 대각선 connector segment가 있으면 완료 보고서에서 이유를
설명한다.
