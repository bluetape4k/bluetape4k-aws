# Issue 194 CloudWatch Spring Boot 자동 구성

## 배경

`aws-spring-boot`에는 AWS SDK 서비스 jar를 소비자에게 선택 사항으로 유지한다는 저장소
규칙을 지키면서 CloudWatch 및 CloudWatch Logs를 일급 기능으로 지원해야 했다.

## 결정

- 공통 `AwsAutoConfiguration` 단계 뒤에 CloudWatch 및 CloudWatch Logs 자동 구성을 추가한다.
- 운영 코드의 `software.amazon.awssdk:cloudwatch`와 `cloudwatchlogs`는 `compileOnly`, 슬라이스 테스트에서는 `testImplementation`으로 유지한다.
- Spring Boot 애플리케이션이 이미 Micrometer를 관측성 기준으로 사용하므로 `micrometer-core`를 일반 `aws-spring-boot` 의존성으로 추가한다.
- `CloudWatchMeterPublishingOperations`를 `micrometer-registry-cloudwatch` 대체물이나 스케줄러가 아니라 현재 `MeterRegistry`의 명시적 스냅샷 도우미로 제공한다.

## 결과

애플리케이션은 코루틴 메트릭/로그 게시 도우미와 기본 네임스페이스 및 로그 그룹/스트림
프로퍼티 지원을 얻는다. `MeterRegistry` 빈이 있으면 Micrometer 스냅샷 게시자도
제공한다. 기존 서비스 자동 구성 패턴과 AWS 자격 증명/사용자 지정 경로는 변경하지 않는다.

## 검증

- `dependencyInsight`로 `compileClasspath`의 `io.micrometer:micrometer-core:1.16.5`를 확인했다.
- CloudWatch 대상 테스트 21개가 통과했다.
- 전체 `:bluetape4k-aws-spring-boot:test`에서 테스트 178개가 통과했다.
- README SVG를 파싱하고 PNG를 렌더링한 뒤 갱신한 아키텍처 다이어그램을 README 크기로 시각 검사했다.
- `git diff --check`가 통과했다.

## 향후 보호 장치

명시적 메트릭 스냅샷 게시와 Micrometer 레지스트리 내보내기를 혼동하지 않는다. 사용자가
예약된 CloudWatch 레지스트리 내보내기를 필요로 하면 `micrometer-registry-cloudwatch`
통합용 별도 이슈를 만들고 운영 절충점을 따로 문서화한다.
