# Issue #620 Kinesis DryRun 지원 설계

## 문서 상태

- 대상 저장소: `bluetape4k/bluetape4k-aws`
- 대상 브랜치: `feat/issue-620-kinesis-dry-run`
- 기준 브랜치와 SHA: `origin/develop`, `f07015b6e9a3e6aceb4f301081b502cb88eb40c3`
- 관련 이슈: [#620](https://github.com/bluetape4k/bluetape4k-aws/issues/620)
- 승인된 접근: A — catalog 갱신과 일관된 전체 Kinesis 표면을 한 PR에서 제공
- 공개 대상: `bluetape4k-aws-kotlin` 사용자와 유지보수자
- 구현 전 상태: Step 2-R 설계 검토 PASS, 구현 계획 작성 대기

## 문제

현재 저장소가 고정한 중앙 catalog는 AWS SDK for Kotlin `1.8.26`을 제공한다.
이 버전의 Kinesis request model에는 `dryRun`이 없다. AWS SDK for Kotlin
`1.8.44`부터 Kinesis data-plane API에 `DryRun`이 추가됐고, 이번 작업이 사용할
`1.8.46`에는 다음 request model과 JSON serializer가 포함된다.

- `PutRecordRequest`
- `PutRecordsRequest`
- `GetShardIteratorRequest`
- `GetRecordsRequest`

현재 [KinesisClientExtensions.kt](../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensions.kt)는
네 operation의 builder를 노출하지만 `dryRun`을 이름 있는 인자로 표현하지 않는다.
[PutRecord.kt](../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecord.kt)와
[GetShardIterator.kt](../../../aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIterator.kt)의
full-request helper도 같은 제약을 가진다.

## 목표

1. 네 convenience API와 두 기존 full-request helper에 `dryRun`을 이름 있는 인자로 제공한다.
2. `dryRun = false`를 기본값으로 두어 기존 source 호출의 동작을 유지한다.
3. 명시 인자와 builder가 충돌하면 기존 함수들과 동일하게 builder가 마지막에 적용된다.
4. `dryRun = true`가 SDK request model과 wire JSON의 `DryRun`으로 전달됨을 검증한다.
5. 지원하는 backend에서는 유효한 dry-run이 `DryRunOperationException`을 반환하고 쓰기 요청이 레코드를 저장하지 않음을 검증한다.
6. 기존 JVM descriptor와 `$default` 호출 경로를 보존해 이미 컴파일된 소비자를 깨뜨리지 않는다.

## 범위

### 포함

- `settings.gradle.kts`의 immutable central catalog ref를
  `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`로 변경
- `KinesisClient.putRecord`
- `KinesisClient.putRecords`
- `KinesisClient.getShardIterator`
- `KinesisClient.getRecords`
- `putRecordRequestOf`
- `getShardIteratorRequestOf`
- request mapping, builder precedence, serialization, emulator capability 테스트
- `build.gradle.kts`와 `src/abi-fixtures/`의 Kinesis 공개 ABI baseline
- `.github/workflows/ci.yml`의 catalog pin과 compatibility path filter
- `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`와 KDoc
- catalog 변경에 따른 전체 저장소 compile/build·compatibility 검증

### 제외

- 새로운 `putRecordsRequestOf` 또는 `getRecordsRequestOf` helper 추가
- `SubscribeToShardRequest` 지원
- 실제 AWS 계정과 운영 자격 증명을 사용하는 테스트
- emulator 자체의 DryRun 구현 변경
- 중앙 catalog 저장소의 추가 변경
- retry, IAM policy, credential, client lifecycle 정책 변경
- 이 PR의 병합

## 현재 근거

| 근거 | 확인 결과 | 설계 반영 |
|---|---|---|
| Issue #620 | 네 convenience API, request helper, builder 우선순위, fake-first, emulator fallback을 요구한다. | acceptance와 검증 순서를 그대로 고정한다. |
| AWS SDK Kotlin `1.8.26` sources JAR | 대상 네 request model에 `dryRun`이 없다. | 기존 catalog에서는 구현하지 않는다. |
| AWS SDK Kotlin `1.8.46` sources JAR | 대상 네 request model에 `dryRun: Boolean?`가 있고 serializer가 JSON `DryRun`을 기록한다. | reflection이나 임시 adapter 없이 native model을 사용한다. |
| AWS SDK Kotlin `1.8.44` release | Kinesis data-plane dry-run은 권한과 요청 인자를 검증하며, 성공 가능하면 `DryRunOperationException`을 반환한다. | 예외를 성공 신호로 문서화하되 wrapper가 변환하지 않는다. |
| catalog ref `9698c9d…` | `aws.sdk.kotlin:kinesis:1.8.46`으로 resolve되고 `compileTestKotlin`이 성공한다. | 이 ref를 정확히 고정한다. |
| catalog ref 비교 | version key 126개가 달라진다. | Kotlin 모듈 테스트만으로 닫지 않고 전체 build와 compatibility를 실행한다. |
| 기존 module baseline | `756 passing`, `13 pending`, `BUILD SUCCESSFUL` | 변경 후 동일 범위 회귀 기준으로 사용한다. |
| 현재 PR CI | `.github/workflows/ci.yml`의 catalog 환경 변수가 이전 ref를 덮어쓰고, compatibility filter가 `settings.gradle.kts`와 `aws-kotlin/src/main/**`를 포함하지 않는다. | settings와 CI pin을 함께 갱신하고 이 변경에서 compatibility job이 실제 실행되게 한다. |
| 현재 `compatibilityCheck` | Spring SQS/S3 ABI와 consumer fixture만 검사하고 Kinesis top-level 함수는 검사하지 않는다. | Kinesis pre-change `javap` baseline을 aggregate gate에 추가한다. |
| Kotlin compiler probe | `DeprecationLevel.HIDDEN` overload가 새 descriptor와 옛 descriptor 및 두 `$default` bridge를 함께 만들었고, 옛 JAR로 컴파일한 기본 호출 binary가 새 JAR에서 실행됐다. | 실제 프로젝트 JAR에서도 같은 descriptor와 legacy invocation을 다시 검증한다. |

외부 근거:

- [AWS SDK for Kotlin v1.8.44 release](https://github.com/aws/aws-sdk-kotlin/releases/tag/v1.8.44)
- [AWS SDK for Kotlin v1.8.46 release](https://github.com/aws/aws-sdk-kotlin/releases/tag/v1.8.46)
- [Kinesis PutRecord API](https://docs.aws.amazon.com/kinesis/latest/APIReference/API_PutRecord.html)
- [Kinesis PutRecords API](https://docs.aws.amazon.com/kinesis/latest/APIReference/API_PutRecords.html)
- [Kinesis GetShardIterator API](https://docs.aws.amazon.com/kinesis/latest/APIReference/API_GetShardIterator.html)
- [Kinesis GetRecords API](https://docs.aws.amazon.com/kinesis/latest/APIReference/API_GetRecords.html)

## 승인된 설계

### 1. catalog ref를 먼저 고정한다

`settings.gradle.kts`의 기본 ref를 다음 commit으로 변경한다.

```text
9698c9d66bea6fcba373143ee8fa5bfbd9812d4b
```

이 ref는 중앙 catalog PR #241의 merge commit이며 `aws-kotlin = "1.8.46"`을
제공한다. 구현은 Gradle property override에 의존하지 않는다. 임시 override는 구현 전
호환성 조사 근거일 뿐 최종 검증 근거가 아니다.

catalog 변경은 Kinesis 한 라이브러리만 올리는 변경이 아니다. 따라서 rollback 단위는
`settings.gradle.kts` ref와 그 ref에 의존하는 DryRun 구현 전체다. 새 ref에서 전체
build가 실패하면 다른 버전을 임의로 섞지 않고 catalog pin 또는 이 작업 전체를 되돌린다.

### 2. 이름 있는 `dryRun` 인자와 builder-last 규칙

공개 source API는 다음 모양을 사용한다.

```kotlin
suspend inline fun KinesisClient.putRecord(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    dryRun: Boolean = false,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordResponse
```

나머지 세 convenience API와 두 helper도 기존 builder 바로 앞에
`dryRun: Boolean = false`를 둔다. 함수 내부 설정 순서는 다음과 같다.

```kotlin
this.dryRun = dryRun
builder()
```

따라서 다음 계약이 성립한다.

| 호출 | 최종 request 값 |
|---|---:|
| 기존 호출, `dryRun` 생략 | `false` |
| `dryRun = true` | `true` |
| `dryRun = true`와 builder의 `dryRun = false` | `false` |
| 기본값과 builder의 `dryRun = true` | `true` |
| builder의 `dryRun = null` | `null`이며 wire 필드 생략 |

wrapper는 `DryRunOperationException`을 정상 응답으로 바꾸거나 삼키지 않는다. 호출자는
SDK 예외 타입을 그대로 처리한다. 권한 부족, 잘못된 인자, 존재하지 않는 stream 같은
다른 서비스 예외도 그대로 전파한다.

### 3. 기존 source와 binary 호출을 함께 보존한다

builder 앞에 Boolean parameter를 추가하면 Kotlin source의 trailing lambda는 유지되지만
기존 JVM descriptor와 `$default` descriptor는 달라진다. 이를 그대로 배포하면 이미
컴파일된 소비자가 linkage error를 낼 수 있다.

각 변경 대상 함수에는 변경 전 signature를 가진 public hidden compatibility overload를
남긴다.

```kotlin
@Deprecated("Binary compatibility overload", level = DeprecationLevel.HIDDEN)
suspend inline fun KinesisClient.putRecord(
    streamName: String,
    partitionKey: String,
    data: ByteArray,
    crossinline builder: PutRecordRequest.Builder.() -> Unit = {},
): PutRecordResponse = putRecord(
    streamName = streamName,
    partitionKey = partitionKey,
    data = data,
    dryRun = false,
    builder = builder,
)
```

새 source compiler는 hidden overload를 후보에서 제외하므로 `dryRun` 기본 인자와 trailing
lambda가 모호해지지 않는다. 이전에 컴파일된 bytecode는 옛 descriptor를 계속 찾을 수
있다. 다만 기존 source에서 builder lambda를 괄호 안의 마지막 positional argument로
전달한 호출은 hidden overload를 볼 수 없으므로 source-compatible하지 않다. trailing
lambda 또는 `builder = { ... }`는 유지된다. 이 제한은 migration note와 compile fixture에
명시한다.

현재 root `compatibilityCheck`의 `VerifyLegacyAbiTask`는 완전 일치만 허용하므로 새 overload를
추가하는 이번 변경의 검증기로 재사용하지 않는다. 별도 `VerifyAdditiveKinesisAbiTask`를
만들어 다음 세 class의 변경 전 `javap -public -s` signature가 변경 후 JAR에 모두 남아
있는지만 확인하고 새 overload는 허용한다.

- `io.bluetape4k.aws.kotlin.kinesis.KinesisClientExtensionsKt`
- `io.bluetape4k.aws.kotlin.kinesis.model.PutRecordKt`
- `io.bluetape4k.aws.kotlin.kinesis.model.GetShardIteratorKt`

보존해야 할 owner/name/descriptor의 closed set은 다음 12개다.

| Owner | Method | Descriptor |
| --- | --- | --- |
| `KinesisClientExtensionsKt` | `putRecord` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/lang/String;[BLkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `putRecord$default` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/lang/String;[BLkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `putRecords` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/util/List;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `putRecords$default` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/util/List;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `getShardIterator` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/lang/String;Laws/sdk/kotlin/services/kinesis/model/ShardIteratorType;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `getShardIterator$default` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;Ljava/lang/String;Laws/sdk/kotlin/services/kinesis/model/ShardIteratorType;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `getRecords` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;ILkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` |
| `KinesisClientExtensionsKt` | `getRecords$default` | `(Laws/sdk/kotlin/services/kinesis/KinesisClient;Ljava/lang/String;ILkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;` |
| `PutRecordKt` | `putRecordRequestOf` | `(Ljava/lang/String;Ljava/lang/String;[BLkotlin/jvm/functions/Function1;)Laws/sdk/kotlin/services/kinesis/model/PutRecordRequest;` |
| `PutRecordKt` | `putRecordRequestOf$default` | `(Ljava/lang/String;Ljava/lang/String;[BLkotlin/jvm/functions/Function1;ILjava/lang/Object;)Laws/sdk/kotlin/services/kinesis/model/PutRecordRequest;` |
| `GetShardIteratorKt` | `getShardIteratorRequestOf` | `(Ljava/lang/String;Ljava/lang/String;Laws/sdk/kotlin/services/kinesis/model/ShardIteratorType;Lkotlin/jvm/functions/Function1;)Laws/sdk/kotlin/services/kinesis/model/GetShardIteratorRequest;` |
| `GetShardIteratorKt` | `getShardIteratorRequestOf$default` | `(Ljava/lang/String;Ljava/lang/String;Laws/sdk/kotlin/services/kinesis/model/ShardIteratorType;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)Laws/sdk/kotlin/services/kinesis/model/GetShardIteratorRequest;` |

대상 함수가 `inline`이므로 일반 Kotlin consumer를 다시 컴파일한 결과만으로 옛 owner의
descriptor linkage를 증명하지 않는다. `src/abi-fixtures/`에는 위 12개 static method를
그 descriptor 그대로 선언한 Java stub과 12개를 모두 `invokestatic`으로 호출하는 Java
legacy consumer source를 둔다. stub과 consumer의 compile classpath에는 stub output과
의존성만 넣고 production JAR은 명시적으로 제외한다. consumer runtime classpath에는
consumer output, 변경 후 production JAR, 의존성만 넣고 stub output은 명시적으로 제외한
뒤 실제로 실행한다. task는 두 classpath에서 각각 제외 대상이 없음을 사전에 assert한다.

`javap -c -s` assertion은 legacy consumer bytecode가 위 12개 owner/name/descriptor를 정확히
포함하는지 검증한다. 네 client extension의 direct와 `$default` 호출은 null client를 넘겨
linkage가 완료된 뒤 발생하는 예상 `NullPointerException`만 성공 조건으로 인정한다. 두
request helper의 direct와 `$default` 호출은 반환 request model의 필드를 검사한다. 이
Java 실행 fixture와 `VerifyAdditiveKinesisAbiTask`를 root `compatibilityCheck`에 연결하되,
기존 `VerifyLegacyAbiTask`의 baseline과 완전 일치 규칙은 변경하지 않는다.

실제 compiler, 이 pre-change Java consumer 실행, `javap` 결과가 옛 operation descriptor와
`$default` descriptor를 모두 보존하지 못하면 구현을 중단하고 spec을 다시 연다.
`@JvmOverloads`, production reflection, 수동 JVM default bridge, 별도 이름의 dry-run 함수는
fallback으로 추가하지 않는다.

### 4. fake-first request 계약

emulator 결과와 무관하게 다음 검증을 먼저 고정한다.

1. MockK `KinesisClient`가 받은 네 request를 capture하고 operation별 호출이 정확히 한
   번인지 확인한다.
2. 기본값의 최종 값 `false`, explicit `true`, builder의 `true → false`, `true → null`
   override를 확인한다. `null`은 wire field 생략이며 실제 실행 경로가 될 수 있음을
   안전 계약으로 고정한다.
3. 두 full-request helper의 동일 계약을 model unit test로 확인한다.
4. JDK loopback `HttpServer`를 `127.0.0.1`의 임의 port에 열고, 명시적인 endpoint/region/
   static fake credentials를 가진 실제 `KinesisClient`로 네 operation을 호출한다. server는
   `X-Amz-Target`과 body를 메모리에서 capture하고 AWS JSON 형식의
   `DryRunOperationException`을 응답한다. request body가 기본 호출에서
   `"DryRun":false`를 한 번만, explicit true에서 `"DryRun":true`를 한 번만 기록하고
   `null`에서는 필드를 생략하는지 확인한다.
5. `PutRecords`의 각 record에 `DryRun`을 복제하거나 기존 list/`ByteArray`를 복사하지
   않는지 객체 identity와 body 구조로 확인한다.
6. `DryRunOperationException`, 일반 SDK 예외, `CancellationException`의 동일 instance가
   변환·삼킴 없이 전달되는지 fake로 검증한다.
7. internal serializer를 reflection으로 직접 호출하지 않는다.

loopback capture는 SDK internal API를 사용하지 않는다. JDK `HttpServer`가 현재 test JVM에서
사용 불가능하면 wire proof를 성공으로 대체하지 않고 DoD를 `PENDING`으로 남긴 뒤 spec을
다시 연다. sources JAR와 public request model test만으로 실제 runtime payload 전송을
검증했다고 주장하지 않는다.

wire proof와 emulator proof는 loopback 또는 명시적으로 허용한 emulator endpoint로 제한하고
synthetic payload와 명시적인 static fake credentials만 사용한다. ambient/default credential
chain과 실제 AWS endpoint를 사용하지 않으며, test failure에도 `Authorization`, 전체 header,
payload body를 출력하지 않는다. test-only client factory와 create/describe/delete/read helper는
매 호출 전에 endpoint allow-list와 fake credential marker를 검사한다. null/default credential,
AWS endpoint, endpoint userinfo, 임의 host는 network 호출 전에 실패시킨다.

### 5. emulator capability 검증

기본 backend는 Floci다. LocalStack은 Floci가 새 API를 구현하지 않은 경우에만 명시적으로
실행한다. 공유 Docker 자원을 사용하므로 두 backend를 병렬 실행하지 않는다.

각 테스트는 run nonce와 UUID를 포함한 disposable stream 이름을 만들고 `describeStream`으로
부재를 확인한다. 이미 존재하거나 `ResourceInUseException`이 반환되면 그 이름은 소유하지
않은 것으로 보고 삭제하지 않으며 최대 세 번 새 이름을 만든다. 부재를 확인한 이름에만
`createStream` 호출 전에 idempotent cleanup 책임을 등록한다. 따라서 생성 요청이 서버에는
반영됐지만 client가 timeout/예외를 받은 ambiguous 경로는 test가 소유한 create attempt로
보고 같은 이름의 삭제를 시도할 수 있다. cleanup의 `ResourceNotFoundException`은 성공으로
취급한다.

cleanup은 `NonCancellable` 안에서 최대 30초로 제한하며, 본문/취소 예외를 primary로
보존하고 cleanup 실패를 suppressed exception으로 붙인다. cleanup 자체만 실패한 경우에는
그 실패를 test failure로 전달한다. 이 fixture는 name collision, ambiguous create failure,
생성 실패, 본문 실패, 정리 실패, 실제 coroutine 취소를 fake로 주입해 먼저 검증한다.

integration scenario 전체는 cleanup 예산을 별도로 남기는 JUnit `@Timeout(180초)`로
제한한다. 본문 공통 deadline은 120초이며 stream `ACTIVE` 대기, baseline record 관측,
operation은 각각 최대 `withTimeout(30초)`와 최대 500ms의 bounded polling/backoff를
사용한다. 본문이 timeout돼도 `NonCancellable` cleanup에 별도 30초를 보장하고 30초의
JUnit 여유 예산을 남긴다. timeout 경로도 primary timeout/cancellation과 suppressed cleanup
failure를 fake test로 검증한다. test helper는 operation별 30초 deadline과 500ms 이하 poll
interval을 단일 경계로 제공하며 stream `ACTIVE`, baseline, record 관측이 이 helper를 우회하지
않는지 fake clock/delay로 검증한다. SDK retry policy를 확장하거나 무한 polling하지 않는다.
PR CI의 순차 실행 예산은 네 scenario의 최악 경계를 수용하도록 30분으로 제한한다. 동일한
전체 module test를 최대 5회 재시도하는 Full Nightly job은 네 scenario의 최악 12분 x 5,
재시도 backoff와 setup/teardown을 수용하도록 75분으로 제한한다.

검증 순서는 다음과 같다.

1. stream과 shard를 준비하고, 고유한 baseline payload가 실제 조회될 때까지 bounded
   polling해 비교 기준을 확정한다.
2. write operation마다 별도의 고유 dry-run payload marker를 사용해
   `PutRecord(dryRun = true)` 또는 `PutRecords(dryRun = true)`를 호출한다.
3. `DryRunOperationException`이면 baseline iterator를 새로 얻어 dry-run marker가 끝까지
   관측되지 않으며 기존 record 집합이 유지되는지 bounded polling으로 확인한다.
4. generic classifier에서 정상 응답은 계속 `FAILED/normal_response`다. 다만 isolated write
   scenario가 정상 응답 뒤 marker persistence를 직접 관측한 경우에는 backend가 `DryRun`을
   무시하고 실제 write를 수행한 결정적 capability evidence이므로
   `UNSUPPORTED/dry_run_ignored_write`로 기록한다. marker가 없더라도 정상 응답이면
   `UNSUPPORTED/dry_run_ignored_response`이며 `SUPPORTED`로 간주하지 않는다.
5. `GetShardIterator(dryRun = true)`는 operation별 fixture에서 검증한다. `GetRecords`는
   별도의 non-dry-run `getShardIterator`로 유효한 iterator를 만든 뒤
   `GetRecords(dryRun = true)`만 probe한다.
6. read operation이 정상 iterator나 record 응답을 반환하면 isolated scenario에서
   `UNSUPPORTED/dry_run_ignored_response`로 기록한다. 이 승격은 generic classifier에는
   적용하지 않으며, disposable stream과 operation-specific 정상 response가 함께 확인된
   경우에만 허용한다.
7. 오직 아래 closed set만 `UNSUPPORTED`로 분류한다.
   - HTTP `501`과 error code `NotImplemented` 또는 `NotImplementedException`
   - HTTP `400`과 error code `SerializationException`, `ValidationException`, 또는
     `InvalidArgumentException`이며 sanitized message가 `DryRun`과 정확한
     unknown/unsupported member 원인을 함께 포함하는 경우
   - isolated write 정상 응답 뒤 marker persistence를 관측한 `dry_run_ignored_write`
   - isolated operation이 정상 응답을 반환한 `dry_run_ignored_response`
8. 지원되지 않는 operation은 fake/model/wire proof를 필수 대체 증거로 유지한다.

인증 오류, endpoint 오류, Docker 오류, timeout, assertion 실패를 capability 부족으로
분류하지 않는다. `AccessDenied`, `403`, 연결 실패, timeout, 정상 응답이 generic classifier에서
skip되지 않는 unit test를 둔다. 정상 응답의 isolated capability 승격은 별도 emulator scenario와
marker 관측으로만 검증한다. capability decision은 `operation`, `backend`, backend version,
sanitized reason, disposable stream 식별자를 JUnit assumption message와 test artifact에 남긴다.
sanitizer는 임의 exception message를 출력하지 않고 bounded allow-list의 backend, 제한 길이의
version, operation, reason code, 생성한 stream token만 반환한다. credential/access key/session
token, `Authorization`, payload/body, 전체 header, endpoint userinfo sentinel을 exception과
cleanup failure에 주입해 assumption, JSON, JUnit/Gradle 출력 어디에도 남지 않는지 검증한다.
client request logging은 비활성화한다.

PR CI는 기본 Floci scenario와 필수 fake/model/wire proof를 실행한다. Floci가 위 closed set으로
미지원이거나 `DryRun`을 무시함이 확인되면 LocalStack fallback을 로컬에서 순차 진단해 evidence를 남긴다. CI에
두 Docker backend를 동시에 올리지 않는다. LocalStack도 미지원이면 operation별 skip evidence와
필수 대체 증거로 닫되, 다른 실패를 미지원으로 바꾸지 않는다.

2026-09-06 실행 evidence에서 Floci `1.6.0`과 LocalStack `4`는 네 operation의 `DryRun:true`를
모두 정상 응답으로 처리했다. 두 write operation에서는 marker persistence도 관측됐다. 따라서
이 두 backend는 AWS `DryRunOperationException`/no-write semantics의 증명 수단이 아니며,
capability artifact에는 지원으로 기록하지 않는다. public SDK wire proof와 fake/model test가
wrapper 구현의 필수 증거이고 emulator artifact는 이 제한을 명시적으로 드러내는 보조 증거다.

PR CI와 Full Nightly의 각 backend run은
`aws-kotlin/build/reports/kinesis-dry-run/capability-<backend>.json`을 만들고 validator가 네
operation row, schema, closed set, secret sentinel을 확인한 뒤 allow-list field만 포함한
`capability-<backend>.validated.json`을 생성한다. CI는 validator 성공 시에만 validated file을
별도 artifact로 업로드한다. 실패 시 raw report를 삭제하고 고정된 redacted failure metadata만
log에 남기며 capability artifact는 업로드하지 않는다. report 누락이나 validator 실패는 job도
실패한다.
top-level `ci-status`는 compatibility filter가 true인 변경에서 `compatibility` job의 success를
요구하며 unexpected skip을 성공으로 취급하지 않는다.

### 6. 문서 계약

KDoc과 README는 다음 사실을 동일하게 설명한다.

- `dryRun = true`는 데이터를 처리하지 않고 권한과 요청 인자를 검증한다.
- 이는 client-side validation, payload 암호화 또는 network 차단 기능이 아니다. write
  payload와 자격 증명은 구성된 endpoint로 그대로 전송되고, 서버 측 operation만 실행되지 않는다.
- 요청이 실행 가능하면 SDK가 `DryRunOperationException`을 던지는 것이 정상 계약이다.
- wrapper는 예외를 변환하지 않는다.
- builder가 이름 있는 `dryRun` 인자보다 나중에 적용된다. 따라서 builder의 `false` 또는
  `null` override는 dry-run 안전장치를 해제해 실제 실행 경로가 될 수 있다.
- 기본 호출은 `DryRun:false`를 전송한다. 이전 wire shape처럼 필드를 생략해야 하는
  경우에만 `builder { dryRun = null }`을 명시한다.
- 실제 AWS 계정이나 emulator가 operation을 지원하는지는 별도 capability다.

`aws-kotlin/README.md`와 `aws-kotlin/README.ko.md`의 Kinesis 절 구조, 코드 예제, 기술
토큰, 링크를 맞춘다. 기존 `putRecordRequestOf` 예제의 잘못된 positional 인자를 named
argument로 고치고, `dryRun = true`, `DryRunOperationException` 처리, builder override,
네 operation 지원 범위와 backend capability 표를 추가한다. 표 또는 인접 예제에는
`client.putRecord(..., dryRun = true)`, `client.putRecords(...)`,
`client.getShardIterator(...)`, `client.getRecords(...)`와 두 helper의 named argument 호출을
모두 포함한다. 각 public KDoc은 해당 operation의 `dryRun` 의미를 설명하고 write KDoc은
payload 전송 경고를 포함한다. 괄호 안 positional builder 호출은 trailing lambda 또는
`builder = {}`로 바꾸라는 migration note를 포함한다. 중앙 manual은 이번 저장소가 소유하지
않으므로 변경하지 않는다. diagram은 API parameter 추가를 설명하는 데 필요하지 않으므로
추가하지 않는다.

## 검토한 대안

### 대안 B — 이슈가 직접 지목한 helper만 변경

네 convenience API와 `putRecordRequestOf`만 변경하면 diff가 조금 줄어든다. 하지만
`getShardIteratorRequestOf`는 같은 dry-run 가능 request model을 만들면서 이름 있는 인자를
제공하지 않는 비대칭이 남는다. 승인된 A와 API 일관성에 맞지 않아 기각한다.

### 대안 C — catalog PR과 API PR 분리

의존성 영향과 API diff를 분리할 수 있다. 반면 두 PR 사이에 선행 관계가 생기고 두 번의
CI·리뷰·병합 승인이 필요하다. 후보 ref의 module compile이 이미 성공했고 이번 작업에서
전체 catalog 영향 검증을 수행하므로 한 PR로 진행한다.

### reflection 또는 구버전 호환 adapter

1.8.26에서 `dryRun`을 reflection으로 설정하면 컴파일 계약이 없고 serializer 지원도 없다.
실제로 전송되지 않는 옵션을 제공할 위험이 있으므로 기각한다.

### builder만 문서화

1.8.46으로 pin만 올리고 사용자가 builder에서 `dryRun`을 설정하게 둘 수 있다. 그러나
이슈가 요구한 이름 있는 Kotlin API와 기본값·override 계약을 제공하지 못하므로 기각한다.

## 실패 모드와 처리

| 실패 모드 | 탐지 | 처리 |
|---|---|---|
| settings와 CI catalog pin이 달라짐 | pin parity assertion과 PR dependency resolution | 두 pin을 같은 immutable SHA로 갱신하고 불일치 시 CI를 실패시킨다. |
| 새 catalog가 다른 모듈을 깨뜨림 | 전체 compile/build, dependency resolution, `compatibilityCheck` | catalog와 DryRun 변경을 함께 rollback하고 임의 version override를 남기지 않는다. |
| parameter 추가가 기존 JVM descriptor를 제거함 | Kinesis additive `javap` baseline, legacy invocation fixture, `compatibilityCheck` | hidden overload를 보정한다. 보존할 수 없으면 spec을 다시 승인받는다. |
| builder가 이름 있는 값을 덮지 못함 | request capture와 helper unit test | 설정 순서를 `dryRun` 후 `builder()`로 고친다. |
| emulator가 `DryRun`을 무시하고 정상 응답 또는 실제 record를 반환함 | 고유 marker와 bounded record 비교 | capability skip으로 숨기지 않고 계약 위반으로 실패시킨 뒤 idempotent cleanup한다. |
| emulator capability 부족을 인증·네트워크 실패로 오판함 | closed-set classifier와 negative unit test | capability skip을 금지하고 테스트 실패로 남긴다. |
| 성공 가능한 dry-run 예외를 일반 실패로 오해함 | `DryRunOperationException` 타입 assertion과 README/KDoc | 예외를 성공 신호로 설명하되 wrapper는 변환하지 않는다. |
| `dryRun = false`의 명시적 wire 필드가 backend 호환성을 깨뜨림 | wire capture와 기존 integration 회귀 | 기본 호출에서 `false`가 서비스 의미상 동일한지 확인한다. 문제가 있으면 nullable 설계를 재승인받는다. |
| 생성 응답 전에 client failure가 발생해 stream이 남음 | 생성 전 cleanup 등록과 생성 실패 주입 | 동일 이름 delete를 시도하고 not-found를 성공으로 처리한다. |
| 취소 또는 cleanup 실패가 primary failure를 가림 | cancellation/cleanup failure fake test | `NonCancellable` bounded cleanup 후 cleanup failure를 suppressed로 붙이고 primary를 재전파한다. |
| wire proof가 실제 endpoint나 credential을 사용함 | loopback guard와 fake credential assertion | 요청 전에 실패시키고 header/body를 로그에 남기지 않는다. |

## 호환성과 migration

- 기존 Kotlin source 호출은 인자를 생략하거나 trailing lambda 및 named `builder`를 그대로
  사용할 수 있다. 괄호 안 마지막 positional lambda는 trailing/named 형태로 옮긴다.
- 새 호출은 `dryRun = true`를 이름 있는 인자로 사용한다.
- builder-last 정책은 기존 extension/helper 전체 관례를 유지한다.
- public hidden overload로 변경 전 JVM descriptor와 default 호출 경로를 보존한다.
- 기본 호출은 이전과 동일하게 실제 operation 경로지만 wire에는 `DryRun:false`가 추가된다.
  필드 생략은 builder의 `dryRun = null`로 명시한다.
- 서비스 SDK는 기존과 같이 `compileOnly`다. 새 runtime dependency를 추가하지 않는다.
- 소비자는 실제 사용하는 AWS Kotlin Kinesis SDK를 runtime classpath에 제공해야 한다.
- catalog pin 때문에 실제 resolution이 변한 모든 저장소 모듈을 전체 build로 확인한다.

## 검증 전략

검증은 다음 순서를 지킨다.

1. settings/CI catalog pin parity와 resolved Kinesis `1.8.46`
2. helper와 extension RED 테스트
3. 최소 구현 후 targeted GREEN
4. default false/true/null wire serialization과 request-copy 부재 proof
5. cleanup/classifier RED-GREEN과 exception identity/cancellation test
6. Floci capability와 no-write integration
7. 필요한 경우 LocalStack fallback을 순차 실행
8. `bluetape4k-aws-kotlin` 전체 테스트
9. `git rev-parse HEAD`를 고정한 뒤 `./gradlew detekt --no-daemon
   --no-configuration-cache --max-workers=1 --console=plain`을 exact-head 로컬 검증으로
   실행한다. `build/reports/detekt/` report와 명령 종료 상태를 PR DoD에 기록한다.
10. Kinesis ABI baseline이 포함된 `compatibilityCheck`와 legacy binary invocation
11. 전체 build
12. README locale parity, 예제 compile, 링크, terminology audit, `git diff --check`

PR CI에 별도 detekt job을 추가하는 것은 이 작업의 범위가 아니다. 대신 기존 local/static
analysis gate를 exact-head에서 실행해 PR DoD에 고정한다. CI workflow 변경은 catalog pin
재현과 compatibility path selection에만 한정한다.

성능 benchmark는 추가하지 않는다. request에 Boolean 하나를 설정하는 경로이며 반복 loop,
buffer, concurrency 또는 네트워크 round trip을 추가하지 않는다. 기본 경로는 SDK가
`DryRun:false` 한 member를 직렬화하는 고정 비용을 수용한다. 대신 performance review와
request/wire test로 operation 호출이 정확히 한 번이고 record/list/`ByteArray` 복사나
record별 `DryRun` 중복이 없음을 확인한다.

## 수용 기준

- [ ] immutable catalog ref가 `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`다.
- [ ] resolved `aws.sdk.kotlin:kinesis`가 `1.8.46`이다.
- [ ] settings와 CI catalog pin이 같고 이 PR에서 compatibility job이 실제 실행된다.
- [ ] 네 convenience API에 `dryRun: Boolean = false`가 있다.
- [ ] `putRecordRequestOf`와 `getShardIteratorRequestOf`에 같은 인자가 있다.
- [ ] 기본값, explicit true, builder override, nullable builder override가 테스트로 고정된다.
- [ ] 네 operation의 request model이 `dryRun`을 정확히 받는다.
- [ ] 네 operation의 JSON `DryRun:false`, `DryRun:true`, null 생략이 public test surface 또는 승인된 대체 증거로 검증된다.
- [ ] operation당 SDK 호출은 한 번이며 record/list/`ByteArray`를 새로 복사하지 않는다.
- [ ] SDK 예외와 cancellation이 동일 instance로 전달된다.
- [ ] 지원 backend에서는 `DryRunOperationException`과 write no-op가 검증된다.
- [ ] 정상 응답은 skip되지 않고 미지원 backend는 closed-set 사유로만 skip되며 다른 오류는 실패한다.
- [ ] 생성 실패·본문 실패·취소·cleanup 실패 뒤에도 bounded cleanup이 실행되고 primary failure가 보존된다.
- [ ] 기존 trailing/named-builder source 호출과 JVM descriptor 및 `$default` 경로가 유지된다.
- [ ] positional builder migration과 pre-change binary invocation이 fixture로 검증된다.
- [ ] KDoc과 module README 영어·한국어가 동작·예외·payload 전송·builder 우선순위·기본 false wire shape를 설명한다.
- [ ] wire/emulator test가 loopback 또는 명시적 emulator endpoint와 fake credentials만 사용하고 민감 데이터를 기록하지 않는다.
- [ ] targeted/module/detekt/compatibility/full build가 성공한다.
- [ ] spec, plan, 구현, PR 리뷰의 최신 통합 결과가 `P0=0`, `P1=0`이다.
- [ ] PR exact-head CI가 모두 terminal success다.

## 완료 정의

Issue #620은 위 수용 기준을 모두 충족하고, 한국어 lesson과 PR `## DoD Status`가 exact
head 증거를 가리키며, GitHub CI와 리뷰가 수렴했을 때 merge-ready다. 병합은 이 설계의
권한에 포함되지 않으며 사용자의 별도 exact-head 승인을 기다린다.

## Writer DoD

- [x] `SPW-01` — 독자, 목적, 근거, technical token, 불확실한 emulator capability를 고정했다.
- [x] `SPW-02` — 문제, 범위, 결정, 대안, 호환성, 실패 모드, 수용 기준, 완료 정의를 포함했다.
- [x] `SPW-03` — 한국어 기술 문체를 적용하고 API·명령·URL은 원문 token을 유지했다.
- [x] `SPW-04` — Issue #620, local source, SDK sources JAR, AWS 문서, catalog ref와 주장을 대조했다.
- [x] `SPW-05` — heading, 표, 목록, 코드 fence, 링크와 범위 문장을 다시 읽었다.

Step DoD: `PASS` — 여섯 관점의 초기 finding을 통합했고 focused 재검토에서
`P0=0`, `P1=0`으로 수렴했다. 상세 근거는
[`2026-09-05-issue-620-kinesis-dry-run-spec-review.md`](../reviews/2026-09-05-issue-620-kinesis-dry-run-spec-review.md)에 기록한다.
