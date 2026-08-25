package io.bluetape4k.aws.examples.spring.exposed

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * AWS Exposed database bean이 준비된 뒤 예제 테이블을 생성합니다.
 */
@Component
class OrderSchemaInitializer(
    private val database: Database,
): ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        transaction(database) {
            SchemaUtils.create(OrdersTable)
        }
    }
}
