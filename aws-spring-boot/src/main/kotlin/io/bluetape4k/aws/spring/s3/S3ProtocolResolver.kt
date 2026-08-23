package io.bluetape4k.aws.spring.s3

import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.io.ProtocolResolver
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import software.amazon.awssdk.services.s3.S3Client

/**
 * exact `s3://bucket/key` 위치를 Spring protocol resolver chain에 연결한다.
 *
 * S3가 아닌 위치에는 `null`을 반환해 기존 resolver에 위임하고, S3 client는
 * 실제 resolve 시점에만 [ObjectProvider]에서 조회한다. [S3Resource]의 stream과
 * client 수명은 caller와 owning ApplicationContext가 관리하며, `@Value`와
 * `ApplicationContext.getResource` exact 경로에서 사용할 수 있다.
 */
open class S3ProtocolResolver(
    private val s3ClientProvider: ObjectProvider<S3Client>,
): ProtocolResolver {

    private val parser = S3ResourceLocationParser()

    override fun resolve(location: String, resourceLoader: ResourceLoader): Resource? {
        if (!location.startsWith("s3:", ignoreCase = true)) {
            return null
        }
        val parsed = parser.parseExact(location)
        return S3Resource(s3ClientProvider.getObject(), parsed)
    }
}
