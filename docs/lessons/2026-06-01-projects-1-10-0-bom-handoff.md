# Projects 1.10.0 BOM 인계

## 배경

`bluetape4k-projects` 1.10.0이 출시됐고 Maven Central에서
`bluetape4k-bom:1.10.0`을 확인할 수 있습니다.

## 결정

Exposed BOM과 AWS release line은 유지하면서 local catalog의 projects BOM version을
기존 1.9.2 버전에서 1.10.0으로 갱신합니다.

## 결과

AWS build는 공통 bluetape4k module version에 안정 버전인 projects 1.10.0 BOM을
사용합니다.

## 검증

- `bluetape4k-bom:1.10.0`에 대한 Maven Central HTTP 200.
