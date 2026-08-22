package io.bluetape4k.aws.consumer

import io.bluetape4k.aws.cloudwatch.cloudWatchClient
import io.bluetape4k.aws.dynamodb.dynamoDbClient
import io.bluetape4k.aws.kinesis.kinesisClient
import io.bluetape4k.aws.kms.kmsClient
import io.bluetape4k.aws.s3.S3ClientFactory
import io.bluetape4k.aws.ses.sesClient
import io.bluetape4k.aws.sns.snsClient
import io.bluetape4k.aws.sqs.sqsClient
import io.bluetape4k.aws.sts.stsClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.scheduler.SchedulerClient
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sts.StsClient

/**
 * Java SDK wrapper의 대표 compileOnly 서비스 표면을 외부 consumer 관점에서 확인합니다.
 *
 * 이 fixture는 실행하지 않고 컴파일만 하므로 AWS client를 생성하지 않습니다.
 */
fun javaServiceConsumerFixture(): List<Any> = listOf(
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
    { stsClient { } },
)
