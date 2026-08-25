# 이슈 #459 SNS HTTP annotation adapter 구현 lesson

## 핵심 결정

1. 기존 `SnsHttpMessageParser`와 `SnsHttpMessageVerifier`를 재사용하고,
   Spring adapter는 HTTP lifecycle·parameter binding 경계만 소유한다. 서명
   규칙을 새로 복제하지 않아 parser/verifier와 endpoint의 보안 의미가 갈라지지
   않는다.
2. 검증 순서는 body bound read → parser/attribute shape → expected TopicArn
   allowlist → verifier → handler다. 이 순서를 Servlet `OncePerRequestFilter`와
   WebFlux `WebFilter` 모두에서 유지해야 resolver parameter가 없는 mapping도
   우회할 수 없다.
3. request body는 플랫폼별로 한 번만 소유한다. Servlet은 bounded byte snapshot과
   replay wrapper를, WebFlux는 joined buffer를 즉시 복사·release하고
   subscription마다 새 buffer를 만든다. pooled buffer는 정상, cancellation,
   overflow에서 모두 release해야 한다.
4. confirmation은 `NotificationStatus`를 통한 명시적 `confirmSubscription()`만
   허용한다. subscription과 unsubscribe를 별도 mapping으로 유지해 자동 side
   effect와 재전달 처리를 섞지 않는다.
5. `ObjectMapper`와 web stack은 optional classpath 경계로 취급한다. Jackson은
   reflection bridge로만 조회하고, MVC/WebFlux auto-configuration은 string-based
   condition으로 back off한다. AOT는 annotation만 보존하지 않고 실제 controller
   method와 payload type을 reflective processor로 등록한다.

## 검증에서 배운 점

- `ResponseStatusException`을 직접 `sendError`하면 fail-closed 경로가 운영 로그에서
  사라진다. status mapping을 하나의 `SnsHttpEndpointErrorPolicy`로 모으고
  category/status/bounded size/safe type만 기록하면 Servlet과 WebFlux의 진단 의미를
  맞출 수 있다.
- WebFlux filter가 있으면 resolver는 완료된 exchange cache를 읽기만 해야 한다.
  filter 없는 standalone fallback도 여러 SNS parameter가 body를 재소비하지 않도록
  exchange attribute에 `Mono.cache()`를 저장해야 한다.
- `bodyValue` 한 덩어리 테스트만으로는 `DataBufferLimitException`과 release를
  증명할 수 없다. `TestPublisher<DataBuffer>`로 `MAX_BYTES + 1`을 여러 chunk로
  공급하고 각 pooled chunk의 allocation 상태를 확인해야 한다.
- public annotation의 `path`가 `Array<String>`이면 README도
  `path = ["/..."]` 문법을 사용해야 한다. 독립 API review에서 발견한 예제 오류를
  문서 양국에서 수정하고 reflection/endpoint fixture로 실제 사용 형태를 고정했다.
- compileOnly SDK는 코드가 컴파일된다는 사실만으로 runtime 계약이 완성되지 않는다.
  `FilteredClassLoader`에서 raw/String path가 시작하는지와 runtime dependency,
  SDK timeout/retry ownership을 함께 문서화해야 한다.

## 다음 변경자를 위한 지침

- 새로운 SNS handler parameter를 추가할 때 `SnsHttpMessageResolverSupport`의
  exact-type/static validation, MVC/WebFlux resolver, startup validator, AOT
  processor, EN/KO README, endpoint fixture를 한 세트로 갱신한다.
- body limit, cancellation, replay, log redaction 중 하나라도 바꾸면
  `SnsHttpMessageWebFilterTest`의 pooled `DataBuffer` 증거와 Servlet ReadListener
  순서를 먼저 재실행한다.
- verifier나 AWS SDK retry 정책을 adapter에 넣지 않는다. certificate fetch와
  SDK resource lifecycle은 `SnsHttpMessageVerifier`/AWS SDK 소유로 유지하고,
  실제 AWS signed delivery가 필요하면 별도 credential-gated smoke 범위로 등록한다.
- Spring reflection API deprecation은 upstream migration 시 별도 유지보수 작업으로
  처리하며, 그때도 `@Reflective` processor와 실제 consumer AOT fixture를 함께 보존한다.

## DoD Status

- 상태: lesson 기록 완료
- 기록한 항목: parser/verifier reuse, pre-handler gate, single body ownership,
  pooled release/cancellation, explicit confirmation, optional classpath/AOT,
  redacted error policy, documentation parity
- 남은 환경 경계: hosted CI와 실제 AWS signed SNS delivery는 이번 local 범위 밖
