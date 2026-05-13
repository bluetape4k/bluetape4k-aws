package io.bluetape4k.aws.ktor.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class S3KtorXmlTest {

    @Test
    fun `ListObjectsV2 XML을 파싱한다`() {
        val xml = """
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Name>demo-bucket</Name>
              <Prefix>logs/</Prefix>
              <KeyCount>1</KeyCount>
              <MaxKeys>1000</MaxKeys>
              <IsTruncated>true</IsTruncated>
              <NextContinuationToken>token-2</NextContinuationToken>
              <Contents>
                <Key>logs/2026/app.log</Key>
                <LastModified>2026-05-10T01:02:03Z</LastModified>
                <ETag>&quot;etag-1&quot;</ETag>
                <Size>42</Size>
                <StorageClass>STANDARD</StorageClass>
              </Contents>
              <CommonPrefixes>
                <Prefix>logs/2026/</Prefix>
              </CommonPrefixes>
            </ListBucketResult>
        """.trimIndent()

        val result = S3KtorXml.parseListObjectsV2(xml)

        result.bucket shouldBeEqualTo "demo-bucket"
        result.prefix shouldBeEqualTo "logs/"
        result.isTruncated.shouldBeTrue()
        result.nextContinuationToken shouldBeEqualTo "token-2"
        result.contents.single().key shouldBeEqualTo "logs/2026/app.log"
        result.contents.single().eTag shouldBeEqualTo "\"etag-1\""
        result.contents.single().size shouldBeEqualTo 42L
        result.commonPrefixes shouldBeEqualTo listOf("logs/2026/")
    }

    @Test
    fun `CompleteMultipartUpload XML은 part 번호 순서로 생성한다`() {
        val xml = S3KtorXml.completeMultipartUpload(
            listOf(
                S3KtorCompletedPart(partNumber = 2, eTag = "\"etag-2\""),
                S3KtorCompletedPart(partNumber = 1, eTag = "\"etag-1\""),
            )
        )

        xml shouldContain "<CompleteMultipartUpload xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        (xml.indexOf("<PartNumber>1</PartNumber>") < xml.indexOf("<PartNumber>2</PartNumber>")).shouldBeTrue()
        xml shouldContain "<ETag>&quot;etag-1&quot;</ETag>"
        xml shouldContain "<ETag>&quot;etag-2&quot;</ETag>"
    }

    @Test
    fun `S3 error XML을 파싱한다`() {
        val xml = """
            <Error>
              <Code>NoSuchKey</Code>
              <Message>The specified key does not exist.</Message>
              <RequestId>request-1</RequestId>
              <HostId>host-1</HostId>
            </Error>
        """.trimIndent()

        val result = S3KtorXml.parseError(xml)

        result.code shouldBeEqualTo "NoSuchKey"
        result.message shouldBeEqualTo "The specified key does not exist."
        result.requestId shouldBeEqualTo "request-1"
        result.hostId shouldBeEqualTo "host-1"
    }
}
