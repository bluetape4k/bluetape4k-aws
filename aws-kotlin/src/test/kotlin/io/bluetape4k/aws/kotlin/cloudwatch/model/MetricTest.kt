package io.bluetape4k.aws.kotlin.cloudwatch.model

import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class MetricTest {

    companion object: KLogging()

    @Test
    fun `metricDatum DSL 블록으로 MetricDatum을 생성한다`() {
        val datum = metricDatum {
            metricName = "Latency"
            value = 100.0
            unit = StandardUnit.Milliseconds
        }

        log.debug { "datum=$datum" }
        datum.metricName shouldBeEqualTo "Latency"
        datum.value shouldBeEqualTo 100.0
        datum.unit shouldBeEqualTo StandardUnit.Milliseconds
    }

    @Test
    fun `metricDatumOf는 이름과 값으로 MetricDatum을 생성한다`() {
        val datum = metricDatumOf("RequestCount", 42.0, StandardUnit.Count)

        log.debug { "datum=$datum" }
        datum.metricName shouldBeEqualTo "RequestCount"
        datum.value shouldBeEqualTo 42.0
        datum.unit shouldBeEqualTo StandardUnit.Count
    }

    @Test
    fun `metricDatumOf는 기본 단위로 None을 사용한다`() {
        val datum = metricDatumOf("CPUUtilization", 75.5)

        log.debug { "datum=$datum" }
        datum.unit shouldBeEqualTo StandardUnit.None
        datum.value shouldBeEqualTo 75.5
    }

    @Test
    fun `metricDatumOf는 builder 블록을 통해 추가 설정이 가능하다`() {
        val datum = metricDatumOf("Errors", 3.0, StandardUnit.Count) {
            storageResolution = 1
        }

        log.debug { "datum=$datum" }
        datum.metricName shouldBeEqualTo "Errors"
        datum.storageResolution shouldBeEqualTo 1
    }

    @Test
    fun `metricDatum 인스턴스는 null이 아니다`() {
        val datum = metricDatumOf("TestMetric", 1.0)

        log.debug { "datum=$datum" }
        datum.shouldNotBeNull()
    }

    @Test
    fun `MetricDatum은 기본 생성자로 생성할 수 있다`() {
        val datum = MetricDatum {
            metricName = "TestMetric"
            value = 0.0
        }
        log.debug { "datum=$datum" }
        datum.metricName shouldBeEqualTo "TestMetric"
    }
}
