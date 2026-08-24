# Issue #541 SNS batch 확장 구현 검토

## 범위와 기준점

- 저장소: `bluetape4k-aws`
- 작업 브랜치: `feat/issue-541-sns-batch-extensions`
- 기준 base: `origin/develop` (`fe24e602...`)
- Phase 1 checkpoint: `0a3939fb`
- Phase 2 implementation checkpoint: `8fe6211cb88135e19d446d1682bcaaeb47f95351`
- 후속 환경 재검증: 2026-08-25, Colima/Docker 정상, 직렬 Floci check와 publication/manual 경로 재실행
- 이번 구현은 기존 `SnsOperations` fallback과 `SnsCoroutinesTemplate` 2-인자 생성자를 보존하고, 명시적인 strategy와 opt-in Message converter를 추가한다.
- PR 생성·push·merge·GitHub issue/metadata mutation은 수행하지 않았다.

## 구현 결과

### 실행 경계

- `SnsBatchExecutionPort`가 AWS client, credential, retry, lifecycle을 strategy에서 숨긴다.
- 기본 strategy는 최대 10개 chunk, bounded in-flight worker, 입력 순서 보존, sibling cancellation, terminal ID 기록, in-flight drain을 소유한다.
- `SnsBatchExecutionGuard`는 중복 claim, 요청 밖 ID, 동시성 상한, close 이후 호출을 fail-closed로 차단한다.
- `SnsCoroutinesTemplate`은 기존 2-인자 생성자를 기본 strategy에 연결하고, 3-인자 생성자는 명시적 strategy를 받는다. 취소 시 원래 `CancellationException` instance를 보존하며 불확실한 전체 요청을 자동 재시도하지 않는다.
- transport/protocol/contract 예외에는 payload, ARN, credential, raw SDK cause를 넣지 않는다.

### Message converter

- `SnsBatchMessageConverter`는 `Message<*>`와 유한 `Collection<Message<*>>`만 받아 `SnsPublishBatchEntry`/`SnsPublishBatchRequest`를 만든다.
- 기본 생성자는 `String` payload만 허용하고, 두 번째 생성자는 명시적인 suspend `SnsPayloadSerializer`를 받는다.
- `SnsBatchMessageHeaders` allowlist는 `MESSAGE_ID`, `SUBJECT`, `MESSAGE_ATTRIBUTES`, `MESSAGE_GROUP_ID`, `MESSAGE_DEDUPLICATION_ID`다. explicit ID는 `UUID`, fallback은 `MessageHeaders.ID`의 `UUID`다.
- collection size/options/duplicate ID/FIFO 규칙을 serializer와 request 생성 전에 검증한다. attribute map은 defensive copy하며 unrelated header는 전달하지 않는다.
- converter는 SNS client나 network를 호출하지 않는다. 변환 실패는 cause-free typed error와 entry index/allowlisted field만 보존하고, 취소 instance는 그대로 재전파한다.
- `spring-messaging`은 `compileOnly`이며 converter 사용자는 `org.springframework:spring-messaging`를 runtime에 직접 추가해야 한다.

## 변경 파일

- 실행: `SnsBatchExecutionStrategy.kt`, `SnsBatchExecutionCoordinator.kt`, `SnsBatchExecutionGuard.kt`, `SnsBatchResponseMapper.kt`, `SnsBatchExecutor.kt`, `SnsCoroutinesTemplate.kt`
- 변환: `SnsBatchMessageConverter.kt`
- dependency: `gradle/libs.versions.toml`, `aws-spring-boot/build.gradle.kts`
- 테스트: `SnsBatchExecutionStrategyTest.kt`, `SnsBatchExecutorTest.kt`, `SnsBatchMessageConverterTest.kt`, `SnsSpringMessagingClasspathTest.kt`, `SnsBatchExecutionFlociTest.kt`
- 문서: `README.md`, `README.ko.md`, 양 언어 storage/messaging manual

## 검증 증거

