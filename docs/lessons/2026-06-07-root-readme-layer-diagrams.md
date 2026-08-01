# Root README 계층 다이어그램

## 배경

Root README service coverage chart와 architecture diagram이 현재 module surface를 반영하지
못했다. Chart에는 이제 `aws-exposed`, `aws-spring-boot`, `aws-ktor`를 통해 제공하는 RDS
IAM, Secrets Manager, Parameter Store 경로가 빠져 있었다.

## 결정

Root README service chart와 architecture diagram을
`docs/images/readme-diagrams` 아래의 공통 영문 label SVG/PNG asset으로 다시 생성한다.
Architecture diagram은 Mermaid 대신 왼쪽 label gutter가 있는 명시적 layer band와 색상으로
구분한 route를 사용한다.

## 결과

Root README와 한국어 README는 같은 asset path를 유지하고, image에는 최신 service
coverage와 계층화한 module boundary를 표시한다. README service 목록에도 RDS IAM,
Secrets Manager, Parameter Store를 추가했다.

## 검증

- Service coverage chart와 architecture diagram 세 개의 SVG, PNG, DOT, plain, sketch
  asset을 다시 생성했다.
- 다시 생성한 모든 diagram에서 geometry gate 결과가 `badEndpointAngle=0`,
  `badBends=0`, `interiorCrossings=0`이었다.
- Service chart, runtime architecture, combined contact sheet의 spacing, label overlap,
  route readability를 시각 검사했다.

## 향후 지침

Component를 배치하기 전에 layer title과 짧은 subtitle용 왼쪽 label gutter를 확보한다.
Label이나 route가 빽빽해지면 canvas를 키우거나 inline connector label을 제거하고 색상
의미와 footer legend를 사용한다.
