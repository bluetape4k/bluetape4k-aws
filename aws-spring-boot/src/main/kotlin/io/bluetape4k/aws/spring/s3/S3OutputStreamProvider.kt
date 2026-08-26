package io.bluetape4k.aws.spring.s3

/** S3 key와 metadata를 이용해 streaming output stream을 만드는 계약입니다. */
interface S3OutputStreamProvider {

    /**
     * [bucket]/[key]에 기록할 stream을 만듭니다.
     *
     * [contentType]이 비어 있으면 resolver가 key와 metadata에서 자동 결정합니다.
     */
    fun outputStream(
        bucket: String,
        key: String,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): S3OutputStream
}
