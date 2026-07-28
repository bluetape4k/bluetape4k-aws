package io.bluetape4k.aws.exposed

import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * [AwsExposedDatabaseFactory]가 생성한 closeable Exposed database handle입니다.
 */
class AwsExposedDatabaseHandle(
    /** registry에서 이 handle을 식별하는 database 이름입니다. */
    val name: String,
    /** 이 handle을 만들 때 사용한 최종 연결 설정입니다. */
    val properties: AwsDatabaseConnectionProperties,
    /** Exposed [Database]가 사용하는 JDBC [DataSource]입니다. */
    val dataSource: DataSource,
    /** 연결된 Exposed database instance입니다. */
    val database: Database,
): AutoCloseable {

    companion object: KLogging()

    /**
     * 소유한 [dataSource]가 [AutoCloseable]을 지원하면 닫습니다.
     */
    override fun close() {
        val closeable = dataSource as? AutoCloseable ?: return
        closeable.close()
    }
}
