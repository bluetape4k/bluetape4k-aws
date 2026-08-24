---
title: "Issue #466 CloudWatch Micrometer registry 자동 구성 설계"
issue: 466
epic: 500
status: approved-design
date: 2026-08-24
---

# Issue #466 CloudWatch Micrometer registry 자동 구성 설계

## 결정 요약

기존 `CloudWatchAutoConfiguration`에 native exporter를 직접 섞지 않고,
`CloudWatchMeterRegistryAutoConfiguration`을 별도 자동 설정 단계로 추가한다.
`micrometer-registry-cloudwatch2`가 런타임 classpath에 있고
`bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true`일 때만
`CloudWatchMeterRegistry`를 만든다. 기존 CloudWatch client, region, endpoint,
customizer와 수동 `CloudWatchMeterPublishingOperations`는 유지한다.

native exporter는 CloudWatch에 주기적으로 데이터를 전송하므로 기본값을
`false`로 둔다. 수동 meter 기준값 전송 helper의 기존
`bluetape4k.aws.cloudwatch.micrometer.enabled=true` 기본값과 분리해, 기존
애플리케이션의 동작과 비용 경계를 바꾸지 않는다.

## 1. 문제와 현재 근거

Issue #194에서 CloudWatch coroutine operations와 수동 Micrometer 기준값 전송
helper를 추가했지만, 기존 `MeterRegistry`를 CloudWatch로 주기적으로 export하는
native registry 자동 설정은 범위 밖이었다. Issue #466은 다음 네 가지를
추가하도록 요구한다.

- 선택적 `micrometer-registry-cloudwatch2` dependency와
  `CloudWatchMeterRegistry` bean
- namespace, step, batch size, filters, common tags, enablement 설정
- 기존 client 설정 재사용과 명시적 user bean/Composite registry back-off
- API 오류, flush, application shutdown을 mock/contract로 확인하는 테스트

현재 구현은 [CloudWatchAutoConfiguration](../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchAutoConfiguration.kt)에서
`CloudWatchAsyncClient`, coroutine operations, 수동 meter 기준값 전송 helper를 만든다.
[`CloudWatchProperties`](../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchProperties.kt)는
서비스의 `enabled`, `region`, `endpointOverride`, `namespace`, `batchSize`와
수동 helper용 `micrometer.enabled`만 제공한다. 자동 설정 목록은
[`AutoConfiguration.imports`](../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)에
직접 등록된 클래스 순서로 관리된다.

Micrometer의 `CloudWatchMeterRegistry`는 `CloudWatchConfig`, `Clock`,
`CloudWatchAsyncClient`를 받아 자체 publisher thread를 시작한다. `StepMeterRegistry.close()`는
마지막 완료 step을 flush한 뒤 publisher를 종료하고, CloudWatch 구현은
`PutMetricData` 비동기 오류를 로그로 기록한다. 따라서 새 자동 설정은 이
lifecycle을 감싸는 별도 publisher를 만들지 않고 공식 registry lifecycle을
그대로 노출한다.

공식 registry는 생성자 안에서 publisher를 시작하므로 설정 적용 순서가
lifecycle 계약의 일부다. 구현은 construction 단계에서 `enabled=false`인 start
gate config로 registry를 만든 뒤 common tags와 filters를 적용하고, 설정이
완료된 뒤에만 gate를 열고 `start(threadFactory)`를 호출한다.

## 2. 목표와 범위 경계

### 목표

1. 선택적 registry dependency가 없으면 AWS SDK와 기존 auto-configuration을
   깨뜨리지 않고 registry 자동 설정을 건너뛴다.
2. 명시적 opt-in 시 기존 `CloudWatchAsyncClient`를 재사용해
   `CloudWatchMeterRegistry`를 하나만 만든다.
3. 설정값이 registry의 `CloudWatchConfig`와 Micrometer registry config에
   결정적으로 반영되도록 한다.
