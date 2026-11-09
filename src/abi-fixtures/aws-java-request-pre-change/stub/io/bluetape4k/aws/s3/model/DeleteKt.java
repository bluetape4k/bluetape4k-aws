package io.bluetape4k.aws.s3.model;

import java.util.Collection;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

public final class DeleteKt {
    private DeleteKt() {}

    public static Delete deleteOfObjectsArray(ObjectIdentifier[] objectIds, boolean quiet) {
        return Delete.builder().objects(objectIds).quiet(quiet).build();
    }

    public static Delete deleteOfObjectsCollection(Collection<ObjectIdentifier> objectIds, boolean quiet) {
        return Delete.builder().objects(objectIds).quiet(quiet).build();
    }
}
