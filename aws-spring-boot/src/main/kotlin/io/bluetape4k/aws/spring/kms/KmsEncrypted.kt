package io.bluetape4k.aws.spring.kms

/**
 * [KmsEncryptedFieldCodec]을 통해 String 값을 명시적으로 암호화하는 필드를 표시합니다.
 *
 * 이 애너테이션은 메타데이터일 뿐 DTO, 엔티티, 구성 객체를 투명하게 변경하지 않습니다.
 * 애플리케이션 코드는 평문 수명 주기가 명확한 매퍼, 변환기 또는 경계 지점에서
 * [KmsEncryptedFieldCodec]을 호출해야 합니다.
 *
 * 첫 번째 범위에서 지원하는 필드 타입은 `String`/`String?`입니다.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class KmsEncrypted(
    /**
     * 필드별 선택적 KMS 키 id입니다. 비어 있으면 `bluetape4k.aws.kms.key-id`를 사용합니다.
     */
    val keyId: String = "",

    /**
     * `name=value` 형식의 필드별 암호화 컨텍스트 항목입니다.
     *
     * 항목은 `bluetape4k.aws.kms.encryption-context` 위에 병합되므로 같은 이름의
     * 필드 항목이 구성된 기본값보다 우선합니다.
     */
    val encryptionContext: Array<String> = [],
)
