package io.bluetape4k.aws.spring.config

import io.bluetape4k.aws.spring.AwsProperties
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider
import java.nio.file.Files

/** ConfigData bootstrap 단계에서 공유 AWS 자격 증명 정책을 적용합니다. */
internal fun AwsProperties.configDataCredentialsProvider(): AwsCredentialsProvider {
    val webIdentity = credentials.webIdentity
    if (!webIdentity.enabled) {
        return DefaultCredentialsProvider.builder().build()
    }

    AwsConfigDataBootstrapBridge.requireClass(
        className = "software.amazon.awssdk.services.sts.StsClient",
        dependency = "software.amazon.awssdk:sts",
    )
    val roleArn = webIdentity.roleArn?.takeIf { it.isNotBlank() }
    val roleSessionName = webIdentity.roleSessionName?.takeIf { it.isNotBlank() }
    val tokenFile = webIdentity.tokenFile
    require(
        roleArn != null &&
            roleSessionName != null &&
            tokenFile != null &&
            Files.isRegularFile(tokenFile) &&
            Files.isReadable(tokenFile),
    ) {
        "AWS ConfigData web identity configuration is invalid."
    }
    return WebIdentityTokenFileCredentialsProvider.builder()
        .apply {
            roleArn(roleArn)
            roleSessionName(roleSessionName)
            webIdentityTokenFile(tokenFile)
        }
        .build()
}
