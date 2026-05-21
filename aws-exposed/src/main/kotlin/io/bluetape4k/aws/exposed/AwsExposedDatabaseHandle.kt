package io.bluetape4k.aws.exposed

import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * Closeable Exposed database handle created by [AwsExposedDatabaseFactory].
 */
class AwsExposedDatabaseHandle(
    val name: String,
    val properties: AwsDatabaseConnectionProperties,
    val dataSource: DataSource,
    val database: Database,
): AutoCloseable {

    /**
     * Closes the owned [dataSource] when it supports [AutoCloseable].
     */
    override fun close() {
        val closeable = dataSource as? AutoCloseable ?: return
        closeable.close()
    }
}
