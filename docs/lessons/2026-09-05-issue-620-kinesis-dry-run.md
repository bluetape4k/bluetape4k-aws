# DryRun은 API 필드 추가보다 wire·ABI·backend capability 증명이 더 중요하다

## 배경

[Issue #620](https://github.com/bluetape4k/bluetape4k-aws/issues/620)은 AWS SDK for
Kotlin의 Kinesis `PutRecord`, `PutRecords`, `GetShardIterator`, `GetRecords`에 추가된
`DryRun`을 기존 bluetape4k extension과 request helper에 노출한다. 요청을 보내지 않는
client-side 검사처럼 보이기 쉽지만, 실제 계약은 endpoint에 요청과 payload를 보내 서비스가
권한과 요청 유효성을 검사하는 방식이다. 검증 성공도 일반 응답이 아니라
`DryRunOperationException`으로 표현된다.

## SDK와 CI를 같은 commit으로 고정한다

이 기능은 `bluetape4k-dependencies` catalog가 제공하는 Kinesis `1.8.46`에 의존한다.
로컬 `settings.gradle.kts`만 새 catalog로 바꾸고 CI의 catalog checkout을 그대로 두면 개발자
환경에서는 compile되지만 GitHub Actions에서는 이전 API를 보게 된다. 따라서 settings와
`ci.yml`·`nightly-tests.yml`을 동일한 commit
`9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`으로 함께 고정하고, 계약 테스트가 두 경로의
drift를 거절하도록 했다. 최종 `dependencyInsight`에서
`aws.sdk.kotlin:kinesis:1.8.46` resolve를 확인했다.

## nullable Boolean의 세 wire 상태를 보존한다

AWS Kotlin SDK builder의 `dryRun`은 nullable Boolean이다. bluetape4k API의 기본값은 기존
동작을 보존하기 위해 `false`지만, 마지막에 실행되는 `builder`는 이를 다시 `false` 또는
`null`로 덮어쓸 수 있다.

- `true`: `DryRun` 요청 필드를 활성화한다.
- `false`: 기존 일반 호출을 유지한다.
- `null`: wire에서 `DryRun` 필드 자체를 생략한다.

request 객체만 확인하면 serializer나 public client 경계에서 필드가 달라질 가능성을 놓칠 수
있다. fake test는 operation당 한 번 호출, request identity와 exception identity를 고정하고,
JDK loopback wire test는 실제 public `KinesisClient`가 보낸 body에서 `true`·`false`·필드
생략을 확인한다.

## inline default 변경은 source 호환만으로 충분하지 않다

기존 builder-last 함수 앞에 `dryRun` 기본 인자를 넣으면 Kotlin source 호출은 다시 compile될
수 있어도 이미 compile된 consumer가 참조하는 JVM descriptor와 `$default` mask는 달라질 수
있다. 이를 막기 위해 pre-change `javap` 선언과 Java stub을 fixture로 고정하고 다음을 각각
검증했다.

- 기존 12개 direct/`$default` owner·name·descriptor가 새 bytecode에도 존재한다.
- consumer compile classpath에는 pre-change stub만 있고 production JAR는 없다.
- runtime classpath에는 production JAR만 있고 stub은 없다.
- legacy consumer의 12개 `invokestatic`이 실제 production classes에 연결된다.
- Kotlin 외부 consumer가 기존 trailing/named builder와 새 `dryRun = true` 호출을 함께 compile한다.

fixture 파일 집합과 SHA-256도 build logic에 고정해 baseline이 조용히 현재 출력으로 바뀌면
검사가 fail closed하도록 했다. `DeprecationLevel.HIDDEN` overload는 새 source surface를
혼잡하게 만들지 않으면서 기존 binary descriptor를 보존한다.

## emulator 미지원은 성공도 실패도 아닌 capability 결과다

Floci 1.6.0과 LocalStack은 이 작업 시점의 Kinesis DryRun을 지원하지 않았다. 더 위험한 경우는
요청을 거절하지 않고 `DryRun`을 무시하는 backend다. write operation을 일반 테스트처럼 실행하면
capability probe가 실제 record를 만들 수 있다.

따라서 emulator 테스트는 run-scoped stream과 sentinel record를 사용하고, 결과를 다음과 같이
구분한다.

- `supported`: `DryRunOperationException`을 받았다.
- `dry_run_ignored_write`: backend가 정상 응답을 반환해 write 가능성을 감지했다.
- `dry_run_ignored_response`: read operation이 정상 응답을 반환했다.
- `unexpected_failure`: 알려진 capability 차이가 아닌 실패다.

validator는 정해진 4개 operation, backend/version, redacted 진단 필드와 상태 조합만 허용한다.
cleanup은 테스트가 만든 stream만 bounded하게 제거하고, 외부 stream이나 caller-owned client를
소유권 밖에서 닫지 않는다. JUnit sanitizer는 credential property나 민감한 원문 오류가 artifact로
남지 않게 한다.

## 증거 계층과 결과

하나의 테스트 유형으로 전체 계약을 대신하지 않았다.

1. model test: 기본값과 builder override를 확인한다.
2. fake/mock test: 단일 SDK 호출, no-copy, exception/cancellation identity를 확인한다.
3. loopback wire test: public client serializer가 만든 실제 요청 body를 확인한다.
4. emulator capability test: backend의 지원·무시 상태와 no-foreign-delete cleanup을 확인한다.
5. ABI fixture: pre-change binary linkage와 additive declaration을 확인한다.
6. README/CI 계약: 사용자 경고, locale parity와 catalog dual pin을 확인한다.

exact head `aa91b5bb9682897f303c61f1f230df5d47526dd3`에서 targeted test는 62개 중
58개 통과·4개 capability skip, `aws-kotlin` module test는 820개 중 803개 통과·17개
명시적 skip이었다. 전체 build의 JUnit은 3,363개 중 3,325개 통과·38개 skip이며 failure와
error는 0개였다. `detekt`, `compatibilityCheck`, full `build`, capability validator도 모두
성공했다.

## 향후 지침

- SDK 모델에 새 필드가 보이면 request object뿐 아니라 public client의 wire serialization을
  검증한다.
- catalog commit과 CI checkout commit은 하나의 계약으로 취급하고 drift test를 둔다.
- Kotlin inline/default public API를 바꾸기 전 pre-change descriptor와 실제 legacy consumer를
  고정한다.
- emulator가 새 AWS 기능을 지원하지 않으면 skip 숫자만 남기지 말고 backend/version/operation별
  capability artifact를 만든다.
- mutating capability probe는 backend가 옵션을 무시할 수 있다고 가정하고, run-scoped 자원과
  ownership-safe bounded cleanup을 먼저 설계한다.
- DryRun을 payload 비전송, credential 미사용, client-side validation 또는 암호화 대체 수단으로
  설명하지 않는다.
