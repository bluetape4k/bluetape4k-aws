# 이슈 #190 Spring Boot AWS Core 계획

날짜: 2026-05-26
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/190

## 구현 단계

1. 공유 `AwsProperties`와 공통 client-builder 지원을 추가한다.
2. 전역 sync/async 및 typed 서비스별 customizer API를 추가한다.
3. `AwsAutoConfiguration`에 opt-in web-identity 자격 증명 지원을 등록한다.
4. 기존 Spring Boot AWS client 자동 설정에 공유 기본값/customizer를 적용한다.
5. ApplicationContextRunner 커버리지를 추가한다.
   - 공유 기본값 binding과 endpoint 검증
   - STS가 있을 때와 없을 때의 web identity 동작
   - S3 공유 기본값과 sync/service customizer 순서
   - SQS 공유 기본값과 async/service customizer 순서
6. 영어와 한국어 모듈 README의 설정 문서를 갱신한다.
7. #190 기반 결정에 관한 lesson을 추가한다.
8. 범위가 좁은 compile/test, `git diff --check`, 검토 gate로 검증한다.

## 검증 명령

- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test --tests '*AwsAutoConfigurationTest' --tests '*S3AutoConfigurationTest' --tests '*SqsAutoConfigurationTest'`
- `./gradlew --no-daemon --max-workers=1 :bluetape4k-aws-spring-boot:test`
- `git diff --check`

## 완료 조건

- #190 인수 조건이 공유 기본값, customizer hook, 선택적 STS/web-identity 동작, 문서를 다룬다.
- P0/P1 검토 지적은 0개다.
- PR의 대상은 `develop`이고 담당자는 `debop`이며 #190에 연결된다.
