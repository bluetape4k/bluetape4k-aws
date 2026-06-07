package io.bluetape4k.aws.spring.dynamodb

import io.bluetape4k.aws.spring.AwsProperties
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

internal fun resolveDynamoDbCredentialsProvider(
    provider: ObjectProvider<AwsCredentialsProvider>,
): AwsCredentialsProvider =
    provider.getIfAvailable { DefaultCredentialsProvider.builder().build() }

internal fun resolveDynamoDbAwsProperties(provider: ObjectProvider<AwsProperties>): AwsProperties =
    provider.getIfAvailable { AwsProperties() }
