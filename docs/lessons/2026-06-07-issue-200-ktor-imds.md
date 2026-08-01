# Issue #200 Ktor IMDS 통합

날짜: 2026-06-07
이슈: #200

## 배경

`aws-ktor`에는 공통 Ktor AWS 설정 도우미가 있었지만 EC2 Instance Metadata Service를
위한 Ktor 통합은 없었다. #196의 Spring Boot IMDS 작업에서 선택적 의존성, 시작 탐색
금지, 제한된 조회, 임시 자격 증명 문서 비노출이라는 안전 기준을 정했다.

## 결정

수동적인 Ktor 플러그인으로 선택적 Ktor IMDS 도우미를 추가한다.

- 사용자에게 `software.amazon.awssdk:imds`를 선택적 컴파일 의존성으로, 이 모듈에는 테스트 의존성으로 유지한다.
- 코루틴 메타데이터 조회를 위한 `ImdsKtorOperations`와 `ImdsKtorTemplate`을 제공한다.
- `ImdsKtorPlugin`을 통해 연산을 Ktor 애플리케이션 속성에 저장한다.
- 일반 AWS 서비스 엔드포인트 재정의를 상속하지 않고 명시적인 IMDS 엔드포인트 설정을 사용한다.
- 안전한 메타데이터 도우미와 IAM 역할 이름만 제공한다.

## 결과

이제 `aws-ktor`에 `ImdsKtorPlugin`을 설치하고 제한된 메타데이터 조회를 위한
`Application.imds()`를 해석할 수 있다. 플러그인을 비활성화할 수 있고 테스트용으로
주입한 연산 또는 클라이언트를 받을 수 있다. 직접 생성한 클라이언트만 소유하며
애플리케이션 시작 중에는 메타데이터를 호출하지 않는다.

## 검증

- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency imds --configuration compileClasspath`
  `software.amazon.awssdk:imds:2.46.0`을 확인했다.
- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.imds.*'`
  IMDS 대상 테스트 13개가 통과했다.
- `./gradlew :bluetape4k-aws-ktor:test`에서 테스트 82개가 통과했다.
- `git diff --check`가 통과했다.

## 향후 보호 장치

Ktor IMDS는 수동적으로 유지한다. 설치 시 탐색을 추가하거나 역할 자격 증명 문서를
노출하지 않는다. IMDS 엔드포인트 선택을 일반 AWS 서비스 엔드포인트 재정의와
결합하지 않는다.
