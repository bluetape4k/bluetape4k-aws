# README 다이어그램 체크리스트 보완

## 배경

모든 rendering PNG에 `bluetape4k-diagram` checklist를 엄격히 적용하기 전에 README
diagram 갱신을 병합했다. 후속 audit에서 고유 README diagram asset을 하나씩 검사한 결과
전체 batch에서 실패를 발견했다.

## 결정

Checklist를 gate로 삼아 README diagram batch를 다시 생성한다.

- 최종 README asset은 표준 title/subtitle/frame/chip/footer shell을 사용한다.
- 최종 service/database endpoint shape는 title text를 가로지르는 cylinder stroke 대신
  표준 card를 사용한다.
- Flow와 lifecycle diagram은 vertical layout을 유지한다.
- Connector endpoint는 node boundary에서 orthogonal하게 연결한다.
- Endpoint가 아닌 connector lane은 box와 눈에 띄는 간격을 유지한다.
- Box text block은 세로 중앙에 정렬한다.
- 모든 README asset 옆에 PNG/SVG/Graphviz 증거를 유지한다.

## Source 변경 증거

2026-05-30에 현재 checkout한 repository 구조와 README 범위를 기준으로 다시 생성한
diagram을 검사했다.

- 루트 저장소 다이어그램: `README.md`, `README.ko.md`, `settings.gradle.kts`, 루트 모듈
  디렉터리, 현재 README 기능/모듈 섹션
- `aws-exposed`: `aws-exposed/README.md`, `aws-exposed/README.ko.md`, 현재 Exposed
  configuration, registry, factory, transaction API 문서
- `aws-java`: `aws-java/README.md`, `aws-java/README.ko.md`, 빌더, 비동기 클라이언트,
  코루틴 어댑터, 수명 주기 소유권에 관한 현재 Java SDK 래퍼 README 섹션
- `aws-kotlin`: `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`, `withXClient`, `clientOf`,
  Flow, short-lived client lifecycle에 관한 현재 Kotlin SDK wrapper README section
- `aws-ktor`: `aws-ktor/README.md`, `aws-ktor/README.ko.md`, 현재 Ktor plugin 및 고급
  S3/SQS README 섹션
- `aws-spring-boot`: `aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`, 현재 Spring
  Boot 자동 구성 README section
- `bom`: `bom/README.md`, `bom/README.ko.md`, BOM 플랫폼 역할
- Examples: Ktor/Spring Boot DynamoDB, Exposed, S3, SQS/SNS flow를 다루는 모든
  `examples/*/README.md` 및 `examples/*/README.ko.md` 파일

## 결과

README에 보이는 모든 diagram asset은 생성 후 전체 checklist로 다시 audit해야 한다.
Contact sheet는 triage에만 유용하며, 최종 증거는 diagram별 rendering PNG 검사와 대상
SVG/Graphviz/source 검사에서 얻어야 한다.

첫 후속 작업도 생성한 output을 지나치게 신뢰했다. 눈에 보이는 실패는 box boundary에
90도로 닿지 않는 connector stub, box와 시각적으로 너무 가까운 connector lane, 두 줄
block이 세로 중앙에 오지 않은 footer text였다. 좁은 layout에서 connector를 어색하게
우회하기보다 canvas와 spacing을 늘린다.

## 검증 증거

2026-05-30의 최종 검증은 README에 보이는 고유 PNG asset 32개를 모두 다뤘다.

- README 이미지 참조: `readmes=31`, `unique=32`, `missing=0`, `svgEmbed=0`,
  `nonPng=0`, `c3Missing=0`
- 엔드포인트 라우팅 검증: `files=64`, `totalEdges=189`, `bad=0`
- 커넥터 간격 검증: `files=64`, `segments=478`, `bad=0`
- SVG well-formedness: README diagram SVG 파일에서 `xmllint --noout` 통과
- Whitespace validation: `git diff --check` 통과
- Routing gate 뒤의 visual spot check에서는 보고된 실패 사례인 root overview footer 세로
  정렬, Java SDK API routing, KMS dashed route 및 label 배치, component map bottom routing,
  Ktor SQS custom route, vertical aws-java/aws-kotlin/aws-exposed flow를 다뤘다.
- Root diagram 1-9: diagram별 visual audit에서 C1-C12 통과
- Module 및 BOM diagram 10-18과 24: C1-C12 통과, 각 PNG 옆의 Graphviz evidence file 확인
- Framework diagram 19-23: connector endpoint route를 수정하고 고급 S3 architecture
  canvas height를 늘린 뒤 C1-C12 통과
- Example diagram 25-32: C1-C12 통과, 각 PNG 옆의 Graphviz evidence file 확인

## 향후 규칙

README diagram batch는 README에 보이는 고유 PNG마다 checklist result가 한 줄씩 있고,
필수 항목에 실패나 누락이 없을 때만 병합하거나 완료로 보고한다.

Source script가 실행됐다는 이유만으로 생성한 diagram이 시각적으로 올바르다고 판단하지
않는다. Connector가 많은 diagram은 deterministic endpoint와 clearance gate를 먼저
실행한 뒤 변경됐거나 이전에 실패한 rendering PNG를 연다. Connector가 보기 흉하게
우회해야만 통과한다면 route를 승인하기 전에 diagram canvas나 node spacing을 늘린다.
