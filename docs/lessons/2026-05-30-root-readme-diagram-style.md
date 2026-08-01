# Root README 다이어그램 스타일 갱신

## 배경

Root README asset은 image 내부 title, subtitle, frame, top chip, role footer가 없는 단순한
Graphviz style diagram을 사용했다. 현재 module overview asset에서 검토한 bluetape4k
README diagram style과 더 이상 맞지 않았다.

## 결정

기존 PNG embed path와 대응하는 SVG source를 유지하면서 root README diagram 모음을
pastel card style infographic으로 다시 생성한다. Image 내부 label은 영문으로 유지하고
README prose는 계속 각 언어로 제공한다.

## 결과

이제 root README visual 9개에 title, subtitle, framed canvas, compact semantic chip,
centered card text, bottom role band가 있다.

- Overview 및 module composition
- Component map 및 service coverage
- Architecture diagram 세 개
- KMS component 및 encrypt/decrypt flow

## 검증

- 갱신한 모든 SVG 파일을 `rsvg-convert`로 PNG rendering했다.
- Root README asset에 빠져 있던 Graphviz sketch PNG 증거를 rendering했다.
- `.omx/artifacts/root-readme-redesign-contact.png`의 contact sheet를 검사하고 처음에
  혼잡했던 diagram 두 개를 개별 확인했다.
- Root README SVG asset에서 `xmllint --noout`이 통과했다.
- README image link 검사에서 `missing=0`, `local_svg_embeds=0`으로 통과했다.
- SVG/PNG pair 검사에서 `png_pairs_missing=0`으로 통과했다.
- `git diff --check`가 통과했다.

## 향후 지침

Root README diagram은 승인된 module overview sample과 같은 card style을 따른다. 위쪽의
title/subtitle, pastel card, 명확한 connector stem, 영문 label, 짧은 role footer를
사용한다. Graphviz가 직접 rendering한 asset을 최종 README diagram으로 게시하지 않는다.