4. Spring Boot 4의 auto-configured `CompositeMeterRegistry`와 명시적 user bean이
   중복 registry를 만들지 않도록 순서와 back-off를 고정한다.
5. real AWS write 없이 mock/contract 테스트로 전송 호출, 오류 관측, flush,
   close를 증명하고 비용·권한 경계를 문서화한다.

### 범위 밖

- Issue #194의 CloudWatch operations 또는 수동 meter 기준값 전송 API 교체
- Prometheus, OTLP 등 다른 Micrometer registry 자동 설정
- Spring Integration metrics 또는 application 전역 `MeterRegistry` 교체
- IAM policy, AWS credential provisioning, 실제 CloudWatch write 테스트
- emulator에 CloudWatch exporter 동작을 새로 구현하는 작업

## 3. 대안과 선택

### A안 — 기존 자동 설정 클래스에 registry bean 추가

변경량은 가장 작지만 client, operations, 수동 helper, native registry의 조건과
lifecycle이 한 클래스에 섞인다. Spring Boot 4 Composite registry보다 먼저
registry를 정의해야 하는 순서를 한 클래스 안에서 명확히 증명하기 어렵고,
기존 helper 조건을 수정할 때 회귀 범위가 커진다. 선택하지 않는다.

### B안 — 별도 `CloudWatchMeterRegistryAutoConfiguration` 추가

기존 service client 단계 뒤, Spring Boot 4
`CompositeMeterRegistryAutoConfiguration` 앞에 native registry 단계를 둔다.
registry dependency/classpath, opt-in, user bean back-off, lifecycle을 독립적으로
검증할 수 있고 기존 수동 helper를 건드리지 않는다. 이 설계를 선택한다.

### C안 — 기존 `MeterRegistry`에 exporter adapter만 추가

중복 registry는 줄일 수 있지만 native `CloudWatchMeterRegistry` bean과
`StepMeterRegistry`의 flush/close 계약을 제공하지 못한다. Issue #466의 핵심
수용 기준을 충족하지 못하므로 제외한다.

## 4. 구성 계약

기존 수동 helper와 native exporter를 혼동하지 않도록 registry 설정을 별도
namespace로 둔다.

```yaml
bluetape4k:
  aws:
    cloudwatch:
      region: ap-northeast-2
      namespace: orders
      micrometer:
        enabled: true                 # 기존 수동 meter 기준값 전송 helper
        registry:
          enabled: true              # native exporter: 기본 false
          namespace: orders-native     # 없으면 cloudwatch.namespace 사용
          step: 1m
          batch-size: 20
          common-tags:
            application: orders
            environment: production
          filters:
            includes:
              - orders.
              - http.server.requests
            excludes:
              - jvm.
```

| Property | 기본값 | 계약 |
| --- | --- | --- |
| `bluetape4k.aws.cloudwatch.micrometer.registry.enabled` | `false` | native registry opt-in. `true`가 아니면 bean을 만들지 않는다. |
| `...registry.namespace` | `null` | native registry namespace. 비어 있으면 기존 `cloudwatch.namespace`를 사용하고, 둘 다 없으면 시작을 실패시킨다. |
| `...registry.step` | `1m` | Micrometer step interval. `Duration` binding을 사용한다. |
| `...registry.batch-size` | `20` | `PutMetricData` 한 번에 보낼 metric datum 수. `1..1000`으로 검증한다. |
| `...registry.read-timeout` | `10s` | 각 CloudWatch API 호출을 기다리는 최대 시간. `1s..5m`으로 검증한다. 전체 close 시간은 batch 수와 이 값의 곱으로 제한된다. |
| `...registry.common-tags` | `{}` | 모든 native registry meter에 추가할 key/value tags. |
| `...registry.filters.includes` | `[]` | 하나라도 prefix가 일치하는 meter만 허용하는 allow-list. 비어 있으면 allow-list를 적용하지 않는다. |
| `...registry.filters.excludes` | `[]` | prefix가 일치하는 meter를 거부하는 deny-list. include보다 거부가 우선한다. |
| `bluetape4k.aws.cloudwatch.micrometer.enabled` | `true` | 기존 수동 meter 기준값 전송 helper 계약. native exporter enablement와 독립적이다. |

