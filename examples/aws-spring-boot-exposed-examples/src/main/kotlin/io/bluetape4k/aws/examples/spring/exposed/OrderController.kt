package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.exposed.core.ExposedCursorPage
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Spring Boot Exposed 주문 예제가 제공하는 HTTP API입니다.
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderService: OrderService,
) {

    @PostMapping
    fun create(@RequestBody request: OrderRequest): ResponseEntity<OrderRecord> =
        ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(request))

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): OrderRecord =
        orderService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order $id was not found.")

    @GetMapping
    fun findOrders(
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: String?,
    ): ExposedCursorPage<OrderRecord, Long> {
        val request = try {
            OrderPageRequest.parse(
                rawCursor = cursor,
                rawLimit = limit,
                customerId = customerId,
            )
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                e.message ?: "Invalid order page request.",
                e,
            )
        }
        return orderService.findOrders(request)
    }

    @GetMapping("/search")
    fun searchOrders(
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "${OrderService.DEFAULT_SEARCH_LIMIT}") limit: Int,
        @RequestParam(defaultValue = "customerId") sort: String,
    ): List<OrderSummaryRecord> {
        if (limit !in 1..OrderService.MAX_SEARCH_LIMIT) {
            throw badRequest("limit must be between 1 and ${OrderService.MAX_SEARCH_LIMIT}.")
        }

        val parsedStatus = status
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { value ->
                runCatching { OrderStatus.valueOf(value) }
                    .getOrElse { throw badRequest("status must be CREATED, PAID, or CANCELLED.") }
            }

        return orderService.searchOrders(
            customerId = customerId,
            status = parsedStatus,
            limit = limit,
            sort = parseSort(sort),
        )
    }

    private fun parseSort(value: String): Sort {
        val normalized = value.trim()
        val descending = normalized.startsWith("-")
        val property = normalized.removePrefix("-")
        if (property !in SUPPORTED_SEARCH_SORTS) {
            throw badRequest("sort must be customerId, status, -customerId, or -status.")
        }
        val order = if (descending) Sort.Order.desc(property) else Sort.Order.asc(property)
        return Sort.by(order)
    }

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private companion object {
        val SUPPORTED_SEARCH_SORTS = setOf("customerId", "status")
    }
}
