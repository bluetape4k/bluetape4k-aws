package io.bluetape4k.aws.examples.spring.exposed

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

    fun findOrders(customerId: String?): List<OrderRecord> =
        transaction(database) {
            if (customerId.isNullOrBlank()) {
                OrderRepository.findAll()
            } else {
                OrderRepository.findByCustomerId(customerId)
            }
        }

    private fun OrderRequest.toRecord(): OrderRecord =
        OrderRecord(
            customerId = customerId,
            status = status,
            notes = notes,
        )
}
