# Issue #469 DynamoDB Streams Flow/checkpoint 교훈

## 결정

Java SDK v2와 AWS SDK for Kotlin에 동일한 소비 계약을 제공한다. Java 경로는
`software.amazon.awssdk.services.dynamodb.model.Record`와
`DynamoDbStreamsAsyncClient`를 사용하고, Kotlin 경로는
`aws.sdk.kotlin.services.dynamodbstreams.model.Record`와
`DynamoDbStreamsClient`를 사용한다. Kotlin dependency alias는
`aws.sdk.kotlin:dynamodbstreams`를 canonical 이름으로 유지하며, Java Streams API는
기존 `software.amazon.awssdk:dynamodb` artifact를 재사용한다.

## 전달과 checkpoint

- 시작 위치는 `TrimHorizon`, `Latest`, `AtSequenceNumber`, `AfterSequenceNumber`다.
- checkpoint 저장은 downstream `emit`이 정상 반환된 뒤에만 수행한다.
- 재시작은 저장된 sequence를 포함하는 `AtSequenceNumber`로 재개하므로
  at-least-once를 보장하고 중복 가능성을 공개한다.
- `NoopDynamoDbStreamsCheckpointStore`는 checkpoint를 요구하지 않는 소비를 위해
  유지하고, 기본 저장소 테스트에는 `InMemoryDynamoDbStreamsCheckpointStore`를
  재사용한다.

## Shard graph와 자원 소유권

`DescribeStream` 페이지를 읽어 root shard tree를 만들고, 서로 다른 root만
`maxShardConcurrency` 범위에서 병렬 처리한다. 한 tree 안에서는 parent Flow가
완료된 뒤 child를 읽어 순서를 보존한다. 빈 polling은 `pollInterval`보다 짧지 않은
backoff을 사용하고, retryable SDK 오류에는 bounded full-jitter backoff를 적용한다.

주입한 client와 HTTP engine의 소유권은 호출자에게 둔다. Kotlin의
`withDynamoDbStreamsClient`는 짧은 수명의 client와 내부 자원을 닫고, Java의
`withDynamoDbStreamsAsyncClient`는 block 성공·실패와 무관하게 client를 닫는다.
애플리케이션 범위 Java factory는 `ShutdownQueue`에 등록한다.

## 검증 경계

실제 AWS 계정은 사용하지 않고 FlociServer만 사용했다.

```bash
./gradlew :bluetape4k-aws-kotlin:test \
  --tests 'io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsFlociTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
./gradlew :bluetape4k-aws-java:test \
  --tests 'io.bluetape4k.aws.dynamodbstreams.DynamoDbStreamsFlociTest' \
  -Dbluetape4k.aws.emulator=floci --no-daemon
```

두 테스트는 Floci DynamoDB Streams endpoint에서 table stream을 생성하고 세 레코드를
넣은 뒤 shard envelope와 checkpoint를 확인했다. 단위 테스트는 emit-후-save 순서,
inclusive resume, retry/iterator 오류, root-child 순서, metrics callback을 검증했다.

Floci가 증명하지 않는 항목은 AWS-only 공백으로 남긴다. 24시간 retention 경계,
실제 throttling 비율, 운영 resharding timing과 IAM/네트워크 정책은 이 변경의 완료
근거로 사용하지 않는다.

## 재사용 규칙

새 Streams consumer는 SDK model을 서로 섞지 않고 이 모듈의 position/options/checkpoint
타입을 우선 재사용한다. payload를 로그나 metrics callback에 전달하지 않으며, 관측에는
shard·batch·retry·checkpoint sequence 같은 저카디널리티 정보만 전달한다.

참고:

- [AWS SDK for Kotlin DynamoDB Streams API](https://docs.aws.amazon.com/sdk-for-kotlin/api/latest/dynamodbstreams/)
- [AWS SDK for Java `DynamoDbStreamsAsyncClient`](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/dynamodb/streams/DynamoDbStreamsAsyncClient.html)
- [Floci service coverage](https://floci.io/floci/services/)
