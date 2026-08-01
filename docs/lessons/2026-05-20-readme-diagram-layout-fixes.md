# README 다이어그램 레이아웃 수정

## 배경

후속 시각 QA에서 생성한 README 다이어그램의 레이아웃 결함 두 가지를 발견했다.

- 일부 아키텍처 connector가 매우 짧은 선분으로 렌더링되어 화살촉만 보였다.
- sequence participant header label이 header box 위쪽으로 치우쳐 있었다.

관련 sequence 문제도 수정했다. 이전에는 self-call이 길이 0인 화살표로 렌더링되어
화살촉만 따로 놓인 것처럼 보였다.

## 결정

기존 다이어그램 스타일을 유지하고 생성한 SVG/PNG asset의 geometry만 갱신한다.
아키텍처 connector 선분은 인접한 card 사이의 눈에 보이는 간격을 모두 이어야 한다.
Sequence participant label은 아키텍처 card와 같은 수직 중앙 baseline을 사용해야 한다.
Sequence self-call은 길이 0인 선 대신 작은 loop로 렌더링한다.

## 검증

- README 이미지 링크 검사: missing=0, localSvgImageLinks=0, mermaidResidue=0
- PNG/SVG shape 검사: shapeCandidates=0
- 아키텍처의 짧은 connector 검사: shortArch=0
- Sequence header 정렬 검사: seqTop=0
- Sequence의 길이 0인 화살표 검사: zeroSeq=0
- `git diff --check`
- Exposed 루트 아키텍처 및 대표 sequence 다이어그램의 시각 sample 검토

## 향후 지침

SVG 문법이 올바르더라도 화살촉만 보이는 connector는 렌더링 실패로 취급한다. PR을
만들기 전에 geometry 검사에서 아키텍처 connector 길이, sequence header baseline,
sequence self-call 화살표를 확인해야 한다.