`filters`는 property로 표현 가능한 prefix 필터만 제공한다. 애플리케이션이
더 복잡한 정책을 필요로 하면 이 기능 범위를 넓히지 않고 명시적 registry bean과
Micrometer `MeterFilter`를 직접 구성한다. AWS CloudWatch의 비용은 meter 수,
dimension 수, publish step에 따라 달라지므로 기본값은 데이터를 보내지 않는다.
native exporter를 켠 뒤 filter를 비워 두면 해당 registry에 등록된 모든 meter를
전송한다는 사실을 명시적 opt-in의 책임으로 둔다. 운영 문서는 관련 meter만
allow-list하는 예시를 우선 제시하고, metric name/tag에 PII·secret·고카디널리티
값을 넣지 않도록 요구한다.

`step < 1m`은 Micrometer의 공식 `highResolution()` 규칙에 따라
`storageResolution=1`인 고해상도 metric을 만든다. 이 경로는 CloudWatch 비용과
전송량이 늘어나므로 `step`은 `1s` 이상만 허용하고, 문서와 경계 테스트에서
`59s`/`60s` 동작을 구분한다. `batch-size=20`은 upstream 기본값을 따르며,
CloudWatch payload 크기와 dimension 수를 고려한 보수적 기본값이다. 1000개까지
키우는 설정은 허용하지만 1MB payload와 API round trip을 운영자가 측정해야 한다.

## 5. 자동 설정 구조와 조건

새 클래스는 다음 조건을 모두 만족할 때만 registry bean을 등록한다.

1. 공유 `bluetape4k.aws.enabled`와 `cloudwatch.enabled`가 활성화되어 있다.
2. `software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient`와
   `io.micrometer.cloudwatch2.CloudWatchMeterRegistry`가 classpath에 있다.
3. `bluetape4k.aws.cloudwatch.micrometer.registry.enabled=true`이다.
4. `CloudWatchMeterRegistry`, `CompositeMeterRegistry`, 또는 일반
   `MeterRegistry`를 애플리케이션이 이미 제공하지 않는다.

새 자동 설정은 `CloudWatchAutoConfiguration`과 Spring Boot 4의
`MetricsAutoConfiguration` 뒤에 두고, class-name 기반으로
Spring Boot 4의
`org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration`
앞에 둔다. 이 순서에서는 기존 registry가 없는 일반 Boot 애플리케이션이 만든
native registry가 Boot의 composite 후보가 된다. 사용자가 `CompositeMeterRegistry`
또는 다른 `MeterRegistry`를 직접 제공한 경우에는 조건이 먼저 back-off하므로
사용자 소유 registry를 교체하거나 두 개의 CloudWatch registry를 만들지 않는다.

registry bean은 `destroyMethod = "close"`로 등록한다. Actuator가 있으면 Boot의
registry closer가 먼저 `ContextClosedEvent`에서 닫아도 `close()`의 idempotent
계약에 맡긴다. Actuator가 없더라도 Spring bean destroy 단계에서 publisher thread와
공유 registry를 종료한다. CloudWatch client는 기존 client bean의 소유권과
`destroyMethod = "close"`를 유지하며 registry가 별도 client를 만들거나 닫지 않는다.

`close()`는 Micrometer 공식 계약대로 진행 중 publish를 기다리고 마지막 완료
step을 동기 flush한다. 각 `PutMetricData` 호출은 `read-timeout`까지만 기다리므로
최악의 close 대기 시간은 전송 batch 수 × `read-timeout`에 비례한다. 이 이슈에서는
강제 취소나 별도 비동기 shutdown thread를 추가하지 않는다. timeout이 발생하면
해당 batch는 손실되고 오류 로그를 남기며, 다음 step의 publish는 공식
`PushMeterRegistry` 규칙에 따라 중복 실행을 건너뛸 수 있다. 운영자는 step,
batch-size, read-timeout을 조정하고 이 손실/지연 semantics를 수용해야 한다.

