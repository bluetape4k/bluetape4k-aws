# Issue #75 Spring Exposed 자동 구성

## 배경

Issue #75에서는 #74의 프레임워크 중립적인 `bluetape4k-aws-exposed` 레지스트리를 위한
Spring Boot 어댑터를 추가한다.

## 결정

데이터베이스 생성은 `:bluetape4k-aws-exposed`에 유지한다.
`:bluetape4k-aws-spring-boot`에서는 `bluetape4k.aws.exposed` 아래의 Spring 로컬
DTO를 바인딩하고 `AwsDatabaseProperties`로 변환한다. 레지스트리가 생긴 뒤에만 기본
`AwsExposedDatabaseHandle`, `DataSource`, Exposed `Database` 별칭을 제공한다.

이제 Spring Boot AWS 에뮬레이터 테스트의 기본값은 공통
`AwsSpringBootTestEmulator` 도우미를 통해 Floci를 사용한다. LocalStack은
`-Dbluetape4k.aws.emulator=localstack`의 명시적 대체값으로만 남긴다.

## 결과

어댑터는 명시적 H2 프로퍼티, 이름별 데이터베이스 바인딩, 보안 값 또는 파라미터 기반
Spring 프로퍼티 소스, 사용자 빈이 있으면 물러나는 동작, 기본 URL이 없을 때 아무 작업도
하지 않는 시작, Exposed JDBC 트랜잭션 사용을 지원한다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:cleanTest :bluetape4k-aws-spring-boot:test --no-build-cache --no-configuration-cache --no-daemon` — 기본 Floci 에뮬레이터로 116개 통과
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.exposed.AwsExposedAutoConfigurationTest' --tests 'io.bluetape4k.aws.spring.kms.KmsCoroutinesEncryptorAwsEmulatorTest' --tests 'io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplateAwsEmulatorTest' --no-build-cache --no-configuration-cache --no-daemon` — 21개 통과
- `git diff --check` — clean

## 향후 보호 장치

간단한 Spring 로컬 DTO가 바인딩 동작을 보존하고 검증 후 공통 모델로 변환할 수 있다면
프레임워크 중립적인 보안 값 객체를 Spring Binder로 직접 바인딩하지 않는다.
`aws-spring-boot`의 AWS 에뮬레이터 테스트에서는 `LocalStackServer.Launcher`를 직접
호출하지 말고 공통 도우미를 사용한다.
