package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.io.Serializable

/**
 * Ktor 주문 예제에서 사용하는 생명주기 상태입니다.
 */
enum class OrderStatus {
    CREATED,
    PAID,
    CANCELLED,
}

/**
 * 예제 주문을 생성할 때 사용하는 요청 본문입니다.
 */
data class OrderRequest(
    val customerId: String,
    val status: OrderStatus = OrderStatus.CREATED,
    val notes: String? = null,
): Serializable {

    init {
        customerId.requireNotBlank("customerId")
    }

    companion object {
        private const val serialVersionUID: Long = 7850077202585310099L
    }
}

/**
 * Ktor Exposed 주문 repository가 반환하는 레코드입니다.
 */
data class OrderRecord(
    val id: Long = 0L,
    val customerId: String,
    val status: OrderStatus,
    val notes: String? = null,
): Serializable {

    init {
        customerId.requireNotBlank("customerId")
        id.requireGe(0L, "id")
    }

    companion object {
        private const val serialVersionUID: Long = 6507672816037403037L
    }
}

/**
 * Ktor 주문 예제에서 사용하는 Exposed 테이블입니다.
 */
object OrdersTable: LongIdTable("ktor_example_orders") {
    val customerId = varchar("customer_id", 96)
    val status = varchar("status", 32)
    val notes = varchar("notes", 512).nullable()
}

/**
 * `call.awsExposedTransaction` 내부에서만 사용하는 JDBC repository입니다.
 */
object OrderRepository: LongJdbcRepository<OrderRecord> {

    override val table = OrdersTable

    override fun extractId(entity: OrderRecord): Long = entity.id

    override fun ResultRow.toEntity(): OrderRecord =
        OrderRecord(
            id = this[OrdersTable.id].value,
            customerId = this[OrdersTable.customerId],
            status = OrderStatus.valueOf(this[OrdersTable.status]),
            notes = this[OrdersTable.notes],
        )

    fun save(entity: OrderRecord): OrderRecord {
        val id = OrdersTable.insertAndGetId { statement ->
            statement[customerId] = entity.customerId
            statement[status] = entity.status.name
            statement[notes] = entity.notes
        }
        return entity.copy(id = id.value)
    }

    fun findByCustomerId(customerId: String): List<OrderRecord> {
        customerId.requireNotBlank("customerId")
        return findAll { OrdersTable.customerId eq customerId }
    }
}
