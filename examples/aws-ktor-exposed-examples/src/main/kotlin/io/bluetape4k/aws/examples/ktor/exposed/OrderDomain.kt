package io.bluetape4k.aws.examples.ktor.exposed

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.support.requireNotBlank
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.io.Serializable

/**
 * Lifecycle states used by the Ktor order example.
 */
enum class OrderStatus {
    CREATED,
    PAID,
    CANCELLED,
}

/**
 * Request body used to create an example order.
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
 * Record returned by the Ktor Exposed order repository.
 */
data class OrderRecord(
    val id: Long = 0L,
    val customerId: String,
    val status: OrderStatus,
    val notes: String? = null,
): Serializable {

    init {
        customerId.requireNotBlank("customerId")
        require(id >= 0L) { "id must be zero or greater: $id" }
    }

    companion object {
        private const val serialVersionUID: Long = 6507672816037403037L
    }
}

/**
 * Exposed table for the Ktor order example.
 */
object OrdersTable: LongIdTable("ktor_example_orders") {
    val customerId = varchar("customer_id", 96)
    val status = varchar("status", 32)
    val notes = varchar("notes", 512).nullable()
}

/**
 * JDBC repository used only inside `call.awsExposedTransaction`.
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