설정 완료 후 start gate를 여는 순서와 `MetricsAutoConfiguration` 상대 순서는
short-step 첫 publish가 filters/common tags 없이 실행되지 않고 Boot의 early
registry closer가 native registry를 수집하도록 context lifecycle 테스트로 고정한다.

## 6. 실패 모드와 관측성

| 실패/경계 | 기대 동작 | 검증 |
| --- | --- | --- |
| registry dependency 없음 | 새 자동 설정만 back-off. 기존 CloudWatch operations는 영향 없음. | `FilteredClassLoader` context test |
| `enabled=false` | native registry와 publisher thread를 만들지 않음. 수동 helper 설정은 별도로 평가. | negative context test |
| namespace 없음 | registry bean 생성 전 명확한 property 오류로 실패. 빈 namespace로 AWS 요청을 만들지 않음. | startup failure test |
| step/read timeout이 허용 범위 밖 | `step`은 `1s` 이상, `read-timeout`은 `1s..5m`으로 검증하고 publisher를 시작하지 않음. | boundary property test |
| batch size가 `1..1000` 밖 | property binding/constructor validation에서 실패. | boundary property test |
| 명시적 `MeterRegistry`/`CompositeMeterRegistry` | native bean back-off. 사용자 registry와 client를 교체하지 않음. | context back-off tests |
| CloudWatch API failed future | Micrometer registry의 오류 로그와 client 호출을 유지하고 scheduler thread를 삼키지 않음. | mocked failed future + log/call contract test |
| 정상 flush | 마지막 완료 step이 `PutMetricData`로 전달되고 batch size로 분할됨. | registry publish/close contract test |
| application shutdown | registry `close()`가 마지막 flush 후 publisher와 registry를 닫음; CloudWatch client는 기존 bean lifecycle로 닫힘. | context close + `isClosed`/client verification |
| common tags/filter | native registry에만 적용되고 기존 application registry/수동 helper에는 전파하지 않음. | meter id/filter context test |
| 1분 미만 step | `storageResolution=1` 고해상도 metric을 전송하고 비용 경고를 남김. | `59s`/`60s` config와 request contract test |
| 30개 초과 dimension | native Micrometer의 공식 동작대로 초과 dimension을 절삭하고 warning을 남김. common tags도 dimension 합계에 포함한다. | 30/31 dimension request test |

비동기 오류는 `CloudWatchMeterRegistry`가 제공하는 logger를 사용한다. 새 코드가
예외를 무시하거나 별도 재시도 루프를 만들지 않는다. 따라서 오류가 호출·로그와
함께 관측되며, 재시도/알림 정책은 애플리케이션의 logging/monitoring 계약으로
남긴다.

## 7. 호환성과 문서

- `micrometer-registry-cloudwatch2`는 library의 `compileOnly` dependency로 둔다.
  소비자는 중앙 `bluetape4k-dependencies` BOM과 함께 필요할 때 runtime
  dependency를 직접 추가한다.
- 소비자용 opt-in 예시는 다음 좌표와 scope를 고정한다. Gradle에서는
  `implementation("io.github.bluetape4k:bluetape4k-aws-spring-boot:<version>")`,
  `runtimeOnly("io.micrometer:micrometer-registry-cloudwatch2")`,
  `runtimeOnly("software.amazon.awssdk:cloudwatch")`를 중앙 BOM으로 버전
  관리하고, Maven에서는 같은 두 runtime artifact를 `<scope>runtime</scope>`로
  선언한다. library 자체는 compileOnly 경계를 유지하므로 이 예제가 없으면
  enablement가 true여도 classpath 조건 때문에 registry가 생성되지 않는다.
