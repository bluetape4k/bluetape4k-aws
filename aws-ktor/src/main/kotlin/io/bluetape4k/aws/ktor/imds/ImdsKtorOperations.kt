package io.bluetape4k.aws.ktor.imds

/**
 * Coroutine-based EC2 Instance Metadata Service operations for Ktor applications.
 *
 * ## Contract
 *
 * Operations call IMDS only when invoked and are intended for EC2-hosted
 * applications. This API intentionally exposes metadata helpers only; it does
 * not expose temporary credential values from the IAM security-credentials
 * endpoint.
 */
interface ImdsKtorOperations {

    /**
     * Returns the metadata value at [path] as a string.
     */
    suspend fun get(path: String): String

    /**
     * Returns the metadata value at [path] as line-separated list entries.
     */
    suspend fun getList(path: String): List<String>

    /**
     * Returns the EC2 instance id.
     */
    suspend fun instanceId(): String = get(IMDS_PATH_INSTANCE_ID)

    /**
     * Returns the EC2 instance type.
     */
    suspend fun instanceType(): String = get(IMDS_PATH_INSTANCE_TYPE)

    /**
     * Returns the placement availability zone.
     */
    suspend fun availabilityZone(): String = get(IMDS_PATH_AVAILABILITY_ZONE)

    /**
     * Returns the placement region.
     */
    suspend fun region(): String = get(IMDS_PATH_REGION)

    /**
     * Returns the local IPv4 address.
     */
    suspend fun localIpv4(): String = get(IMDS_PATH_LOCAL_IPV4)

    /**
     * Returns IAM role names attached to the instance without reading
     * role credential documents.
     */
    suspend fun iamRoleNames(): List<String> = getList(IMDS_PATH_IAM_ROLE_NAMES)
}

internal const val IMDS_PATH_INSTANCE_ID = "/latest/meta-data/instance-id"
internal const val IMDS_PATH_INSTANCE_TYPE = "/latest/meta-data/instance-type"
internal const val IMDS_PATH_AVAILABILITY_ZONE = "/latest/meta-data/placement/availability-zone"
internal const val IMDS_PATH_REGION = "/latest/meta-data/placement/region"
internal const val IMDS_PATH_LOCAL_IPV4 = "/latest/meta-data/local-ipv4"
internal const val IMDS_PATH_IAM_ROLE_NAMES = "/latest/meta-data/iam/security-credentials/"
