# Issue 207 Ktor 고급 예제

## 배경

Issue #207에서는 Spring 예제를 복사하지 않고 고급 S3 및 SQS 기능을 사용할 수 있는
Ktor native 예제를 요청했다.

## 결정

새 예제 모듈을 추가하지 않고 기존 `aws-ktor-s3-examples`와
`aws-ktor-sqs-examples` 모듈을 확장한다. 이렇게 하면 CI와 README 진입점을 작게
유지하면서 #203 및 #199의 새 고급 API를 입증할 수 있다.

## 결과

- 이제 S3 예제에서 content-type 감지, S3 기반 config object, client-side 암호화 데모용
  로컬 in-memory data-key provider를 다룬다.
- 이제 SQS 예제는 수동 acknowledgement mode로 실행하며 Ktor route를 통해 1회 재시도,
  interceptor, observer 증거를 제공한다.
- 모듈 README와 `aws-ktor` README가 고급 예제를 연결한다.

## 검증

이 branch의 남은 작업: PR 전에 대상 예제 테스트와 로컬 검토를 실행한다.

## 향후 보호 장치

AWSpring parity를 위한 Ktor 예제 커버리지를 추가할 때는 먼저 기존 Ktor 예제 모듈을
확장한다. 실행할 수 없는 예제를 추가하지 말고 지원하지 않는 AWS 전용 scenario를
명확히 문서화한다.
