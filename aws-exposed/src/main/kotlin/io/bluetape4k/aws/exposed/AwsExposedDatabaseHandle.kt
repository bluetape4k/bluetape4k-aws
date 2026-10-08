package io.bluetape4k.aws.exposed

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.atomicfu.atomic
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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

    private val closed = atomic(false)

    /**
     * Exposed transaction manager에서 [database]를 해제한 뒤 소유한 [dataSource]를 닫습니다.
     * 두 정리 단계가 모두 실패하면 첫 실패에 다음 실패를 suppressed exception으로 보존합니다.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) {
            return
        }

        val failure = runCatching {
            TransactionManager.closeAndUnregister(database)
        }.exceptionOrNull()

        val closeFailure = runCatching {
            (dataSource as? AutoCloseable)?.close()
        }.exceptionOrNull()

        if (failure != null && closeFailure != null) {
            failure.addSuppressed(closeFailure)
        }

        (failure ?: closeFailure)?.let {
            log.debug(it) { "AwsExposedDatabaseHandle 을 close 하는데 실패했습니다." }
            throw it
        }
    }

    override fun toString(): String =
        ToStringBuilder(this)
            .add("name", name)
            .add("properties", properties)
            .add("dataSource", dataSource)
            .add("database", database)
            .add("closed", closed.value)
            .toString()
}
