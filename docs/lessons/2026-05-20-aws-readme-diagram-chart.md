# AWS README 다이어그램 및 차트

## 배경

2026 `bluetape4k_part4-2_aws.pptx` deck에서는 AWS Java SDK v2 bridge, AWS Kotlin SDK
suspend helper, Spring Boot operation, Ktor 통합, service coverage를 설명한다. README에
architecture diagram은 있었지만 module table에는 간결한 component map과 service
coverage chart가 없었다.

## 결정

`docs/images/readme-diagrams/` 아래에 pastel README asset 두 개를 추가한다.

- Module/component composition용 `bluetape4k-aws-components-04.{svg,png}`
- Module별 service coverage용 `bluetape4k-aws-service-coverage-chart-05.{svg,png}`

SVG source file은 PNG output 옆에 두고 `README.md`와 `README.ko.md`의 module table 뒤에
image 두 개를 모두 배치한다.

## 결과

이제 README module section에서 상세 architecture diagram보다 먼저 integration 구조와
service coverage matrix를 볼 수 있다.

## 검증

- SVG XML parse에 성공했다.
- `rsvg-convert`로 1200x720 PNG 파일을 rendering했다.
- README local image link가 모두 존재했다.

## 향후 참고

AWS README 시각 자료를 더 추가할 때는 같은 pastel near-white frame, Architects
Daughter style section label, SVG+PNG asset pair 관례를 유지한다. Component map의 arrow는
실제 module dependency와 대조한다. Kotlin service는 `aws-java`와 `aws-kotlin`, Spring
Boot는 `aws-java`와 `aws-spring-boot`, Ktor는 `aws-kotlin`과 `aws-ktor`를 사용한다.
Physical source directory는 `aws/`로 유지할 수 있지만 공개 Gradle module 및 artifact
label은 `bluetape4k-aws-java`다.
