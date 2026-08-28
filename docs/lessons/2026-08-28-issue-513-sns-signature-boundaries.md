# Issue #513 SNS 서명 검증 경계 lesson

## 배경

PR #512가 parser·auto-configuration·fail-closed 위임 경계를 제공했지만,
Floci는 서명된 SNS HTTP delivery를 만들지 않습니다. 따라서 이번 이슈는
실제 AWS endpoint 대신 AWS SDK v2 `SnsMessageManager`와 로컬
`SdkHttpClient` double을 사용해 서명·인증서·네트워크 실패 경계를 고정했습니다.

## 결정

- AWS SDK v2 공식 테스트 리소스의 JSON 형식을 기준으로 SignatureVersion 1/2
  fixture를 만들고, 테스트 인증서는 외부 인증서 만료에 흔들리지 않도록
  `sns.amazonaws.com` 이름의 장기 self-signed 인증서를 사용했습니다.
- 공식 만료 인증서는 별도 fixture로 보존해 만료 거부를 `CertificateRetriever`
  경계에서 직접 확인했습니다.
- parser 조기 거부(HTTPS SNS host·expected TopicArn), 서명·canonical field 변조,
  손상 인증서, connect/read timeout 원인, cancellation, response body cleanup,
  10개 bounded certificate cache, 동시 검증을 15개 결정적 테스트로 고정했습니다.
- 로컬 측정 harness는 warmup 10회, 5개 sample, sample당 200회 검증으로
  cache hit/miss fetch 수, throughput, peak heap을 JSON으로 기록합니다. 절대
  성능 목표가 아니라 동일 JVM 기준값으로만 해석합니다.

## 검증 결과

- `SnsHttpMessageVerifierFixtureTest`: 15 passing.
- `SnsHttpMessageVerifierMeasurementTest` opt-in 실행: cache hit은 인증서 1회,
  cache miss는 1,010회 fetch를 관찰했습니다. 이번 JDK 25 로컬 실행의 평균
  throughput 측정값은 각각 약 6,019 ops/s와 7,446 ops/s였고 peak heap
  측정값은 44,698,656 bytes와 45,094,032 bytes였습니다.
- 기존 `SnsHttpMessageVerifierTest`와 Floci SNS API smoke는 별도 명령으로
  순차 검증하며, Floci 결과를 signed HTTP delivery 증거로 해석하지 않습니다.

## 실패한 가정/판단 → 교정

1. **서명 fixture의 SignatureVersion 2 값만 맞으면 통과할 것이라는 판단**
   → 첫 RED 실행에서 base64 decode 오류가 발생했습니다. 공식 canonical
   signature와 인증서를 다시 대조해 fixture 오타를 수정한 뒤 v2·동시성 테스트가
   GREEN이 되었습니다.
2. **Gradle `-D`만 주면 opt-in 측정 property가 forked test JVM에 전달된다는 판단**
   → Gradle test가 측정 메서드를 pending으로 남겼습니다. 기존 measurement
   harness와 같은 `JAVA_TOOL_OPTIONS` 전달 경계를 사용하고 실제 JSON artifact를
   읽어 실행 여부를 확인했습니다.
3. **Floci SNS API 성공이 signed HTTP delivery 검증을 대신할 수 있다는 판단**
   → Floci capability를 확인해 API smoke와 signature delivery를 분리했습니다.
   README에 두 명령과 한계를 함께 기록했습니다.

## 향후 예방 확인

- 외부 인증서 만료에 의존하는 fixture를 추가하지 말고, 공식 형식 출처와 만료
  fixture provenance를 `src/test/resources/sns-signature/NOTICE.md`에 기록합니다.
- 측정값은 command, warmup·sample 수, JVM, backend, cache fetch 수, heap/throughput
  caveat가 있는 JSON artifact로 남기며 제품 SLA로 승격하지 않습니다.
- `SnsHttpMessageVerifier`는 root `Throwable`을 직접 보관하지 않는 현재 구조를
  유지하고, AWS SDK manager 내부 lifecycle을 adapter의 retention 계약으로
  과장하지 않습니다.
- credential-gated real AWS signed-delivery smoke와 hosted certificate endpoint는
  실제 자격 증명과 외부 네트워크가 필요한 별도 범위로 유지합니다.

## 출처

- [AWS SNS 서명 검증 문서](https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message-verify-message-signature.html)
- [AWS SDK for Java v2 SNS message-manager 공식 테스트 리소스](https://github.com/aws/aws-sdk-java-v2/tree/master/services-custom/sns-message-manager/src/test/resources/software/amazon/awssdk/messagemanager/sns/internal)
