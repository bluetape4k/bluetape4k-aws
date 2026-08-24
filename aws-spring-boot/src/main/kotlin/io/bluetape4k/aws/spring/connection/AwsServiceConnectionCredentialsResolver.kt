package io.bluetape4k.aws.spring.connection

import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

/** Resolves one immutable static credential tuple from all discovered service details. */
internal object AwsServiceConnectionCredentialsResolver {

    fun resolve(details: ObjectProvider<AwsServiceConnectionDetails>): AwsCredentialsProvider {
        val candidates = details.orderedStream().toList()
        if (candidates.isEmpty()) {
            return DefaultCredentialsProvider.builder().build()
        }

        val tuples = candidates
            .map { CredentialTuple(it.accessKey, it.secretKey) }
            .distinct()

        if (tuples.size > 1) {
            throw AwsServiceConnectionConfigurationException(
                reason = AwsServiceConnectionConfigurationException.Reason.CREDENTIAL_CONFLICT,
                serviceNames = candidates.map(::serviceName).toSet(),
                candidateCount = candidates.size,
            )
        }

        val tuple = tuples.single()
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(tuple.accessKey, tuple.secretKey))
    }

    private fun serviceName(details: AwsServiceConnectionDetails): String = when (details) {
        is S3ConnectionDetails -> "s3"
        is SqsConnectionDetails -> "sqs"
        is SnsConnectionDetails -> "sns"
        is DynamoDbConnectionDetails -> "dynamodb"
        is KinesisConnectionDetails -> "kinesis"
        else -> "aws"
    }

    private class CredentialTuple(
        val accessKey: String,
        val secretKey: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is CredentialTuple && accessKey == other.accessKey && secretKey == other.secretKey

        override fun hashCode(): Int = 31 * accessKey.hashCode() + secretKey.hashCode()

        override fun toString(): String = "CredentialTuple(accessKey=[REDACTED], secretKey=[REDACTED])"
    }
}
