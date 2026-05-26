package io.bluetape4k.aws.examples.ktor.s3

import io.bluetape4k.aws.ktor.s3.S3KtorClient
import io.bluetape4k.aws.ktor.s3.S3KtorListObjectsRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import java.time.Duration

/**
 * `S3KtorClient`를 Ktor server routing에 연결하는 예제 모듈입니다.
 *
 * ## 동작/계약
 *
 * [bucket] 하나를 대상으로 `/s3/objects/{key...}` REST API를 등록합니다. 업로드는 요청 body
 * bytes를 S3에 저장하고, 다운로드는 S3 객체 bytes를 응답합니다. Streaming route는
 * `S3KtorClient.getObjectStream`을 사용해 Ktor channel 기반 다운로드 경로를 보여줍니다.
 *
 * ```kotlin
 * fun Application.module() {
 *     s3KtorExampleModule(s3 = S3KtorExamples.localStackClient(), bucket = "demo-bucket")
 * }
 * ```
 */
fun Application.s3KtorExampleModule(
    s3: S3KtorClient,
    bucket: String,
) {
    routing {
        s3DocumentRoutes(s3, bucket)
    }
}

/**
 * S3 object upload/download/presign route를 등록합니다.
 *
 * ## 동작/계약
 *
 * `key`는 tailcard path parameter로 받아 slash가 포함된 S3 object key를 보존합니다.
 * Presigned URL은 15분 만료 기본값을 사용합니다.
 */
fun Route.s3DocumentRoutes(
    s3: S3KtorClient,
    bucket: String,
) {
    put("/s3/detected-objects/{key...}") {
        val key = call.s3KeyParameter()
        val bytes = call.receive<ByteArray>()
        val response = s3.putObjectDetectingContentType(
            bucket = bucket,
            key = key,
            bytes = bytes,
            metadata = mapOf("source" to "ktor-route"),
        )
        call.respondText(
            text = """{"bucket":${bucket.jsonString()},"key":${key.jsonString()},"eTag":${response.eTag.jsonOrNull()}}""",
            contentType = ContentType.Application.Json,
        )
    }

    put("/s3/objects/{key...}") {
        val key = call.s3KeyParameter()
        val bytes = call.receive<ByteArray>()
        val response = s3.putObject(
            bucket = bucket,
            key = key,
            bytes = bytes,
            contentType = call.request.headers["Content-Type"],
        )
        call.respondText(
            text = """{"bucket":${bucket.jsonString()},"key":${key.jsonString()},"eTag":${response.eTag.jsonOrNull()}}""",
            contentType = ContentType.Application.Json,
        )
    }

    get("/s3/objects/{key...}/stream") {
        val response = s3.getObjectStream(bucket = bucket, key = call.s3KeyParameter())
        call.respondBytes(response.body.toByteArray(), ContentType.Application.OctetStream)
    }

    get("/s3/objects/{key...}") {
        val bytes = s3.getObjectBytes(bucket = bucket, key = call.s3KeyParameter())
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    get("/s3/objects") {
        val prefix = call.request.queryParameters["prefix"]
        val page = s3.listObjectsV2(
            S3KtorListObjectsRequest(
                bucket = bucket,
                prefix = prefix,
                maxKeys = 100,
            )
        )
        call.respondText(
            text = page.contents.joinToString(prefix = "[", postfix = "]") { content ->
                """{"key":${content.key.jsonString()},"size":${content.size ?: "null"}}"""
            },
            contentType = ContentType.Application.Json,
        )
    }

    put("/s3/config/{key...}") {
        val key = call.s3KeyParameter()
        val text = call.receiveText()
        val response = s3.putConfigObject(
            bucket = bucket,
            key = key,
            text = text,
            metadata = mapOf("source" to "ktor-route"),
        )
        call.respondText(
            text = """{"bucket":${bucket.jsonString()},"key":${key.jsonString()},"eTag":${response.eTag.jsonOrNull()}}""",
            contentType = ContentType.Application.Json,
        )
    }

    get("/s3/config/{key...}") {
        val config = s3.getConfigObject(bucket = bucket, key = call.s3KeyParameter())
        call.respondText(config.text, ContentType.parse(config.contentType ?: "text/plain; charset=utf-8"))
    }

    get("/s3/presigned-get/{key...}") {
        val presigned = s3.presignGetObject(bucket, call.s3KeyParameter(), Duration.ofMinutes(15))
        call.respondText(
            text = """{"method":"${presigned.method}","url":${presigned.url.toString().jsonString()}}""",
            contentType = ContentType.Application.Json,
        )
    }

    get("/s3/presigned-put/{key...}") {
        val presigned = s3.presignPutObject(bucket, call.s3KeyParameter(), Duration.ofMinutes(15))
        call.respondText(
            text = """{"method":"${presigned.method}","url":${presigned.url.toString().jsonString()}}""",
            contentType = ContentType.Application.Json,
        )
    }

    delete("/s3/objects/{key...}") {
        s3.deleteObject(bucket = bucket, key = call.s3KeyParameter())
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun io.ktor.server.application.ApplicationCall.s3KeyParameter(): String =
    parameters.getAll("key").orEmpty().joinToString("/")

private fun String?.jsonOrNull(): String =
    this?.jsonString() ?: "null"

private fun String.jsonString(): String =
    buildString {
        append('"')
        this@jsonString.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
