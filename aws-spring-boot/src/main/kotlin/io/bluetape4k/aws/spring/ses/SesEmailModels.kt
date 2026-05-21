package io.bluetape4k.aws.spring.ses

import software.amazon.awssdk.services.sesv2.model.AttachmentContentDisposition
import software.amazon.awssdk.services.sesv2.model.AttachmentContentTransferEncoding
import java.io.Serializable
import java.nio.charset.Charset
import java.util.Arrays

internal const val MAX_SES_MESSAGE_BYTES: Int = 40 * 1024 * 1024

/**
 * Recipient address set used by SES email requests.
 */
data class SesEmailAddressSet(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
): Serializable {

    init {
        require(to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty()) {
            "at least one recipient address is required."
        }
        (to + cc + bcc).forEach { it.requireEmailHeaderValue("recipient") }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Text and/or HTML body for SES simple email content.
 */
data class SesEmailBody(
    val text: String? = null,
    val html: String? = null,
    val charset: String = "UTF-8",
): Serializable {

    init {
        require(!text.isNullOrBlank() || !html.isNullOrBlank()) {
            "text or html body must not be blank."
        }
        require(charset.isNotBlank()) { "charset must not be blank." }
        require(Charset.isSupported(charset)) { "charset '$charset' is not supported." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SES v2 attachment value object for simple and templated messages.
 */
class SesEmailAttachment(
    val fileName: String,
    content: ByteArray,
    val contentType: String,
    val contentDisposition: AttachmentContentDisposition = AttachmentContentDisposition.ATTACHMENT,
    val contentTransferEncoding: AttachmentContentTransferEncoding = AttachmentContentTransferEncoding.BASE64,
    val contentDescription: String? = null,
    val contentId: String? = null,
): Serializable {

    val content: ByteArray = content.copyOf()

    init {
        fileName.requireEmailHeaderValue("fileName")
        contentType.requireEmailHeaderValue("contentType")
        require(content.isNotEmpty()) { "attachment content must not be empty." }
        require(content.size <= MAX_SES_MESSAGE_BYTES) { "attachment content exceeds SES 40 MB limit." }
        contentDescription?.requireEmailHeaderValue("contentDescription")
        contentId?.requireEmailHeaderValue("contentId")
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SesEmailAttachment &&
            fileName == other.fileName &&
            content.contentEquals(other.content) &&
            contentType == other.contentType &&
            contentDisposition == other.contentDisposition &&
            contentTransferEncoding == other.contentTransferEncoding &&
            contentDescription == other.contentDescription &&
            contentId == other.contentId

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + Arrays.hashCode(content)
        result = 31 * result + contentType.hashCode()
        result = 31 * result + contentDisposition.hashCode()
        result = 31 * result + contentTransferEncoding.hashCode()
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        result = 31 * result + (contentId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SesEmailAttachment(fileName=$fileName, contentSize=${content.size}, contentType=$contentType)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SES simple email request.
 */
data class SesEmailRequest(
    val destination: SesEmailAddressSet,
    val subject: String,
    val body: SesEmailBody,
    val from: String? = null,
    val replyTo: List<String> = emptyList(),
    val configurationSetName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val attachments: List<SesEmailAttachment> = emptyList(),
): Serializable {

    init {
        subject.requireEmailHeaderValue("subject")
        from?.requireEmailHeaderValue("from")
        replyTo.forEach { it.requireEmailHeaderValue("replyTo") }
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }
        headers.requireValidSesHeaders()
        require(totalAttachmentBytes(attachments) <= MAX_SES_MESSAGE_BYTES) {
            "attachments exceed SES 40 MB limit."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SES template email request.
 */
data class SesTemplateEmailRequest(
    val destination: SesEmailAddressSet,
    val templateName: String? = null,
    val templateArn: String? = null,
    val templateData: String? = null,
    val from: String? = null,
    val replyTo: List<String> = emptyList(),
    val configurationSetName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val attachments: List<SesEmailAttachment> = emptyList(),
): Serializable {

    init {
        require((templateName.isNullOrBlank()) xor (templateArn.isNullOrBlank())) {
            "exactly one of templateName or templateArn is required."
        }
        templateName?.let { require(it.isNotBlank()) { "templateName must not be blank." } }
        templateArn?.let { require(it.isNotBlank()) { "templateArn must not be blank." } }
        templateData?.let { require(it.isNotBlank()) { "templateData must not be blank when provided." } }
        from?.requireEmailHeaderValue("from")
        replyTo.forEach { it.requireEmailHeaderValue("replyTo") }
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }
        headers.requireValidSesHeaders()
        require(totalAttachmentBytes(attachments) <= MAX_SES_MESSAGE_BYTES) {
            "attachments exceed SES 40 MB limit."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SES raw email request.
 */
class SesRawEmailRequest(
    rawContent: ByteArray,
    val from: String? = null,
    val destination: SesEmailAddressSet? = null,
    val configurationSetName: String? = null,
): Serializable {

    val rawContent: ByteArray = rawContent.copyOf()

    init {
        require(rawContent.isNotEmpty()) { "rawContent must not be empty." }
        require(rawContent.size <= MAX_SES_MESSAGE_BYTES) { "rawContent exceeds SES 40 MB limit." }
        from?.requireEmailHeaderValue("from")
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SesRawEmailRequest &&
            rawContent.contentEquals(other.rawContent) &&
            from == other.from &&
            destination == other.destination &&
            configurationSetName == other.configurationSetName

    override fun hashCode(): Int {
        var result = Arrays.hashCode(rawContent)
        result = 31 * result + (from?.hashCode() ?: 0)
        result = 31 * result + (destination?.hashCode() ?: 0)
        result = 31 * result + (configurationSetName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SesRawEmailRequest(rawContentSize=${rawContent.size}, from=$from, destination=$destination)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal fun String.requireEmailHeaderValue(name: String): String =
    also {
        require(it.isNotBlank()) { "$name must not be blank." }
        require(!it.contains('\r') && !it.contains('\n') && !it.contains('\u0000')) {
            "$name must not contain CR, LF, or NUL characters."
        }
    }

internal fun Map<String, String>.requireValidSesHeaders() {
    forEach { (name, value) ->
        name.requireEmailHeaderValue("header name")
        require(!name.any(Char::isWhitespace)) { "header name must not contain whitespace." }
        value.requireEmailHeaderValue("header value")
    }
}

private fun totalAttachmentBytes(attachments: List<SesEmailAttachment>): Int =
    attachments.sumOf { it.content.size }
