# 이슈 239 AWS Emulator 이관 계획

이슈: [#239](https://github.com/bluetape4k/bluetape4k-aws/issues/239)
날짜: 2026-06-01

## 결정

`bluetape4k-aws`는 **Floci 우선** emulator 이관 정책을 유지한다.

- Floci는 새로 만들거나 이관한 emulator 인식 테스트의 기본 선택이다.
- LocalStack은 legacy 동작과 fidelity 차이를 위한 명시적인 fallback으로 유지한다.
- MiniStack은 대상 모듈에서 같은 SDK smoke matrix를 반복해서 통과할 때까지 평가 backend로만 사용한다.

MiniStack의 서비스 수만으로는 기본값을 바꿀 수 없다. 채택 판단은 이 저장소가 사용하는 AWS SDK 호출을 기준으로 해야 한다.

## 현재 Matrix

| 범위 | 현재 기본값 | 지원하는 override | 다음 조치 |
|---|---|---|---|
| `bluetape4k-aws-spring-boot` | Floci | `AwsSpringBootTestEmulator`를 통한 `floci`, `localstack`, `ministack` | 첫 smoke matrix 대상으로 사용한다. |
| `aws-ktor-sqs-examples` | Floci | 직접 Floci fixture 사용 | Floci 우선을 유지하고 재사용이 늘 때만 공통화한다. |
| `bluetape4k-aws-java` | Floci | 공유 AWS 테스트 기반을 통한 `floci`, `localstack` | Floci API 차이는 LocalStack으로 다룬다. |
| `bluetape4k-aws-kotlin` | Floci | 공유 AWS 테스트 기반을 통한 `floci`, `localstack` | Floci API 차이는 LocalStack으로 다룬다. |
| `bluetape4k-aws-ktor` | Floci | emulator 인식 테스트의 `floci`, `localstack` | Floci API 차이는 LocalStack으로 다룬다. |
| 예제 모듈 | AWS emulator 인식 예제는 Floci | 이관한 예제의 `floci`, `localstack` | AWS emulator를 사용하지 않는 예제의 불필요한 변경을 계속 피한다. |

## Smoke Matrix

Testcontainers 기반 검사를 직렬로 실행한다.

| 서비스 | 필수 동작 | Floci | MiniStack | LocalStack |
|---|---|---|---|---|
| S3 | bucket/object CRUD, path-style endpoint, 모듈에서 사용하는 presigned URL | 필수 | 비교 | Fallback |
| SQS | queue create/send/receive/delete, visibility timeout | 필수 | 비교 | Fallback |
| SNS | topic에서 SQS로 fanout | 필수 | 비교 | Fallback |
| DynamoDB | 모듈에서 사용하는 table CRUD/query/index 경로 | 필수 | 비교 | Fallback |
| KMS | Spring 테스트에서 사용하는 encrypt/decrypt 경로 | 필수 | 비교 | Fallback |
| Secrets Manager / SSM | environment post-processor 경로 | 필수 | 비교 | Fallback |

## 검증 순서

1. 문서와 agent 지침을 Floci 우선 정책에 맞춘다.
2. 기본 Floci로 `bluetape4k-aws-spring-boot`를 검증한다.
3. 비교 증거로 같은 모듈을 MiniStack에서 실행한다.
4. 차이를 문서화하고 해결할 때까지 LocalStack fallback을 유지한다.
5. Floci API 차이에 대한 명시적 fallback으로 LocalStack을 계속 사용한다.

## 검증 증거

- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=floci`
  통과: 테스트 34개.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=ministack`
  실패: 테스트 33개 통과, 1개 실패. 실패한 SQS FIFO 테스트는 `orders` 대신
  `null` message group id를 받았다. 따라서 MiniStack은 아직 이 모듈의 기본값인 Floci를 대체할 준비가 되지 않았다.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*AwsEmulatorTest' -Dbluetape4k.aws.emulator=localstack`
  통과: 테스트 34개. 명시적 fallback 경로가 계속 동작함을 확인했다.
