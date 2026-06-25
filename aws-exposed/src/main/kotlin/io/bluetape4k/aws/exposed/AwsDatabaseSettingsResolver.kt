package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging

/**
 * Resolves database settings before [AwsExposedDatabaseFactory] creates a pool.
 *
 * Framework adapters may implement this contract with AWS Secrets Manager,
 * Parameter Store, cached values, or test doubles. The foundation module keeps
 * the contract suspend-friendly but does not own AWS client lifecycle.
 */
fun interface AwsDatabaseSettingsResolver {

    /**
     * Resolves settings for [databaseName].
     */
    suspend fun resolve(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): AwsDatabaseConnectionProperties
}

/**
 * Resolver that returns settings unchanged.
 */
object NoopAwsDatabaseSettingsResolver: AwsDatabaseSettingsResolver, KLogging() {
    override suspend fun resolve(
        databaseName: String,
        properties: AwsDatabaseConnectionProperties,
    ): AwsDatabaseConnectionProperties = properties
}
