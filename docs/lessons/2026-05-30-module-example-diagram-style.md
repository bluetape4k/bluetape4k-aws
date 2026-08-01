# 모듈 및 예제 다이어그램 스타일 갱신

## 배경

`aws-exposed`, `aws-java`, `aws-kotlin`, BOM, 예제 README diagram은 여전히 Graphviz를
직접 rendering한 모습이었다. Operation flow와 lifecycle sequence diagram 여러 개가
너무 넓고 얕아 GitHub README 화면에서 읽기 어려웠다.

## 결정

Module, BOM, 예제 README diagram을 root README 갱신과 같은 pastel card style visual
language로 다시 생성한다. Operation flow, client lifecycle sequence, exposed
configuration flow, BOM architecture에는 vertical layout을 사용한다.

## 결과

갱신한 asset은 기존 README image path를 유지하면서 image 내부 title, subtitle, top
chip, framed canvas, centered card, 명확한 connector, role footer를 추가한다. 이전에 최종
SVG/PNG만 있던 예제를 포함해 갱신한 asset의 Graphviz DOT/plain/sketch 증거도 생성했다.

## 검증

- 갱신한 모든 SVG asset을 `rsvg-convert`로 PNG rendering했다.
- 대응하는 Graphviz sketch PNG 파일을 rendering했다.
- `.omx/artifacts/module-example-diagram-redesign-contact.png`를 검사했다.
- README diagram SVG asset에서 `xmllint --noout`이 통과했다.
- README image link 검사에서 `missing=0`, `local_svg_embeds=0`으로 통과했다.
- SVG/PNG pair 검사에서 `png_pairs_missing=0`으로 통과했다.
- `git diff --check`가 통과했다.

## 향후 지침

Graphviz style module 또는 example README diagram을 최종 asset으로 직접 게시하지 않는다.
Process, flow, lifecycle diagram에는 vertical layout을 우선하고, content가 실제 component
map일 때만 wide layout을 사용한다.
