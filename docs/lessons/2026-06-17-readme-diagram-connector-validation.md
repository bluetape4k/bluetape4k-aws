# README 다이어그램 connector 검증

## 배경

S3 README 다이어그램을 갱신하는 동안 시각 검토에서
`bluetape4k-aws-s3-components-24.svg`의 분리된 connector endpoint를 놓쳤다.

## 결정

README 다이어그램 검증에는 card 내부 배치, card 겹침, XML 유효성, PNG 렌더링,
수동 PNG 검사뿐 아니라 화살표 endpoint 연결 상태를 확인하는 자동 검사도 포함해야
한다.

## 규칙

모듈 다이어그램이 검토할 준비가 되었다고 보고하기 전에 다음을 수행한다.

1. 변경한 모든 SVG에 `xmllint --noout`을 실행한다.
2. CairoSVG로 변경한 모든 SVG를 PNG로 렌더링한다.
3. 렌더링한 모든 PNG를 검사한다.
4. `node docs/diagram-validation/validate-readme-diagram-svg.mjs <touched-svg...>`를 실행한다.
5. 화살표의 시작점이나 끝점이 card, layer 또는 lane 경계에서 떨어져 있으면
   다이어그램을 거부한다.

## 결과

`Body helpers` 화살표를 `List objects` card 가장자리에 다시 연결해 S3 component
map을 수정했다. 이제 재사용 가능한 validator는 분리된 connector를 명시적인 실패로
처리한다.
