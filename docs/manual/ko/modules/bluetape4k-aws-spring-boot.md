---
manualId: "bluetape4k-aws-spring-boot"
id: "bluetape4k-aws-spring-boot"
title: "AWS Spring Boot 통합"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-aws-spring-boot"
sourceDir: "aws-spring-boot"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.aws:bluetape4k-aws-spring-boot
---

# AWS Spring Boot 통합

> 0.5.0 릴리스 소스를 기준으로 작성한 라이브러리 매뉴얼입니다.

## 제공하는 기능 {#problem}

선택한 AWS 서비스를 위한 Spring Boot 4 자동 설정, coroutine template, repository, listener, 설정 소스와 Micrometer 계측을 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Spring이 AWS client와 애플리케이션용 template의 수명 주기를 관리하되 서비스 SDK 의존성은 애플리케이션이 명시적으로 고르고 싶을 때 적합합니다.

## 의존성 좌표 {#coordinates}

애플리케이션에서는 중앙 BOM 버전 하나만 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
}
```

AWS 서비스 SDK는 `compileOnly` 정책 때문에 실제 사용하는 모듈을 런타임 의존성으로 별도 추가해야 합니다.

## 핵심 개념 {#concepts}

서비스 SDK가 없으면 조건부 자동 설정은 물러납니다. properties로 region, endpoint, credentials와 서비스 동작을 조정하고, template은 suspend 작업을 제공하며, listener container가 백그라운드 작업을 소유합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
@Service
class ObjectStore(private val s3: S3Operations) {
    suspend fun put(bucket: String, key: String, bytes: ByteArray) =
        s3.upload(bucket, key, bytes)
}
```

사용할 서비스 SDK가 classpath에 있어야 해당 bean이 생성됩니다.

## 작업별 API {#api-by-task}

S3 object와 Transfer Manager 작업, provider 기반 client-side encryption,
DynamoDB repository·DAX, SQS listener/runtime, SNS, SES, KMS, CloudWatch,
IMDS, Secrets Manager, Parameter Store, S3 Access Grants·Vectors, Exposed를
지원합니다.

## S3 client-side encryption provider {#s3-client-side-encryption}

> 미출시/develop: 이 절은 Issue #475 API를 설명하며 `0.5.0` 릴리스 소스에는 포함되지 않습니다.

`S3ClientSideEncryptionOperations`는 선택 기능입니다. `provider=KMS`가
기본값이며 기존 `KmsOperations` 기반 byte-array 계약을 유지합니다. KMS는
provider streaming adapter를 제공하지 않습니다. 애플리케이션이 공급한 key
material을 사용하려면 `provider=AES` 또는 `provider=RSA`를 설정하세요.

```yaml
bluetape4k:
  aws:
    s3:
      client-side-encryption:
        enabled: true
        provider: AES
        key-id: orders-key
        key-version: 2026-08
        encryption-context:
          service: order-api
      transfer:
        enabled: true
```

### Provider 선택 {#s3-cse-provider-selection}

| `provider` | 필요한 bean | 계약 |
| --- | --- | --- |
| `KMS` (기본값) | `KmsOperations` | 기존 `S3ClientSideEncryptionTemplate`; byte-array 작업 |
| `AES` | `S3AesProvider` 정확히 하나 | AES secret key를 사용하는 `S3ClientSideEncryptionProviderTemplate` |
| `RSA` | `S3RsaProvider` 정확히 하나 | RSA key pair를 사용하는 `S3ClientSideEncryptionProviderTemplate` |

선택한 AES/RSA provider는 하나만 존재해야 합니다. provider가 없거나 여러
개이면 필요한 provider와 설정된 `provider` 값을 포함한 메시지로 애플리케이션
시작을 실패합니다. 설정이 KMS 또는 다른 provider로 조용히 대체되지 않습니다.

애플리케이션이 자체 secret-management 경계에서 key material을 읽고 일치하는
provider를 등록합니다.

```kotlin
import io.bluetape4k.aws.spring.s3.S3AesProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.crypto.SecretKey

@Configuration
class S3EncryptionConfiguration {
    @Bean
    fun s3AesProvider(secret: SecretKey): S3AesProvider =
        S3AesProvider.of(secret)
}
```

RSA에는 `S3RsaProvider.of(keyPair)`를 사용합니다. AES material은 16, 24,
32 encoded bytes를 제공해야 합니다. RSA public/private key는 RSA algorithm을
사용하고 동일한 modulus를 가지며 2048-bit 이상이어야 합니다. Provider template은
생성 시점에 material을 고정하므로 암호화와 복호화에 같은 key를 사용합니다.

### 데이터와 메타데이터 계약 {#s3-cse-envelope}