- 기존 `CloudWatchProperties.namespace`, `batchSize`, `micrometer.enabled`의
  의미와 기본값을 바꾸지 않는다.
- 기존 `CloudWatchMeterPublishingOperations`는 native registry를 대체하지도,
  native registry를 자동으로 meter 기준값 전송하지도 않는다.
- README와 `docs/manual/en|ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md`
  에 runtime dependency, opt-in property, namespace/step/filter/common tags,
  비용·권한·emulator 테스트 경계를 같은 예제로 추가한다. English/Korean 문서의
  property key와 acceptance 의미는 일치시킨다.
- 실제 AWS 전송 없이 mock/contract를 기본 검증으로 사용한다. Floci/LocalStack은
  CloudWatch exporter API의 완전한 대체가 아니므로 이 이슈에서 새 emulator
  의존성을 만들지 않고, 필요한 범위와 미검증 범위를 문서에 남긴다.
- endpoint override는 기존 trusted deployment configuration을 재사용한다.
  요청 payload나 metric tag에서 endpoint를 바꾸지 않으며, production은 HTTPS
  endpoint를 사용하고 loopback/non-HTTPS endpoint는 명시적인 emulator/test
  설정으로만 문서화한다. IAM은 `cloudwatch:PutMetricData` 최소 권한과 필요한
  namespace 조건을 권장하고 credential 값은 로그에 남기지 않는다.

기존 수동 helper와 native scheduled exporter의 migration 경계는 다음처럼
고정한다.

| 기존 애플리케이션 상태 | 새 동작 | 소비자 조치 |
| --- | --- | --- |
| `micrometer.enabled=true`, `registry.enabled` 미설정 | 수동 helper만 유지하고 주기 전송은 시작하지 않음 | 변경 없음 |
| `registry.enabled=true`이고 두 runtime dependency가 있음 | native registry를 추가하고 `MeterRegistry` 인터페이스로 주입 가능 | namespace와 allow-list를 명시하고 `PutMetricData` IAM을 부여 |
| 명시적 `MeterRegistry`/`CompositeMeterRegistry` bean 제공 | native 자동 설정 back-off | 사용자 registry 구성을 계속 소유 |
| `bluetape4k.aws.enabled=false` 또는 CloudWatch disabled | client와 native registry 모두 비활성 | exporter 설정이 있어도 AWS 호출 없음 |

운영 문서는 `MeterRegistry` 인터페이스 주입을 권장하고, 단일 native registry,
Boot composite, 사용자 registry 제공 시의 차이와 Actuator condition report 확인
경로를 설명한다. 기본 예제는 `includes` allow-list를 사용하며, 빈 allow-list는
등록된 모든 meter를 전송한다는 경고와 PII·secret·고카디널리티 금지 규칙을 함께
표시한다. `excludes`가 include보다 우선하고 common tag도 CloudWatch dimension과
비용에 포함된다.

`close()`의 worst-case 대기 시간은 `ceil(등록 meter 수 / batch-size) ×
read-timeout`으로 계산한다. 운영자는 이 값을 Kubernetes
`terminationGracePeriodSeconds` 또는 `spring.lifecycle.timeout-per-shutdown-phase`
보다 작게 유지하고, 맞출 수 없으면 batch-size/step/read-timeout을 조정해
강제 종료에 따른 batch 손실을 감수해야 한다. API failed future, timeout,
throttling은 Micrometer 공식 logger의 WARN/ERROR와 namespace·batch·예외 유형으로
관측하고 credential/payload secret은 기록하지 않는다. 이 이슈는 별도 health
indicator나 retry loop를 추가하지 않으며, 실패 후 scheduler 지속 동작과 로그 수집
알림을 운영 계약으로 둔다.

`step < 1m`인 경우 설정 시점에 high-resolution 비용 WARN을 한 번 기록하고,
`59s`/`60s` 경계와 동일한 logger 계약을 테스트한다. batch-size 20은 일반적인
round trip과 payload의 균형 기본값이며, 1000으로 올릴 때는 CloudWatch quota,
1MB payload, throttling 로그를 측정하고 SDK client의 기본 retry 책임을 넘지 않는다.

