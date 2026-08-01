# Issue #182 SNS-to-SQS fanout 안정성

## 배경

Issue #182에서는 LocalStack 검증이 결정적일 때만 SNS-to-SQS 전달을 활성 상태로
유지하도록 Spring Boot SQS 예제 fanout 테스트를 다시 검토했다.

## 결정

Fanout 테스트를 활성 상태로 유지한다. 현재 LocalStack/Testcontainers 동작은 REST
전송, listener 수신, SNS-to-SQS fanout, DLQ 설정을 결합한 예제에서 안정적이다.

## 결과

이제 테스트는 REST, listener, fanout, queue, topic, DLQ 이름에 짧은
`Base58.randomString(8)` suffix를 사용한다. 따라서 이전 수신에서 남은 resource나
message가 우연히 assertion을 만족할 수 없다. Timeout 실패에는 기다린 조건도 포함한다.

## 검증 증거

- `:aws-spring-boot-sqs-examples:test --tests '*SqsSnsExampleLocalStackTest' --rerun-tasks`가 연속 3회 통과했다.
- Suffix를 `Base58.randomString(8)`로 바꾼 뒤 `:aws-spring-boot-sqs-examples:compileTestKotlin :aws-spring-boot-sqs-examples:test --tests '*SqsSnsExampleLocalStackTest' --rerun-tasks`가 통과했다.
- 최종 suffix 변경 후 `:aws-spring-boot-sqs-examples:test`가 통과했다.

## 향후 보호 장치

Fanout이 다시 불안정해지면 테스트를 비활성화하기 전에 queue policy 전파와 SNS
subscription 설정을 검사한다. 비활성화를 피할 수 없다면 필수
`@Disabled("#NNN — <reason>")` 형식을 사용하고 이 교훈이나 후속 교훈에 emulator
blocker를 문서화한다.
