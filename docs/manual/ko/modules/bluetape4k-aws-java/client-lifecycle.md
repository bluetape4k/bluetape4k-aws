---
title: Client 수명 주기와 소유권
description: AWS SDK v2 client를 애플리케이션 소유권에 맞춰 생성·공유·종료하는 방법입니다.
manualId: bluetape4k-aws-java
chapterId: client-lifecycle
---

# Client 수명 주기와 소유권

AWS SDK v2 client는 생성이 끝난 뒤 thread-safe하게 공유할 수 있습니다. 애플리케이션 범위에서 하나를 만들고 재사용한 뒤, 이를 사용하는 백그라운드 작업이 모두 멈춘 다음 정확히 한 번 닫으세요. 이 모듈의 factory는 client를 구성할 뿐 소유권까지 가져가지는 않습니다.

## 어떤 client를 고를까

| Client | 알맞은 작업 | 호출 방식 |
|---|---|---|
| 서비스 동기 client | 범위가 분명한 blocking 작업 | 결과를 직접 반환 |
| 서비스 async client | 동시 I/O와 coroutine adapter | `CompletableFuture` |
| Transfer Manager | multipart와 대용량 S3 전송 | 비동기 transfer handle |

coroutine 안에서 동기 client를 호출한다고 논블로킹이 되지는 않습니다. 플랫폼 스레드를 잡지 않고 기다려야 한다면 async client를 사용하세요.

## 소유자는 하나, 사용자는 여러 곳

```kotlin
class AwsClients : AutoCloseable {
    val s3: S3AsyncClient = S3ClientFactory.Async.create(region = region)

    override fun close() {
        s3.close()
    }
}
```

소유 객체나 client를 repository와 handler에 주입하세요. 공유 client를 빌려 쓰는 코드는 닫지 않습니다. 프레임워크가 client를 만들었다면 애플리케이션 수명 주기에서 닫고, 애플리케이션이 Ktor runtime에 주입했다면 소유권도 애플리케이션에 남습니다.

## Endpoint와 자격 증명

region과 credentials는 client를 만들 때 정합니다. endpoint override는 Floci나 LocalStack에 유용하지만 SigV4 scope에 region이 들어가므로 region도 필요합니다. 요청마다 default credential provider나 HTTP client를 새로 만들지 마세요.

## 종료 순서

1. 새 요청을 받지 않습니다.
2. poller, listener, transfer 제출을 멈춥니다.
3. 실행 중인 작업을 제한된 시간 동안 기다립니다.
4. 서비스 client를 닫습니다.
5. 애플리케이션이 소유한 경우에만 공용 HTTP transport를 닫습니다.

client부터 닫으면 graceful shutdown이 연결 오류와 취소 오류로 바뀝니다.

## 실패 점검표

- 서비스 SDK JAR이 없으면 client를 만들기 전에 실패합니다.
- async client를 닫지 않으면 event-loop thread와 연결이 남습니다.
- repository가 주입받은 client를 닫으면 다른 호출자가 갑자기 실패합니다.
- 요청마다 client를 만들면 connection pooling 효과가 사라지고 credentials 조회 비용이 커집니다.

## 근거 자료

- [S3 client factory](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/s3/S3ClientFactory.kt)
- [Async HTTP client provider](../../../../../aws-java/src/main/kotlin/io/bluetape4k/aws/http/SdkAsyncHttpClientProvider.kt)
- [Client 설정 테스트](../../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/client/ClientConfigurationSupportTest.kt)
