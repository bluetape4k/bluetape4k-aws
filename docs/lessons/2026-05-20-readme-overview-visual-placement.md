# 2026-05-20 — README 개요 시각 자료 배치

## 배경

README 다이어그램과 차트는 장식용 생성 asset이 아니라 source에 근거한 문서로 취급해야
한다. 이번 작업은 2026 reference 문서와 공통 README diagram style guide를 사용했지만,
module name과 grouping의 기준은 source code와 build layout으로 유지했다.

## 결정

Root README에 SVG+PNG 형식의 영문 전용 README 개요 시각 자료를 추가하고 설치, 사용법,
build 지침보다 앞에 배치한다. 기존 Architecture/Diagram section이 사용 예제 뒤에
추가되어 있으면 위로 옮긴다.

## 결과

이제 `bluetape4k-aws` root README에 개요 diagram과 module composition chart가 있고,
README 시각 자료는 개요 우선 규칙에 따라 배치된다. 생성한 image 내부 label에는 번역한
text를 넣지 않는다.

## 검증

- 생성한 SVG 파일을 `xmllint --noout`으로 parse했다.
- 생성한 PNG 파일을 `rsvg-convert`로 rendering했다.
- Workspace README image link scan에서 누락된 local image가 0건이었다.
- Workspace Architecture/Diagram ordering scan에서 Installation, Usage, Examples, Build
  heading 뒤에 남은 section이 0건이었다.
- 생성한 root overview SVG text에 non-ASCII character가 없었다.

## 향후 참고

Architecture diagram을 README 끝에 추가하지 않는다. Overview 또는 architecture
diagram은 위쪽에 두고 class, sequence, ERD, flow diagram은 설명하는 section 가까이에
배치한다.

Root overview diagram과 composition chart에서는 BOM이 있으면 맨 앞에, Examples 또는
Additional examples가 있으면 맨 뒤에 둔다. Repo별 README가 alphabetic grouping을
요구하지 않으면 중간 group은 source에 근거한 방향 순서를 유지한다.