## 8. 수용 기준과 DoD 매핑

| Issue #466 수용 기준 | 구현/검증 증거 |
| --- | --- |
| dependency와 enablement 조건 | catalog alias, `compileOnly` dependency, classpath/property negative/positive tests |
| namespace/step/filter/common tags 반영 | immutable properties, `CloudWatchConfig`, registry config test |
| custom registry/disabled back-off | `MeterRegistry`·`CompositeMeterRegistry`·`CloudWatchMeterRegistry` context tests |
| API 오류/flush/shutdown 관측 | mocked async client, publish/close contract, context lifecycle test |
| 기존 client/region/endpoint/customizer 재사용 | 기존 `CloudWatchAsyncClient` bean 주입 및 custom client back-off test |
| manual API 비대체 | 기존 CloudWatch tests 유지, native property와 helper property 독립성 test |
| 비용/권한 안전 | 기본 disabled, README/English/Korean manual의 opt-in·filter·real-write 경계 |

완료 시점에는 다음 명령과 결과를 기록한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*CloudWatch*'
./gradlew :bluetape4k-aws-spring-boot:test -PskipAwsEmulatorTests=true --no-daemon
./gradlew :bluetape4k-aws-spring-boot:compileKotlin
./gradlew detekt
git diff --check
```

변경된 manual/README 경로의 locale parity와 configuration metadata도 별도로
확인한다. 실제 AWS write, IAM 권한, CloudWatch 비용 발생은 이 DoD의 검증 항목이
아니며 미검증으로 명시한다.

## 9. 추적성 원장과 설계 DoD

### SPW-01 — audience, purpose, evidence

- 대상: `aws-spring-boot` 유지보수자와 Spring Boot 소비자
- 목적: #466의 native CloudWatch registry 계약과 기존 helper 경계를 고정
- 로컬 근거: `CloudWatchAutoConfiguration.kt`, `CloudWatchProperties.kt`,
  `CloudWatchAutoConfigurationTest.kt`, `AutoConfiguration.imports`, README와
  English/Korean auto-configuration manual
- 외부 근거: [Micrometer CloudWatch registry 문서](https://docs.micrometer.io/micrometer/reference/implementations/cloudwatch.html),
  [Micrometer CloudWatchConfig](https://github.com/micrometer-metrics/micrometer/blob/main/implementations/micrometer-registry-cloudwatch2/src/main/java/io/micrometer/cloudwatch2/CloudWatchConfig.java),
  [Micrometer CloudWatchMeterRegistry](https://github.com/micrometer-metrics/micrometer/blob/main/implementations/micrometer-registry-cloudwatch2/src/main/java/io/micrometer/cloudwatch2/CloudWatchMeterRegistry.java),
  [Micrometer StepMeterRegistry](https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/step/StepMeterRegistry.java),
  [Micrometer PushMeterRegistry](https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/push/PushMeterRegistry.java),
  [Spring Cloud AWS CloudWatch 문서](https://github.com/awspring/spring-cloud-aws/blob/main/docs/src/main/asciidoc/cloudwatch.adoc)
- 미확정 범위: 실제 AWS write/IAM과 emulator의 CloudWatch exporter 완전성은 검증하지 않는다.

### SPW-02 — artifact contract

문제, 목표/범위, 대안, 선택, property contract, 자동 설정 조건/순서,
lifecycle, 실패 모드, 호환성, 문서, 테스트, acceptance/DoD를 포함했다.

### SPW-03 — Korean technical register

`references/korean-naturalness-checklist.md`를 적용했다. API 이름, property key,
명령, URL, 숫자와 공식 클래스명은 그대로 보존했고, `native registry`,
`back-off`, `flush`, `lifecycle`, `prefix`는 이 repository의 기존
기술 용어를 유지했다. 광고성 표현과 근거 없는 성공 주장은 사용하지 않았다.

### SPW-04 — technical meaning and traceability

현재 코드의 수동 helper 기본값과 native registry의 기본 disabled를 분리했고,
Micrometer `CloudWatchConfig`의 필수 namespace·최대 batch size·step registry
lifecycle, sub-minute high-resolution, publish-overrun skip, dimension 절삭을
property와 테스트 계약에 연결했다. Spring Boot 4 composite/metrics ordering과
명시적 user bean back-off, trusted endpoint·least-privilege 경계를 별도 조건으로
기록했다.

### SPW-05 — read-back

설계서 전체를 다시 읽어 heading, table, YAML code fence, source URL, acceptance
mapping과 미검증 경계를 확인했다. 리뷰에서 추가한 start gate, read-timeout,
high-resolution, overrun, dimension, endpoint·IAM 경계를 반영한 최신 문서를
기준으로 SPW-01..05를 다시 수행한다.

## 10. 통합 리뷰 disposition

6개 리뷰 관점과 통합 검토를 최신 설계서 기준으로 판정했다. 즉시 중단 결함
P0은 없고, 구현·문서·테스트 단계로 넘길 P1은 모두 다음과 같이 해소했다.

| 관점 | 원래 위험 | 최종 disposition | 증거/후속 게이트 |
| --- | --- | --- | --- |
| 성능·안정성 | `close()` 지연, publish overrun skip, batch 20, sub-minute high-resolution | P1 해소. read-timeout, close 상한 계산, overrun semantics, 20 기본값 근거와 59/60초 경계를 계약에 반영 | Step 4-T/4-P, 종료 grace-period 문서 |
| 보안 | trusted endpoint override와 전체 meter/tag export 위험 | P1을 P2로 재분류. endpoint는 기존 배포 설정이며 요청 경로가 아니므로 새 trust gate를 추가하지 않고 production HTTPS·emulator 경계·credential 비기록을 고정. native exporter는 기본 disabled이며 빈 allow-list는 명시적 caller 책임과 경고 문서로 제한 | Step 4-T sentinel/filter test, Step 5 문서 |
| 운영·Ops | 종료 grace-period 초과, 로그 의존 관측 | P1 해소. batch 수 × read-timeout 계산식과 Kubernetes/Spring shutdown 조정 기준, logger 필드·retry/health 범위를 고정 | Step 4-T/4-P, Step 5 운영 문서 |
| 개발·API | Boot 4 ordering, config validation, explicit registry back-off | P1 해소. `MetricsAutoConfiguration` 뒤, `CompositeMeterRegistryAutoConfiguration` 앞의 class-name ordering과 1s..5m/1..1000 검증, `MeterRegistry` 조건을 고정 | Step 4-T context/property tests |
| 사용자·Caller | runtime dependency와 migration 경계 불명확 | P1 해소. Micrometer/AWS SDK runtime 좌표·scope, 기존 helper/native opt-in/back-off 표, allow-list·shutdown·IAM 예시를 README와 EN/KO manual에 동일하게 추가 | Step 5 locale parity/metadata |
| 통합 | 공식 registry lifecycle을 대체할 위험 | P0/P1 없음. constructor start gate, 공식 `close()`/logger 계약, shared client 소유권을 유지하고 새 retry/shutdown thread를 만들지 않음 | Step 3 plan review, Step 4 verifier |

보안 리뷰의 endpoint와 caller-owned tag 데이터는 library가 런타임 PII를 판별할
수 없는 범위 경계다. 따라서 자동 sanitization이나 새 endpoint allow-list는
추가하지 않고, opt-in·allow-list·HTTPS·IAM·비밀 비기록을 실행 계약으로 남긴다.
이 disposition은 P0=0, P1=0으로 닫혔으며 P2/P3는 구현·문서 게이트에서 검증하거나
위험을 명시한 채 이슈 범위 밖으로 유지한다.
