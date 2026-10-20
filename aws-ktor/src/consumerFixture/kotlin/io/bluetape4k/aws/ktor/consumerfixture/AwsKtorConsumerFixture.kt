package io.bluetape4k.aws.ktor.consumerfixture

import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import io.bluetape4k.aws.ktor.AwsKtorCore
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import io.bluetape4k.aws.ktor.AwsKtorDynamoDbClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorKinesisClientCustomizer
import io.bluetape4k.aws.ktor.client.AwsSigV4PluginConfig
import io.bluetape4k.aws.ktor.imds.ImdsKtorPluginConfig
import io.bluetape4k.aws.ktor.s3.S3KtorClient
import io.bluetape4k.aws.ktor.s3.s3KtorClientOf
import io.bluetape4k.aws.ktor.sqs.SqsConsumer
import io.bluetape4k.aws.ktor.sqs.SqsConsumerPluginConfig
import io.bluetape4k.aws.ktor.sqs.SqsMessageConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import java.net.URI

/** 외부 소비자 소스 세트에서 공개 AWS Kotlin API의 컴파일을 확인합니다. */
class AwsKtorConsumerFixture {

    fun installSqsConsumer(
        application: Application,
        sqsClient: SqsClient,
        credentialsProvider: CredentialsProvider,
        converter: SqsMessageConverter,
    ) {
        application.install(AwsKtorCore) {
            region = "ap-northeast-2"
            kotlinCredentialsProvider = credentialsProvider
            kinesisClient(AwsKtorKinesisClientCustomizer { })
            dynamoDbClient(AwsKtorDynamoDbClientCustomizer { })
        }
        application.install(SqsConsumer) {
            this.sqsClient = sqsClient
            queueName = "orders"
            this.converter = converter
            onMessage<String> { }
        }
    }

    fun s3RestClient(defaults: AwsKtorDefaults): S3KtorClient = s3KtorClientOf(defaults)

    fun signingConfig(credentialsProvider: CredentialsProvider) = AwsSigV4PluginConfig().apply {
        region = "ap-northeast-2"
        service = "execute-api"
        this.credentialsProvider = credentialsProvider
    }

    fun imdsConfig(): ImdsKtorPluginConfig = ImdsKtorPluginConfig().apply {
        endpoint = URI("http://127.0.0.1:9911")
    }

    fun createTableRequest(): CreateTableRequest = CreateTableRequest {
        tableName = "orders"
    }
}
