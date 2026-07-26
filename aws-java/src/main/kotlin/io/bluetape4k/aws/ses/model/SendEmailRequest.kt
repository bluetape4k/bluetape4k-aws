package io.bluetape4k.aws.ses.model

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.services.ses.model.Destination
import software.amazon.awssdk.services.ses.model.MessageTag
import software.amazon.awssdk.services.ses.model.SendEmailRequest
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest

/**
 * Creates a [SendEmailRequest] instance with [SendEmailRequest.Builder].
 *
 * ```kotlin
 * val request = sendEmailRequest {
 *    source("xxx")
 *    destination(destinationOf("yyy"))
 *    ...
 * ```
 *
 * @param builder [SendEmailRequest.Builder] initialization lambda.
 * @return [SendEmailRequest] instance.
 */
inline fun sendEmailRequest(
    builder: SendEmailRequest.Builder.() -> Unit,
): SendEmailRequest =
    SendEmailRequest.builder().apply(builder).build()

/**
 * Creates a [SendEmailRequest] instance.
 *
 * ```kotlin
 * val request = sendEmailRequestOf(
 *     source = "xxx",
 *     destination = destinationOf("yyy"),
 *     ...
 * )
 * ```
 *
 * @param source sender.
 * @param destination recipient destination.
 * @param sourceArn sender ARN.
 * @param replyToAddresses reply-to addresses.
 * @param returnPath return path.
 * @param returnPathArn return path ARN.
 * @param tags message tags.
 * @return [SendEmailRequest] instance.
 */
inline fun sendEmailRequestOf(
    source: String,
    destination: Destination,
    sourceArn: String? = null,
    replyToAddresses: Collection<String>? = null,
    returnPath: String? = null,
    returnPathArn: String? = null,
    tags: Collection<MessageTag>? = null,
    builder: SendEmailRequest.Builder.() -> Unit = {},
): SendEmailRequest = sendEmailRequest {
    source(source)
    destination(destination)
    sourceArn?.run { sourceArn(this) }
    replyToAddresses?.run { replyToAddresses(this) }
    returnPath?.run { returnPath(this) }
    returnPathArn?.run { returnPathArn(this) }
    tags?.run { tags(this) }

    builder()
}

/**
 * Creates a [SendTemplatedEmailRequest] instance with [SendTemplatedEmailRequest.Builder].
 *
 * ```kotlin
 * val request = sendTemplatedEmailRequest {
 *    source("xxx")
 *    destination(destinationOf("yyy"))
 *    template("template-1")
 *    ...
 * ```
 *
 * @param builder [SendTemplatedEmailRequest.Builder] initialization lambda.
 * @return [SendTemplatedEmailRequest] instance.
 */
inline fun sendTemplatedEmailRequest(
    builder: SendTemplatedEmailRequest.Builder.() -> Unit,
): SendTemplatedEmailRequest =
    SendTemplatedEmailRequest.builder().apply(builder).build()

/**
 * Creates a [SendTemplatedEmailRequest] instance.
 *
 * ```kotlin
 * val request = sendTemplatedEmailRequestOf(
 *     source = "xxx",
 *     destination = destinationOf("yyy"),
 *     template = "template-1",
 *     ...
 * )
 * ```
 *
 * @param source sender.
 * @param destination recipient destination.
 * @param template template name.
 * @param templateArn template ARN.
 * @param templateData template data.
 * @param sourceArn sender ARN.
 * @param replyToAddresses reply-to addresses.
 * @param returnPath return path.
 * @param returnPathArn return path ARN.
 * @param tags message tags.
 * @param configurationSetName configuration set name.
 *
 * @return [SendTemplatedEmailRequest] instance.
 */
inline fun sendTemplatedEmailRequestOf(
    source: String,
    destination: Destination,
    template: String,
    templateArn: String? = null,
    templateData: String? = null,
    sourceArn: String? = null,
    replyToAddresses: Collection<String>? = null,
    returnPath: String? = null,
    returnPathArn: String? = null,
    tags: Collection<MessageTag>? = null,
    configurationSetName: String? = null,
    builder: SendTemplatedEmailRequest.Builder.() -> Unit = {},
): SendTemplatedEmailRequest {
    source.requireNotBlank("source")
    template.requireNotBlank("destination")

    return sendTemplatedEmailRequest {
        source(source)
        destination(destination)
        template(template)
        templateArn?.run { templateArn(this) }
        templateData?.run { templateData(this) }
        sourceArn?.run { sourceArn(this) }
        replyToAddresses?.run { replyToAddresses(this) }
        returnPath?.run { returnPath(this) }
        returnPathArn?.run { returnPathArn(this) }
        tags?.run { tags(this) }
        configurationSetName?.run { configurationSetName(this) }

        builder()
    }
}