| 영역 | 결과 | 증거 |
|---|---|---|
| Phase 1 + Phase 2 focused matrix | PASS | `:bluetape4k-aws-spring-boot:test` 지정 8개 클래스, 50 passing |
| Converter contract | PASS | 8 tests: String/UUID, explicit serializer, headers/attributes, FIFO, preflight, duplicate/redaction, cancellation/no-network |
| Public ABI/classpath | PASS | `SnsSpringMessagingClasspathTest` 3 passing; legacy consumer는 `org.springframework.messaging.*` 거부 classloader에서도 로드 |
| Constructor/default wiring | PASS | `SnsBatchExecutionStrategyTest`, `SnsAutoConfigurationTest`; 2-인자/3-인자 template와 converter `()`/`(SnsPayloadSerializer)` descriptor 확인 |
| Floci SNS batch smoke | PASS | `SnsBatchExecutionFlociTest`, `-Dbluetape4k.aws.emulator=floci`, 12 entries/2 in-flight, 1 passing |
| Module unit check | PASS (emulator 제외) | `:bluetape4k-aws-spring-boot:check -PskipAwsEmulatorTests=true`, 626 passing |
| Detekt | PASS | `:bluetape4k-aws-spring-boot:detekt --no-configuration-cache`, 새 코드 findings 0 |
| Manual contracts | PASS | `manual_contract_test.rb`: 9 runs/44 assertions; manifest snapshot current |
| Release-pinned manual | PASS | `validate_release_manuals.rb 0.5.0 664e4dfb...`: 250 checked/0 missing |
| Korean terminology | PASS | `audit-korean-terms.mjs`: `findings: []` |
| EN/KO parity | PASS | README headings 50/fences 74/links 47; manual headings 10/fences 10/links 3 |
| Generated Gradle metadata | PASS | `generateMetadataFileForBluetapeAwsPublication`; `module.json`에 `spring-messaging` 없음, runtimeClasspath에도 없음, compileClasspath에만 `org.springframework:spring-messaging -> 7.0.8` 존재 |
| Maven POM generation | PASS (비캐시) / PENDING (config cache) | `--no-configuration-cache` 경로가 성공하고 19,053-byte `pom-default.xml`을 생성했으며 `spring-messaging`가 없다. config-cache 경로는 기존 `withXml`의 null `ConfigurationContainer.delegate` 오류로 계속 실패 |
| Module metadata/manual inventory | PASS | `module.json` 생성 성공, `spring-messaging` runtime 누출 없음; `exportManualModuleInventory`도 성공 |
| Full `check`/`build -x test` | PENDING (기존 환경) | Colima/Docker 정상과 직렬 실행에도 665개 중 32개가 공유 Floci lifecycle 경쟁으로 `Mapped port can only be obtained after the container is started` 실패; root build는 기존 POM config-cache 오류 영향 범위로 별도 재검증 필요 |

## Public KDoc 선언 checklist

- [x] `SnsBatchExecutionPort`, `SnsBatchExecutionStrategy`
- [x] contract/conversion error enums와 cause-free exceptions
- [x] `SnsPayloadSerializer`, `SnsBatchMessageConversionOptions`, `SnsBatchMessageHeaders`
- [x] `SnsBatchMessageConverter` 두 생성자, `convert`, `convertAll`
- [x] typed input, cancellation identity, no-network conversion, redaction/no-cause, caller-owned lifecycle, no automatic retry, `spring-messaging` opt-in을 문서와 KDoc에 명시

## Canary, rollback, telemetry

- Canary는 명시적 3-인자 strategy와 isolated legacy classloader/constructor test다.
- rollback은 Phase 1 checkpoint `0a3939fb`로 되돌려 기본 2-인자 path만 남기는 local revert다.
- guard의 stop/drain과 sibling cancellation은 lifecycle 테스트로 검증했다.
- 이번 변경은 runtime telemetry나 IAM mutation을 추가하지 않는다. payload, ARN, credential, raw SDK message를 metric/log tag로 사용하지 않는다.
- low-cardinality strategy/chunk/protocol/transport counter, publisher latency/cleanup telemetry는 후속 범위다.

## 후속 이슈 후보

다음 converter/telemetry 작업은 이번 구현과 분리한다.

1. SNS 262,144-byte individual/aggregate byte preflight와 serializer media type 계약
2. opt-in Jackson 3 serializer와 `ByteArray` payload 지원
3. raw payload 없이 bounded strategy/chunk/protocol/transport telemetry와 benchmark receipt

각 항목은 byte accounting, no-secret metric tags, cancellation/redaction, Floci 또는 실제 AWS throughput evidence를 acceptance criteria로 삼는다. 이번 단계에서는 GitHub issue를 생성하지 않았다.

## DoD Status

| 항목 | 상태 | 근거 |
|---|---|---|
| 승인된 strategy/guard/coordinator/template 구현 | PASS | Phase 1 checkpoint와 50개 focused test |
| Message converter와 compileOnly 경계 | PASS | converter 8 tests, classpath 3 tests, compile/runtime dependency inspection |
| 문서·manual·release pin 검증 | PASS | manual contracts, release validator, parity, Korean audit |
| Floci 실제 SNS PublishBatch | PASS | `SnsBatchExecutionFlociTest` 12 entries |
| 정적 분석 | PASS | module detekt |
| full repository check/build | PENDING | 직렬 재실행에서도 shared Floci test lifecycle 32건 실패; config-cache POM 경로 오류가 남아 있다 |
| PR/merge/release | PENDING | 별도 권한과 승인 범위 |

최종 판정: `PENDING` — 구현과 대상 기능 검증 및 비캐시 publication 경로는 완료했지만, config-cache POM 오류와 공유 Floci lifecycle 안정성 문제는 별도 정리 후 재검증해야 한다.
