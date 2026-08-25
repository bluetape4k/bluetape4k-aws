package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class OrderDomainTest {

    @Test
    fun `order record rejects negative ids through bluetape validation`() {
        val error = assertFailsWith<IllegalArgumentException> {
            OrderRecord(
                id = -1L,
                customerId = "customer",
                status = OrderStatus.CREATED,
            )
        }

        error.message.orEmpty() shouldContain "id"
    }
}
