package io.bluetape4k.aws.s3.model;

import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

public final class HeadObjectRequestKt {
    private HeadObjectRequestKt() {}

    public static HeadObjectRequest headObjectRequestOf(String bucket, String key) {
        return HeadObjectRequest.builder().bucket(bucket).key(key).build();
    }
}
