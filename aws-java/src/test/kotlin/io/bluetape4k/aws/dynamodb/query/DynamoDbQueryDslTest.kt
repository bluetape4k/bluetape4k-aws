package io.bluetape4k.aws.dynamodb.query

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.dynamodb.model.describe
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class DynamoDbQueryDslTest {

    companion object: KLoggingChannel()

    @Test
    fun `build nested filter queries`() {
        val request = queryRequest {
            tableName = "local-table"

            primaryKey("myPrimaryKey") {
                eq(2)
            }
            sortKey("mySortKey") {
                between(2 to 3)
            }

            filtering {
                attribute("a") {
                    lt(1)
                } and attribute("b") {
                    gt(2)
                } or {
                    attribute("c") {
                        eq(3)
                    } and attributeExists("d") or {
                        attribute("e") {
                            ne(4)
                        }
                    }
                } or attributeExists("f")
            }
        }

        log.debug { "queryRequest=${request.describe()}" }

        request.keyConditions()["myPrimaryKey"].shouldNotBeNull()
        request.keyConditions()["mySortKey"].shouldNotBeNull()
        request.filterExpression().shouldNotBeEmpty()
        request.expressionAttributeNames().size shouldBeEqualTo 6
        request.expressionAttributeValues().size shouldBeEqualTo 4
    }

    @Test
    fun `queryRequest는 tableName 없으면 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            queryRequest {
                primaryKey("pk") { eq("value") }
            }
        }
    }

    @Test
    fun `queryRequest는 primaryKey 없으면 예외를 던진다`() {
        assertFailsWith<IllegalArgumentException> {
            queryRequest {
                tableName = "table"
            }
        }
    }

    @Test
    fun `PrimaryKeyBuilder는 comparator 미설정 시 예외를 던진다`() {
        assertFailsWith<IllegalStateException> {
            PrimaryKeyBuilder("pk").build()
        }
    }

    @Test
    fun `SortKeyBuilder는 comparator 미설정 시 예외를 던진다`() {
        assertFailsWith<IllegalStateException> {
            SortKeyBuilder("sk").build()
        }
    }

    @Test
    fun `filtering rejects empty filter`() {
        val error = assertFailsWith<IllegalArgumentException> {
            queryRequest {
                tableName = "orders"
                primaryKey("orderId") { eq("order-123") }
                filtering {
                }
            }
        }

        error.message.orEmpty() shouldContain "filterQueries"
    }

    @Test
    fun `filtering rejects blank attribute name`() {
        val error = assertFailsWith<IllegalArgumentException> {
            queryRequest {
                tableName = "orders"
                primaryKey("orderId") { eq("order-123") }
                filtering {
                    attribute(" ") { eq("OPEN") }
                }
            }
        }

        error.message.orEmpty() shouldContain "attribute"
    }

    @Test
    fun `filtering rejects blank attributeExists name`() {
        val error = assertFailsWith<IllegalArgumentException> {
            queryRequest {
                tableName = "orders"
                primaryKey("orderId") { eq("order-123") }
                filtering {
                    attributeExists(" ")
                }
            }
        }

        error.message.orEmpty() shouldContain "attributeExists"
    }

    @Test
    fun `primaryKey만으로 queryRequest 생성 가능`() {
        val request = queryRequest {
            tableName = "orders"
            primaryKey("orderId") { eq("order-123") }
        }

        request.keyConditions()["orderId"].shouldNotBeNull()
        request.tableName() shouldBeEqualTo "orders"
    }
}
