package io.bluetape4k.aws.ktor.sns;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;

final class StrictObjectMapperSupport {

    private StrictObjectMapperSupport() {
    }

    static ObjectMapper enforce(ObjectMapper objectMapper) {
        return objectMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }
}
