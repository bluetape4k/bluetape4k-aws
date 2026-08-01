# Issues #281-#283 구현 검토

날짜: 2026-06-08

범위:

- #281: Ktor IMDS injected operation은 client-only validation을 우회해야 한다.
- #282: Netty가 없어도 async HTTP client가 제공되면 Spring Boot IMDS가 활성 상태여야 한다.
- #283: DAX capacity 설정은 0을 거부해야 한다.

## 판정

PASS (P0: 0, P1: 0, P2: 0).

## 7-Tier 검토

| Tier | 결과 | 증거 |
|---|---|---|
| 정확성 | PASS | `ImdsKtorPluginConfig.toRuntime()`은 client 설정 검증 전에 injected operation을 반환하고 DAX zero boundary test는 startup 실패를 확인한다. |
| Spring 자동 구성 | PASS | `ImdsAutoConfigurationTest`는 Netty가 classpath에서 제외되고 `SdkAsyncHttpClient`가 제공된 경우를 다룬다. |
| Coroutine/수명 주기 | PASS | suspend/cancellation은 바뀌지 않고 injected operation은 application 소유이며 stop 동작도 유지된다. |
| 재사용 | PASS | DAX는 `requirePositiveNumber`, IMDS fallback은 `SdkAsyncHttpClientProvider.defaultHttpClient`를 사용한다. |
| 테스트 | PASS | 집중 IMDS/DAX 및 영향 모듈 test가 통과했고 `--rerun-tasks`로 33개 집중 test를 실행했다. |
| 문서/API | PASS | 공개 API/README 계약은 바뀌지 않았고 review/lesson에 증거를 남겼다. |
| Build hygiene | PASS | `git diff --check`와 targeted Gradle 검증을 PR DoD에 포함했다. |

## 메모

- Native subagent tool 계약상 명시적 사용자 요청이 없어 review lane을 실행하지 않았다.
- Local 7-Tier 검토 후 P0/P1은 남지 않았다.
- Injected Ktor IMDS 설치, Spring `SdkAsyncHttpClient` classpath backoff, DAX 최소 양수 capacity test를 보강했다.
