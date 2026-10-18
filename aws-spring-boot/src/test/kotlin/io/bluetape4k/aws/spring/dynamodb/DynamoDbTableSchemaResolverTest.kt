package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean
import kotlin.test.assertFailsWith

class DynamoDbTableSchemaResolverTest {

    companion object: KLogging()

    @Test
    fun `bean schema is cached by entity class`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()

        val first = resolver.resolve<Item>()
        val second = resolver.resolve<Item>()

        first shouldBeSameInstanceAs second
    }

    @Test
    fun `explicit schema overrides cached bean schema`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()
        val explicit = TableSchema.fromBean(Item::class.java)

        resolver.resolve<Item>(explicit) shouldBeSameInstanceAs explicit
    }

    @Test
    fun `null entity class is rejected with a useful message`() {
        val resolver = DefaultDynamoDbTableSchemaResolver()

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve<Item>(null, null)
        }
    }

    @DynamoDbBean
    class Item {
        var id: String = ""
    }
}
