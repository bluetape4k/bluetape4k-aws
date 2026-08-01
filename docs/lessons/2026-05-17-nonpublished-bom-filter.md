# 미발행 모듈 BOM filter

## 배경

릴리스 워크플로는 example, demo, benchmark를 consumer BOM constraint로 관리하지
않아야 합니다.

## 결정

`bluetape4k-aws-bom`은 constraint를 추가하기 전에 정규화한 project path와 artifact
name으로 미발행 모듈을 걸러냅니다.

## 결과

앞으로 추가되는 example, demo, benchmark 모듈은 모듈별 제외 설정 없이 AWS BOM에서
제외됩니다.

## 검증

- `./gradlew generatePomFileForBluetapeAwsPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성한 BOM POM을 검사한 결과 `examples`, `demo`, `benchmark` 항목이 없었습니다.
