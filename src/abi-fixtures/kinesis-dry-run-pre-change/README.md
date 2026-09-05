# Issue #620 Kinesis DryRun pre-change ABI fixture

이 fixture는 `dryRun` production/catalog 변경 전 기존 JVM ABI의 closed set을 고정한다.
Task 5 verifier는 이 파일과 세 `javap` output을 읽기만 하며 변경 후 JAR에서 다시 생성하지
않는다.

- Base commit: `f07015b6e9a3e6aceb4f301081b502cb88eb40c3`
- Catalog ref: `850959d0ea5f76ac7e2c442400f47653d5f95eed`
- JAR: `aws-kotlin/build/libs/bluetape4k-aws-kotlin-1.1.0.jar`
- JAR SHA-256: `f1cecbc4ffc15d68d3247b89f17cdea10ce1c242353a987be652d5b505df829d`
- Captured methods: 12 (`direct` 6, `$default` 6)

## Capture command

```bash
./gradlew :bluetape4k-aws-kotlin:jar --no-daemon --no-configuration-cache
javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-1.1.0.jar -public -s \
  io.bluetape4k.aws.kotlin.kinesis.KinesisClientExtensionsKt
javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-1.1.0.jar -public -s \
  io.bluetape4k.aws.kotlin.kinesis.model.PutRecordKt
javap -classpath aws-kotlin/build/libs/bluetape4k-aws-kotlin-1.1.0.jar -public -s \
  io.bluetape4k.aws.kotlin.kinesis.model.GetShardIteratorKt
```

## Closed set

| Owner | Method | Kind |
| --- | --- | --- |
| `KinesisClientExtensionsKt` | `putRecord` | direct |
| `KinesisClientExtensionsKt` | `putRecord$default` | default bridge |
| `KinesisClientExtensionsKt` | `putRecords` | direct |
| `KinesisClientExtensionsKt` | `putRecords$default` | default bridge |
| `KinesisClientExtensionsKt` | `getShardIterator` | direct |
| `KinesisClientExtensionsKt` | `getShardIterator$default` | default bridge |
| `KinesisClientExtensionsKt` | `getRecords` | direct |
| `KinesisClientExtensionsKt` | `getRecords$default` | default bridge |
| `PutRecordKt` | `putRecordRequestOf` | direct |
| `PutRecordKt` | `putRecordRequestOf$default` | default bridge |
| `GetShardIteratorKt` | `getShardIteratorRequestOf` | direct |
| `GetShardIteratorKt` | `getShardIteratorRequestOf$default` | default bridge |

이 baseline은 binary compatibility의 과거 입력이다. 변경 후 새 overload가 추가되는지는
Task 5의 별도 additive verifier와 격리된 legacy consumer runtime test가 판정한다.
