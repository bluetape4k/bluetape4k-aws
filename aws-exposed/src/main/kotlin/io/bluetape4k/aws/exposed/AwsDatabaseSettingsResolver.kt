package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging

/**
 * [AwsExposedDatabaseFactory]가 pool을 만들기 전에 database 설정을 해석합니다.
 *
 * framework adapter는 AWS Secrets Manager, Parameter Store, cache 값, test double로 이 계약을 구현할 수 있습니다.
 * foundation module은 suspend-friendly 계약만 제공하며 AWS client lifecycle은 소유하지 않습니다.
 */
fun interface AwsDatabaseSettingsResolver {

    /**
     * [databaseName]에 대한 설정을 해석합니다.
     */
    suspend fun resolve(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): AwsDatabaseConnectionProperties
}

/**
 * 설정을 변경하지 않고 그대로 반환하는 resolver입니다.
 */
object NoopAwsDatabaseSettingsResolver: AwsDatabaseSettingsResolver, KLogging() {
    override suspend fun resolve(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): AwsDatabaseConnectionProperties = properties
}
