package io.bluetape4k.aws.ktor.imds

/**
 * Ktor 애플리케이션을 위한 코루틴 기반 EC2 Instance Metadata Service 작업입니다.
 *
 * ## 계약
 *
 * 작업은 호출할 때만 IMDS에 요청하며 EC2에서 호스팅되는 애플리케이션을 대상으로 합니다.
 * 이 API는 의도적으로 메타데이터 도우미만 제공하며 IAM security-credentials 엔드포인트의
 * 임시 자격 증명 값은 노출하지 않습니다.
 */
interface ImdsKtorOperations {

    /**
     * [path]의 메타데이터 값을 문자열로 반환합니다.
     */
    suspend fun get(path: String): String

    /**
     * [path]의 메타데이터 값을 줄로 구분된 목록 항목으로 반환합니다.
     */
    suspend fun getList(path: String): List<String>

    /**
     * EC2 인스턴스 id를 반환합니다.
     */
    suspend fun instanceId(): String = get(IMDS_PATH_INSTANCE_ID)

    /**
     * EC2 인스턴스 타입을 반환합니다.
     */
    suspend fun instanceType(): String = get(IMDS_PATH_INSTANCE_TYPE)

    /**
     * 배치 가용 영역을 반환합니다.
     */
    suspend fun availabilityZone(): String = get(IMDS_PATH_AVAILABILITY_ZONE)

    /**
     * 배치 리전을 반환합니다.
     */
    suspend fun region(): String = get(IMDS_PATH_REGION)

    /**
     * 로컬 IPv4 주소를 반환합니다.
     */
    suspend fun localIpv4(): String = get(IMDS_PATH_LOCAL_IPV4)

    /**
     * 역할 자격 증명 문서를 읽지 않고 인스턴스에 연결된 IAM 역할 이름을 반환합니다.
     */
    suspend fun iamRoleNames(): List<String> = getList(IMDS_PATH_IAM_ROLE_NAMES)
}

internal const val IMDS_PATH_INSTANCE_ID = "/latest/meta-data/instance-id"
internal const val IMDS_PATH_INSTANCE_TYPE = "/latest/meta-data/instance-type"
internal const val IMDS_PATH_AVAILABILITY_ZONE = "/latest/meta-data/placement/availability-zone"
internal const val IMDS_PATH_REGION = "/latest/meta-data/placement/region"
internal const val IMDS_PATH_LOCAL_IPV4 = "/latest/meta-data/local-ipv4"
internal const val IMDS_PATH_IAM_ROLE_NAMES = "/latest/meta-data/iam/security-credentials/"
