package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.exposed.core.ExposedCursorPage
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

/**
 * Spring Boot Exposed 예제의 transaction 경계를 소유합니다.
 */
@Service
class OrderService(
    private val database: Database,
) {

    fun create(request: OrderRequest): OrderRecord =
        transaction(database) {
            OrderRepository.save(request.toRecord())
        }

    fun findById(id: Long): OrderRecord? =
        transaction(database) {
            OrderRepository.findByIdOrNull(id)
        }

    internal fun findOrders(request: OrderPageRequest): ExposedCursorPage<OrderRecord, Long> =
        transaction(database) {
            OrderRepository.findOrderPage(request)
        }

    private fun OrderRequest.toRecord(): OrderRecord =
        OrderRecord(
            customerId = customerId,
            status = status,
            notes = notes,
        )
}
