# AWS 문서 갱신

## 배경

저장소 문서에는 오래된 README 예제와 snapshot dependency version이 남아 있었다. 여러
image asset에 DOT/PLAIN layout evidence가 없어 README diagram도 유지보수하기 어려웠다.

## 결정

현재 Gradle module layout을 기준으로 사용자용 README content를 갱신하고 오래된 README
diagram을 Graphviz 기반 SVG/PNG asset으로 교체한다. README에는 PNG file을 embed하고,
rendering asset 옆에 DOT, PLAIN, sketch SVG evidence를 저장한다.

## 결과

- Root README module table에 Ktor/Spring Exposed 예제와 현재 Ktor S3/SQS scenario 설명을
  추가했다.
- Dependency snippet은 현재 `baseVersion` line을 사용한다.
- KMS PlantUML block을 rendering README image로 교체했다.
- Ktor S3/SQS 및 새 Exposed/Spring SQS 예제 diagram에 Graphviz source와 rendering
  PNG/SVG asset을 추가했다.

## 검증

- Local README image link 검사: 62개 link, 누락 0개
- SVG parse 검사: README diagram SVG asset에서 `xmllint --noout` 통과
- Diagram audit: 정보가 적은 S3 diagram text의 label을 고친 뒤 P1=0

## 향후 지침

README diagram을 추가하거나 갱신할 때 같은 변경에 DOT source와 PLAIN layout evidence를
포함한다. README architecture content에 inline Mermaid 또는 PlantUML block을 다시
도입하지 않는다.
