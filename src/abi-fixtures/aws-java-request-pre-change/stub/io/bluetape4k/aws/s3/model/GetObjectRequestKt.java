package io.bluetape4k.aws.s3.model;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;

public final class GetObjectRequestKt {
    private GetObjectRequestKt() {}

    public static GetObjectRequest getObjectRequestOf(
            String bucket,
            String key,
            String versionId,
            Integer partNumber
    ) {
        return GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .versionId(versionId)
                .partNumber(partNumber)
                .build();
    }
}
