# Core Secrets 및 Parameters 경계

## 배경

Issue #268에서는 Java SDK v2와 AWS Kotlin SDK core module에 framework 중립적인 Secrets
Manager 및 SSM Parameter Store helper를 추가했다. 저장소에는 이미 Spring Environment와
Exposed configuration source 작업이 있었다. 따라서 low-level SDK wrapper와 상위
configuration loading, caching, refresh, rotation concern의 경계가 흐려지는 것이 주요
위험이었다.

## 결정

Core module은 SDK에 맞춘 얇은 helper만 제공한다.

- String secret을 위한 redacted `AwsSecretValue` wrapper
- Service client factory와 호출자가 소유하는 lifecycle helper
- Request builder와 single-page get/list/put helper
- Partial batch failure와 pagination token을 보존하는 원본 SDK response

Spring Environment 로딩, JSON 평탄화, cache/refresh 정책, rotation orchestration,
IAM/KMS policy management, 숨겨진 all-pages collection helper 같은 상위 기능은 이 core
module 밖에 둔다.

## 결과

이제 Java SDK v2 module에는 Secrets Manager와 SSM용 sync, async `CompletableFuture`,
coroutine wrapper가 있다. AWS Kotlin SDK module에는 같은 service용 native suspend helper와
client lifecycle helper가 있다. Public documentation이 새 core API surface와 일치하도록
README locale set와 service coverage chart를 함께 갱신했다.

## 검증

- Java 대상 test: Secrets/SSM/redaction 테스트 18개, 실패 0, skip 0
- Kotlin 대상 test: Secrets/SSM/redaction 테스트 15개, 실패 0, skip 0
- `git diff --check` 통과
- Static grep에서 변경한 helper의 custom retry/backoff/deadline/fan-out이 발견되지 않음
- Static grep에서 README/source의 `reveal()` logging 또는 printing이 발견되지 않음
- 변경한 module의 warning mode compile 통과
- Root, Java, Kotlin locale pair의 README local link와 code fence parity 통과
- Service coverage SVG를 `xmllint`로 parse하고 CairoSVG로 3800 x 2080 PNG를 rendering한
  뒤 전체 크기로 시각 검사

## 향후 지침

새 issue가 Spring/Exposed/rotation/cache 동작을 명시적으로 다루지 않는다면 향후 Secrets
Manager와 Parameter Store core 작업을 low-level로 유지한다. 나중에 all-pages helper를
추가한다면 이름으로 opt-in하게 하고, 가능한 경우 cold/lazy하게 만들며, SDK
pagination/error detail을 보존한다.
