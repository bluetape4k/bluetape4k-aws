package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.exposed.core.ExposedCursorPage
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Spring Boot Exposed 예제의 transaction 경계를 소유합니다.
 */
@Service
class OrderService(
    private val database: Database,
    private val orderSpringDataRepository: OrderSpringDataRepository,
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

    /**
     * Spring Data Exposed 2.0.0의 QBE와 FluentQuery projection을 함께 사용하는 검색입니다.
     *
     * QBE adapter는 현재 transaction에 연결된 영속 Entity를 probe로 요구하므로, 조건에
     * 맞는 첫 주문을 probe로 읽은 뒤 probe의 나머지 필드는 무시합니다. 최종 조회는
     * `OrderSummaryProjection`에 필요한 두 column만 선택하고 정렬과 limit을 SQL에 위임합니다.
     */
    @Transactional(transactionManager = "springTransactionManager")
    fun searchOrders(
        customerId: String?,
        status: OrderStatus?,
        limit: Int,
        sort: Sort,
    ): List<OrderSummaryRecord> {
        require(limit in 1..MAX_SEARCH_LIMIT) {
            "limit must be between 1 and $MAX_SEARCH_LIMIT."
        }

        return if (TransactionManager.currentOrNull() == null) {
            transaction(database) { searchOrdersInCurrentTransaction(customerId, status, limit, sort) }
        } else {
            searchOrdersInCurrentTransaction(customerId, status, limit, sort)
        }
    }

    private fun searchOrdersInCurrentTransaction(
        customerId: String?,
        status: OrderStatus?,
        limit: Int,
        sort: Sort,
    ): List<OrderSummaryRecord> {
        val normalizedCustomerId = customerId?.trim()?.takeIf(String::isNotBlank)
        val probe = findProbe(normalizedCustomerId, status)
            ?: return emptyList()

        val ignoredPaths = buildList {
            add("notes")
            if (normalizedCustomerId == null) add("customerId")
            if (status == null) add("status")
        }
        val matcher = ExampleMatcher.matchingAll()
            .withIgnorePaths(*ignoredPaths.toTypedArray())
            .withMatcher("customerId", ExampleMatcher.GenericPropertyMatchers.exact())
            .withMatcher("status", ExampleMatcher.GenericPropertyMatchers.exact())
        val example = Example.of(probe, matcher)

        return orderSpringDataRepository.findBy(example) { query ->
            query.`as`(OrderSummaryProjection::class.java)
                .project(mutableListOf("customerId", "status"))
                .sortBy(sort)
                .limit(limit)
                .all()
        }.map { projected ->
            OrderSummaryRecord(
                customerId = projected.customerId,
                status = OrderStatus.valueOf(projected.status),
            )
        }
    }

    private fun findProbe(customerId: String?, status: OrderStatus?): OrderEntity? = when {
        customerId != null && status != null ->
            OrderEntity.find {
                (OrdersTable.customerId eq customerId) and (OrdersTable.status eq status.name)
            }.firstOrNull()

        customerId != null ->
            OrderEntity.find { OrdersTable.customerId eq customerId }.firstOrNull()

        status != null ->
            OrderEntity.find { OrdersTable.status eq status.name }.firstOrNull()

        else -> OrderEntity.all().firstOrNull()
    }

    companion object {
        const val DEFAULT_SEARCH_LIMIT: Int = 20
        const val MAX_SEARCH_LIMIT: Int = 100
    }

    private fun OrderRequest.toRecord(): OrderRecord =
        OrderRecord(
            customerId = customerId,
            status = status,
            notes = notes,
        )
}
