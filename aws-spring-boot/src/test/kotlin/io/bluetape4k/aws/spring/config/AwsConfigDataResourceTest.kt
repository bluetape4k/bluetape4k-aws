package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation

class AwsConfigDataResourceTest {

    companion object: KLogging()

    private val parser = AwsConfigDataLocationParser()

    @Test
    fun `resource identity ignores bound properties and follows canonical options`() {
        val firstLocation = parser.parse(
            ConfigDataLocation.of(
                "aws-s3:/bucket/application.yml?format=yaml&prefix=app",
            ),
        )
        val reorderedLocation = parser.parse(
            ConfigDataLocation.of(
                "aws-s3:/bucket/application.yml?prefix=app&format=yaml",
            ),
        )
        val changedLocation = parser.parse(
            ConfigDataLocation.of(
                "aws-s3:/bucket/application.yml?format=json&prefix=app",
            ),
        )

        val first = AwsConfigDataResource.from(firstLocation, boundProperties = "first")
        val reordered = AwsConfigDataResource.from(reorderedLocation, boundProperties = "second")
        val changed = AwsConfigDataResource.from(changedLocation, boundProperties = "third")

        log.debug { "first=$first" }
        log.debug { "reordered=$reordered" }
        log.debug { "changed=$changed" }

        first shouldBeEqualTo reordered
        first.hashCode() shouldBeEqualTo reordered.hashCode()
        first shouldNotBeEqualTo changed
        first.opaqueIdentity shouldBeEqualTo reordered.opaqueIdentity
        first.opaqueIdentity shouldNotContain "bucket"
    }

    @Test
    fun `optional flag participates in identity and rendering is opaque`() {
        val required = AwsConfigDataResource.from(
            parser.parse(ConfigDataLocation.of("aws-secretsmanager:prod-secret?prefix=app")),
            boundProperties = Any(),
        )
        val optional = AwsConfigDataResource.from(
            parser.parse(ConfigDataLocation.of("optional:aws-secretsmanager:prod-secret?prefix=app")),
            boundProperties = Any(),
        )
        log.debug { "required=$required" }
        log.debug { "optional=$optional" }

        required shouldNotBeEqualTo optional
        required.toString().also {
            it shouldNotContain "prod-secret"
            it shouldNotContain "app"
            it shouldContain "bluetape4k.aws.configdata.secrets-manager"
            it shouldContain "prefix"
        }
    }

    @Test
    fun `resource exposes optional and disabled state without exposing the source`() {
        val location = parser.parse(
            ConfigDataLocation.of("optional:aws-parameterstore:/application?recursive=true"),
        )
        log.debug { "location=$location" }

        val resource = AwsConfigDataResource.from(location, disabled = true)
        log.debug { "resource=$resource" }
        resource.isOptionalResource.shouldBeTrue()
        resource.isDisabled.shouldBeTrue()
        resource.toString() shouldNotContain "/application"
    }
}
