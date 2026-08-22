package io.bluetape4k.aws.kotlin.consumer

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kms.KmsClient
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.scheduler.SchedulerClient
import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.sfn.SfnClient
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sts.StsClient
import io.bluetape4k.aws.kotlin.cloudwatch.cloudWatchClientOf
import io.bluetape4k.aws.kotlin.dynamodb.dynamoDbClientOf
import io.bluetape4k.aws.kotlin.kinesis.kinesisClientOf
import io.bluetape4k.aws.kotlin.kms.kmsClientOf
import io.bluetape4k.aws.kotlin.s3.s3ClientOf
import io.bluetape4k.aws.kotlin.ses.sesClientOf
import io.bluetape4k.aws.kotlin.sns.snsClientOf
import io.bluetape4k.aws.kotlin.sqs.sqsClientOf
import io.bluetape4k.aws.kotlin.sts.stsClientOf

/**
 * AWS Kotlin SDK wrapper의 대표 compileOnly 서비스 표면을 외부 consumer 관점에서 확인합니다.
 *
 * 이 fixture는 실행하지 않고 컴파일만 하므로 AWS client를 생성하지 않습니다.
 */
fun kotlinServiceConsumerFixture(): List<Any> = listOf(
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
    StsClient::class.java,
    { s3ClientOf() },
    { dynamoDbClientOf(region = "ap-northeast-2") },
    { snsClientOf() },
    { sqsClientOf() },
    { kmsClientOf() },
    { sesClientOf() },
    { cloudWatchClientOf() },
    { kinesisClientOf() },
    { SchedulerClient { } },
    { SfnClient { } },
    { stsClientOf() },
)
