# README class/ERD routing

## 배경

문서, blog post, presentation에서 재사용할 수 있도록 bluetape4k workspace 전체의
README class 및 ERD image를 다시 생성했습니다.

## 결정

class 및 ERD 다이어그램에는 장애물을 고려해 lane을 선택하는 직교 connector routing을
사용합니다. pastel color와 기존 typography는 유지하되, cubic curve와 component 내부를
가로지르는 connector path는 사용하지 않습니다.

## 결과

다시 생성한 class/ERD SVG는 관계를 고려한 component 배치, 직선형 수평/수직 lane, 작은
arrow marker, 첫 구간과 마지막 구간이 수직인 상단/하단 port를 사용합니다. 수평 lane은
component edge 대신 row 중심선 가까이에 배치합니다.

## 검증

- `node --check .omx/scripts/refine-readme-diagrams.mjs`
- Changed class/ERD SVGs: cubic connector count `0`
- Changed class/ERD SVGs: card-interior crossing candidates `0`

## 향후 지침

다이어그램을 다시 생성할 때는 장애물을 고려하는 route scoring을 유지하고, 대규모 image
변경을 수용하기 전에 contact sheet를 검사합니다.
