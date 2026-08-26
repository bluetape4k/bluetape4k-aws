package io.bluetape4k.aws.kotlin.consumer

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodbstreams.DynamoDbStreamsClient
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3tables.S3TablesClient
import aws.sdk.kotlin.services.scheduler.SchedulerClient
import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sts.StsClient
import io.bluetape4k.aws.kotlin.cloudwatch.cloudWatchClientOf
import io.bluetape4k.aws.kotlin.dynamodb.dynamoDbClientOf
import io.bluetape4k.aws.kotlin.dynamodbstreams.DynamoDbStreamsStartingPosition
import io.bluetape4k.aws.kotlin.dynamodbstreams.dynamoDbStreamsClientOf
import io.bluetape4k.aws.kotlin.kinesis.kinesisClientOf
import io.bluetape4k.aws.kotlin.kms.kmsClientOf
import io.bluetape4k.aws.kotlin.lambda.invokeString
import io.bluetape4k.aws.kotlin.lambda.lambdaClientOf
import io.bluetape4k.aws.kotlin.lambda.withLambdaClient
import io.bluetape4k.aws.kotlin.s3.s3ClientOf
import io.bluetape4k.aws.kotlin.s3tables.s3TablesClientOf
import io.bluetape4k.aws.kotlin.ses.sesClientOf
import io.bluetape4k.aws.kotlin.sfn.listExecutionsByMapRun
import io.bluetape4k.aws.kotlin.sfn.listExecutionsByStateMachine
import io.bluetape4k.aws.kotlin.sfn.sfnClientOf
import io.bluetape4k.aws.kotlin.sfn.withSfnClient
import io.bluetape4k.aws.kotlin.sns.snsClientOf
import io.bluetape4k.aws.kotlin.sqs.sqsClientOf
import io.bluetape4k.aws.kotlin.sts.stsClientOf
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine

private const val FIXTURE_STATE_MACHINE_ARN =
    "arn:aws:states:ap-northeast-2:123456789012:stateMachine:consumer"
private const val FIXTURE_MAP_RUN_ARN =
    "arn:aws:states:ap-northeast-2:123456789012:mapRun:consumer/map-1"

private suspend fun kotlinSfnCustomHttpClient(engine: HttpClientEngine): SfnClient =
    withSfnClient(httpClient = engine) { it }

/**
 * AWS Kotlin SDK wrapper의 대표 compileOnly 서비스 표면을 외부 consumer 관점에서 확인합니다.
 *
 * 이 fixture는 실행하지 않고 컴파일만 하므로 AWS client를 생성하지 않습니다.
 */
fun kotlinServiceConsumerFixture(): List<Any> = listOf<Any>(
    S3Client::class.java,
    S3TablesClient::class.java,
    DynamoDbClient::class.java,
    DynamoDbStreamsClient::class.java,
    SnsClient::class.java,
    SqsClient::class.java,
    KmsClient::class.java,
    SesClient::class.java,
    CloudWatchClient::class.java,
    KinesisClient::class.java,
    SchedulerClient::class.java,
    SfnClient::class.java,
    LambdaClient::class.java,
    StsClient::class.java,
    { s3ClientOf() },
    { s3TablesClientOf(region = "ap-northeast-2") },
    { dynamoDbClientOf(region = "ap-northeast-2") },
    { dynamoDbStreamsClientOf(region = "ap-northeast-2") },
    DynamoDbStreamsStartingPosition.Latest,
    { snsClientOf() },
    { sqsClientOf() },
    { kmsClientOf() },
    { sesClientOf() },
    { cloudWatchClientOf() },
    { kinesisClientOf() },
    { SchedulerClient { } },
    { SfnClient { } },
    { sfnClientOf(region = "ap-northeast-2") },
    suspend {
        withSfnClient(region = "ap-northeast-2") { client ->
            client.listExecutionsByStateMachine(
                FIXTURE_STATE_MACHINE_ARN,
                builder = { maxResults = 10 },
            )
        }
    },
    suspend {
        withSfnClient(region = "ap-northeast-2") { client ->
            client.listExecutionsByMapRun(
                FIXTURE_MAP_RUN_ARN,
                builder = { maxResults = 10 },
            )
        }
    },
    ::kotlinSfnCustomHttpClient,
    { LambdaClient { } },
    { lambdaClientOf(region = "ap-northeast-2") },
    suspend {
        withLambdaClient(region = "ap-northeast-2") { client ->
            client.invokeString("orders", "{}").value
        }
    },
    { stsClientOf() },
)
