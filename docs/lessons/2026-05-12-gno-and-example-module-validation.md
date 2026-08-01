# GNO 및 예제 모듈 검증

날짜: 2026-05-12

## 배경

Issues #33, #34, #12, #15에서는 KDoc 유지보수와 새로운 Spring Boot 및 Ktor S3 예제
모듈 추가를 함께 진행했다.

## 결정

기존 설계 계획을 찾을 때는 `GNO`를 먼저 사용한다. 현재 작업 트리가 인덱싱되지 않았거나
`gno query`가 로컬 재순위화 모델을 다운로드하려 하면 직접 파일을 검사한다.

## 결과

Spring Boot에서는 `GNO`로 기존 S3 설계 메모를 빠르게 찾았다. 반면 범위가 넓은 Ktor
질의는 대용량 모델 다운로드를 시도했다. 이미 구조를 아는 예제 모듈은 직접 파일을
검사하는 편이 더 빨랐다.

## 검증

다음 명령으로 검증했다.

```bash
./gradlew :aws-spring-boot:compileKotlin :aws-ktor:compileKotlin :aws-spring-boot-s3-examples:test :aws-ktor-s3-examples:test
./gradlew :aws-ktor-s3-examples:test
./gradlew detekt
```

## 향후 지침

구현 중 가볍게 검색할 때는 `gno search` 또는 `gno query --no-rerank`를 우선 사용한다.
대상을 아는 산출물은 `gno get`으로 조회한다. 추가 검색 품질이 꼭 필요하지 않다면 짧은
편집-테스트 반복 안에서 범위가 넓은 재순위화 질의를 피한다.
