package io.bluetape4k.aws.consumer

import io.bluetape4k.aws.cloudwatch.cloudWatchClient
import io.bluetape4k.aws.dynamodb.dynamoDbClient
import io.bluetape4k.aws.kinesis.kinesisClient
import io.bluetape4k.aws.kms.kmsClient
import io.bluetape4k.aws.lambda.lambdaAsyncClient
import io.bluetape4k.aws.lambda.lambdaAsyncClientOf
import io.bluetape4k.aws.lambda.lambdaClient
import io.bluetape4k.aws.lambda.lambdaClientOf
import io.bluetape4k.aws.lambda.invokeString
import io.bluetape4k.aws.lambda.withLambdaAsyncClient
import io.bluetape4k.aws.lambda.withLambdaClient
import io.bluetape4k.aws.s3.S3ClientFactory
import io.bluetape4k.aws.ses.sesClient
import io.bluetape4k.aws.sfn.listExecutionsByMapRun
import io.bluetape4k.aws.sfn.listExecutionsByStateMachine
import io.bluetape4k.aws.sfn.sfnAsyncClient
import io.bluetape4k.aws.sfn.sfnAsyncClientOf
import io.bluetape4k.aws.sfn.sfnClient
import io.bluetape4k.aws.sfn.sfnClientOf
import io.bluetape4k.aws.sfn.withSfnAsyncClient
import io.bluetape4k.aws.sfn.withSfnClient
import io.bluetape4k.aws.sns.snsClient
import io.bluetape4k.aws.sqs.sqsClient
import io.bluetape4k.aws.sts.stsClient
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.scheduler.SchedulerClient
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sts.StsClient
import java.net.URI

private const val FIXTURE_STATE_MACHINE_ARN =
    "arn:aws:states:ap-northeast-2:123456789012:stateMachine:consumer"
private const val FIXTURE_MAP_RUN_ARN =
    "arn:aws:states:ap-northeast-2:123456789012:mapRun:consumer:map-1"

private fun javaSfnCustomHttpClients(
    httpClient: SdkHttpClient,
    asyncHttpClient: SdkAsyncHttpClient,
): List<Any> = listOf<Any>(
    { sfnClientOf(httpClient = httpClient) },
    { sfnAsyncClientOf(httpClient = asyncHttpClient) },
)

/**
 * Java SDK wrapper의 대표 compileOnly 서비스 표면을 외부 consumer 관점에서 확인합니다.
 *
 * 이 fixture는 실행하지 않고 컴파일만 하므로 AWS client를 생성하지 않습니다.
 */
fun javaServiceConsumerFixture(): List<Any> = listOf<Any>(
    S3Client::class.java,
    DynamoDbClient::class.java,
    SnsClient::class.java,
    SqsClient::class.java,
    KmsClient::class.java,
    SesClient::class.java,
    CloudWatchClient::class.java,
    KinesisClient::class.java,
    SchedulerClient::class.java,
    SfnClient::class.java,
    LambdaClient::class.java,
    LambdaAsyncClient::class.java,
    StsClient::class.java,
    { S3ClientFactory.Sync.create { } },
    { dynamoDbClient { } },
    { snsClient { } },
    { sqsClient { } },
    { kmsClient { } },
    { sesClient { } },
    { cloudWatchClient { } },
    { kinesisClient { } },
    { SchedulerClient.builder().build() },
    { SfnClient.builder().build() },
    { sfnClient { region(Region.AP_NORTHEAST_2) } },
    { sfnClientOf(endpoint = URI.create("http://localhost:4566"), region = Region.AP_NORTHEAST_2) },
    { withSfnClient(region = Region.AP_NORTHEAST_2) { client -> client } },
    { sfnAsyncClient { region(Region.AP_NORTHEAST_2) } },
    { sfnAsyncClientOf(endpoint = URI.create("http://localhost:4566"), region = Region.AP_NORTHEAST_2) },
    suspend {
        withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
            client.listExecutionsByStateMachine(
                FIXTURE_STATE_MACHINE_ARN,
                builder = { maxResults(10) },
            )
        }
    },
    suspend {
        withSfnAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
            client.listExecutionsByMapRun(
                FIXTURE_MAP_RUN_ARN,
                builder = { maxResults(10) },
            )
        }
    },
    ::javaSfnCustomHttpClients,
    { lambdaClient { region(Region.AP_NORTHEAST_2) } },
    { lambdaClientOf(endpoint = URI.create("http://localhost:4566"), region = Region.AP_NORTHEAST_2) },
    { withLambdaClient(region = Region.AP_NORTHEAST_2) { client -> client.invokeString("orders", "{}") } },
    { lambdaAsyncClient { region(Region.AP_NORTHEAST_2) } },
    { lambdaAsyncClientOf(endpoint = URI.create("http://localhost:4566"), region = Region.AP_NORTHEAST_2) },
    suspend {
        withLambdaAsyncClient(region = Region.AP_NORTHEAST_2) { client ->
            client.invokeString("orders", "{}").value
        }
    },
    { stsClient { } },
)
