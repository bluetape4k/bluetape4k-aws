# SNS 서명 fixture 출처

이 디렉터리의 JSON은 AWS SNS HTTP 알림의 `SignatureVersion` 1/2 canonical
필드 순서를 재현한 결정적 테스트 fixture입니다. 형식과 변조 시나리오는
AWS SDK for Java v2 `sns-message-manager`의 공식 테스트 리소스를 기준으로
작성했습니다.

- 기준 리소스: <https://github.com/aws/aws-sdk-java-v2/tree/master/services-custom/sns-message-manager/src/test/resources/software/amazon/awssdk/messagemanager/sns/internal>
- 형식 문서: <https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message-verify-message-signature.html>
- `signing-cert.pem`은 테스트가 외부 AWS 인증서 만료에 의존하지 않도록 `sns.amazonaws.com` 이름과 장기 유효기간으로 생성한 self-signed 인증서입니다. SDK는 이 테스트에서 인증서 체인이 아니라 hostname과 유효기간, 공개키 서명을 검증합니다.
- `expired-cert.pem`은 AWS SDK 공식 만료 인증서 fixture를 그대로 보존해 만료 경계 테스트에 사용합니다.

실제 AWS SNS 전송 또는 인증서 endpoint에는 연결하지 않습니다.