Provider object마다 random 32-byte AES data key와 12-byte payload nonce를
생성합니다. Payload는 128-bit authentication tag를 사용하는
`AES/GCM/NoPadding`입니다. AES provider는 AES-GCM으로 data key를 wrapping하고,
RSA provider는 `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`을 사용합니다.

Object에는 다음 Bluetape4k 예약 metadata를 저장합니다. 사용자 metadata는
대소문자를 구분하지 않고 이 key를 재사용할 수 없습니다.

| Metadata | 의미 |
| --- | --- |
| `bt4k-cek-version` | Provider envelope version `2` |
| `bt4k-cek-provider` | `aes` 또는 `rsa` |
| `bt4k-cek-alg` | `AES/GCM/NoPadding` |
| `bt4k-cek-wrap-alg` | Provider key-wrapping algorithm |
| `bt4k-cek-encoding` | `base64` |
| `bt4k-cek`, `bt4k-cek-nonce` | Wrapped data key와 payload nonce |
| `bt4k-cek-wrap-nonce` | AES wrapping nonce; RSA에는 없음 |
| `bt4k-cek-key-id`, `bt4k-cek-key-version` | Provider identity와 선택 version |

`key-id`와 `key-version`은 provider material을 식별합니다. `key-id`를 생략하면
template이 provider material에서 만든 fingerprint를 사용합니다. Provider
envelope는 `key-version`을 설정한 경우 복호화할 때 값이 일치해야 합니다. 병합된
encryption context는 AES-GCM additional authenticated data로 canonicalize하며
metadata, log, temporary file에는 기록하지 않습니다.

잘못된 metadata, 지원하지 않는 algorithm/encoding, 유효하지 않은 key material,
provider 또는 key identity 불일치는 plaintext를 반환하기 전에 실패합니다.
Wrapping, unwrapping, payload authentication 실패는
`S3ClientSideEncryptionException`으로 전달됩니다. Provider envelope는
Bluetape4k 전용 wire format이므로 AWS Encryption SDK나 다른 S3 client와
호환되지 않습니다. 기존 KMS metadata는 KMS 구현 경로에서만 처리합니다.

### Typed와 transfer API {#s3-cse-transfer}

공통 `S3ClientSideEncryptionOperations` extension인
`uploadEncryptedObject`와 `downloadEncryptedObject`는 `S3ObjectConverter`를
재사용하며 KMS와 provider template 모두에서 사용할 수 있습니다. Converter가
직렬화한 byte를 선택한 암호화 작업에 전달하며, 작업이 임시 byte array를 호출 후
zeroize합니다.

`software.amazon.awssdk:s3-transfer-manager`가 classpath에 있고
`bluetape4k.aws.s3.transfer.enabled=true`이며 provider transfer 조건을 만족하면
자동 설정이 `S3ClientSideEncryptionTransferOperations`를 추가합니다.

```kotlin
class SecureFiles(
    private val encryptedTransfer: S3ClientSideEncryptionTransferOperations,
) {
    suspend fun write(bucket: String, key: String, bytes: ByteArray) {
        encryptedTransfer.encryptedOutputStream(bucket, key).use { output ->
            output.write(bytes)
        }
    }
}
```

`encryptedOutputStream`은 내부 `S3OutputStream` 앞에서 암호화합니다.
`complete()`는 권장하는 suspend 완료 경로이며 GCM authentication tag를 기록하고,
`close()`는 blocking 호환 경로입니다. threshold를 넘겨 spill하면 임시 파일에는
ciphertext만 기록됩니다.

`downloadEncryptedFile`은 HEAD로 size와 ETag를 확인하고 `If-Match`로 다운로드한
뒤 ciphertext size를 확인합니다. 전체 ciphertext 인증이 성공한 뒤에만 destination에
plaintext를 기록합니다. 성공·실패·취소 모두 temporary ciphertext file과 복호화
buffer를 정리하므로 authentication 실패가 기존 destination을 바꾸지 않습니다. 평문
temporary file을 만들지 않고 기존 destination을 보존하기 위해 최종 기록은
non-cancellable I/O 경계에서 수행하며, 기존 destination도 같은
`MAX_CIPHERTEXT_BYTES` 상한을 적용해 write 실패 시 memory에서 복원합니다.

## 권장 패턴 {#patterns}

client와 백그라운드 작업의 소유자를 한 곳으로 정하고, region·credentials·endpoint를 호출마다 만들지 말고 애플리케이션 경계에서 구성하세요. Provider key material도 애플리케이션 경계에서 관리하고 암호화 작업마다 새 provider를 만들지 않습니다.

## 연동 {#integrations}

중앙 BOM을 가져오고 이 라이브러리는 버전 없이 추가하세요. 활성화할 자동 설정이 사용하는 `software.amazon.awssdk:<service>` 모듈만 더합니다.

## 설정 {#configuration}

