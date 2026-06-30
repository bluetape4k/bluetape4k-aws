package io.bluetape4k.aws.ktor.ses

import software.amazon.awssdk.services.sesv2.model.AttachmentContentDisposition
import software.amazon.awssdk.services.sesv2.model.AttachmentContentTransferEncoding
import java.io.Serializable
import java.nio.charset.Charset
import java.util.Arrays

/** Maximum SES v2 message size accepted by the Ktor value objects. */
const val MAX_SES_MESSAGE_BYTES: Int = 40 * 1024 * 1024

/**
 * Recipient address set used by SES email requests.
 *
 * ## Contract
 *
 * At least one recipient must be present. Address strings are validated only
 * for header-safety; SES performs final email-address validation.
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
        private const val serialVersionUID: Long = 3800401201528551625L
    }
}

/**
 * Text and/or HTML body for SES simple email content.
 *
 * ## Contract
 *
 * Either text or HTML must be non-blank. The charset must be supported by the
 * running JVM.
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
        private const val serialVersionUID: Long = -6572918147739449822L
    }
}

/**
 * SES v2 attachment value object for simple and templated messages.
 *
 * ## Contract
 *
 * Attachment bytes are copied at construction and on public read. Internal SDK
 * mapping reuses the constructor-owned copy to avoid additional large-buffer
 * copies.
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

    private val contentValue: ByteArray = content.copyOf()

    /** Defensive copy of the attachment content. */
    val content: ByteArray
        get() = contentValue.copyOf()

    internal val contentForSdk: ByteArray
        get() = contentValue

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
            contentValue.contentEquals(other.contentValue) &&
            contentType == other.contentType &&
            contentDisposition == other.contentDisposition &&
            contentTransferEncoding == other.contentTransferEncoding &&
            contentDescription == other.contentDescription &&
            contentId == other.contentId

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + Arrays.hashCode(contentValue)
        result = 31 * result + contentType.hashCode()
        result = 31 * result + contentDisposition.hashCode()
        result = 31 * result + contentTransferEncoding.hashCode()
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        result = 31 * result + (contentId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SesEmailAttachment(fileName=$fileName, contentSize=${contentValue.size}, contentType=$contentType)"

    companion object {
        private const val serialVersionUID: Long = 175054541493604665L
    }
}

/**
 * SES simple email request.
 *
 * ## Contract
 *
 * Groups destination, subject, body, sender, reply-to, headers, attachments,
 * and configuration-set options into a named request value.
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
        require(totalMessageBytes() <= MAX_SES_MESSAGE_BYTES.toLong()) {
            "email content exceeds SES 40 MB limit."
        }
    }

    companion object {
        private const val serialVersionUID: Long = -5941137867574665530L
    }
}

/**
 * SES template email request.
 *
 * ## Contract
 *
 * Exactly one of [templateName] or [templateArn] must be supplied.
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
        require(totalMessageBytes() <= MAX_SES_MESSAGE_BYTES.toLong()) {
            "email content exceeds SES 40 MB limit."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 4162391212538963818L
    }
}

/**
 * SES raw MIME email request.
 *
 * ## Contract
 *
 * Raw bytes are copied at construction and on public read. Internal SDK mapping
 * reuses the constructor-owned copy to avoid additional large-buffer copies.
 */
class SesRawEmailRequest(
    rawContent: ByteArray,
    val from: String? = null,
    val destination: SesEmailAddressSet? = null,
    val configurationSetName: String? = null,
): Serializable {

    private val rawContentValue: ByteArray = rawContent.copyOf()

    /** Defensive copy of the raw MIME content. */
    val rawContent: ByteArray
        get() = rawContentValue.copyOf()

    internal val rawContentForSdk: ByteArray
        get() = rawContentValue

    init {
        require(rawContent.isNotEmpty()) { "rawContent must not be empty." }
        require(rawContent.size <= MAX_SES_MESSAGE_BYTES) { "rawContent exceeds SES 40 MB limit." }
        from?.requireEmailHeaderValue("from")
        configurationSetName?.let { require(it.isNotBlank()) { "configurationSetName must not be blank." } }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SesRawEmailRequest &&
            rawContentValue.contentEquals(other.rawContentValue) &&
            from == other.from &&
            destination == other.destination &&
            configurationSetName == other.configurationSetName

    override fun hashCode(): Int {
        var result = Arrays.hashCode(rawContentValue)
        result = 31 * result + (from?.hashCode() ?: 0)
        result = 31 * result + (destination?.hashCode() ?: 0)
        result = 31 * result + (configurationSetName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SesRawEmailRequest(rawContentSize=${rawContentValue.size}, hasFrom=${from != null}, hasDestination=${destination != null})"

    companion object {
        private const val serialVersionUID: Long = 2823247628984838144L
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
        require(name.all(::isHeaderNameChar)) {
            "header name must contain only RFC 5322 token characters."
        }
        value.requireEmailHeaderValue("header value")
    }
}

private fun SesEmailRequest.totalMessageBytes(): Long =
    subject.encodedSize(body.charset) +
        body.text.encodedSizeOrZero(body.charset) +
        body.html.encodedSizeOrZero(body.charset) +
        from.encodedSizeOrZero() +
        replyTo.sumOf { it.encodedSize() } +
        configurationSetName.encodedSizeOrZero() +
        headers.entries.sumOf { it.key.encodedSize() + it.value.encodedSize() } +
        totalAttachmentBytes(attachments)

private fun SesTemplateEmailRequest.totalMessageBytes(): Long =
    templateName.encodedSizeOrZero() +
        templateArn.encodedSizeOrZero() +
        templateData.encodedSizeOrZero() +
        from.encodedSizeOrZero() +
        replyTo.sumOf { it.encodedSize() } +
        configurationSetName.encodedSizeOrZero() +
        headers.entries.sumOf { it.key.encodedSize() + it.value.encodedSize() } +
        totalAttachmentBytes(attachments)

private fun totalAttachmentBytes(attachments: List<SesEmailAttachment>): Long =
    attachments.sumOf { it.contentForSdk.size.toLong() }

private fun String?.encodedSizeOrZero(charset: String = "UTF-8"): Long =
    this?.encodedSize(charset) ?: 0L

private fun String.encodedSize(charset: String = "UTF-8"): Long =
    toByteArray(Charset.forName(charset)).size.toLong()

private fun isHeaderNameChar(char: Char): Boolean =
    char in '!'..'~' && char != ':'
