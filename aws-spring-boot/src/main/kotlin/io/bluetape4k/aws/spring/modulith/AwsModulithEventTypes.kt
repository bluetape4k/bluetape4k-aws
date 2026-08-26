package io.bluetape4k.aws.spring.modulith

/**
 * 외부화할 Spring Modulith 이벤트 형식과 식별자 추출 규칙을 등록합니다.
 *
 * @property type 외부 envelope에서 사용하는 안정적인 이벤트 형식 이름
 * @property version 이벤트 payload 형식 버전
 * @property eventClass 등록할 이벤트의 정확한 JVM class
 * @property eventId 이벤트에서 안정적인 식별자를 추출하는 함수
 * @property allowedHeaderNames 외부 envelope에 포함할 수 있는 header 이름
 * @property headers 이벤트에서 header 값을 추출하는 함수
 */
data class AwsModulithEventTypeRegistration<T : Any>(
    val type: String,
    val version: Int,
    val eventClass: Class<T>,
    val eventId: (T) -> String,
    val allowedHeaderNames: Set<String> = emptySet(),
    val headers: (T) -> Map<String, String> = { emptyMap() },
)

/** 등록된 이벤트 형식을 보관하는 immutable registry입니다. */
class AwsModulithEventTypeRegistry private constructor(
    registrations: List<AwsModulithEventTypeRegistration<*>>,
) {
    private val registrations: List<AwsModulithEventTypeRegistration<*>> = registrations.toList()

    companion object {
        /** 주어진 등록 항목으로 registry를 만듭니다. */
        fun of(vararg registrations: AwsModulithEventTypeRegistration<*>): AwsModulithEventTypeRegistry =
            AwsModulithEventTypeRegistry(registrations.toList())
    }
}
