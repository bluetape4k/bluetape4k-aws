package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import kotlin.test.assertFailsWith

class DynamoDbTableSchemaResolverTest {

    @Test
    fun `bean schema is cached by entity class`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()

        val first = resolver.resolve(Item::class.java)
        val second = resolver.resolve(Item::class.java)

        first shouldBeSameInstanceAs second
    }

    @Test
    fun `explicit schema overrides cached bean schema`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()
        val explicit = TableSchema.fromBean(Item::class.java)

        resolver.resolve(Item::class.java, explicit) shouldBeSameInstanceAs explicit
    }

    @Test
    fun `null entity class is rejected with a useful message`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve<Item>(null)
        }
    }

    @DynamoDbBean
    class Item {
        var id: String = ""
    }
}
