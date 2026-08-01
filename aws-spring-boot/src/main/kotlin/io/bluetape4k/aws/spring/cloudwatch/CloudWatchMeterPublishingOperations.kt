package io.bluetape4k.aws.spring.cloudwatch

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.micrometer.core.instrument.Measurement
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import java.util.Locale

private const val CLOUDWATCH_MAX_DIMENSIONS = 30

/**
 * 선택한 Micrometer 미터 스냅샷을 [CloudWatchOperations]를 통해 CloudWatch에 게시합니다.
 *
 * ## 계약
 *
 * 이 도우미는 전역 Micrometer 레지스트리를 생성하거나 교체하지 않습니다. 애플리케이션 코드가
 * 게시 메서드 중 하나를 호출할 때만 기존 [MeterRegistry]에서 미터를 읽습니다.
 */
interface CloudWatchMeterPublishingOperations {

    /**
     * [predicate]가 허용한 모든 미터의 스냅샷을 게시합니다.
     *
     * ## 계약
     *
     * 도우미는 현재 인메모리 Micrometer 측정값을 한 번 전송합니다. 예약 게시자를 등록하거나
     * 원본 레지스트리를 변경하지 않습니다. CloudWatch는 메트릭 데이터당 최대 30개 차원을
     * 허용하므로 태그가 30개보다 많은 Micrometer 미터는 CloudWatch 호출 전에 거부합니다.
     */
    suspend fun publishMeters(predicate: (Meter) -> Boolean = { true }): List<PutMetricDataResponse>

    /**
     * Micrometer 이름이 [name]과 같은 미터의 스냅샷을 게시합니다.
     */
    suspend fun publishMeter(name: String): List<PutMetricDataResponse>
}

/**
 * 기본 [CloudWatchMeterPublishingOperations] 구현입니다.
 */
class CloudWatchMeterPublishingTemplate(
    private val meterRegistry: MeterRegistry,
    private val cloudWatchOperations: CloudWatchOperations,
): CloudWatchMeterPublishingOperations {

    override suspend fun publishMeters(predicate: (Meter) -> Boolean): List<PutMetricDataResponse> {
        val metricData = meterRegistry.meters
            .asSequence()
            .filter(predicate)
            .flatMap { it.toMetricData().asSequence() }
            .toList()
        if (metricData.isEmpty()) {
            return emptyList()
        }

        return cloudWatchOperations.putMetricData(metricData)
    }

    override suspend fun publishMeter(name: String): List<PutMetricDataResponse> {
        name.requireNotBlank("name")
        return publishMeters { it.id.name == name }
    }

    private fun Meter.toMetricData(): List<MetricDatum> {
        val dimensions = toCloudWatchDimensions()

        return measure()
            .filter { it.value.isFinite() }
            .map { measurement -> measurement.toMetricDatum(id.name, dimensions) }
    }

    private fun Meter.toCloudWatchDimensions(): List<Dimension> =
        id.tags
            .map { tag ->
                Dimension.builder()
                    .name(tag.key)
                    .value(tag.value)
                    .build()
            }
            .also { dimensions ->
                dimensions.size.requireInRange(0, CLOUDWATCH_MAX_DIMENSIONS, "CloudWatch metric dimensions")
            }

    private fun Measurement.toMetricDatum(
        meterName: String,
        dimensions: List<Dimension>,
    ): MetricDatum =
        MetricDatum.builder()
            .metricName("${meterName}.${statistic.name.lowercase(Locale.US)}")
            .dimensions(dimensions)
            .unit(statistic.toStandardUnit())
            .value(value)
            .build()

    private fun Statistic.toStandardUnit(): StandardUnit =
        when (this) {
            Statistic.COUNT -> StandardUnit.COUNT
            else -> StandardUnit.NONE
        }
}
