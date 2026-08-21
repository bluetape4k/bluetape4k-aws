package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.config.ConfigDataLocation

class AwsConfigDataResourceTest {

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

        first shouldBeEqualTo reordered
        first.hashCode() shouldBeEqualTo reordered.hashCode()
        first.equals(changed) shouldBeEqualTo false
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

        required.equals(optional) shouldBeEqualTo false
        required.toString() shouldNotContain "prod-secret"
        required.toString() shouldNotContain "app"
        required.toString() shouldContain "bluetape4k.aws.configdata.secrets-manager"
        required.toString() shouldContain "prefix"
    }

    @Test
    fun `resource exposes optional and disabled state without exposing the source`() {
        val location = parser.parse(
            ConfigDataLocation.of("optional:aws-parameterstore:/application?recursive=true"),
        )
        val resource = AwsConfigDataResource.from(location, disabled = true)

        resource.isOptionalResource shouldBeEqualTo true
        resource.isDisabled shouldBeEqualTo true
        resource.toString() shouldNotContain "/application"
    }
}
