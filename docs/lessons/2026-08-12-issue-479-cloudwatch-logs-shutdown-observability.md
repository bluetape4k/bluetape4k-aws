---
title: CloudWatch Logs shutdown timeout 관찰성과 취소 정리
date: 2026-08-12
issue: 479
module: aws-ktor
---

# CloudWatch Logs shutdown timeout 관찰성과 취소 정리

## 상황

`CloudWatchLogsKtorRuntime.stop()`은 `withTimeoutOrNull`로 bounded flush를
수행했지만 timeout 결과를 무시했다. `flush()`가 복원한 event가 buffer에 남아도
종료가 정상처럼 보였고, plugin-owned client가 닫힌 뒤에는 재시도 경로와 유실 규모를
운영자가 확인할 수 없었다.

## 원인

- timeout은 `null` 결과로 표현되므로 명시적인 shutdown outcome 기록이 없었다.
- caller cancellation과 flush 예외를 timeout과 구분하지 않았다.
- 취소된 coroutine의 `finally`에서 suspend cleanup을 수행하면 client close가 다시
  취소될 수 있다.

## 결정

- 기본 `CloudWatchLogsShutdownPolicy.WarnAndContinue`로 기존 호출자의 비예외 종료
  계약을 유지한다.
- `CloudWatchLogsShutdownObservation`과 선택적 observer로
  `Success`, `Timeout`, `Failure`, `Cancelled`, pending event 수와 dropped event 수를
  metrics/tracing backend에 전달한다. Observer 실패는 shutdown 결과를 덮어쓰지 않는다.
- `ThrowOnTimeout`은 timeout observation과 owned client close를 먼저 완료한 뒤
  `CloudWatchLogsShutdownTimeoutException`을 전파한다.
- caller `CancellationException`은 원래 예외를 다시 던지고,
  `withContext(NonCancellable)` 안에서 observer 통지와 client close를 보장한다.
- 주입한 client는 기존 소유권 계약대로 닫지 않는다.

## 검증

- runtime 테스트에서 timeout, strict timeout, caller cancellation, flush failure,
  정상 flush 각각의 observation과 pending/dropped count를 확인했다.
- 정상 경로는 `flush -> close` 순서를 확인하고, 모든 경로에서 owned client가 한 번만
  닫히는지 확인했다.
- runtime/plugin CloudWatch Logs 회귀 테스트 22개가 통과했다.

## 재사용 규칙

bounded shutdown은 timeout 결과를 버리지 말고 outcome과 남은 작업량을 관찰 가능하게
기록한다. 취소 경로의 suspend cleanup은 `NonCancellable` 경계에 두며, strict 실패
정책은 자원 정리와 관찰 이벤트를 완료한 뒤 도메인 예외를 전파한다.
