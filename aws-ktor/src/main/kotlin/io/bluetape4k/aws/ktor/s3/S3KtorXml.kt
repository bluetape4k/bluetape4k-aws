package io.bluetape4k.aws.ktor.s3

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val S3_XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/"

internal object S3KtorXml {

    fun parseListObjectsV2(xml: String): S3KtorListObjectsResponse {
        val root = parseRoot(xml)
        return S3KtorListObjectsResponse(
            bucket = root.childText("Name"),
            prefix = root.childText("Prefix"),
            delimiter = root.childText("Delimiter"),
            maxKeys = root.childText("MaxKeys")?.toIntOrNull(),
            keyCount = root.childText("KeyCount")?.toIntOrNull(),
            isTruncated = root.childText("IsTruncated")?.toBooleanStrictOrNull() ?: false,
            nextContinuationToken = root.childText("NextContinuationToken"),
            contents = root.children("Contents").map { element ->
                S3KtorObjectSummary(
                    key = element.childText("Key").orEmpty(),
                    eTag = element.childText("ETag"),
                    size = element.childText("Size")?.toLongOrNull(),
                    lastModified = element.childText("LastModified")?.let(Instant::parse),
                    storageClass = element.childText("StorageClass"),
                )
            },
            commonPrefixes = root.children("CommonPrefixes").mapNotNull { it.childText("Prefix") },
        )
    }

    fun parseCreateMultipartUpload(xml: String): S3KtorMultipartUpload {
        val root = parseRoot(xml)
        return S3KtorMultipartUpload(
            bucket = root.childText("Bucket").orEmpty(),
            key = root.childText("Key").orEmpty(),
            uploadId = root.childText("UploadId").orEmpty(),
        )
    }

    fun parseCompleteMultipartUpload(xml: String): S3KtorCompleteMultipartUploadResponse {
        val root = parseRoot(xml)
        return S3KtorCompleteMultipartUploadResponse(
            bucket = root.childText("Bucket"),
            key = root.childText("Key"),
            location = root.childText("Location"),
            eTag = root.childText("ETag"),
        )
    }

    fun parseError(xml: String): ParsedS3Error {
        if (xml.isBlank()) {
            return ParsedS3Error(null, "S3 request failed.", null, null)
        }
        val root = runCatching { parseRoot(xml) }.getOrNull()
            ?: return ParsedS3Error(null, xml.take(512), null, null)

        return ParsedS3Error(
            code = root.childText("Code"),
            message = root.childText("Message") ?: "S3 request failed.",
            requestId = root.childText("RequestId"),
            hostId = root.childText("HostId"),
        )
    }

    fun completeMultipartUpload(parts: List<S3KtorCompletedPart>): String {
        val sorted = parts.sortedBy { it.partNumber }
        return buildString {
            append("""<CompleteMultipartUpload xmlns="$S3_XMLNS">""")
            sorted.forEach { part ->
                append("<Part>")
                append("<PartNumber>").append(part.partNumber).append("</PartNumber>")
                append("<ETag>").append(escape(part.eTag)).append("</ETag>")
                append("</Part>")
            }
            append("</CompleteMultipartUpload>")
        }
    }

    private fun parseRoot(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isExpandEntityReferences = false

        return factory
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            .documentElement
    }

    private fun Element.childText(name: String): String? {
        val nodes = getElementsByTagName(name)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return buildList {
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? Element)?.let(::add)
            }
        }
    }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

internal data class ParsedS3Error(
    val code: String?,
    val message: String,
    val requestId: String?,
    val hostId: String?,
)
