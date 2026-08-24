package io.bluetape4k.aws.spring.connection

import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails
import java.net.URI

/**
 * AWS 에뮬레이터 컨테이너에서 복사한 클라이언트 연결 정보입니다.
 *
 * 자격 증명은 클라이언트 빌더 경계에서만 사용하며, 진단 문자열이나 로그에
 * 포함하지 않아야 합니다.
 */
interface AwsServiceConnectionDetails: ConnectionDetails {
    val endpoint: URI
    val region: String
    val accessKey: String
    val secretKey: String
}

/** S3 클라이언트에 사용할 ServiceConnection 정보입니다. */
interface S3ConnectionDetails: AwsServiceConnectionDetails

/** SQS 클라이언트에 사용할 ServiceConnection 정보입니다. */
interface SqsConnectionDetails: AwsServiceConnectionDetails

/** SNS 클라이언트에 사용할 ServiceConnection 정보입니다. */
interface SnsConnectionDetails: AwsServiceConnectionDetails

/** DynamoDB 클라이언트에 사용할 ServiceConnection 정보입니다. */
interface DynamoDbConnectionDetails: AwsServiceConnectionDetails

/** Kinesis 클라이언트에 사용할 ServiceConnection 정보입니다. */
interface KinesisConnectionDetails: AwsServiceConnectionDetails
