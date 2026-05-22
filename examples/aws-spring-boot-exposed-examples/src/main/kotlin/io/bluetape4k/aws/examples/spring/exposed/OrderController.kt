package io.bluetape4k.aws.examples.spring.exposed

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
 * HTTP API for the Spring Boot Exposed order example.
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
    fun findOrders(@RequestParam(required = false) customerId: String?): List<OrderRecord> =
        orderService.findOrders(customerId)
}
