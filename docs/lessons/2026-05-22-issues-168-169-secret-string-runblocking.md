# Issues #168 및 #169 Secret Redaction 보호 장치

## 배경

Daily review에서 Exposed database 작업의 작은 공백 두 가지를 발견했다.

- 새 instance에서 `AwsSecretString` redaction을 테스트했지만 Java serialization
  round-trip 뒤에는 테스트하지 않았다.
- `AwsExposedAutoConfiguration`의 Spring bean factory에서
  `runBlocking(Dispatchers.IO)`을 사용하면서 수명 주기 근거를 명시한 comment가 없었다.

## 결정

`AwsSecretString`을 Kotlin value class에서 명시적 value equality를 구현한 일반
serializable class로 바꾼다. Kotlin이 value class의 method name을
`readResolve-impl`로 mangle하므로 Java serialization은 value-class `readResolve`를
호출하지 않는다. Deserialization 뒤 validation을 다시 실행하려면 일반 class가
필요하다. 대상 serialization test를 추가하고 Spring factory method가 suspend registry
생성을 `runBlocking`으로 bridge하는 이유를 문서화한다.

## 결과

이제 test로 deserialize한 `AwsSecretString`도 `reveal()`로만 값을 드러내고,
`toString()`을 통한 진단 text는 redaction하며, 변조한 blank value는 `readResolve`에서
거부함을 입증한다. Equality는 constant-time byte comparison을 사용하고 `hashCode()`는
secret에서 파생한 hash 대신 redacted constant를 반환한다. README에는 Java-serialized
byte에 raw secret이 포함된다고 명시했다. Spring 자동 구성에는 Ktor plugin에서 사용한
것과 같은 동기식 수명 주기 근거를 남겼다.

## 검증

- `./gradlew :bluetape4k-aws-exposed:test --tests "io.bluetape4k.aws.exposed.AwsExposedDatabaseFactoryTest.secret string serialization round-trip preserves redaction" :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:test :bluetape4k-aws-spring-boot:test --no-daemon --continue --max-workers=1` (test body는 통과했으나 Spring task 뒤 Gradle test-results binary cleanup 문제 발생)
- `./gradlew :bluetape4k-aws-spring-boot:cleanTest :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1`
- `./gradlew :bluetape4k-aws-exposed:test :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`

## 향후 보호 장치

Redacted value object가 `Serializable`을 구현하면 새 instance의 redaction test뿐 아니라
serialization/copy/logging boundary test도 추가한다. Deserialization 뒤 invariant를
강제해야 하는 Java-serializable redacted wrapper에는 Kotlin value class를 사용하지
않는다. 운영 initialization code에서 `runBlocking`을 사용하면 bridge 옆에 수명 주기
근거를 남긴다.
