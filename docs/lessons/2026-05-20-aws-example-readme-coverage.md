# AWS 예제 README Coverage

## 배경

AWS 예제 모듈 `aws-ktor-dynamodb-examples`, `aws-ktor-sqs-examples`,
`aws-spring-boot-dynamodb-examples`에는 module README가 없었다. 반면 형제 예제는 이미
PNG architecture diagram이 있는 다국어 README pair를 사용했다.

## 결정

README가 빠진 각 예제 모듈에 source로 검증한 `README.md`와 `README.ko.md`를 추가하고,
대응하는 SVG와 rendering PNG architecture diagram을 `docs/images/readme-diagrams/` 아래에
배치한다.

## 결과

새 README는 현재 source에서 확인한 endpoint, configuration, API name만 설명한다.
Diagram label은 영문 전용으로 유지하고 기존 README diagram font/style 계열을 사용한다.

## 검증

- 대상 build file, Ktor route module, Spring Boot controller, repository, application
  entrypoint, test, resource, 형제 README를 검사했다.
- README image link가 존재하고 언급한 API token이 source에 있는지 확인했다.
- 모든 새 SVG diagram을 `rsvg-convert`로 PNG rendering했다.
- `identify`로 PNG dimension을 확인했다.

## 향후 보호 장치

Module README 누락을 수정할 때 먼저 형제 README 구조를 검사한다. 문서를 쓰기 전에 언급할
모든 endpoint, property, public type을 현재 source와 test에서 grep한다.
