---
manualId: "database-with-exposed"
title: "AWS 설정을 Exposed JDBC로 연결하기"
locale: "ko"
releaseRef: "0.5.0"
---

# AWS 설정을 Exposed JDBC로 연결하기

`bluetape4k-aws-exposed`는 AWS에서 읽은 데이터베이스 설정으로 Hikari 데이터 소스와 Exposed JDBC `Database`를 만든다. 이 경로에는 설정 위치, 자격 증명 해석, 풀 생성과 소유권, 트랜잭션 실행이라는 네 가지 책임이 있다. 이를 분리해 두면 비밀 값 조회나 IAM 토큰 갱신 코드가 저장소 안으로 번지지 않는다.

![AWS 설정에서 Exposed 데이터베이스까지의 연동 흐름](../../assets/database/integration-flow.png)

## 데이터베이스가 만들어지는 순서

1. `AwsDatabaseProperties`에 기본 데이터베이스와 선택적인 이름 있는 데이터베이스를 정의한다.
2. `AwsDatabaseConfigSource`로 Secrets Manager나 Parameter Store의 위치를 가리킨다. 이 객체가 값을 직접 읽지는 않는다.
3. 프레임워크 계층의 `AwsDatabaseSettingsResolver`가 원격 값을 읽고 필요하면 RDS IAM 자격 증명을 구성한다.
4. `AwsExposedDatabaseFactory`가 해석한 JDBC 설정을 검증하고 Hikari 데이터 소스를 만든 다음 `Database.connect(dataSource)`를 호출한다.
5. `AwsExposedDatabaseRegistry`가 기본 핸들과 이름 있는 핸들을 제공하고, 종료할 때 데이터 소스를 역순으로 닫는다.

팩토리는 트랜잭션을 시작하지 않는다. Exposed 트랜잭션 경계는 저장소나 서비스에서 명시해야 한다.

## 자격 증명 소스 선택

| Source | 적합한 경우 | 운영 시 확인할 점 |
| --- | --- | --- |
| 정적/애플리케이션 속성 | 로컬 개발 또는 외부에서 비밀 값을 주입할 때 | 비밀 값을 저장소 설정에 커밋하지 않는다 |
| Secrets Manager | JDBC URL, 사용자 이름, 비밀번호 등을 구조화한 비밀 값으로 관리할 때 | 갱신과 캐시 정책을 정하고 쿼리마다 조회하지 않는다 |
| Parameter Store | 환경별 매개변수를 경로로 정리할 때 | 접두사 매핑, 선택 값과 갱신 정책을 정한다 |
| RDS IAM 인증 | IAM 인증을 켠 RDS 엔드포인트에 접속할 때 | 토큰 유효 시간이 짧으므로 연결 생성 시점과 TLS 조건을 지킨다 |

`AwsDatabaseConfigSource`는 저장소 종류만 표현하고 AWS 클라이언트를 소유하지 않는다. 실제 값은 Spring Boot나 Ktor 어댑터가 자신이 관리하는 클라이언트와 수명 주기 안에서 읽는다.

## 기본 데이터베이스와 이름 있는 데이터베이스

일반 경로에는 기본 핸들 하나를 두고, 실제로 풀이 여러 개 필요할 때만 이름 있는 핸들을 추가한다. 알 수 없는 이름을 조회하면 기본 데이터베이스로 넘어가지 않고 실패한다. 레지스트리를 만들다가 중간 데이터베이스에서 오류가 나면 이미 만든 핸들을 먼저 닫고 원래 예외를 다시 던진다.

레지스트리는 애플리케이션 종료 시 한 번 닫는다. Spring Boot에서는 닫을 수 있는 빈으로 등록하고, Ktor 플러그인은 자신이 만든 레지스트리를 런타임 종료 시 닫는다. 팩토리를 직접 사용했다면 애플리케이션이 레지스트리를 소유한다.

## 예제로 익히기

- [Spring Boot Exposed 예제](../../../../examples/aws-spring-boot-exposed-examples/README.ko.md)는 HTTP 처리, 서비스 트랜잭션, 스키마 초기화와 자동 설정한 데이터베이스 자원을 나눈다.
- [Ktor Exposed 예제](../../../../examples/aws-ktor-exposed-examples/README.ko.md)는 라우트, Exposed 쿼리와 플러그인 수명 주기를 서로 다른 층에 둔다.

두 예제 모두 PostgreSQL Testcontainers를 사용한다. Hikari, PostgreSQL 드라이버와 Exposed 연결은 검증하지만 Secrets Manager, Parameter Store, RDS IAM, 운영 TLS나 풀 크기까지 증명하지는 않는다. 이 경로들은 별도 테스트가 필요하다.

## bluetape4k-exposed와의 관계

이 모듈은 [`bluetape4k-exposed`](https://github.com/bluetape4k/bluetape4k-exposed)의 JDBC 경로 위에 구성된다. 저장소 패턴, 트랜잭션 경계, 데이터베이스 어댑터와 JDBC/R2DBC 선택은 Exposed 매뉴얼을 참고한다. AWS 모듈은 설정과 수명 주기를 연결할 뿐, Exposed 데이터 접근 모델을 대체하지 않는다.

## 근거 소스

- [설정 소스 설명자](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseConfigSource.kt)
- [데이터베이스 설정 해석기](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsDatabaseSettingsResolver.kt)
- [Exposed 데이터베이스 팩토리](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseFactory.kt)
- [데이터베이스 레지스트리](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsExposedDatabaseRegistry.kt)
- [RDS IAM 지원](../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt)