`bluetape4k.aws` 설정 영역과 emulator용 endpoint override를 사용하세요. client
builder를 애플리케이션에서 세밀하게 제어해야 하면 customizer bean을 제공합니다.
S3 client-side encryption은
`bluetape4k.aws.s3.client-side-encryption.enabled=true`로 켜고 AES/RSA를 사용할
때 provider를 명시하세요. 일치하는 provider bean은 정확히 하나 등록해야 합니다.
KMS 기본값은 `KmsOperations`가 있을 때만 암호화 bean을 만들며, AES/RSA로 자동
대체하지 않습니다. Provider transfer에는 transfer manager와
`bluetape4k.aws.s3.transfer.enabled=true`도 필요합니다.

## 실패 유형과 해결 방법 {#failures}

bean이 생기지 않는 경우는 대부분 서비스 SDK가 없거나 조건이 맞지 않기
때문입니다. S3 client-side encryption에서 KMS 기본값에 `KmsOperations`가 없으면
AES/RSA로 바꾸지 않고 backoff합니다. AES/RSA provider가 없거나 후보가 두 개
이상이면 시작을 실패합니다. 잘못된 key 길이·RSA key pair·예약 metadata 충돌·잘못된
envelope metadata·provider/key identity 불일치는 fail closed로 처리합니다.
Authentication 또는 wrapping 실패는 `S3ClientSideEncryptionException`으로
전달하며 plaintext를 반환하지 않습니다. SQS visibility, listener 동시성, payload
변환, 종료 timeout은 명시적으로 조정하세요.

## 운영 {#operations}

Micrometer 지표를 노출하고 listener acknowledgement 정책을 분명히 하세요. 직접 만든
client는 닫고 환경 property source 조회는 요청 처리 경로에서 반복하지 않습니다.
`SecretKey`/`KeyPair` 원본, 저장 위치, rotation 정책은 애플리케이션이 소유합니다.
Spring은 context 종료 때 provider template의 내부 material을 닫지만 key store,
rotation service, HSM 연동을 제공하지 않습니다. key material, provider context,
plaintext payload를 log에 기록하지 마세요.

## 테스트 {#testing}

조건부 bean은 `ApplicationContextRunner`로 확인하고 활성화한 서비스는 Floci 통합
테스트로 검증하세요. Provider acceptance lane은 AES byte/typed/stream/file 작업,
RSA byte 작업, ciphertext metadata와 KMS 회귀를 확인합니다.

```bash
./gradlew -Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1 \
  :bluetape4k-aws-spring-boot:test \
  --tests '*S3ClientSideEncryptionProviderAwsEmulatorTest'
```

성공적인 전송뿐 아니라 listener 종료와 redelivery도 테스트해야 합니다. Floci
테스트는 AWS quota, 운영 credentials, HSM 동작을 검증하지 않습니다.

## 학습 경로와 예제 {#workshops}

`auto-configuration` → `storage-and-messaging` → `runtime-operations` 순서로 읽고 릴리스에 포함된 Spring Boot 예제 네 개를 실행하세요.

## 제약 사항 {#limitations}

이 모듈은 awspring이 아니며 모든 AWS 서비스를 자동으로 켜지 않습니다. 선택 기능은
필요한 클래스와 설정이 있을 때만 활성화됩니다. KMS client-side encryption의 기본
작업 경계는 byte-array이며 provider transfer adapter를 제공하지 않습니다. 공통 typed
extension은 KMS와 provider 구현 모두에서 사용할 수 있고, provider streaming/file API는
위에서 설명한 Bluetape4k envelope을 사용합니다. 이 envelope은 AWS Encryption SDK
형식이 아니며 다른 S3 client와의 상호운용성을 보장하지 않습니다. 이 모듈은 key를
저장·rotation·복구하지 않고 HSM 또는 법적 compliance 보장을 제공하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Bluetape4k AWS Spring Boot 아키텍처

[![Bluetape4k AWS Spring Boot 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-architecture-01.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.ko.md)_

### Bluetape4k AWS Spring Boot configuration 처리 흐름

[![Bluetape4k AWS Spring Boot configuration 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-flow-02.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-flow-02.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.ko.md)_

### Bluetape4k AWS Spring Boot SQS listener 시퀀스 다이어그램

[![Bluetape4k AWS Spring Boot SQS listener 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-aws/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/docs/images/readme-diagrams/aws-spring-boot-sequence-03.svg)

_배포본 README: [`aws-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-aws/blob/664e4dfb544a3c19db484b0f9a8e023a73774b49/aws-spring-boot/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [릴리스 소스: `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`](../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [릴리스 소스: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt`](../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsListener.kt)
- [릴리스 테스트: 자동 설정](../../../../aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/AwsAutoConfigurationTest.kt)
