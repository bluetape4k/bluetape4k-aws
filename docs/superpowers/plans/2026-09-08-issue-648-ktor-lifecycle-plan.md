# #648 구현 계획

설계: [Ktor 종료 통합](../specs/2026-09-08-issue-648-ktor-lifecycle-design.md)

1. [완료] 설계와 계획을 성능, 안정성, 보안, 운영, API, 호출자 관점에서 독립 검토한다.
2. [완료] `AwsKtorApplicationResourceLifecycleTest.kt`에 실제 ApplicationStopping/ApplicationStopped 소유권 회귀를 추가해 기존 코드에서 RED를 확인한다. 존재하지 않는 API의 컴파일 오류만으로 재현을 대체하지 않는다.
3. [완료] STS/SNS/SES/EventBridge/Kinesis/S3Vectors의 Plugin.kt와 Runtime.kt에 registry 등록을 적용한다. 소유권/중복 등록/직접 stop 회귀를 추가한다.
4. [완료] CloudWatch metrics/IMDS/S3 Access Grants에도 동일 계약을 적용한다. 9개 서비스 fixture로 caller-owned 등록 0, plugin-owned 한 번 close, 실패 후 나머지 close를 검증한다.
5. [완료] 기존 CloudWatchLogs/SQS/Exposed 종료 테스트를 실행해 flush/drain 경계를 확인한다. 이 세 production 경로는 수정하지 않는다.
6. [완료] `aws-ktor/README.md`와 `README.ko.md`에 이관 서비스와 유지 서비스, 종료 이벤트/소유권/종료 지연 한계를 맞춘다.
7. [완료] 관련 테스트, 모듈 test/build, Kover, detekt, compatibilityCheck(실제 task 확인 후)를 직렬 실행한다. 독립 코드 및 아키텍처 리뷰의 범위 내 결함을 수정한다.
8. [진행 중] 교훈과 검증 결과를 기록하고 한국어 Lore commit과 issue metadata를 반영한 PR을 생성한다. 최신 head/CI를 확인하고 머지는 보류한다.

## 실행 경계

수정 대상은 위 9개 Plugin/Runtime 쌍, 테스트, 모듈 README 양 언어, 설계/계획/교훈 문서다. public config나 새 dependency는 추가하지 않는다. SDK close의 기존 interrupt/dispatcher 동작은 보존한다. 테스트 및 Docker 작업은 리더가 직렬 예약한다.

## 성능·안정성 검토 반영

- 종료 완료 후 late registration과 중복 등록을 검증해 실제 close가 한 번인지 확인한다.
- latch로 잠시 지연시키는 fake close를 사용해 registry가 순차 대기하고 release 후 다음 close로 진행하는지 검증한다. 무한 blocking 테스트나 임의 속도 임계값은 사용하지 않는다.
- 반복 등록은 동일 registry/adapter를 재사용하는지 확인한다. SDK close의 강제 시간 상한은 제공하지 않는 기존 한계를 PR에 남긴다. 별도 벤치마크나 새 timeout 설정은 이번 변경에 포함하지 않는다.

## 최신 검증

전체 Ktor 테스트 267개·실패0·건너뜀0, build/detekt/Kover 및 compatibilityCheck PASS. 최초 SQS 시간 제한 실패 후 해당 클래스6개와 전체모듈을 재실행해 검증했다. public runtime 서명9개 비교에서 기존 callable 누락0. PR 생성과 exact-head CI 확인 뒤 단계8을 완료하며 머지는 보류한다.
