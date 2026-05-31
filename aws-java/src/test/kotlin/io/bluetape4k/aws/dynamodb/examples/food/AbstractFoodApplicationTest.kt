package io.bluetape4k.aws.dynamodb.examples.food

import io.bluetape4k.aws.dynamodb.AbstractDynamodbTest
import io.bluetape4k.idgenerators.snowflake.GlobalSnowflake
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
abstract class AbstractFoodApplicationTest: AbstractDynamodbTest() {

    companion object: KLoggingChannel() {

        @JvmStatic
        protected val dynamodb = localStackServer

        @JvmStatic
        protected val snowflake = GlobalSnowflake()

        @JvmStatic
        @DynamicPropertySource
        fun registerAwsProperties(registry: DynamicPropertyRegistry) {
            registry.add("aws.region") { dynamodb.regionName }
            registry.add("aws.accessKey") { dynamodb.awsAccessKey }
            registry.add("aws.secretKey") { dynamodb.awsSecretKey }
            registry.add("aws.dynamodb.endpoint") { dynamodb.awsEndpoint.toString() }
        }
    }
}
